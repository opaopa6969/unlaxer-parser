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
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Generation-stage decisions and actual parsing share one independent corpus. */
public class NullableSeparationConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private JsonArray cases() throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("spec-corpus/nullable-separation/cases.json"))).getAsJsonArray();
    }
    @Test public void javaGeneratedNullableSeparationsMatchIndependentOracle() throws Exception {
        for (var raw : cases()) {
            var row=raw.getAsJsonObject();
            var grammar=UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            var units=new ArrayList<JavaFileObject>();
            for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator(),new EvaluatorGenerator(21))) {
                var source=generator.generate(grammar);
                units.add(new SimpleJavaFileObject(URI.create("string:///nullableprobe/"+source.className()+".java"),JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignore){return source.source();}
                });
            }
            Path classes=temporary.newFolder().toPath();
            var compiler=org.unlaxer.dsl.Java21TestSupport.compiler();
            var diagnostics=new DiagnosticCollector<JavaFileObject>();
            try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
                assertTrue(row.get("name")+" "+diagnostics.getDiagnostics(),compiler.getTask(null,manager,diagnostics,
                    List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,units).call());
            }
            try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
                var parser=(Parser)loader.loadClass("nullableprobe.NullableParsers").getMethod("getRootParser").invoke(null);
                var mapper=loader.loadClass("nullableprobe.NullableMapper");
                for(var value:row.getAsJsonArray("cases")) {
                    var expected=value.getAsJsonObject(); String input=expected.get("input").getAsString();
                    for(var memo:Memoization.values()) for(var mode:ParseOptions.Diagnostics.values())
                    try(var context=ParseContext.withOptions(StringSource.createRootSource(input),ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                        var parsed=parser.parse(context); String label=row.get("name")+" "+expected+" "+memo+" "+mode;
                        assertEquals(label,expected.get("success").getAsBoolean(),parsed.isSucceeded());
                        assertEquals(label,expected.get("consumed").getAsInt(),context.getConsumedPosition().value());
                        assertEquals(label,expected.get("matched").getAsInt(),context.getMatchedPosition().value());
                        assertEquals(label,1,context.getTokenStack().size());
                        if(parsed.isSucceeded()){
                            var mapped=mapper.getMethod("mapParsedTokenWithSourceMap",org.unlaxer.Token.class).invoke(null,context.getCurrent().getTokens().get(0));
                            assertNotNull(label,mapped);
                            if(expected.has("items")){
                                var ast=mapped.getClass().getMethod("ast").invoke(mapped);
                                assertEquals(label,expected.get("items"),new Gson().toJsonTree(ast.getClass().getMethod("items").invoke(ast)));
                            }
                        }
                    }
                }
            }
        }
    }
    @Test public void bothRustHostsGenerateAndRunTheSameNullableSeparationOracle() throws Exception {
        if(!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] NullableSeparationConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo","build","--locked","--manifest-path",ROOT.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"),0);
        for(var raw:cases()) {
            var row=raw.getAsJsonObject(); String source=row.get("grammar").getAsString();
            var grammar=UBNFMapper.parse(source).grammars().get(0);
            Path dir=temporary.newFolder().toPath(),input=dir.resolve("input.ubnf"),out=dir.resolve("generated");
            Files.writeString(input,source);
            run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",out.toString()),0);
            for(var file:new RustBackend().generate(grammar)) assertEquals(row.get("name")+" "+file.relativePath(),file.content(),Files.readString(out.resolve(file.relativePath())));
            Files.writeString(dir.resolve("main.rs"), rustProbe(java.util.stream.StreamSupport.stream(row.getAsJsonArray("cases").spliterator(),false).anyMatch(value -> value.getAsJsonObject().has("items"))));
            Files.writeString(dir.resolve("Cargo.toml"),"[package]\nname=\"nullable-repetition-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[dependencies]\nunlaxer-runtime={path=\""+ROOT.resolve("rust/unlaxer-runtime")+"\"}\n");
            Path target=ROOT.resolve("rust/target/nullable-separation-probe");
            run(List.of("cargo","build","--offline","--quiet","--manifest-path",dir.resolve("Cargo.toml").toString(),"--target-dir",target.toString()),0);
            var command=new ArrayList<String>(List.of(target.resolve("debug/probe").toString()));
            var expectedLines=new ArrayList<String>();
            for(var value:row.getAsJsonArray("cases")) {
                var expected=value.getAsJsonObject(); command.add(expected.get("input").getAsString());
                for(int n=0;n<6;n++)expectedLines.add(expected.get("success")+"\t"+expected.get("consumed")+"\t"+expected.get("matched")+"\t"+(expected.has("items")?expected.get("items").toString():"-"));
            }
            assertEquals(row.get("name").toString(),expectedLines,run(command,0).lines().toList());
        }
    }
    private static String rustProbe(boolean items) {
        String observe=items ? "match &ast { generated::ast::Ast::Node { items, .. } => format!(\"[{}]\", items.iter().map(|value|json_string(value)).collect::<Vec<_>>().join(\",\")) }" : "\"-\".to_owned()";
        return """
            #![allow(dead_code,unused_imports,unused_variables)]
            mod generated;
            use unlaxer_runtime::*;
            fn json_string(value:&str)->String {
                let mut result=String::from(char::from(34));
                for ch in value.chars(){
                    if ch as u32==34 || ch as u32==92 {result.push(char::from(92));result.push(ch);}
                    else if (ch as u32)<32 {result.push(char::from(92));result.push('u');result.push_str(&format!("{:04x}",ch as u32));}
                    else {result.push(ch);}
                }
                result.push(char::from(34));result
            }
            fn main(){
                let root=generated::parser::rules().iter().position(|rule|rule.name=="Root").unwrap();
                for input in std::env::args().skip(1){
                    for memo in [Memoization::Off,Memoization::SafeFailures]{
                        for mode in [Diagnostics::Auto,Diagnostics::Detailed,Diagnostics::DetailedOnFailure]{
                            let options=ParseOptions::with_memoization(memo).with_diagnostics(mode);
                            let mut context=ParseContext::with_options(&input,options);
                            let result=context.parse_shared_grammar(generated::parser::grammar(),root,false);
                            let succeeded=result.is_ok();
                            let value=if let Ok(parsed)=result{let tree=context.tree(parsed.nodes[0]).unwrap();let ast=generated::mapper::map(&tree).unwrap(); OBSERVE }else{"-".to_owned()};
                            println!("{}\t{}\t{}\t{}",succeeded,context.position(),context.matched_position(),value);
                        }
                    }
                }
            }
            """.replace("OBSERVE",observe);
    }
    private String run(List<String> command,int expected) throws Exception {
        Path log=temporary.newFile().toPath();
        var process=org.unlaxer.dsl.TestProcesses.builder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(180,TimeUnit.SECONDS)) {process.destroyForcibly();fail("timeout "+command);}
        String output=Files.readString(log);
        if(expected==0) assertEquals(command+"\n"+output,0,process.exitValue());
        else assertNotEquals(command+"\n"+output,0,process.exitValue());
        return output;
    }
}
