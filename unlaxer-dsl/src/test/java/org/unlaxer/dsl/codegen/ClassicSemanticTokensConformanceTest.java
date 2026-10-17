package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.*;
import org.junit.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Real nonempty application hooks and an independent oracle through generated stdio servers. */
public class ClassicSemanticTokensConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Gson JSON = new Gson();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURE = ROOT.resolve("docs/fixtures/classic-lsp/semantic-tokens.json");
    private static final String FULL = "textDocument/semanticTokens/full";
    private static final String GRAMMAR = "grammar Emoji { @package: semantic.wire token INNER = NEGATION('>') @root @mapping(EmojiAst) Root ::= '<' { INNER } '>' '!'; }";

    @Test public void generatedJavaAndRustFullProvidersShareTheWireOracle() throws Exception {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        var sources = new ArrayList<JavaFileObject>();
        for (var generated : List.of(new ParserGenerator().generate(grammar), new LSPGenerator().generate(grammar), new LSPLauncherGenerator().generate(grammar)))
            sources.add(source(generated.packageName() + "." + generated.className(), generated.source()));
        sources.add(source("semantic.wire.Main", """
            package semantic.wire;
            public final class Main extends EmojiLspLauncher {
                private static com.google.gson.JsonObject fixture;
                private static boolean custom, large;
                public static final class Provider extends EmojiLanguageServer {
                    @Override protected void configureAdditionalCapabilities(org.eclipse.lsp4j.ServerCapabilities caps) {
                        var legend = fixture.getAsJsonObject("legend");
                        var options = caps.getSemanticTokensProvider();
                        options.setLegend(new org.eclipse.lsp4j.SemanticTokensLegend(
                            large ? java.util.stream.IntStream.range(0, 65537).mapToObj(i -> "type" + i).toList() : legend.getAsJsonArray("tokenTypes").asList().stream().map(com.google.gson.JsonElement::getAsString).toList(),
                            legend.getAsJsonArray("tokenModifiers").asList().stream().map(com.google.gson.JsonElement::getAsString).toList()));
                    }
                    @Override protected int[] additionalSemanticTokenData(String uri, String content) {
                        for (var element : fixture.getAsJsonArray("cases")) {
                            var row = element.getAsJsonObject();
                            if (!("file:///" + row.get("id").getAsString()).equals(uri)) continue;
                            if (!row.get("text").getAsString().equals(content)) return new int[0];
                            if (row.has("providerFailure")) throw new IllegalStateException("provider failure");
                            return row.getAsJsonArray("tokens").asList().stream().mapToInt(com.google.gson.JsonElement::getAsInt).toArray();
                        }
                        throw new AssertionError("provider called for unknown document");
                    }
                }
                @Override protected EmojiLanguageServer createServer() {
                    return custom ? new Provider() : new EmojiLanguageServer() {};
                }
                public static void main(String[] args) throws Exception {
                    fixture = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(java.nio.file.Path.of(args[0]))).getAsJsonObject();
                    custom = !args[1].equals("default"); large = args[1].equals("large");
                    System.exit(new Main().launchStdio());
                }
            }
            """));
        Path classes = temporary.newFolder("java-classes").toPath();
        var compiler = ToolProvider.getSystemJavaCompiler(); var errors = new StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(errors.toString(), compiler.getTask(new PrintWriter(errors), manager, null,
                List.of("--release", Integer.toString(Math.min(21, Runtime.version().feature())), "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()), null, sources).call());
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> javaCommand = List.of(java, "-cp", classes + File.pathSeparator + System.getProperty("java.class.path"), "semantic.wire.Main");
        exerciseBothModes(javaCommand, "java");
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] Rust semantic-token stdio cross-check requires -DrustConformance=true; Java oracle ran");
            return;
        }
        Path project = temporary.newFolder("rust-project").toPath(), modules = project.resolve("src/generated");
        Files.createDirectories(modules);
        Path grammarFile = project.resolve("fixture.ubnf"); Files.writeString(grammarFile, GRAMMAR);
        Path nativeOutput = project.resolve("native");
        run(List.of("cargo", "run", "--quiet", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--bin", "unlaxer", "--", "generate", "--target", "rust", "--grammar", grammarFile.toString(), "--output", nativeOutput.toString(), "--lsp"));
        var generated = new RustBackend().generateWithLsp(grammar);
        assertEquals(6, generated.size());
        for (var file : generated) {
            assertEquals("Java/native artifact " + file.relativePath(), file.content(), Files.readString(nativeOutput.resolve(file.relativePath())));
            Files.writeString(modules.resolve(file.relativePath()), file.content());
        }
        Files.writeString(project.resolve("Cargo.toml"), """
            [package]
            name="semantic-token-wire-fixture"
            version="0.1.0"
            edition="2021"
            [dependencies]
            unlaxer-runtime={path="%s"}
            unlaxer-lsp={path="%s"}
            serde_json="1"
            """.formatted(ROOT.resolve("rust/unlaxer-runtime").toString().replace("\\", "\\\\"), ROOT.resolve("rust/unlaxer-lsp").toString().replace("\\", "\\\\")));
        Files.writeString(project.resolve("src/main.rs"), """
            mod generated;
            use serde_json::Value;
            use unlaxer_lsp::{Backend, Diagnostic, GrammarBackend, SemanticTokensLegend};
            use unlaxer_runtime::source::Snapshot;
            struct Provider { grammar: GrammarBackend, fixture: Value, custom: bool, large: bool }
            impl Backend for Provider {
                fn grammar(&self) -> &str { self.grammar.grammar() }
                fn entry(&self) -> &str { self.grammar.entry() }
                fn keywords(&self) -> &[String] { self.grammar.keywords() }
                fn validate(&mut self, snapshot: &Snapshot) -> Vec<Diagnostic> { self.grammar.validate(snapshot) }
                fn semantic_tokens_legend(&self) -> SemanticTokensLegend {
                    if !self.custom { return SemanticTokensLegend::default(); }
                    let legend = &self.fixture["legend"];
                    SemanticTokensLegend {
                        token_types: if self.large { (0..65537).map(|i| format!("type{i}")).collect() } else { legend["tokenTypes"].as_array().unwrap().iter().map(|v| v.as_str().unwrap().into()).collect() },
                        token_modifiers: legend["tokenModifiers"].as_array().unwrap().iter().map(|v| v.as_str().unwrap().into()).collect(),
                    }
                }
                fn semantic_tokens(&mut self, snapshot: &Snapshot) -> Result<Vec<u32>, String> {
                    if !self.custom { return Ok(vec![]); }
                    for row in self.fixture["cases"].as_array().unwrap() {
                        if format!("file:///{}", row["id"].as_str().unwrap()) != snapshot.uri { continue; }
                        if row["text"].as_str().unwrap() != snapshot.text { return Ok(vec![]); }
                        if row["providerFailure"] == true { return Err("provider failure".into()); }
                        return Ok(row["tokens"].as_array().unwrap().iter().map(|v| v.as_u64().and_then(|n| u32::try_from(n).ok()).unwrap_or(u32::MAX)).collect());
                    }
                    panic!("provider called for unknown document");
                }
            }
            fn main() {
                let args: Vec<_> = std::env::args().collect();
                let fixture = serde_json::from_str(&std::fs::read_to_string(&args[1]).unwrap()).unwrap();
                let backend = Provider { grammar: generated::lsp::backend(), fixture, custom: args[2] != "default", large: args[2] == "large" };
                let mut server = unlaxer_lsp::Server::new(backend);
                server.serve_stdio().unwrap();
                std::process::exit(server.exit_code());
            }
            """);
        run(List.of("cargo", "build", "--quiet", "--offline", "--manifest-path", project.resolve("Cargo.toml").toString()));
        exerciseBothModes(List.of(ROOT.resolve("rust/target/debug/semantic-token-wire-fixture").toString()), "rust");
        Files.writeString(Path.of("target/classic-semantic-tokens.tsv"),
            "backend\tmodes\tstatus\njava\t7\tpassed\nrust\t7\tpassed\ngenerator-artifacts\t6\tidentical\n");
    }

    private void exerciseBothModes(List<String> baseCommand, String backend) throws Exception {
        for (String mode : List.of("default", "custom", "custom-00", "custom-10", "custom-01", "custom-11", "large")) {
            boolean custom = !mode.equals("default"), large = mode.equals("large");
            boolean multiline = mode.equals("custom-10") || mode.equals("custom-11") || large;
            boolean overlapping = mode.equals("custom-01") || mode.equals("custom-11") || large;
            Object capabilities = mode.equals("custom") || mode.equals("default") ? Map.of() : Map.of("textDocument", Map.of("semanticTokens", Map.of("multilineTokenSupport", multiline, "overlappingTokenSupport", overlapping)));
            var command = new ArrayList<>(baseCommand); command.add(FIXTURE.toString()); command.add(mode);
            try (var wire = new Wire(new ProcessBuilder(command).redirectError(temporary.newFile()).start())) {
                var initialized = wire.request("initialize", Map.of("capabilities", capabilities));
                var provider = initialized.getAsJsonObject("capabilities").getAsJsonObject("semanticTokensProvider");
                assertTrue(provider.get("full").getAsBoolean());
                assertFalse(provider.has("range"));
                var fixture = JsonParser.parseString(Files.readString(FIXTURE)).getAsJsonObject();
                assertEquals(30, fixture.getAsJsonArray("cases").size());
                if (large) assertEquals(65537, provider.getAsJsonObject("legend").getAsJsonArray("tokenTypes").size());
                else assertEquals(mode, custom ? fixture.get("legend") : JSON.toJsonTree(Map.of("tokenTypes", List.of("valid", "invalid"), "tokenModifiers", List.of())), provider.get("legend"));
                wire.notify("initialized", Map.of());
                assertEquals(JSON.toJsonTree(List.of()), tokens(wire, "file:///missing"));
                for (var element : fixture.getAsJsonArray("cases")) {
                    var row = element.getAsJsonObject();
                    if (large != row.has("largeLegend")) continue;
                    String uri = "file:///" + row.get("id").getAsString();
                    open(wire, uri, 2, row.get("text").getAsString());
                    var response = wire.response(FULL, document(uri));
                    boolean invalid = row.has("error") || multiline && row.has("errorWhenMultiline")
                        || !overlapping && (row.has("requiresOverlap") || multiline && row.has("overlapsWhenMultiline"));
                    if (custom && invalid) assertEquals(row.toString(), -32603, response.getAsJsonObject("error").get("code").getAsInt());
                    else {
                        assertFalse(response.toString(), response.has("error"));
                        assertEquals(row.toString(), custom ? row.get("expected") : JSON.toJsonTree(List.of()), response.getAsJsonObject("result").get("data"));
                    }
                    close(wire, uri);
                    assertEquals(JSON.toJsonTree(List.of()), tokens(wire, uri));
                }
                String uri = "file:///ascii";
                JsonElement expected = custom ? fixture.getAsJsonArray("cases").get(1).getAsJsonObject().get("expected") : JSON.toJsonTree(List.of());
                open(wire, uri, 2, "<foo>!");
                for (Object changes : List.of(List.of(Map.of("text", "<>!")), List.of(), List.of(Map.of("text", "<>!", "range", Map.of("start", Map.of("line", 0, "character", 0), "end", Map.of("line", 0, "character", 1)))))) {
                    int version = changes instanceof List<?> list && list.size() == 1 && !((Map<?, ?>) list.get(0)).containsKey("range") ? 1 : 3;
                    wire.notify("textDocument/didChange", Map.of("textDocument", Map.of("uri", uri, "version", version), "contentChanges", changes));
                    assertEquals("rejected version/ranged edit", expected, tokens(wire, uri));
                    assertTrue("rejected update emits no diagnostics", wire.pending.isEmpty());
                }
                change(wire, uri, 3, "<>!"); assertEquals(JSON.toJsonTree(List.of()), tokens(wire, uri));
                change(wire, uri, 4, "<foo>!"); assertEquals(expected, tokens(wire, uri));
                String profile = Files.readString(ROOT.resolve("language-profiles/java/profile.tsv")).replace("Java21", "Emoji").replace("CompilationUnit", "Root");
                var selected = wire.request("unlaxer/configure", Map.of("initializationOptions", Map.of("languageProfile", Map.of("tsv", profile, "entry", "Root"))));
                assertTrue("semantic tokens have no profile operation and remain available", selected.getAsJsonObject("capabilities").has("semanticTokensProvider"));
                assertEquals(expected, tokens(wire, uri));
                assertEquals(-32602, wire.response("unlaxer/configure", Map.of("initializationOptions", Map.of("languageProfile", Map.of("tsv", profile, "entry", "wrong")))).getAsJsonObject("error").get("code").getAsInt());
                assertEquals("invalid initialize preserves provider", expected, tokens(wire, uri));
                close(wire, uri); open(wire, uri, 1, "<foo>!"); assertEquals(expected, tokens(wire, uri));
                wire.response("shutdown", null);
                assertEquals(-32600, wire.response(FULL, document(uri)).getAsJsonObject("error").get("code").getAsInt());
                wire.notify("textDocument/didChange", Map.of("textDocument", Map.of("uri", uri, "version", 2), "contentChanges", List.of(Map.of("text", "<>!"))));
                wire.notify("textDocument/didClose", document(uri));
                assertEquals(-32600, wire.response(FULL, document(uri)).getAsJsonObject("error").get("code").getAsInt());
                assertTrue(wire.pending.isEmpty());
                wire.notify("exit", null);
                assertTrue("exit stops without EOF", wire.process.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, wire.process.exitValue());
            }
            System.out.println("Semantic tokens full oracle: " + backend + "/" + mode + " (30-case corpus, versions/profile/lifecycle)");
        }
    }
    private static Map<String, Object> document(String uri) { return Map.of("textDocument", Map.of("uri", uri)); }
    private static JsonElement tokens(Wire wire, String uri) throws Exception { return wire.request(FULL, document(uri)).get("data"); }
    private static void open(Wire wire, String uri, int version, String text) throws Exception {
        wire.notify("textDocument/didOpen", Map.of("textDocument", Map.of("uri", uri, "languageId", "emoji", "version", version, "text", text)));
        wire.notification("textDocument/publishDiagnostics");
    }
    private static void close(Wire wire, String uri) throws Exception {
        wire.notify("textDocument/didClose", document(uri)); wire.notification("textDocument/publishDiagnostics");
    }
    private static void change(Wire wire, String uri, int version, String text) throws Exception {
        wire.notify("textDocument/didChange", Map.of("textDocument", Map.of("uri", uri, "version", version), "contentChanges", List.of(Map.of("text", text))));
        wire.notification("textDocument/publishDiagnostics");
    }
    private void run(List<String> command) throws Exception {
        var builder = new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true);
        builder.environment().put("CARGO_INCREMENTAL", "0"); builder.environment().put("CARGO_TARGET_DIR", ROOT.resolve("rust/target").toString());
        Path log = temporary.newFile().toPath(); builder.redirectOutput(log.toFile()); var process = builder.start();
        try { assertTrue("command timeout: " + command, process.waitFor(180, TimeUnit.SECONDS)); assertEquals(command + "\n" + Files.readString(log), 0, process.exitValue()); }
        finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return body; }
        };
    }
    private static final class Wire implements AutoCloseable {
        final Process process;final BufferedInputStream input;final OutputStream output;final ExecutorService reader=Executors.newSingleThreadExecutor();final List<JsonObject> pending=new ArrayList<>();int next=0;
        Wire(Process process){this.process=process;input=new BufferedInputStream(process.getInputStream());output=process.getOutputStream();}
        void notify(String method,Object params)throws Exception{send(method,params,null);}
        void send(String method,Object params,Integer id)throws Exception {var message=new JsonObject();message.addProperty("jsonrpc","2.0");message.addProperty("method",method);if(params!=null)message.add("params",JSON.toJsonTree(params));if(id!=null)message.addProperty("id",id);byte[] body=JSON.toJson(message).getBytes(StandardCharsets.UTF_8);output.write(("Content-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();}
        JsonObject read()throws Exception{return reader.submit(()->{int length=-1;StringBuilder line=new StringBuilder();while(true){int unit=input.read();if(unit<0)throw new EOFException();line.append((char)unit);if(unit=='\n'){String header=line.toString();line.setLength(0);if(header.equals("\r\n"))break;if(header.startsWith("Content-Length:"))length=Integer.parseInt(header.substring(15).trim());}}if(length<0||length>4*1024*1024)throw new IOException("invalid frame");byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException();return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}).get(20,TimeUnit.SECONDS);}
        JsonObject response(String method,Object params)throws Exception{int id=++next;send(method,params,id);while(true){var result=read();if(result.has("id")&&result.get("id").getAsInt()==id)return result;pending.add(result);}}
        JsonObject request(String method,Object params)throws Exception{var response=response(method,params);assertFalse(response.toString(),response.has("error"));return response.getAsJsonObject("result");}
        JsonArray requestArray(String method,Object params)throws Exception{var response=response(method,params);assertFalse(response.toString(),response.has("error"));return response.getAsJsonArray("result");}
        JsonObject notification(String method)throws Exception{for(var iterator=pending.iterator();iterator.hasNext();){var candidate=iterator.next();if(candidate.has("method")&&candidate.get("method").getAsString().equals(method)){iterator.remove();return candidate.getAsJsonObject("params");}}while(true){var candidate=read();if(candidate.has("method")&&candidate.get("method").getAsString().equals(method))return candidate.getAsJsonObject("params");pending.add(candidate);}}
        @Override public void close()throws Exception{output.close();process.destroyForcibly();reader.shutdownNow();process.waitFor(5,TimeUnit.SECONDS);}
    }
}
