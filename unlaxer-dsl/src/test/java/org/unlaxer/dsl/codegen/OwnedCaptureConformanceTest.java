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
            var compiler=ToolProvider.getSystemJavaCompiler();
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
                        if(resource!=null){var saved=resource;assertSame(label,saved,assertThrows(org.unlaxer.context.ParseLimitException.class,()->new org.unlaxer.parser.combinator.Repeat(new org.unlaxer.parser.elementary.WordParser("unused"),0,0).parse(context)));}
                        assertEquals(label,expected.get("success").getAsBoolean(),parsed.isSucceeded());
                        assertEquals(label,expected.get("consumed").getAsInt(),context.getConsumedPosition().value());
                        assertEquals(label,expected.get("matched").getAsInt(),context.getMatchedPosition().value());
                        assertEquals(label,1,context.getTokenStack().size());
                        assertEquals(label,"",context.captured("sentinel").orElseThrow().text());
                        var binding=context.captured("word");
                        com.google.gson.JsonElement bindingJson=JsonNull.INSTANCE;
                        if(binding.isPresent()){
                            var b=binding.orElseThrow();var object=new JsonObject();object.addProperty("text",b.text());object.addProperty("source",b.provenance().name());object.addProperty("start",b.start());object.addProperty("end",b.end());bindingJson=object;
                        }
                        assertEquals(label,expected.has("binding")?expected.get("binding"):JsonNull.INSTANCE,bindingJson);
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
    }
    @Test public void bothRustHostsGenerateAndRunTheSameOwnedCaptureOracle() throws Exception {
        if(!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] OwnedCaptureConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo","build","--locked","--manifest-path",ROOT.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"),0);
        for(var raw:cases()) {
            var row=raw.getAsJsonObject();
            Path dir=temporary.newFolder().toPath(),input=writeGrammar(row,dir),out=dir.resolve("generated");
            var grammar=UBNFModuleLoader.load(input).grammars().get(0);
            run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",out.toString()),0);
            for(var file:new RustBackend().generate(grammar)) assertEquals(row.get("name")+" "+file.relativePath(),file.content(),Files.readString(out.resolve(file.relativePath())));
            Files.writeString(dir.resolve("main.rs"), rustProbe());
            Files.writeString(dir.resolve("Cargo.toml"),"[package]\nname=\"nullable-repetition-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[dependencies]\nunlaxer-runtime={path=\""+ROOT.resolve("rust/unlaxer-runtime")+"\"}\n");
            Path target=ROOT.resolve("rust/target/owned-capture-probe");
            run(List.of("cargo","build","--offline","--quiet","--manifest-path",dir.resolve("Cargo.toml").toString(),"--target-dir",target.toString()),0);
            var command=new ArrayList<String>(List.of(target.resolve("debug/probe").toString()));
            for(var value:row.getAsJsonArray("cases")){command.add(value.getAsJsonObject().has("resource")?"resource":"normal");command.add(value.getAsJsonObject().get("input").getAsString());}
            var output=run(command,0).lines().toList();assertEquals(row.get("name").toString(),row.getAsJsonArray("cases").size()*6,output.size());
            int index=0;
            for(var value:row.getAsJsonArray("cases"))for(int option=0;option<6;option++){
                var expected=value.getAsJsonObject();String label=row.get("name")+" "+expected+" option="+option;var parts=output.get(index++).split("\\t",-1);
                assertEquals(label,5,parts.length);assertEquals(label,expected.get("success").getAsString(),parts[0]);
                assertEquals(label,expected.get("consumed").getAsString(),parts[1]);assertEquals(label,expected.get("matched").getAsString(),parts[2]);
                assertEquals(label,expected.has("ast")?expected.get("ast"):JsonNull.INSTANCE,JsonParser.parseString(parts[3]));
                assertEquals(label,expected.has("binding")?expected.get("binding"):JsonNull.INSTANCE,JsonParser.parseString(parts[4]));
            }
        }
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
                        if phase=="resource"{let error=result.as_ref().unwrap_err();assert!(error.expected.iter().any(|value|value.contains("below")));assert_eq!(&context.parse(&Expr::Literal("unused").repeat_java(0,Some(0))).unwrap_err(),error);}
                        let ast=if let Ok(parsed)=result{let tree=context.tree(parsed.nodes[0]).unwrap();generated::mapper::map(&tree).unwrap().canonical_json()}else{"null".into()};
                        assert_eq!(context.captured_text("sentinel"),Some(""));
                        println!("{}\t{}\t{}\t{}\t{}",success,context.position(),context.matched_position(),ast,binding(&context));
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
