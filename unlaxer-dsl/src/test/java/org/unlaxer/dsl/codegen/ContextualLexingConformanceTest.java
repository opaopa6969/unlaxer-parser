package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;
import org.unlaxer.dsl.runtime.Lexing;
import org.unlaxer.dsl.PortabilityCheck;


/** Independent oracles across four input modes, generated Java/Rust and native emission. */
public class ContextualLexingConformanceTest {
    private static final String PACKAGE = "example.lexing";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private java.util.Map<String,String> captureNames=java.util.Map.of();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private JsonArray corpus(String file) throws Exception {
        return JsonParser.parseString(Files.readString(repo.resolve("spec-corpus/contextual-lexing/"+file))).getAsJsonArray();
    }
    private GrammarDecl grammar(JsonObject fixture) throws Exception {
        return fixture.has("grammarFile") ? org.unlaxer.dsl.bootstrap.UBNFModuleLoader.load(repo.resolve("spec-corpus/contextual-lexing/"+fixture.get("grammarFile").getAsString())).grammars().get(0)
            : UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
    }
    private List<Lexing.Mode> modes(JsonObject fixture) {
        return fixture.has("modes") ? fixture.getAsJsonArray("modes").asList().stream().map(value -> Lexing.Mode.valueOf(value.getAsString())).toList() : List.of(Lexing.Mode.values());
    }
    @Test public void javaModesPreserveAuthoredOracles() throws Exception {
        for (var fixture : corpus("corpus.json")) {
            var f=fixture.getAsJsonObject();
            String example = switch (f.get("name").getAsString()) {
                case "character-kinds" -> "character-kinds.ubnf";
                case "guarded-first-match" -> "first-match.ubnf";
                default -> null;
            };
            if (example != null) assertEquals("runnable example drift: " + example,
                f.get("grammar").getAsString().strip(), Files.readString(repo.resolve("examples/parse-composition/" + example)).strip());
            String contextualExample = switch (f.get("name").getAsString()) {
                case "named-trivia" -> "named-trivia.ubnf";
                case "named-global-scoped-context" -> "named-global-scoped.ubnf";
                default -> null;
            };
            if (contextualExample != null) assertEquals("runnable contextual example drift: " + contextualExample,
                f.get("grammar").getAsString().strip(), Files.readString(repo.resolve("spec-corpus/contextual-lexing/" + contextualExample)).strip());
            var grammar=grammar(f);
            GrammarValidator.validateOrThrow(grammar);
            try(var loader=compileJava(grammar)) {
                for(var c:f.getAsJsonArray("cases")) for(var mode:modes(f)) for(boolean keep:List.of(true,false)) {
                    var row=c.getAsJsonObject(); String name=f.get("name").getAsString()+"/"+row.get("name")+"/"+mode+"/"+keep;
                    var observed=javaObservation(loader,row.get("input").getAsString(),mode,keep);
                    assertOracle(name,row,mode,keep,observed);
                    if(mode==Lexing.Mode.DIRECT && keep) {
                        var parser=(Parser)loader.loadClass(PACKAGE+".InputParsers").getMethod("getRootParser").invoke(null);
                        String input=row.get("input").getAsString();
                        for (var memo : org.unlaxer.context.Memoization.values()) {
                        var legacy=prefix(parser,input,memo);
                        assertEquals(name+" ordinary entry acceptance",observed.get("accepted").getAsBoolean(),
                            legacy.get(0).getAsBoolean() && legacy.get(1).getAsInt()==input.codePointCount(0,input.length()));
                        assertEquals(name+" ordinary entry consumed",observed.get("consumed"),legacy.get(1));
                        assertEquals(name+" ordinary entry matched",observed.get("matched"),legacy.get(2));
                        if (!observed.get("accepted").getAsBoolean()) assertEquals(name+" ordinary entry failure/memo "+memo,observed.get("farthest"),legacy.get(3));
                        }
                        if(observed.get("accepted").getAsBoolean()) {
                            var mapped=loader.loadClass(PACKAGE+".InputMapper").getMethod("parseWithSourceMap",String.class).invoke(null,input);
                            assertEquals(name+" ordinary entry AST",observed.get("ast"),canonical(mapped.getClass().getMethod("ast").invoke(mapped),mapped));
                        }
                    }
                }
            }
        }
    }
    private JsonObject javaObservation(URLClassLoader loader,String input,Lexing.Mode mode,boolean keep) throws Exception {
        var parsers=loader.loadClass(PACKAGE+".InputParsers"); var mapper=loader.loadClass(PACKAGE+".InputMapper");
        Lexing.Outcome result;
        try { result=(Lexing.Outcome)parsers.getMethod("parseWithLexing",String.class,Lexing.Options.class).invoke(null,input,new Lexing.Options(mode,keep)); }
        catch (NoSuchMethodException absent) { result=Lexing.parse((Parser)parsers.getMethod("getRootParser").invoke(null),input,new Lexing.Options(mode,keep),List.of(),false,true); }
        var observed=new JsonObject();observed.addProperty("accepted",result.succeeded());
        observed.addProperty("consumed",result.consumed()); observed.addProperty("matched",result.matched());
        observed.addProperty("farthest",result.farthest());
        observed.add("ast",JsonNull.INSTANCE);
        var captures=new JsonArray();
        if(result.succeeded()) captures(result.root(),captures);
        observed.add("captures",captures);
        if(result.succeeded()) {
            var mapped=mapper.getMethod("mapParsedTokenWithSourceMap",org.unlaxer.Token.class).invoke(null,result.root());
            var ast=mapped.getClass().getMethod("ast").invoke(mapped); observed.add("ast",canonical(ast,mapped));
        }
        var entries=new JsonArray();
        for(var e:result.session().lexemes()) {
            var item=new JsonObject();item.addProperty("kind",e.kind());item.addProperty("name",e.name());
            var span=new JsonArray();span.add(e.start());span.add(e.end());item.add("span",span);
            item.addProperty("text",result.session().text(e));entries.add(item);
        }
        observed.add("lexemes",entries);return observed;
    }
    private void captures(org.unlaxer.Token token,JsonArray output) throws Exception {
        if(token.parser.getClass().getSimpleName().equals("__CaptureSite")) {
            for(Object name:(List<?>)token.parser.getClass().getMethod("captureBindings").invoke(token.parser)) {
                var item=new JsonObject();if(!captureNames.containsKey(name.toString())) continue;
                item.addProperty("name",captureNames.get(name.toString()));
                var span=new JsonArray();int start=token.source.offsetFromRoot().value();span.add(start);span.add(start+token.source.codePointLength().value());item.add("span",span);output.add(item);
            }
        }
        for(var child:token.getOriginalChildren()) captures(child,output);
    }
    private void assertOracle(String name,JsonObject row,Lexing.Mode mode,boolean keep,JsonObject actual) {
        assertEquals(name+" CP length",row.get("sourceLength").getAsInt(),row.get("input").getAsString().codePointCount(0,row.get("input").getAsString().length()));
        assertEquals(name+" UTF-16 length",row.get("utf16Length").getAsInt(),row.get("input").getAsString().length());
        boolean tokens=mode==Lexing.Mode.TOKENS_EAGER||mode==Lexing.Mode.TOKENS_LAZY;
        var oracle=tokens&&row.has("tokens")?row.getAsJsonObject("tokens"):row;
        boolean accepted=oracle.get("accepted").getAsBoolean();
        assertEquals(name+" acceptance",accepted,actual.get("accepted").getAsBoolean());
        String input=row.get("input").getAsString();
        if(accepted) {
            assertEquals(name+" AST/source spans",oracle.get("ast"),actual.get("ast"));
            assertEquals(name+" named CST captures",oracle.get("captures"),actual.get("captures"));
            assertEquals(name+" consumption",input.codePointCount(0,input.length()),actual.get("consumed").getAsInt());
            assertEquals(name+" matched",actual.get("consumed"),actual.get("matched"));
        } else {
            assertEquals(name+" failure",oracle.get("farthest"),actual.get("farthest"));
            assertEquals(name+" failure consumption",oracle.has("consumed")?oracle.get("consumed").getAsInt():0,actual.get("consumed").getAsInt());
            assertEquals(name+" failure matched",oracle.has("matched")?oracle.get("matched").getAsInt():0,actual.get("matched").getAsInt());
            assertEquals(name+" no mapped AST",JsonNull.INSTANCE,actual.get("ast"));
        }
        var entries=new JsonArray();
        for(var e:row.has("lexemes") ? row.getAsJsonArray("lexemes") : new JsonArray()) {
            String kind=e.getAsJsonObject().get("kind").getAsString();
            if(keep||kind.equals("token")||kind.equals("error")) entries.add(e);
        }
        if (row.has("lexemes")) assertEquals(name+" token/trivia/source positions",entries,actual.get("lexemes"));
        if(keep) {
            String rebuilt=String.join("",actual.getAsJsonArray("lexemes").asList().stream().map(e->e.getAsJsonObject().get("text").getAsString()).toList());
            assertEquals(name+" lossless reconstruction",input,rebuilt);
        }
    }
    @Test public void rustAndNativeFrontendMatchAllModes() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",Boolean.getBoolean("rustConformance"));
        Path runtime=temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc","--edition=2021","--crate-type=rlib","--crate-name=unlaxer_runtime",repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),"",false));
        Path nativeTarget=repo.resolve("rust/target");
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget.toString()),"",false));
        Path nativeGenerator=nativeTarget.resolve("debug/unlaxer");
        var report=new ArrayList<>(List.of("fixture\tcase\tmode\tpreserve_trivia\tjava\trust"));
        for(var fixture:corpus("corpus.json")) {
            var f=fixture.getAsJsonObject();String source=f.get("grammar").getAsString();var grammar=grammar(f);
            var generated=new RustBackend().generate(grammar);Path folder=temporary.newFolder().toPath();Path ubnf=f.has("grammarFile") ? repo.resolve("spec-corpus/contextual-lexing/"+f.get("grammarFile").getAsString()) : folder.resolve("input.ubnf");
            if(!f.has("grammarFile"))Files.writeString(ubnf,source);
            Path nativeOutput=folder.resolve("generated");
            success(run(List.of(nativeGenerator.toString(),"generate","--grammar",ubnf.toString(),"--output",nativeOutput.toString()),"",true));
            for(var file:generated) assertEquals(f.get("name")+" emitted "+file.relativePath(),file.content(),Files.readString(nativeOutput.resolve(file.relativePath())));
            Files.writeString(folder.resolve("main.rs"),rustProbe(TokenStreamGrammar.enabled(grammar)));
            success(run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,folder.resolve("main.rs").toString(),"-o",folder.resolve("probe").toString()),"",false));
            var framed=new StringBuilder();
            for(var c:f.getAsJsonArray("cases")) for(var mode:modes(f)) for(boolean keep:List.of(true,false)) {
                framed.append(mode.ordinal()).append(keep?'1':'0').append(':').append(HexFormat.of().formatHex(c.getAsJsonObject().get("input").getAsString().getBytes(StandardCharsets.UTF_8))).append('\n');
            }
            var output=run(List.of(folder.resolve("probe").toString()),framed.toString(),false);success(output);
            var results=output.output().lines().map(JsonParser::parseString).toList(); int n=0;
            try(var loader=compileJava(grammar)) {
                for(var c:f.getAsJsonArray("cases")) for(var mode:modes(f)) for(boolean keep:List.of(true,false)) {
                    var row=c.getAsJsonObject();String name=f.get("name")+"/"+row.get("name")+"/"+mode+"/"+keep;
                    var rust=results.get(n++).getAsJsonObject();var java=javaObservation(loader,row.get("input").getAsString(),mode,keep);
                    assertOracle(name+" Rust",row,mode,keep,rust);
                    // Successful parses may retain backend-specific speculative failure hints.
                    if(java.get("accepted").getAsBoolean()) {java.remove("farthest");rust.remove("farthest");}
                    assertEquals(name+" Java/Rust",java,rust);
                    report.add(f.get("name")+"\t"+row.get("name")+"\t"+mode+"\t"+keep+"\t"+java+"\t"+rust);
                }
            }
        }
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/rust-contextual-lexing.tsv"),report);
    }
    @Test public void invalidGoalsHaveIndependentSourceDiagnostics() throws Exception {
        Path folder=temporary.newFolder().toPath(),binary=repo.resolve("rust/target/debug/unlaxer");
        var report=new ArrayList<String>(List.of("case\tline_endings\tcode\tcp_span\tjava_rust"));
        if(Boolean.getBoolean("rustConformance")) success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"),"",false));
        for(var fixture:corpus("invalid.json")) for(String ending:List.of("LF","CRLF")) {
            var row=fixture.getAsJsonObject();String original=row.get("grammar").getAsString();String input=ending.equals("LF")?original:original.replace("\n","\r\n");
            var result=org.unlaxer.dsl.PortabilityCheck.check(input);
            assertFalse(row.get("name").getAsString(),result.portable());
            var contextDiagnostics=result.diagnostics().stream().filter(diagnostic->diagnostic.code().startsWith("E-LEXICAL-CONTEXT-")).toList();
            assertEquals("one owning source diagnostic",1,contextDiagnostics.size());var diagnostic=contextDiagnostics.get(0);
            assertEquals(row.get("code").getAsString(),diagnostic.code());assertEquals(row.get("subject").getAsString(),diagnostic.subject());
            int start=row.getAsJsonArray("span").get(0).getAsInt(),end=row.getAsJsonArray("span").get(1).getAsInt();
            if(ending.equals("CRLF")) {start+=original.substring(0,start).chars().filter(character->character=='\n').count();end+=original.substring(0,end).chars().filter(character->character=='\n').count();}
            assertEquals("authored span start",start,diagnostic.span().start());assertEquals("authored span end",end,diagnostic.span().end());
            if(Boolean.getBoolean("rustConformance")) {
                Path grammar=folder.resolve("invalid.ubnf");Files.writeString(grammar,input);
                var nativeResult=run(List.of(binary.toString(),"check","--target","rust","--grammar",grammar.toString(),"--format","json"),"",true);
                assertNotEquals("native rejects goal",0,nativeResult.code());
                var nativeDiagnostics=JsonParser.parseString(nativeResult.output()).getAsJsonObject().getAsJsonArray("diagnostics").asList().stream().map(JsonElement::getAsJsonObject).filter(value->value.get("code").getAsString().startsWith("E-LEXICAL-CONTEXT-")).toList();
                assertEquals(1,nativeDiagnostics.size());var value=nativeDiagnostics.get(0);assertEquals(diagnostic.code(),value.get("code").getAsString());assertEquals(diagnostic.subject(),value.get("subject").getAsString());assertEquals(start,value.getAsJsonObject("span").get("start").getAsInt());assertEquals(end,value.getAsJsonObject("span").get("end").getAsInt());
                report.add(row.get("name").getAsString()+"\t"+ending+"\t"+diagnostic.code()+"\t["+start+","+end+"]\tequal");
            }
        }
        if(Boolean.getBoolean("rustConformance")) {Files.createDirectories(Path.of("target"));Files.write(Path.of("target/rust-contextual-lexing-invalid.tsv"),report);}
    }
    private Parser scope(List<String> terminals, Parser... children) {
        return new org.unlaxer.parser.combinator.Chain(children) {
            @Override public org.unlaxer.Parsed parse(ParseContext context, org.unlaxer.TokenKind kind, boolean invert) {
                return Lexing.withContext(context, terminals.stream().map(ContextualLexingConformanceTest::literalTerminal).toList(), () -> super.parse(context,kind,invert));
            }
        };
    }
    private Parser child(Parser... children) {
        return new org.unlaxer.parser.combinator.Chain(children) {
            @Override public org.unlaxer.Parsed parse(ParseContext context, org.unlaxer.TokenKind kind, boolean invert) {
                return Lexing.withIndependentLexing(context, () -> super.parse(context,kind,invert));
            }
        };
    }
    private static Lexing.Terminal literalTerminal(String text) {
        return new Lexing.Terminal(text,true,org.unlaxer.dsl.runtime.LexicalExpression.leaf(org.unlaxer.dsl.runtime.LexicalExpression.Op.LITERAL,text));
    }
    private Parser literal(String text) { return new Lexing.LiteralParser(text); }
    private Parser runtimeParser(String name) {
        Parser greater = child(literal(">"));
        Parser failingChild = child(literal(">"),literal("!"));
        return switch (name) {
            case "positive-lookahead" -> new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.combinator.MatchOnly(scope(List.of(">>"),literal(">>"))),scope(List.of(">"),literal(">"),literal(">")));
            case "negative-lookahead" -> new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.combinator.Not(scope(List.of(">>","!"),literal(">>"),literal("!"))),scope(List.of(">"),literal(">"),literal(">")));
            case "child-entry" -> scope(List.of(">>"),greater,literal(">>"));
            case "child-failure-choice" -> scope(List.of(">>"),new org.unlaxer.parser.combinator.Choice(failingChild,new org.unlaxer.parser.combinator.Chain(literal(">>"),greater)));
            case "unicode-child" -> scope(List.of(">>"),child(literal("😀")),literal(">>"));
            case "child-failure" -> scope(List.of(">>"),failingChild);
            default -> throw new IllegalArgumentException(name);
        };
    }
    @Test public void lookaheadAndIndependentGrammarRestoreLexicalState() throws Exception {
        Path folder=temporary.newFolder().toPath();
        Path runtime=folder.resolve("libunlaxer_runtime.rlib");
        if(Boolean.getBoolean("rustConformance")) {
            success(run(List.of("rustc","--edition=2021","--crate-type=rlib","--crate-name=unlaxer_runtime",repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),"",false));
            Files.writeString(folder.resolve("main.rs"),runtimeProbe());
            success(run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,folder.resolve("main.rs").toString(),"-o",folder.resolve("probe").toString()),"",false));
        }
        var framed=new StringBuilder();var expected=new ArrayList<JsonObject>();
        for(var c:corpus("runtime.json")) for(var mode:Lexing.Mode.values()) {
            var row=c.getAsJsonObject();String name=row.get("name").getAsString(),input=row.get("input").getAsString();
            var result=Lexing.parse(runtimeParser(name),input,new Lexing.Options(mode,true),List.of(literalTerminal(">>"),literalTerminal(">"),literalTerminal("!"),literalTerminal("😀")),false,true);
            var observed=new JsonObject();observed.addProperty("accepted",result.succeeded());observed.addProperty("consumed",result.consumed());observed.addProperty("matched",result.matched());
            for(String field:List.of("accepted","consumed","matched")) assertEquals(name+"/"+mode+"/"+field,row.get(field),observed.get(field));
            if(row.has("farthest")) {observed.addProperty("farthest",result.farthest());assertEquals(name+" failure position",row.get("farthest"),observed.get("farthest"));}
            if(row.has("span")) {var span=new JsonArray();span.add(result.root().source.offsetFromRoot().value());span.add(result.root().source.offsetFromRoot().value()+result.root().source.codePointLength().value());observed.add("span",span);assertEquals(name+" source span",row.get("span"),span);}
            assertEquals(name+" CP",row.get("sourceLength").getAsInt(),input.codePointCount(0,input.length()));assertEquals(name+" UTF16",row.get("utf16Length").getAsInt(),input.length());
            expected.add(observed);framed.append(name).append(':').append(mode.ordinal()).append(':').append(HexFormat.of().formatHex(input.getBytes(StandardCharsets.UTF_8))).append('\n');
        }
        if(Boolean.getBoolean("rustConformance")) {
            var output=run(List.of(folder.resolve("probe").toString()),framed.toString(),false);success(output);
            assertEquals("same host combinators and child entry input",expected,output.output().lines().map(JsonParser::parseString).toList());
            Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/rust-contextual-lexing-boundaries.tsv"),framed.toString()+output.output());
        }
    }
    private String runtimeProbe() { return """
        use unlaxer_runtime::{Expr,Rule,ParseContext,ParseResult,share_grammar,lexing::{self,Mode,Options,Terminal},lexical::{LexicalExpression,Op}};
        use std::io::{self,BufRead};
        fn terminal(text: &'static str)->Terminal {Terminal{name:text,literal:true,expression:LexicalExpression{op:Op::LITERAL,text,min:0,max:0,children:vec![]}}}
        fn scoped(texts:&[&'static str], child:Expr)->Expr {child.lexical_context_scope(texts.iter().map(|text|terminal(text)).collect())}
        fn child_gt(context:&mut ParseContext<'_>)->ParseResult {context.parse_shared_grammar(&share_grammar(vec![Rule{name:"child",expression:Expr::Literal(">")}]),0,false)}
        fn child_fail(context:&mut ParseContext<'_>)->ParseResult {context.parse_shared_grammar(&share_grammar(vec![Rule{name:"child",expression:Expr::sequence([Expr::Literal(">"),Expr::Literal("!")])}]),0,false)}
        fn child_unicode(context:&mut ParseContext<'_>)->ParseResult {context.parse_shared_grammar(&share_grammar(vec![Rule{name:"child",expression:Expr::Literal("😀")}]),0,false)}
        fn main() {for line in io::stdin().lock().lines() {
            let line=line.unwrap();let fields=line.split(':').collect::<Vec<_>>();let name=fields[0];let mode=[Mode::Direct,Mode::TriviaCache,Mode::TokensLazy,Mode::TokensEager][fields[1].parse::<usize>().unwrap()];
            let bytes=(0..fields[2].len()).step_by(2).map(|i|u8::from_str_radix(&fields[2][i..i+2],16).unwrap()).collect();let source=String::from_utf8(bytes).unwrap();
            let pair=scoped(&[">"],Expr::sequence([Expr::Literal(">"),Expr::Literal(">")]));
            let expression=match name {
                "positive-lookahead"=>Expr::sequence([scoped(&[">>"],Expr::Literal(">>")).ahead(),pair]),
                "negative-lookahead"=>Expr::sequence([scoped(&[">>","!"],Expr::sequence([Expr::Literal(">>"),Expr::Literal("!")])).not_ahead(),pair]),
                "child-entry"=>scoped(&[">>"],Expr::sequence([Expr::Custom(child_gt),Expr::Literal(">>")])),
                "child-failure-choice"=>scoped(&[">>"],Expr::Choice(vec![Expr::Custom(child_fail),Expr::sequence([Expr::Literal(">>"),Expr::Custom(child_gt)])])),
                "unicode-child"=>scoped(&[">>"],Expr::sequence([Expr::Custom(child_unicode),Expr::Literal(">>")])),
                "child-failure"=>scoped(&[">>"],Expr::Custom(child_fail)),_=>panic!()};
            let grammar=share_grammar(vec![Rule{name:"root",expression}]);
            let result=lexing::parse_contextual(&grammar,0,false,&source,Options{mode,preserve_trivia:true},vec![terminal(">>"),terminal(">"),terminal("!"),terminal("😀")].into()).unwrap();
            let extra=if result.succeeded {let tree=result.tree.as_ref().unwrap();let span=tree.nodes[tree.root].span;format!(r#","span":[{},{}]"#,span.start,span.end)} else {format!(r#","farthest":{}"#,result.farthest)};
            println!(r#"{{"accepted":{},"consumed":{},"matched":{}{}}}"#,result.succeeded,result.consumed,result.matched,extra);
        }}
        """; }
    private String rustProbe(boolean generatedEntry) {
        String source = """
            mod generated;
            use std::io::{self,BufRead};
            use unlaxer_runtime::{json_string,lexing::{Mode,Options}};
            fn main() {
                for line in io::stdin().lock().lines() {
                    let line=line.unwrap();let (flags,encoded)=line.split_once(':').unwrap();
                    let bytes=(0..encoded.len()).step_by(2).map(|i|u8::from_str_radix(&encoded[i..i+2],16).unwrap()).collect();
                    let source=String::from_utf8(bytes).unwrap();let mode=[Mode::Direct,Mode::TriviaCache,Mode::TokensLazy,Mode::TokensEager][usize::from(flags.as_bytes()[0]-b'0')];
                    let mut out=generated::parser::parse_with_lexing(&source,Options{mode,preserve_trivia:flags.as_bytes()[1]==b'1'}).unwrap();
                    if mode == Mode::Direct {
                        for memo in [unlaxer_runtime::Memoization::Off,unlaxer_runtime::Memoization::SafeFailures] {
                            let options=unlaxer_runtime::ParseOptions::with_memoization(memo).with_diagnostics(unlaxer_runtime::Diagnostics::Detailed);
                            let mut context=unlaxer_runtime::ParseContext::with_options(&source,options);
                            let ok=generated::parser::parse_context(&mut context).is_ok();
                            assert_eq!(ok && context.position()==source.chars().count(),out.succeeded,"memo acceptance");
                            assert_eq!(context.position(),out.consumed,"memo consumed");
                            let full=generated::parser::parse_tree_detailed_with_options(&source,options);
                            assert_eq!(full.is_ok(),out.succeeded,"memo full acceptance");
                            match full { Ok(tree)=>assert_eq!(generated::mapper::map(&tree).unwrap().canonical_json(),generated::mapper::map(out.tree.as_ref().unwrap()).unwrap().canonical_json(),"memo AST"),
                                Err(error)=>assert_eq!(error.offset,out.farthest,"memo failure position") }
                        }
                    }
                    let ast=if out.succeeded {generated::mapper::map(out.tree.as_ref().unwrap()).unwrap().canonical_json()} else {"null".into()};
                    let captures=if out.succeeded {out.tree.as_ref().unwrap().nodes[out.tree.as_ref().unwrap().root].captures.iter().map(|c|format!(r#"{{"name":{},"span":[{},{}]}}"#,json_string(c.name),c.span.start,c.span.end)).collect::<Vec<_>>().join(",")} else {String::new()};
                    let lexemes=out.session.lexemes().iter().map(|e|format!(r#"{{"kind":{},"name":{},"span":[{},{}],"text":{}}}"#,json_string(e.kind),json_string(e.name),e.span.start,e.span.end,json_string(out.session.text(e)))).collect::<Vec<_>>().join(",");
                    println!(r#"{{"accepted":{},"consumed":{},"matched":{},"farthest":{},"ast":{},"captures":[{}],"lexemes":[{}]}}"#,out.succeeded,out.consumed,out.matched,out.farthest,ast,captures,lexemes);
                }
            }
            """;
        return generatedEntry ? source : source.replace("generated::parser::parse_with_lexing(&source,Options{mode,preserve_trivia:flags.as_bytes()[1]==b'1'})", "unlaxer_runtime::lexing::parse_contextual(generated::parser::grammar(),0,false,&source,Options{mode,preserve_trivia:flags.as_bytes()[1]==b'1'},vec![].into())");
    }
    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var names=new java.util.HashMap<String,String>();
        for(var rule:grammar.rules()) {
            if (rule.annotations().stream().noneMatch(org.unlaxer.dsl.bootstrap.UBNFAST.RootAnnotation.class::isInstance)) continue;
            var plan=new CaptureBindingPlan(rule);
            for(var a:rule.annotations()) if(a instanceof org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation m)
                for(String name:m.paramNames()) for(var site:plan.sites(name)) names.put(site.id(),name);
        }
        captureNames=names;
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(
                new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            sources.add(generator.generate(grammar));
        }
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/"
                + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source.source();
                }
            }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("tests require a JDK, not a JRE", compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath",
                    System.getProperty("java.class.path") + File.pathSeparator, "-d", output.toString()),
                null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JsonArray prefix(Parser parser, String input, org.unlaxer.context.Memoization memo) throws Exception {
        var result = new JsonArray();
        try (var context = ParseContext.withOptions(org.unlaxer.StringSource.createRootSource(input), org.unlaxer.context.ParseOptions.withMemoization(memo).withDiagnostics(org.unlaxer.context.ParseOptions.Diagnostics.DETAILED))) {
            result.add(parser.parse(context).isSucceeded());
            result.add(context.getConsumedPosition().value());
            result.add(context.getMatchedPosition().value());
            result.add(context.getParseFailureDiagnostics().getFarthestOffset());
        }
        return result;
    }

    private JsonObject canonical(Object ast, Object mapped) throws Exception {
        var result = new JsonObject();
        result.addProperty("type", ast.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class)
            .invoke(mapped, ast)).orElseThrow();
        var position = new JsonArray();
        position.add(span[0]);
        position.add(span[1]);
        result.add("span", position);
        var fields = new JsonObject();
        for (var component : ast.getClass().getRecordComponents()) {
            fields.add(component.getName(), canonicalValue(component.getAccessor().invoke(ast), mapped));
        }
        result.add("fields", fields);
        return result;
    }

    private JsonElement canonicalValue(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonicalValue(optional.orElse(null), mapped);
        if (value instanceof List<?> list) {
            var result = new JsonArray();
            for (Object item : list) result.add(canonicalValue(item, mapped));
            return result;
        }
        return canonical(value, mapped);
    }

    private record ProcessResult(int code, String output) {}

    private ProcessResult run(List<String> command, String input, boolean withoutJava) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/nonexistent-unlaxer-java");
        }
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                terminateProcessTree(process);
                fail("process timed out: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) terminateProcessTree(process);
        }
    }

    private void terminateProcessTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) descendants.get(i).destroy();
        process.destroy();
        process.waitFor(2, TimeUnit.SECONDS);
        for (int i = descendants.size() - 1; i >= 0; i--) {
            ProcessHandle descendant = descendants.get(i);
            if (descendant.isAlive()) descendant.destroyForcibly();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private void success(ProcessResult result) {
        assertEquals(result.output(), 0, result.code());
    }
}
