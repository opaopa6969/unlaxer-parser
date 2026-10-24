package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.Memoization;
import org.unlaxer.parser.Parser;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFModuleLoader;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Generation-stage decisions and actual parsing share one independent corpus. */
public class OwnedCaptureConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private JsonArray cases() throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("spec-corpus/owned-capture/cases.json"))).getAsJsonArray();
    }
    @Test public void javaGeneratedOwnedCapturesMatchIndependentOracle() throws Exception {
        var reuseReport=new ArrayList<String>(List.of("fixture\tmemo\tdiagnostics\tretry\tfallback"));
        for (var raw : cases()) {
            var row=raw.getAsJsonObject();
            var grammar=UBNFModuleLoader.load(writeGrammar(row,temporary.newFolder().toPath())).grammars().get(0);
            var units=new ArrayList<JavaFileObject>();
            for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator(),new EvaluatorGenerator(21))) {
                var source=generator.generate(grammar);
                units.add(new SimpleJavaFileObject(URI.create("string:///ownedprobe/"+source.className()+".java"),JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignore){return source.source();}
                });
            }
            Path classes=temporary.newFolder().toPath();
            var compiler=org.unlaxer.dsl.Java21TestSupport.compiler();
            var diagnostics=new DiagnosticCollector<JavaFileObject>();
            try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
                boolean compiled=compiler.getTask(null,manager,diagnostics,
                    List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,units).call();
                assertTrue(row.get("name")+" "+diagnostics.getDiagnostics(),compiled);
            }
            try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
                var parser=(Parser)loader.loadClass("ownedprobe.OwnedParsers").getMethod("getRootParser").invoke(null);
                var mapper=loader.loadClass("ownedprobe.OwnedMapper");
                for(var value:row.getAsJsonArray("cases")) {
                    var expected=value.getAsJsonObject(); String input=expected.get("input").getAsString();
                    for(var memo:Memoization.values()) for(var mode:ParseOptions.Diagnostics.values())
                    try(var context=ParseContext.withOptions(StringSource.createRootSource(input),ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                        context.capture("sentinel",new org.unlaxer.CapturedValue("",org.unlaxer.CapturedValue.Provenance.INPUT,0,0));
                        String label=row.get("name")+" "+expected+" "+memo+" "+mode;
                        org.unlaxer.Parsed parsed=org.unlaxer.Parsed.FAILED;
                        org.unlaxer.context.ParseLimitException resource=null;
                        try{parsed=parser.parse(context);}catch(org.unlaxer.context.ParseLimitException error){resource=error;}
                        assertEquals(label,expected.has("resource"),resource!=null);
                        if(resource!=null){assertEquals(label+" immediate resource frame restoration",1,context.getTokenStack().size());var saved=resource;assertSame(label,saved,assertThrows(org.unlaxer.context.ParseLimitException.class,()->new org.unlaxer.parser.combinator.Repeat(new org.unlaxer.parser.elementary.WordParser("unused"),0,0).parse(context)));}
                        assertEquals(label,expected.get("success").getAsBoolean(),parsed.isSucceeded());
                        assertEquals(label,expected.get("consumed").getAsInt(),context.getConsumedPosition().value());
                        assertEquals(label,expected.get("matched").getAsInt(),context.getMatchedPosition().value());
                        if(expected.has("failureOffset")) {
                            if(mode==ParseOptions.Diagnostics.DETAILED)
                                assertEquals(label+" context diagnostic",expected.get("failureOffset").getAsInt(),context.getParseFailureDiagnostics().getFarthestOffset());
                            var diagnostic=((Optional<?>)mapper.getMethod("diagnose",String.class,ParseOptions.class)
                                .invoke(null,input,ParseOptions.withMemoization(memo).withDiagnostics(mode))).orElseThrow();
                            assertEquals(label+" full-input kind","syntax",diagnostic.getClass().getMethod("kind").invoke(diagnostic));
                            assertEquals(label+" full-input offset",expected.get("failureOffset").getAsInt(),diagnostic.getClass().getMethod("offset").invoke(diagnostic));
                        }
                        assertEquals(label,1,context.getTokenStack().size());
                        assertEquals(label,"",context.captured("sentinel").orElseThrow().text());
                        var binding=context.captured("word");
                        com.google.gson.JsonElement bindingJson=JsonNull.INSTANCE;
                        if(binding.isPresent()){
                            var b=binding.orElseThrow();var object=new JsonObject();object.addProperty("text",b.text());object.addProperty("source",b.provenance().name());object.addProperty("start",b.start());object.addProperty("end",b.end());bindingJson=object;
                        }
                        assertEquals(label,expected.has("binding")?expected.get("binding"):JsonNull.INSTANCE,bindingJson);
                        if(row.get("name").getAsString().startsWith("nested-") && !parsed.isSucceeded() && resource==null){
                            int consumed=context.position(),matched=context.matchedPosition();
                            boolean reset=context.getCurrent().isResetMatchedWithConsumed();
                            var tokens=context.getCurrent().getTokens().stream().toList();
                            assertTrue(label+" same-context retry rejects",parser.parse(context).isFailed());
                            assertEquals(label,consumed,context.position());assertEquals(label,matched,context.matchedPosition());
                            assertEquals(label,reset,context.getCurrent().isResetMatchedWithConsumed());
                            assertEquals(label,tokens,context.getCurrent().getTokens().stream().toList());
                            assertEquals(label,binding,context.captured("word"));assertEquals(label,"",context.captured("sentinel").orElseThrow().text());
                            assertTrue(label+" same-context fallback",new org.unlaxer.parser.elementary.WordParser(input).parse(context).isSucceeded());
                            assertEquals(label,input.codePointCount(0,input.length()),context.position());
                            assertEquals(label,context.position(),context.matchedPosition());
                            assertEquals(label,binding,context.captured("word"));assertEquals(label,"",context.captured("sentinel").orElseThrow().text());
                            reuseReport.add(row.get("name").getAsString()+"\t"+memo+"\t"+mode+"\trejected\tsucceeded");
                        }
                        if(parsed.isSucceeded()){
                            var mapped=mapper.getMethod("mapParsedTokenWithSourceMap",org.unlaxer.Token.class).invoke(null,context.getCurrent().getTokens().get(0));
                            assertNotNull(label,mapped);
                            var ast=mapped.getClass().getMethod("ast").invoke(mapped);
                            assertEquals(label,expected.get("ast"),canonicalAst(ast,mapped));
                    }
                    }
                }
            }
        }
        assertEquals("four non-input rejection cases across six options",25,reuseReport.size());
        Files.write(Path.of("target/owned-capture-reuse-java.tsv"),reuseReport);
    }
    @Test public void bothRustHostsGenerateAndRunTheSameOwnedCaptureOracle() throws Exception {
        if(!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] OwnedCaptureConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo","build","--locked","--manifest-path",ROOT.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"),0);
        var report=new ArrayList<String>(List.of("fixture\thost\tcase\toption\tphase\tactual"));
        for(var raw:cases()) {
            var row=raw.getAsJsonObject();
            Path dir=temporary.newFolder().toPath(),input=writeGrammar(row,dir),out=dir.resolve("generated");
            var grammar=UBNFModuleLoader.load(input).grammars().get(0);
            run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",out.toString()),0);
            Path javaGenerated=Files.createDirectory(dir.resolve("java-generated"));
            var javaModules=new RustBackend().generate(grammar);assertEquals(5,javaModules.size());
            for(var file:javaModules){
                assertEquals(row.get("name")+" "+file.relativePath(),file.content(),Files.readString(out.resolve(file.relativePath())));
                Files.writeString(javaGenerated.resolve(file.relativePath()),file.content());
            }
            Files.writeString(dir.resolve("main.rs"), rustProbe());
            Files.writeString(dir.resolve("java-main.rs"), rustProbe().replace("mod generated;","#[path=\"java-generated/mod.rs\"] mod generated;"));
            Files.writeString(dir.resolve("Cargo.toml"),"[package]\nname=\"nullable-repetition-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[[bin]]\nname=\"java-probe\"\npath=\"java-main.rs\"\n[dependencies]\nunlaxer-runtime={path=\""+ROOT.resolve("rust/unlaxer-runtime")+"\"}\n");
            Path target=ROOT.resolve("rust/target/owned-capture-probe");
            run(List.of("cargo","build","--offline","--quiet","--manifest-path",dir.resolve("Cargo.toml").toString(),"--target-dir",target.toString()),0);
            List<String> nativeOutput=null;
            for(String host:List.of("native","java")){
                var command=new ArrayList<String>(List.of(target.resolve("debug/"+(host.equals("native")?"probe":"java-probe")).toString()));
                for(var value:row.getAsJsonArray("cases")){var expected=value.getAsJsonObject();command.add(phase(row,expected));command.add(expected.get("input").getAsString());}
                var output=run(command,0).lines().toList();assertEquals(row.get("name").toString(),row.getAsJsonArray("cases").size()*6,output.size());
                int index=0,caseIndex=0;
                for(var value:row.getAsJsonArray("cases")){
                    var expected=value.getAsJsonObject();
                    for(int option=0;option<6;option++){
                        String actual=output.get(index++),label=row.get("name")+" "+host+" "+expected+" option="+option;
                        var parts=actual.split("\\t",-1);
                        assertEquals(label,5,parts.length);assertEquals(label,expected.get("success").getAsString(),parts[0]);
                        assertEquals(label,expected.get("consumed").getAsString(),parts[1]);assertEquals(label,expected.get("matched").getAsString(),parts[2]);
                        assertEquals(label,expected.has("ast")?expected.get("ast"):JsonNull.INSTANCE,JsonParser.parseString(parts[3]));
                        assertEquals(label,expected.has("binding")?expected.get("binding"):JsonNull.INSTANCE,JsonParser.parseString(parts[4]));
                        report.add(row.get("name").getAsString()+"\t"+host+"\t"+caseIndex+"\t"+option+"\t"+phase(row,expected)+"\t"+actual);
                    }
                    caseIndex++;
                }
                if(nativeOutput==null)nativeOutput=output;else assertEquals("independently executed Rust hosts",nativeOutput,output);
            }
        }
        Files.write(Path.of("target/owned-capture-rust-hosts.tsv"),report);
    }
    private static String phase(JsonObject row,JsonObject expected){
        if(expected.has("failureOffset"))return (row.get("name").getAsString().startsWith("nested-")?"retry:":"reject:")+expected.get("failureOffset").getAsInt();
        if(expected.has("resource"))return "resource";
        return row.get("name").getAsString().startsWith("nested-")&&!expected.get("success").getAsBoolean()?"retry":"normal";
    }
    private static Path writeGrammar(JsonObject row, Path directory) throws Exception {
        if (row.has("modules")) for (var module : row.getAsJsonObject("modules").entrySet()) {
            Path file = directory.resolve(module.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, module.getValue().getAsString());
        }
        Path input = directory.resolve("input.ubnf");
        Files.writeString(input, row.get("grammar").getAsString());
        return input;
    }
    private static JsonElement canonicalValue(Object value,Object map) throws Exception {
        if(value==null)return JsonNull.INSTANCE;
        if(value instanceof Optional<?> optional)return optional.isEmpty()?JsonNull.INSTANCE:canonicalValue(optional.orElseThrow(),map);
        if(value instanceof List<?> list){var array=new JsonArray();for(var item:list)array.add(canonicalValue(item,map));return array;}
        if(value.getClass().isRecord())return canonicalAst(value,map);
        return new Gson().toJsonTree(value);
    }
    private static JsonObject canonicalAst(Object ast,Object map) throws Exception {
        var result=new JsonObject();result.addProperty("type",ast.getClass().getSimpleName());
        var range=(Optional<?>)map.getClass().getMethod("sourceSpanOf",Object.class).invoke(map,ast);var span=(int[])range.orElseThrow();
        result.add("span",new Gson().toJsonTree(span));var fields=new JsonObject();
        for(var field:ast.getClass().getRecordComponents())fields.add(field.getName(),canonicalValue(field.getAccessor().invoke(ast),map));
        result.add("fields",fields);return result;
    }
    private static String rustProbe() {
        return """
            #![allow(dead_code,unused_imports,unused_variables)]
            mod generated;
            use unlaxer_runtime::*;
            use unlaxer_runtime::token::{TokenKind,TokenSource};
            fn generated_token(c:&mut ParseContext<'_>)->ParseResult{
                c.declare_complete_token_trace();let at=c.position();
                let id=c.add_token_node(TOKEN_LEAF_RULE,TokenKind::VirtualConsumed,TokenSource::Generated{anchor:at,text:"bb".into()}).unwrap();
                Ok(ParseMatch{span:Span{start:at,end:at},nodes:vec![id.index()],captures:vec![]})
            }
            fn projected_token(c:&mut ParseContext<'_>)->ParseResult{
                c.declare_complete_token_trace();if c.text(Span{start:1,end:2})!=Some("b"){return Err(c.error("projected source"));}
                let id=c.add_token_node(TOKEN_LEAF_RULE,TokenKind::VirtualConsumed,TokenSource::Projected{span:Span{start:1,end:3},text:std::sync::Arc::from("bb")}).unwrap();
                assert!(c.advance(1));Ok(ParseMatch{span:Span{start:1,end:3},nodes:vec![id.index()],captures:vec![]})
            }
            fn binding(c:&ParseContext<'_>)->String{
                let Some(source)=c.captured_source("word")else{return "null".into()};
                let (kind,start,end)=match source{TokenSource::Input(s)=>("INPUT",s.start,s.end),TokenSource::Projected{span,..}=>("PROJECTED",span.start,span.end),TokenSource::Generated{anchor,..}=>("GENERATED",*anchor,*anchor)};
                format!("{{{}:{},{}:{},{}:{},{}:{}}}",json_string("text"),json_string(c.captured_text("word").unwrap()),json_string("source"),json_string(kind),json_string("start"),start,json_string("end"),end)
            }
            fn main(){
                let root=generated::parser::rules().iter().position(|rule|rule.name=="Root").unwrap();
                let args:Vec<_>=std::env::args().skip(1).collect();
                for pair in args.chunks_exact(2){let phase=&pair[0];let input=&pair[1];
                    for memo in [Memoization::Off,Memoization::SafeFailures]{for mode in [Diagnostics::Auto,Diagnostics::Detailed,Diagnostics::DetailedOnFailure]{
                        let mut context=ParseContext::with_options(&input,ParseOptions::with_memoization(memo).with_diagnostics(mode));
                        context.parse(&Expr::Literal("unused").repeat_java(0,Some(0)).capture_owned("sentinel")).unwrap();
                        let result=context.parse_shared_grammar(generated::parser::grammar(),root,false);let success=result.is_ok();
                        if let Some((_,offset))=phase.split_once(':') {
                            let offset=offset.parse::<usize>().unwrap();
                            let error=result.as_ref().expect_err("context must reject non-input @f");
                            if mode==Diagnostics::Detailed {assert_eq!(error.offset,offset);}
                            assert!(error.expected.iter().any(|e|e.contains("original input source")) || mode!=Diagnostics::Detailed);
                            let options=ParseOptions::with_memoization(memo).with_diagnostics(mode);
                            let error=generated::parser::parse_tree_detailed_with_options(input,options).unwrap_err();
                            assert_eq!((error.kind,error.offset),("syntax",offset));
                            let grammar=generated::parser::grammar();
                            let error=parse_detailed_shared_with_rule_stack(&grammar,root,false,input,options).unwrap_err();
                            assert_eq!((error.diagnostic.kind,error.diagnostic.offset),("syntax",offset));
                        }
                        if phase=="resource"{let error=result.as_ref().unwrap_err();assert!(error.expected.iter().any(|value|value.contains("below")));assert_eq!(&context.parse(&Expr::Literal("unused").repeat_java(0,Some(0))).unwrap_err(),error);}
                        let ast=if let Ok(parsed)=result{let tree=context.tree(parsed.nodes[0]).unwrap();generated::mapper::map(&tree).unwrap().canonical_json()}else{"null".into()};
                        assert_eq!(context.captured_text("sentinel"),Some(""));
                        println!("{}\t{}\t{}\t{}\t{}",success,context.position(),context.matched_position(),ast,binding(&context));
                        if phase.starts_with("retry"){
                            assert!(!success);
                            let before=(context.position(),context.matched_position(),context.reset_matched_with_consumed());
                            let before_binding=binding(&context);let before_nodes=(0..).map_while(|i|context.node(i)).map(|node|format!("{node:?}")).collect::<Vec<_>>();
                            assert!(context.parse_shared_grammar(generated::parser::grammar(),root,false).is_err());
                            assert_eq!((context.position(),context.matched_position(),context.reset_matched_with_consumed()),before);
                            assert_eq!(binding(&context),before_binding);assert_eq!(context.captured_text("sentinel"),Some(""));
                            assert_eq!((0..).map_while(|i|context.node(i)).map(|node|format!("{node:?}")).collect::<Vec<_>>(),before_nodes);
                            assert!(context.parse(&Expr::literal(Box::leak(input.to_owned().into_boxed_str()))).is_ok());
                            assert_eq!((context.position(),context.matched_position()),(input.chars().count(),input.chars().count()));
                            assert_eq!(binding(&context),before_binding);assert_eq!(context.captured_text("sentinel"),Some(""));
                        }

                    }}
                }
            }
            """;
    }
    private String run(List<String> command,int expected) throws Exception {
        Path log=temporary.newFile().toPath();
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(180,TimeUnit.SECONDS)) {process.destroyForcibly();fail("timeout "+command);}
        String output=Files.readString(log);
        if(expected==0) assertEquals(command+"\n"+output,0,process.exitValue());
        else assertNotEquals(command+"\n"+output,0,process.exitValue());
        return output;
    }
}
