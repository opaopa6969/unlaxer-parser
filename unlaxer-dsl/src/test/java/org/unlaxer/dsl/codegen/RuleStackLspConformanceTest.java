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

/**
 * Rule stacks of syntax errors (#703) in both generated servers, over real Content-Length frames:
 * TinyCalc (ordinary failures, javaStyle trivia) and a grammar with COMMIT / LABEL (#700).
 */
public class RuleStackLspConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final int CASES = 11;

    /** One grammar and its handwritten cases. */
    private record Fixture(String name, String grammarText, JsonArray cases) {}

    private static List<Fixture> fixtures() throws Exception {
        var raising = JsonParser.parseString(Files.readString(ROOT.resolve("conformance/rule-stack/lsp-raising.json"))).getAsJsonObject();
        return List.of(
            new Fixture("tinycalc", Files.readString(ROOT.resolve("unlaxer-dsl/tinycalc-vscode/grammar/tinycalc.ubnf")),
                JsonParser.parseString(Files.readString(ROOT.resolve("conformance/rule-stack/lsp-tinycalc.json"))).getAsJsonArray()),
            new Fixture("raising", raising.get("grammar").getAsString(), raising.getAsJsonArray("cases")));
    }

    @Test public void generatedServersReportTheRuleStack() throws Exception {
        var fixtures = fixtures();
        assertEquals(CASES, fixtures.stream().mapToInt(fixture -> fixture.cases().size()).sum());
        for (var fixture : fixtures) {
            try (var wire = javaServer(fixture)) {
                exercise(wire, "Java " + fixture.name(), fixture.cases());
            }
        }
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] Rust rule-stack cross-check requires -DrustConformance=true; Java oracle ran");
            return;
        }
        for (var fixture : fixtures) {
            try (var wire = rustServer(fixture)) {
                exercise(wire, "Rust " + fixture.name(), fixture.cases());
            }
        }
        Files.writeString(Path.of("target/rule-stack-lsp.tsv"),
            "backend\tcases\tstatus\nJava\t" + CASES + "\tPASS\nRust\t" + CASES + "\tPASS\n");
    }

    private Wire javaServer(Fixture fixture) throws Exception {
        var grammar = UBNFMapper.parse(fixture.grammarText()).grammars().get(0);
        var sources = new ArrayList<JavaFileObject>();
        String packageName = null;
        for (var generated : List.of(new ParserGenerator().generate(grammar), new ASTGenerator().generate(grammar), new MapperGenerator().generate(grammar),
                new LSPGenerator().generate(grammar), new LSPLauncherGenerator().generate(grammar))) {
            sources.add(source(generated.packageName() + "." + generated.className(), generated.source()));
            packageName = generated.packageName();
        }
        String name = grammar.name();
        sources.add(source(packageName + ".DiagnosticMain", """
            package %s;
            public final class DiagnosticMain extends %sLspLauncher {
                @Override protected %sLanguageServer createServer() { return new %sLanguageServer() {}; }
                public static void main(String[] args) throws Exception {
                    System.exit(new DiagnosticMain().launchStdio());
                }
            }
            """.formatted(packageName, name, name, name)));
        Path classes = temporary.newFolder("java-classes-" + fixture.name()).toPath();
        var errors = new StringWriter();
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            boolean compiled = compiler.getTask(new PrintWriter(errors), manager, null,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"),
                    "-d", classes.toString()), null, sources).call();
            assertTrue(errors.toString(), compiled);
        }
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new Wire(new ProcessBuilder(javaExecutable, "-cp",
                classes + File.pathSeparator + System.getProperty("java.class.path"),
                packageName + ".DiagnosticMain")
                .redirectError(temporary.newFile("java-stderr-" + fixture.name() + ".log")).start());
    }

    private Wire rustServer(Fixture fixture) throws Exception {
        var grammar = UBNFMapper.parse(fixture.grammarText()).grammars().get(0);
        Path project = temporary.newFolder("rust-project-" + fixture.name()).toPath();
        Path modules = project.resolve("src/generated");
        Files.createDirectories(modules);
        Path grammarFile = project.resolve("grammar.ubnf");
        Files.writeString(grammarFile, fixture.grammarText());
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
        String crate = "rule-stack-lsp-" + fixture.name();
        Files.writeString(project.resolve("Cargo.toml"), """
            [package]
            name="%s"
            version="0.1.0"
            edition="2021"
            [dependencies]
            unlaxer-runtime={path="%s"}
            unlaxer-lsp={path="%s"}
            """.formatted(crate, ROOT.resolve("rust/unlaxer-runtime"), ROOT.resolve("rust/unlaxer-lsp")));
        Files.writeString(project.resolve("src/main.rs"),
            "mod generated; fn main() { std::process::exit(generated::lsp::serve_stdio_status().unwrap()); }\n");
        run(List.of("cargo", "build", "--quiet", "--offline", "--manifest-path", project.resolve("Cargo.toml").toString()));
        return new Wire(new ProcessBuilder(ROOT.resolve("rust/target/debug/" + crate).toString())
                .redirectError(temporary.newFile("rust-stderr-" + fixture.name() + ".log")).start());
    }

    private void exercise(Wire wire, String backend, JsonArray cases) throws Exception {
        wire.request("initialize", Map.of("capabilities", Map.of()));
        wire.notify("initialized", Map.of());
        int version = 0;
        for (var element : cases) {
            var row = element.getAsJsonObject();
            String label = backend + ": " + row.get("id").getAsString();
            String uri = "file:///rule-stack-fixture", text = row.get("text").getAsString();
            wire.notify("textDocument/didOpen", Map.of("textDocument", Map.of(
                "uri", uri, "languageId", "tinycalc", "version", ++version, "text", text)));
            var diagnostics = wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics");
            if (row.get("offset").isJsonNull()) {
                assertEquals(label + diagnostics, 0, diagnostics.size());
            } else {
                assertEquals(label + diagnostics, 1, diagnostics.size());
                var diagnostic = diagnostics.get(0).getAsJsonObject();
                var data = diagnostic.getAsJsonObject("data");
                assertEquals(label, row.get("offset").getAsInt(), data.get("offset").getAsInt());
                if (row.has("kind")) assertEquals(label, row.get("kind").getAsString(), data.get("kind").getAsString());
                // data.ruleStack: outermost first, UTF-16 offsets of where each rule started.
                var expected = new ArrayList<String>();
                for (var frame : row.getAsJsonArray("stack")) expected.add(frame.getAsString());
                var actual = new ArrayList<String>();
                for (var frame : data.getAsJsonArray("ruleStack")) {
                    var object = frame.getAsJsonObject();
                    actual.add(object.get("rule").getAsString() + "@" + object.get("offset").getAsInt());
                }
                assertEquals(label + diagnostic, expected, actual);
                int omitted = row.get("omitted").getAsInt();
                assertEquals(label, omitted, data.get("ruleStackOmitted").getAsInt());
                // relatedInformation: one location per rule, from its start to the diagnostic.
                var related = diagnostic.getAsJsonArray("relatedInformation");
                assertEquals(label + diagnostic, expected.size(), related.size());
                var start = diagnostic.getAsJsonObject("range").getAsJsonObject("start");
                for (int index = 0; index < expected.size(); index++) {
                    var information = related.get(index).getAsJsonObject();
                    String rule = expected.get(index).substring(0, expected.get(index).indexOf('@'));
                    int offset = Integer.parseInt(expected.get(index).substring(expected.get(index).indexOf('@') + 1));
                    String note = index == 0 && omitted > 0 ? " (" + omitted + " outer rules omitted)" : "";
                    assertEquals(label, "in rule " + rule + note, information.get("message").getAsString());
                    var location = information.getAsJsonObject("location");
                    assertEquals(label, uri, location.get("uri").getAsString());
                    var range = location.getAsJsonObject("range");
                    // Single-line fixtures: the UTF-16 offset is the LSP character.
                    assertEquals(label, JsonParser.parseString("{\"line\":0,\"character\":" + offset + "}"),
                        range.get("start"));
                    assertEquals(label, start, range.get("end"));
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
