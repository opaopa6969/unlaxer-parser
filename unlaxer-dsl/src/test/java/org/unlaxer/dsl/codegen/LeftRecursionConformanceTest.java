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
public class LeftRecursionConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private JsonArray cases() throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("spec-corpus/left-recursion/cases.json"))).getAsJsonArray();
    }
    @Test public void javaRejectsCyclesBeforeGenerationAndRunsConsumingRecursion() throws Exception {
        for (var raw : cases()) {
            var row=raw.getAsJsonObject();
            var grammar=UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            boolean rejected=row.get("status").getAsString().equals("reject");
            assertEquals(row.get("name").toString(),rejected,GrammarValidator.validateWithWarnings(grammar).isPresent());
            var issues=GrammarValidator.validateWithoutClassLoading(grammar);
            assertEquals(row.get("name")+" "+issues,rejected,
                issues.stream().anyMatch(issue->issue.code().equals("E-LEFT-RECURSION")));
            var generators=List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator(),new EvaluatorGenerator(21));
            if (rejected) {
                for(CodeGenerator generator:generators) {
                    var error=assertThrows(IllegalArgumentException.class,()->generator.generate(grammar));
                    assertTrue(row.get("name")+" "+error,error.getMessage().contains("E-LEFT-RECURSION"));
                }
                continue;
            }
            var units=new ArrayList<JavaFileObject>();
            for(CodeGenerator generator:generators) {
                var source=generator.generate(grammar);
                units.add(new SimpleJavaFileObject(URI.create("string:///leftrecprobe/"+source.className()+".java"),JavaFileObject.Kind.SOURCE) {
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
                var parser=(Parser)loader.loadClass("leftrecprobe.LeftRecParsers").getMethod("getRootParser").invoke(null);
                for(var value:row.getAsJsonArray("inputs")) {
                    String input=value.getAsString();
                    for(var memo:Memoization.values()) for(var mode:ParseOptions.Diagnostics.values())
                    try(var context=ParseContext.withOptions(StringSource.createRootSource(input),ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                        var parsed = parser.parse(context);
                        assertTrue(row.get("name")+" "+input,parsed.isSucceeded());
                        var mapped = loader.loadClass("leftrecprobe.LeftRecMapper")
                            .getMethod("mapParsedTokenWithSourceMap", org.unlaxer.Token.class)
                            .invoke(null, context.getCurrent().getTokens().get(0));
                        assertNotNull(row.get("name")+" "+input, mapped);
                        assertEquals(input.codePointCount(0,input.length()),context.getConsumedPosition().value());
                    }
                }
            }
        }
    }
    @Test public void bothRustHostsRejectSameCyclesAndCompileAndRunValidGrammar() throws Exception {
        if(!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] LeftRecursionConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo","build","--locked","--manifest-path",ROOT.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"),0);
        for(var raw:cases()) {
            var row=raw.getAsJsonObject();
            String source=row.get("grammar").getAsString();
            var grammar=UBNFMapper.parse(source).grammars().get(0);
            boolean rejected=row.get("status").getAsString().equals("reject");
            Path dir=temporary.newFolder().toPath(),input=dir.resolve("input.ubnf"),out=dir.resolve("generated");
            Files.writeString(input,source);
            String result=run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",out.toString()),rejected?1:0);
            if(rejected) {
                assertTrue(row.get("name")+" "+result,result.contains("E-LEFT-RECURSION"));
                assertFalse(Files.exists(out.resolve("parser.rs")));
                assertTrue(assertThrows(IllegalArgumentException.class,()->new RustBackend().generate(grammar)).getMessage().contains("E-LEFT-RECURSION"));
                continue;
            }
            for(var file:new RustBackend().generate(grammar)) assertEquals(row.get("name")+" "+file.relativePath(),file.content(),Files.readString(out.resolve(file.relativePath())));
            Files.writeString(dir.resolve("main.rs"),"#![allow(dead_code,unused_imports)]\nmod generated; fn main(){for input in std::env::args().skip(1){for memo in [unlaxer_runtime::Memoization::Off,unlaxer_runtime::Memoization::SafeFailures] {for mode in [unlaxer_runtime::Diagnostics::Auto,unlaxer_runtime::Diagnostics::Detailed,unlaxer_runtime::Diagnostics::DetailedOnFailure] {let tree=generated::parser::parse_tree_with_options(&input,unlaxer_runtime::ParseOptions::with_memoization(memo).with_diagnostics(mode)).unwrap(); generated::mapper::map(&tree).unwrap(); assert_eq!(tree.nodes[tree.root].span, unlaxer_runtime::Span { start: 0, end: input.chars().count() });}}}}\n");
            Files.writeString(dir.resolve("Cargo.toml"),"[package]\nname=\"left-recursion-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[dependencies]\nunlaxer-runtime={path=\""+ROOT.resolve("rust/unlaxer-runtime")+"\"}\n");
            Path target=ROOT.resolve("rust/target/left-recursion-probe");
            run(List.of("cargo","build","--offline","--quiet","--manifest-path",dir.resolve("Cargo.toml").toString(),"--target-dir",target.toString()),0);
            var command=new ArrayList<String>(List.of(target.resolve("debug/probe").toString()));
            row.getAsJsonArray("inputs").forEach(item->command.add(item.getAsString()));
            run(command,0);
        }
    }
    @Test public void sourceAnalysisHasControlledDepthOnSmallThreadStack() throws Exception {
        var rules = new ArrayList<org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl>();
        for (int n = 0; n < 300; n++) {
            org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement child = n == 299
                ? new org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement("x")
                : new org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement("R" + (n + 1));
            rules.add(new org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl(List.of(), "R" + n,
                new org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody(List.of(
                    new org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement(child, Optional.empty(), Optional.empty())))));
        }
        var grammar = new org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl("Deep", List.of(), List.of(), rules);
        var caught = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(null, () -> {
            try { LeftRecursionAnalysis.requireValid(grammar); }
            catch (Throwable error) { caught.set(error); }
        }, "bounded-source-analysis", 2 * 1024 * 1024);
        thread.start(); thread.join(10_000);
        assertFalse("analysis must terminate", thread.isAlive());
        assertTrue(String.valueOf(caught.get()), caught.get() instanceof IllegalArgumentException);
        assertTrue(caught.get().getMessage().contains("E-LEFT-RECURSION-LIMIT"));
        assertTrue(GrammarValidator.validateWithWarnings(grammar).orElseThrow().get(0)
            .startsWith("W-LEFT-RECURSION-LIMIT"));
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
