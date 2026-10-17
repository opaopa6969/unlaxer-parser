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

/** TinyCalc diagnostics checked against a hand-written oracle over real Content-Length frames. */
public class ClassicLspDiagnosticsConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test public void generatedServersSelectFarthestSyntaxFailure() throws Exception {
        var grammarFile = ROOT.resolve("unlaxer-dsl/tinycalc-vscode/grammar/tinycalc.ubnf");
        var grammar = UBNFMapper.parse(Files.readString(grammarFile)).grammars().get(0);
        var sources = new ArrayList<JavaFileObject>();
        for (var generated : List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar), new MapperGenerator().generate(grammar),
                new LSPGenerator().generate(grammar), new LSPLauncherGenerator().generate(grammar))) {
            sources.add(source(generated.packageName() + "." + generated.className(), generated.source()));
        }
        sources.add(source("org.unlaxer.tinycalc.generated.DiagnosticMain", """
            package org.unlaxer.tinycalc.generated;
            public final class DiagnosticMain extends TinyCalcLspLauncher {
                @Override protected TinyCalcLanguageServer createServer() { return new TinyCalcLanguageServer() {}; }
                public static void main(String[] args) throws Exception {
                    System.exit(new DiagnosticMain().launchStdio());
                }
            }
            """));
        Path classes = temporary.newFolder("java-classes").toPath();
        var errors = new StringWriter();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            boolean compiled = compiler.getTask(new PrintWriter(errors), manager, null,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString()), null, sources).call();
            assertTrue(errors.toString(), compiled);
        }
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        try (var wire = new Wire(new ProcessBuilder(javaExecutable, "-cp",
                classes + File.pathSeparator + System.getProperty("java.class.path"),
                "org.unlaxer.tinycalc.generated.DiagnosticMain")
                .redirectError(temporary.newFile("java-stderr.log")).start())) {
            exercise(wire, "Java");
        }
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] Rust diagnostics cross-check requires -DrustConformance=true; Java oracle ran");
            return;
        }
        Path project = temporary.newFolder("rust-project").toPath();
        Path modules = project.resolve("src/generated");
        Files.createDirectories(modules);
        Path nativeOutput = project.resolve("native");
        run(List.of("cargo", "run", "--quiet", "--locked", "--manifest-path",
            ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--bin", "unlaxer", "--",
            "generate", "--target", "rust", "--grammar", grammarFile.toString(),
            "--output", nativeOutput.toString(), "--lsp"));
        for (var file : new RustBackend().generateWithLsp(grammar)) {
            assertEquals("both generator hosts: " + file.relativePath(), file.content(),
                Files.readString(nativeOutput.resolve(file.relativePath())));
            Files.writeString(modules.resolve(file.relativePath()), file.content());
        }
        Files.writeString(project.resolve("Cargo.toml"), """
            [package]
            name="classic-lsp-diagnostic-fixture"
            version="0.1.0"
            edition="2021"
            [dependencies]
            unlaxer-runtime={path="%s"}
            unlaxer-lsp={path="%s"}
            """.formatted(ROOT.resolve("rust/unlaxer-runtime"), ROOT.resolve("rust/unlaxer-lsp")));
        Files.writeString(project.resolve("src/main.rs"),
            "mod generated; fn main() { std::process::exit(generated::lsp::serve_stdio_status().unwrap()); }\n");
        run(List.of("cargo", "build", "--quiet", "--offline", "--manifest-path", project.resolve("Cargo.toml").toString()));
        try (var wire = new Wire(new ProcessBuilder(ROOT.resolve("rust/target/debug/classic-lsp-diagnostic-fixture").toString())
                .redirectError(temporary.newFile("rust-stderr.log")).start())) {
            exercise(wire, "Rust");
        }
        Files.writeString(Path.of("target/classic-lsp-diagnostics.tsv"),
            "backend\tcases\tstatus\nJava\t20\tPASS\nRust\t20\tPASS\n");
    }

    private void exercise(Wire wire, String backend) throws Exception {
        var cases = JsonParser.parseString(Files.readString(ROOT.resolve("docs/fixtures/classic-lsp/diagnostics.json"))).getAsJsonArray();
        assertEquals(20, cases.size());
        wire.request("initialize", Map.of("capabilities", Map.of()));
        wire.notify("initialized", Map.of());
        int version = 0;
        for (var element : cases) {
            var row = element.getAsJsonObject();
            String label = backend + ": " + row.get("id").getAsString();
            String uri = "file:///diagnostic-fixture", text = row.get("text").getAsString();
            wire.notify("textDocument/didOpen", Map.of("textDocument", Map.of(
                "uri", uri, "languageId", "tinycalc", "version", ++version, "text", text)));
            var diagnostics = wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics");
            if (row.get("range").isJsonNull()) {
                assertEquals(label + diagnostics, 0, diagnostics.size());
            } else {
                assertEquals(label + diagnostics, 1, diagnostics.size());
                var diagnostic = diagnostics.get(0).getAsJsonObject();
                var range = diagnostic.getAsJsonObject("range");
                var actual = List.of(range.getAsJsonObject("start").get("line").getAsInt(),
                    range.getAsJsonObject("start").get("character").getAsInt(),
                    range.getAsJsonObject("end").get("line").getAsInt(),
                    range.getAsJsonObject("end").get("character").getAsInt());
                assertEquals(label + diagnostic, row.get("range"), JSON.toJsonTree(actual));
                assertEquals(label, "ULX-PARSE-001", diagnostic.get("code").getAsString());
                assertEquals(label, 1, diagnostic.get("severity").getAsInt());
                var data = diagnostic.getAsJsonObject("data");
                assertEquals(label, row.get("offset"), data.get("offset"));
                assertEquals(label, text.length(), data.get("totalLength").getAsInt());
                var expected = data.getAsJsonArray("expectedTokens");
                for (var token : row.getAsJsonArray("requiredTokens")) {
                    assertTrue(label + " expected hints: " + diagnostic, expected.contains(token)
                        || expected.contains(new JsonPrimitive("'" + token.getAsString() + "'")));
                }
            }
            wire.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
            assertEquals(label, 0, wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics").size());
        }
        wire.response("shutdown", null);
        wire.notify("exit", null);
        assertTrue(backend, wire.process.waitFor(5, TimeUnit.SECONDS));
        assertEquals(backend, 0, wire.process.exitValue());
    }
    private void run(List<String> command) throws Exception {
        var builder = new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true);
        builder.environment().put("CARGO_INCREMENTAL", "0");
        builder.environment().put("CARGO_TARGET_DIR", ROOT.resolve("rust/target").toString());
        Path log = temporary.newFile().toPath();
        var process = builder.redirectOutput(log.toFile()).start();
        try {
            assertTrue("command timeout: " + command, process.waitFor(180, TimeUnit.SECONDS));
            assertEquals(command + "\n" + Files.readString(log), 0, process.exitValue());
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return body; }
        };
    }
    private static final class Wire implements AutoCloseable {
        final Process process;
        final BufferedInputStream input;
        final OutputStream output;
        final ExecutorService reader = Executors.newSingleThreadExecutor();
        final List<JsonObject> pending = new ArrayList<>();
        int next;

        Wire(Process process) {
            this.process = process;
            input = new BufferedInputStream(process.getInputStream());
            output = process.getOutputStream();
        }
        void notify(String method, Object params) throws Exception { send(method, params, null); }
        void send(String method, Object params, Integer id) throws Exception {
            var message = new JsonObject();
            message.addProperty("jsonrpc", "2.0");
            message.addProperty("method", method);
            if (params != null) message.add("params", JSON.toJsonTree(params));
            if (id != null) message.addProperty("id", id);
            byte[] body = JSON.toJson(message).getBytes(StandardCharsets.UTF_8);
            output.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(body);
            output.flush();
        }
        JsonObject read() throws Exception {
            return reader.submit(() -> {
                int length = -1;
                var line = new StringBuilder();
                while (true) {
                    int unit = input.read();
                    if (unit < 0) throw new EOFException();
                    line.append((char) unit);
                    if (unit == '\n') {
                        String header = line.toString();
                        line.setLength(0);
                        if (header.equals("\r\n")) break;
                        if (header.startsWith("Content-Length:")) length = Integer.parseInt(header.substring(15).trim());
                    }
                }
                if (length < 0 || length > 4 * 1024 * 1024) throw new IOException("invalid frame");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new EOFException();
                return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            }).get(20, TimeUnit.SECONDS);
        }
        JsonObject response(String method, Object params) throws Exception {
            int id = ++next;
            send(method, params, id);
            while (true) {
                var result = read();
                if (result.has("id") && result.get("id").getAsInt() == id) return result;
                pending.add(result);
            }
        }
        JsonObject request(String method, Object params) throws Exception {
            var response = response(method, params);
            assertFalse(response.toString(), response.has("error"));
            return response.getAsJsonObject("result");
        }
        JsonObject notification(String method) throws Exception {
            for (var iterator = pending.iterator(); iterator.hasNext();) {
                var candidate = iterator.next();
                if (candidate.has("method") && candidate.get("method").getAsString().equals(method)) {
                    iterator.remove();
                    return candidate.getAsJsonObject("params");
                }
            }
            while (true) {
                var candidate = read();
                if (candidate.has("method") && candidate.get("method").getAsString().equals(method))
                    return candidate.getAsJsonObject("params");
                pending.add(candidate);
            }
        }
        @Override public void close() throws Exception {
            output.close();
            process.destroyForcibly();
            reader.shutdownNow();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
