package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.bootstrap.*;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/** Java deployability is independent from the data-only registry and Rust target. */
public class JavaTokenBindingConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    private JsonObject corpus() throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("spec-corpus/java-token-bindings/cases.json"))).getAsJsonObject();
    }
    private static String source(JsonObject row) {
        String type=row.get("javaClass").getAsString();
        return "grammar Binding { @package: bindingprobe "
            +(row.get("kind").getAsString().equals("adapter")
                ? "@tokenAdapter: { id: 'binding.word' version: '1' java: '"+type+"' rust: 'crate::adapter_word' } token T=ADAPTER('binding.word',version=1) "
                : "token T="+type+" ")
            +"@root @mapping(Value,params=[value]) Root ::= T @value; }";
    }
    private void rejectsFinal(String kind) throws Exception {
        for(var raw:corpus().getAsJsonArray("bindings")) {
            var row=raw.getAsJsonObject();
            if(!row.get("kind").getAsString().equals(kind)||!row.get("java").getAsString().equals("final"))continue;
            var grammar=UBNFMapper.parse(source(row)).grammars().get(0);
            System.clearProperty("final.token.binding.initialized");
            try {
                var output=new ParserGenerator().generate(grammar);
                var result=compile(List.of(output),temporary.newFolder().toPath());
                assertFalse("baseline final alias should fail javac",result.success());
                fail("final Java alias returned source instead of E-JAVA-TOKEN-FINAL: "+result.errors());
            } catch(IllegalArgumentException error) {
                assertTrue(error.toString(),error.getMessage().contains("E-JAVA-TOKEN-FINAL"));
                assertTrue(error.toString(),error.getMessage().contains(row.get("javaClass").getAsString()));
                assertTrue(error.toString(),error.getMessage().contains("T"));
            }
            assertNull("generation must not initialize a target class",System.getProperty("final.token.binding.initialized"));
        }
    }
    @Test public void explicitFinalAdapterIsRejectedBeforeReturningSource() throws Exception { rejectsFinal("adapter"); }
    @Test public void simpleFinalAliasIsRejectedBeforeReturningSource() throws Exception { rejectsFinal("simple"); }

    @Test public void javaValidationAvailabilityAndRealAliasBehaviorFollowTheMatrix() throws Exception {
        for(var raw:corpus().getAsJsonArray("bindings")) {
            var row=raw.getAsJsonObject();String state=row.get("java").getAsString(),label=row.get("name").getAsString();
            var grammar=UBNFMapper.parse(source(row)).grammars().get(0);
            assertTrue(label,TokenAdapterRegistry.build(grammar,null).diagnostics().isEmpty());
            assertFalse(label,GrammarValidator.validateWithoutClassLoading(grammar).stream().anyMatch(i->i.code().equals("E-JAVA-TOKEN-FINAL")));
            assertEquals(label,state.equals("final"),GrammarValidator.validate(grammar).stream().anyMatch(i->i.code().equals("E-JAVA-TOKEN-FINAL")));
            if(state.equals("final"))continue;
            var sources=new ArrayList<CodeGenerator.GeneratedSource>();
            for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator()))sources.add(generator.generate(grammar));
            Path directory=temporary.newFolder().toPath();var result=compile(sources,directory);
            if(state.equals("missing")) {
                assertFalse(label,result.success());assertTrue(result.errors(),result.errors().contains("AbsentParser"));continue;
            }
            assertTrue(label+result.errors(),result.success());
            try(var loader=new URLClassLoader(new URL[]{directory.toUri().toURL()},getClass().getClassLoader())) {
                Class<?> parsers=loader.loadClass("bindingprobe.BindingParsers"),alias=loader.loadClass("bindingprobe.BindingParsers$TParser");
                assertEquals(label,row.get("javaClass").getAsString(),alias.getSuperclass().getName());
                var parser=(Parser)parsers.getMethod("getRootParser").invoke(null);
                for(var value:corpus().getAsJsonArray("inputs"))for(var memo:Memoization.values())for(var diagnostics:ParseOptions.Diagnostics.values()) {
                    var expected=value.getAsJsonObject();String input=expected.get("input").getAsString();
                    try(var context=ParseContext.withOptions(StringSource.createRootSource(input),ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        var parsed=parser.parse(context);assertEquals(label,expected.get("success").getAsBoolean(),parsed.isSucceeded());
                        assertEquals(label,expected.get("consumed").getAsInt(),context.position());assertEquals(label,expected.get("matched").getAsInt(),context.matchedPosition());
                        if(parsed.isSucceeded()) {
                            assertTrue(label,hasAlias(parsed.getRootToken(false),alias));
                            var mapped=loader.loadClass("bindingprobe.BindingMapper").getMethod("mapParsedTokenWithSourceMap",Token.class).invoke(null,context.getCurrent().getTokens().get(0));
                            var ast=mapped.getClass().getMethod("ast").invoke(mapped);
                            assertEquals(label,expected.getAsJsonObject("ast").getAsJsonObject("fields").get("value").getAsString(),ast.getClass().getMethod("value").invoke(ast));
                            var span=(int[])((Optional<?>)mapped.getClass().getMethod("sourceSpanOf",Object.class).invoke(mapped,ast)).orElseThrow();
                            assertArrayEquals(label,new Gson().fromJson(expected.getAsJsonObject("ast").get("span"),int[].class),span);
                        }
                    }
                }
            }
        }
    }
    @Test public void javaCliRejectsKnownFinalsBeforeWritingAnyArtifacts()throws Exception {
        for(var raw:corpus().getAsJsonArray("bindings")) {
            var row=raw.getAsJsonObject();boolean rejected=row.get("java").getAsString().equals("final");
            Path directory=temporary.newFolder().toPath(),input=directory.resolve("input.ubnf"),output=directory.resolve("output");
            Files.writeString(input,source(row));
            var command=javaCli();command.addAll(List.of("--grammar",input.toString(),"--output",output.toString(),"--generators","Parser"));
            String log=runExpected(command,rejected?3:0);
            if(rejected){assertTrue(log,log.contains("E-JAVA-TOKEN-FINAL"));assertFalse(row.toString(),Files.exists(output));}
            else assertTrue(row.toString(),Files.isRegularFile(output.resolve("bindingprobe/BindingParsers.java")));
        }
    }
    private static ArrayList<String> javaCli() {
        return new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"--enable-preview",
            "-cp",System.getProperty("java.class.path"),"org.unlaxer.dsl.CodegenMain"));
    }
    private static boolean hasAlias(Token token,Class<?> alias) {
        if(alias.isInstance(token.parser))return true;
        return token.getOriginalChildren().stream().anyMatch(child->hasAlias(child,alias));
    }
    private record Compilation(boolean success,String errors) {}
    private static Compilation compile(List<CodeGenerator.GeneratedSource> sources,Path directory)throws Exception {
        var units=new ArrayList<JavaFileObject>();
        for(var source:sources)units.add(new SimpleJavaFileObject(URI.create("string:///bindingprobe/"+source.className()+".java"),JavaFileObject.Kind.SOURCE){
            @Override public CharSequence getCharContent(boolean ignore){return source.source();}
        });
        var diagnostics=new DiagnosticCollector<JavaFileObject>();var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            boolean result=compiler.getTask(null,manager,diagnostics,List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",directory.toString()),null,units).call();
            return new Compilation(result,diagnostics.getDiagnostics().toString());
        }
    }
    @Test public void bothRustHostsRunRegardlessOfOptionalJavaClassAvailability()throws Exception {
        if(!Boolean.getBoolean("rustConformance"))System.out.println("[assumption] Java token-binding cross-host matrix requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo","build","--locked","--manifest-path",ROOT.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"));
        for(var raw:corpus().getAsJsonArray("bindings")) {
            var row=raw.getAsJsonObject();if(!row.get("rust").getAsBoolean())continue;
            Path directory=temporary.newFolder().toPath(),input=directory.resolve("input.ubnf"),output=directory.resolve("generated");
            Files.writeString(input,source(row));var grammar=UBNFMapper.parse(source(row)).grammars().get(0);
            run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",output.toString()));
            Path javaOutput=directory.resolve("java-host");var javaCommand=javaCli();
            javaCommand.addAll(List.of("generate","--target","rust","--grammar",input.toString(),"--output",javaOutput.toString()));run(javaCommand);
            for(var file:new RustBackend().generate(grammar)) {
                assertEquals(row.get("name")+" "+file.relativePath(),file.content(),Files.readString(output.resolve(file.relativePath())));
                assertEquals(row.get("name")+" Java CLI "+file.relativePath(),file.content(),Files.readString(javaOutput.resolve(file.relativePath())));
            }
            Files.writeString(directory.resolve("main.rs"),"""
                #![allow(dead_code,unused_imports)]
                mod generated; use unlaxer_runtime::*;
                fn adapter_word(c:&mut ParseContext<'_>)->ParseResult{c.parse(&Expr::Literal("a"))}
                fn main(){let args:Vec<_>=std::env::args().skip(1).collect();
                    for memo in [Memoization::Off,Memoization::SafeFailures]{for d in [Diagnostics::Auto,Diagnostics::Detailed,Diagnostics::DetailedOnFailure]{
                        for row in args.chunks_exact(5){
                            let mut c=ParseContext::with_options(&row[0],ParseOptions::with_memoization(memo).with_diagnostics(d));
                            let result=c.parse_shared_grammar(generated::parser::grammar(),0,false);assert_eq!(result.is_ok(),row[1].parse::<bool>().unwrap());assert_eq!(c.position(),row[2].parse::<usize>().unwrap());assert_eq!(c.matched_position(),row[3].parse::<usize>().unwrap());
                            if let Ok(parsed)=result{let tree=c.tree(parsed.nodes[0]).unwrap();let ast=generated::mapper::map(&tree).unwrap();assert_eq!(ast.canonical_json(),row[4]);}
                        }
                    }}
                }
                """);
            Files.writeString(directory.resolve("Cargo.toml"),"[package]\nname=\"binding-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[dependencies]\nunlaxer-runtime={path=\""+ROOT.resolve("rust/unlaxer-runtime")+"\"}\n");
            var command=new ArrayList<String>(List.of("cargo","run","--offline","--quiet","--manifest-path",directory.resolve("Cargo.toml").toString(),"--target-dir",ROOT.resolve("rust/target/binding-probe").toString(),"--"));
            for(var value:corpus().getAsJsonArray("inputs")) {
                var rowValue=value.getAsJsonObject();
                command.add(rowValue.get("input").getAsString());command.add(rowValue.get("success").getAsString());
                command.add(rowValue.get("consumed").getAsString());command.add(rowValue.get("matched").getAsString());
                command.add(rowValue.has("ast")?rowValue.get("ast").toString():"null");
            }
            run(command);
        }
    }
    private void run(List<String> command)throws Exception {runExpected(command,0);}
    private String runExpected(List<String> command,int exitCode)throws Exception {
        Path log=temporary.newFile().toPath();var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(180,TimeUnit.SECONDS)){process.destroyForcibly();fail("timeout "+command);}
        String output=Files.readString(log);assertEquals(command+"\n"+output,exitCode,process.exitValue());return output;
    }
}
