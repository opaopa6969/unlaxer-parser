package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.ClassicLspProtocolConformanceTest.Wire;

/** Independent lifecycle trace through actual generated Java/Rust stdio processes. */
public class ClassicLspLifecycleConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String GRAMMAR = "grammar Emoji { @package: lifecycle.fixture @root @mapping(Value) Root ::= 'a'; }";

    @Test public void generatedServersEnforceInitializationWithoutExecutingRejectedCallbacks() throws Exception {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        var sources = new ArrayList<JavaFileObject>();
        for (var generated : List.of(new ParserGenerator().generate(grammar), new LSPGenerator().generate(grammar), new LSPLauncherGenerator().generate(grammar)))
            sources.add(source(generated.packageName() + "." + generated.className(), generated.source()));
        sources.add(source("lifecycle.fixture.Main", """
            package lifecycle.fixture;
            public class Main extends EmojiLspLauncher {
                static final Server SERVER = new Server();
                public static class Server extends EmojiLanguageServer {
                    int validationCalls, semanticCalls, initializedCalls, initializeCalls;
                    @Override public java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.InitializeResult> initialize(org.eclipse.lsp4j.InitializeParams params) {
                        initializeCalls++; return super.initialize(params);
                    }
                    @Override public ParseResult parseDocument(String uri, String content) {
                        validationCalls++; return super.parseDocument(uri, content);
                    }
                    @Override protected int[] additionalSemanticTokenData(String uri, String content) {
                        semanticCalls++; return new int[]{0,0,1,validationCalls,0};
                    }
                    @Override public void initialized(org.eclipse.lsp4j.InitializedParams params) { initializedCalls++; }
                    @org.eclipse.lsp4j.jsonrpc.services.JsonRequest("fixture/probe")
                    public java.util.concurrent.CompletableFuture<String> probe(Object params) {
                        throw new AssertionError("rejected custom callback executed");
                    }
                }
                @Override protected EmojiLanguageServer createServer() { return SERVER; }
                public static void main(String[] args) throws Exception {
                    int status = new Main().launchStdio();
                    System.err.println("LIFECYCLE_COUNTS " + new com.google.gson.Gson().toJson(java.util.Map.of(
                        "validate",SERVER.validationCalls,"semantic",SERVER.semanticCalls,"initialized",SERVER.initializedCalls,"initialize",SERVER.initializeCalls)));
                    System.exit(status);
                }
            }
            """));
        Path classes = temporary.newFolder("classes").toPath(); var compiler = ToolProvider.getSystemJavaCompiler(); var errors = new StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(errors.toString(), compiler.getTask(new PrintWriter(errors), manager, null,
                List.of("--release", Integer.toString(Math.min(21, Runtime.version().feature())), "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()), null, sources).call());
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        exercise(List.of(java, "-cp", classes + File.pathSeparator + System.getProperty("java.class.path"), "lifecycle.fixture.Main"), true);
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] Rust lifecycle oracle requires -DrustConformance=true; Java oracle ran"); return;
        }
        Path project = temporary.newFolder("rust").toPath(), modules = project.resolve("src/generated"); Files.createDirectories(modules);
        for (var file : new RustBackend().generateWithLsp(grammar)) Files.writeString(modules.resolve(file.relativePath()), file.content());
        Files.writeString(project.resolve("Cargo.toml"), """
            [package]
            name="lifecycle-wire-fixture"
            version="0.1.0"
            edition="2021"
            [dependencies]
            unlaxer-runtime={path="%s"}
            unlaxer-lsp={path="%s"}
            serde_json="1"
            """.formatted(ROOT.resolve("rust/unlaxer-runtime").toString().replace("\\", "\\\\"), ROOT.resolve("rust/unlaxer-lsp").toString().replace("\\", "\\\\")));
        Files.writeString(project.resolve("src/main.rs"), """
            mod generated;
            use unlaxer_lsp::{Backend, Diagnostic, GrammarBackend};
            use unlaxer_runtime::source::Snapshot;
            use std::{rc::Rc,cell::Cell};
            struct Provider { grammar: GrammarBackend, counts: Rc<Cell<[u32;2]>> }
            impl Backend for Provider {
                fn grammar(&self)->&str { self.grammar.grammar() }
                fn entry(&self)->&str { self.grammar.entry() }
                fn keywords(&self)->&[String] { self.grammar.keywords() }
                fn validate(&mut self,snapshot:&Snapshot)->Vec<Diagnostic> {
                    let mut counts=self.counts.get(); counts[0]+=1; self.counts.set(counts);
                    self.grammar.validate(snapshot)
                }
                fn semantic_tokens(&mut self,_:&Snapshot)->Result<Vec<u32>,String> {
                    let mut counts=self.counts.get(); counts[1]+=1; self.counts.set(counts);
                    Ok(vec![0,0,1,counts[0],0])
                }
            }
            fn main() {
                let counts=Rc::new(Cell::new([0,0]));
                let mut server=unlaxer_lsp::Server::new(Provider{grammar:generated::lsp::backend(),counts:Rc::clone(&counts)});
                server.serve_stdio().unwrap();
                eprintln!("LIFECYCLE_COUNTS {}",serde_json::json!({"validate":counts.get()[0],"semantic":counts.get()[1]}));
                std::process::exit(server.exit_code());
            }
            """);
        var builder = new ProcessBuilder("cargo", "build", "--quiet", "--offline", "--manifest-path", project.resolve("Cargo.toml").toString()).directory(ROOT.toFile()).redirectErrorStream(true);
        builder.environment().put("CARGO_TARGET_DIR", ROOT.resolve("rust/target").toString()); builder.environment().put("CARGO_INCREMENTAL", "0");
        Path log = temporary.newFile().toPath(); var build = builder.redirectOutput(log.toFile()).start();
        try { assertTrue(build.waitFor(180, TimeUnit.SECONDS)); assertEquals(Files.readString(log), 0, build.exitValue()); }
        finally { if (build.isAlive()) build.destroyForcibly(); }
        exercise(List.of(ROOT.resolve("rust/target/debug/lifecycle-wire-fixture").toString()), false);
        Files.writeString(Path.of("target/classic-lsp-lifecycle.tsv"),
            "backend\tstatus\njava\tpassed\nrust\tpassed\n");
    }

    private void exercise(List<String> command, boolean java) throws Exception {
        var oracle = JsonParser.parseString(Files.readString(ROOT.resolve("docs/fixtures/classic-lsp/lifecycle.json"))).getAsJsonObject();
        String profile = Files.readString(ROOT.resolve("language-profiles/java/profile.tsv")).replace("Java21", "Emoji").replace("CompilationUnit", "Root");
        String stepsText = oracle.getAsJsonArray("steps").toString().replace("\"$validProfile\"", new Gson().toJson(Map.of("tsv", profile, "entry", "Root")));
        Path stderr = temporary.newFile().toPath();
        try (var wire = new Wire(new ProcessBuilder(command).redirectError(stderr.toFile()).start())) {
            for (var element : JsonParser.parseString(stepsText).getAsJsonArray()) {
                var step = element.getAsJsonObject(); var params = step.get("params");
                if (step.has("notify")) {
                    wire.notify(step.get("notify").getAsString(), params);
                    if (step.has("diagnostic")) assertEquals(0, wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics").size());
                    continue;
                }
                var response = wire.response(step.get("method").getAsString(), params);
                if (step.has("error")) assertEquals(step.toString(), step.get("error"), response.getAsJsonObject("error").get("code"));
                else {
                    assertFalse(response.toString(), response.has("error"));
                    if (step.has("result")) assertEquals(step.toString(), step.get("result"), response.get("result"));
                    if (step.has("completion")) assertEquals(step.toString(), step.get("completion").getAsBoolean(), response.getAsJsonObject("result").getAsJsonObject("capabilities").has("completionProvider"));
                    if (step.has("nonempty")) assertFalse(response.toString(), response.getAsJsonArray("result").isEmpty());
                }
                assertTrue("rejected notifications emitted callbacks/diagnostics: " + step, wire.pending.isEmpty());
            }
            assertTrue(wire.process.waitFor(5, TimeUnit.SECONDS)); assertEquals(oracle.get("exitCode").getAsInt(), wire.process.exitValue());
        }
        String countsLine = Files.readAllLines(stderr).stream().filter(line -> line.startsWith("LIFECYCLE_COUNTS ")).findFirst().orElseThrow();
        var counts = JsonParser.parseString(countsLine.substring("LIFECYCLE_COUNTS ".length())).getAsJsonObject();
        for (var entry : oracle.getAsJsonObject("callbacks").entrySet()) if (java || !Set.of("initialize", "initialized").contains(entry.getKey())) assertEquals(entry.getKey(), entry.getValue(), counts.get(entry.getKey()));
        System.out.println("LSP lifecycle oracle passed: " + (java ? "Java" : "Rust") + " " + counts);
    }
    private static JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return body; }
        };
    }
}
