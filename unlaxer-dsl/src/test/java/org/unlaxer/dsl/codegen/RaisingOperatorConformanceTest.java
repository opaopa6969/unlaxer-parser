package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/**
 * UBNF COMMIT / LABEL / CATCH (#700) through the Java generator, the Java host Rust generator
 * and the native Rust generator, against one independent corpus.
 */
public class RaisingOperatorConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    private static JsonArray corpus(String file) throws Exception {
        return JsonParser.parseString(Files.readString(ROOT.resolve("spec-corpus/cut-labeled-failure/" + file))).getAsJsonArray();
    }

    /** outcome, offset, message, label: the tuple every host prints for one input and option. */
    private static String expected(JsonObject value) {
        String outcome = value.get("outcome").getAsString();
        String offset = value.has("offset") ? value.get("offset").getAsString() : "";
        String message = value.has("message") ? value.get("message").getAsString() : "";
        String label = value.has("label") ? value.get("label").getAsString() : "";
        return outcome + "\t" + offset + "\t" + message + "\t" + label;
    }

    @Test public void syntaxIsParsedAsTheThreeOperators() throws Exception {
        var grammar = UBNFMapper.parse("grammar G { @root Root ::= COMMIT('a') COMMIT(B, 'm') LABEL(L, 'b' | B) "
            + "CATCH('c' LABEL(L, 'd'), 'e', L, M) ; B ::= 'b' ; }").grammars().get(0);
        var elements = ((UBNFAST.SequenceBody) ((UBNFAST.ChoiceBody) grammar.rules().get(0).body()).alternatives().get(0)).elements();
        assertEquals(new UBNFAST.CommitElement(body(new UBNFAST.TerminalElement("a")), Optional.empty()), elements.get(0).element());
        assertEquals(Optional.of("m"), ((UBNFAST.CommitElement) elements.get(1).element()).message());
        assertEquals("L", ((UBNFAST.LabelElement) elements.get(2).element()).label());
        var caught = (UBNFAST.CatchElement) elements.get(3).element();
        assertEquals(List.of("L", "M"), caught.labels());
        assertEquals(3, ((UBNFAST.ChoiceBody) caught.body()).alternatives().get(0).elements().size()
            + ((UBNFAST.ChoiceBody) caught.body()).alternatives().get(1).elements().size());
        // Only an adjacent '(' reserves the builtin spelling; trivia keeps reference + group.
        var reference = UBNFMapper.parse("grammar G { @root Root ::= COMMIT ('a') LABEL ('b') CATCH ('c') ; "
            + "COMMIT ::= 'x' ; LABEL ::= 'y' ; CATCH ::= 'z' ; }").grammars().get(0);
        var plain = ((UBNFAST.SequenceBody) ((UBNFAST.ChoiceBody) reference.rules().get(0).body()).alternatives().get(0)).elements();
        assertTrue(plain.get(0).element() instanceof UBNFAST.RuleRefElement);
        assertTrue(plain.get(1).element() instanceof UBNFAST.GroupElement);
        for (String body : List.of("COMMIT()", "COMMIT('a',)", "COMMIT('a', b)", "LABEL(L)", "LABEL(, 'a')",
                "LABEL('L', 'a')", "CATCH('a')", "CATCH('a', 'b', 'L')", "CATCH('a',, L)")) {
            assertThrows(body, RuntimeException.class, () -> UBNFMapper.parse("grammar G { @root Root ::= " + body + " ; }"));
        }
    }

    private static UBNFAST.ChoiceBody body(UBNFAST.AtomicElement element) {
        return new UBNFAST.ChoiceBody(List.of(new UBNFAST.SequenceBody(List.of(
            new UBNFAST.AnnotatedElement(element, Optional.empty(), Optional.empty())))));
    }

    @Test public void validatorRejectsWithSourcePositions() throws Exception {
        var rows = corpus("invalid.json");
        assertEquals(5, rows.size());
        for (var raw : rows) {
            var row = raw.getAsJsonObject();
            String id = row.get("id").getAsString(), code = row.get("code").getAsString();
            var snapshot = UBNFMapper.parseWithSource(row.get("grammar").getAsString());
            var grammar = snapshot.ast().grammars().get(0);
            var problem = RaisingOperators.problems(grammar).stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError(id));
            var span = snapshot.spanOf(problem.element()).orElseThrow();
            assertEquals(id, List.of(row.get("startCP").getAsInt(), row.get("endCP").getAsInt()), List.of(span.start(), span.end()));
            assertTrue(id, GrammarValidator.validateWithoutClassLoading(grammar).stream()
                .anyMatch(issue -> issue.code().equals(code) && issue.rule().equals("Root")));
            for (CodeGenerator generator : List.<CodeGenerator>of(new ParserGenerator())) {
                assertTrue(id, assertThrows(IllegalArgumentException.class, () -> generator.generate(grammar)).getMessage().contains(code));
            }
            assertTrue(id, assertThrows(IllegalArgumentException.class, () -> new RustBackend().generate(grammar)).getMessage().contains(code));
            var portability = org.unlaxer.dsl.PortabilityCheck.check(row.get("grammar").getAsString());
            assertFalse(id, portability.portable());
            var reported = portability.diagnostics().stream().filter(d -> d.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError(id + " " + portability));
            assertEquals(id, span, reported.span());
        }
    }

    private Path compileJava(JsonObject row, boolean lsp) throws Exception {
        var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
        var generators = new ArrayList<CodeGenerator>(List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator()));
        if (lsp) generators.addAll(List.of(new LSPGenerator(), new LSPLauncherGenerator()));
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator : generators) {
            var generated = generator.generate(grammar);
            units.add(source(generated.packageName() + "." + generated.className(), generated.source()));
        }
        if (lsp) units.add(source("cutprobe.DiagnosticMain", """
            package cutprobe;
            public final class DiagnosticMain extends CutLspLauncher {
                @Override protected CutLanguageServer createServer() { return new CutLanguageServer() {}; }
                public static void main(String[] args) throws Exception { System.exit(new DiagnosticMain().launchStdio()); }
            }
            """));
        Path classes = temporary.newFolder().toPath();
        var compiler = org.unlaxer.dsl.Java21TestSupport.compiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            assertTrue(row.get("name") + " " + diagnostics.getDiagnostics(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                null, units).call());
        }
        return classes;
    }

    @Test public void javaGeneratedParsersMatchIndependentOracle() throws Exception {
        var rows = corpus("cases.json");
        assertEquals(9, rows.size());
        int observations = 0;
        for (var raw : rows) {
            var row = raw.getAsJsonObject();
            Path classes = compileJava(row, false);
            try (var loader = new URLClassLoader(new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
                var parser = (Parser) loader.loadClass("cutprobe.CutParsers").getMethod("getRootParser").invoke(null);
                var diagnose = loader.loadClass("cutprobe.CutMapper").getMethod("diagnose", String.class, ParseOptions.class);
                for (var value : row.getAsJsonArray("cases")) {
                    var expected = value.getAsJsonObject();
                    String input = expected.get("input").getAsString();
                    for (var memo : Memoization.values()) for (var mode : ParseOptions.Diagnostics.values()) {
                        var options = ParseOptions.withMemoization(memo).withDiagnostics(mode);
                        String label = row.get("name") + " " + expected + " " + memo + " " + mode;
                        String raisedLabel = "";
                        try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                            var parsed = parser.parse(context);
                            var raised = context.getRaisedFailure();
                            if (raised.isPresent()) {
                                assertFalse(label, parsed.isSucceeded());
                                assertEquals(label, 0, context.getConsumedPosition().value());
                                assertEquals(label, 1, context.getTokenStack().size());
                                raisedLabel = raised.get().label() == null ? "" : raised.get().label();
                            }
                        }
                        var diagnostic = (Optional<?>) diagnose.invoke(null, input, options);
                        String actual;
                        if (diagnostic.isEmpty()) actual = "success\t\t\t";
                        else {
                            var record = diagnostic.get();
                            String kind = (String) record.getClass().getMethod("kind").invoke(record);
                            int offset = (int) record.getClass().getMethod("offset").invoke(record);
                            @SuppressWarnings("unchecked")
                            var hints = (List<String>) record.getClass().getMethod("expected").invoke(record);
                            actual = switch (kind) {
                                case "commit", "label" -> kind + "\t" + offset + "\t" + String.join("|", hints) + "\t";
                                case "recovery" -> "recovery\t" + offset + "\t\t";
                                default -> "syntax\t\t\t";
                            };
                        }
                        assertEquals(label, expected(expected), actual + raisedLabel);
                        observations++;
                    }
                }
            }
        }
        assertEquals(25 * 6, observations);
    }

    @Test public void bothRustHostsGenerateAndRunTheSameOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] RaisingOperatorConformanceTest Rust hosts require -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo", "build", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), 0);
        for (var raw : corpus("cases.json")) {
            var row = raw.getAsJsonObject();
            Path dir = temporary.newFolder().toPath(), input = dir.resolve("input.ubnf"), out = dir.resolve("generated");
            Files.writeString(input, row.get("grammar").getAsString());
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar", input.toString(), "--output", out.toString()), 0);
            Path javaGenerated = Files.createDirectory(dir.resolve("java-generated"));
            var modules = new RustBackend().generate(grammar);
            assertEquals(5, modules.size());
            for (var file : modules) {
                assertEquals(row.get("name") + " " + file.relativePath(), file.content(), Files.readString(out.resolve(file.relativePath())));
                Files.writeString(javaGenerated.resolve(file.relativePath()), file.content());
            }
            Files.writeString(dir.resolve("main.rs"), RUST_PROBE);
            Files.writeString(dir.resolve("java-main.rs"), RUST_PROBE.replace("mod generated;", "#[path=\"java-generated/mod.rs\"] mod generated;"));
            Files.writeString(dir.resolve("Cargo.toml"), "[package]\nname=\"cut-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n"
                + "[[bin]]\nname=\"probe\"\npath=\"main.rs\"\n[[bin]]\nname=\"java-probe\"\npath=\"java-main.rs\"\n"
                + "[dependencies]\nunlaxer-runtime={path=\"" + ROOT.resolve("rust/unlaxer-runtime") + "\"}\n");
            Path target = ROOT.resolve("rust/target/cut-labeled-failure-probe");
            run(List.of("cargo", "build", "--offline", "--quiet", "--manifest-path", dir.resolve("Cargo.toml").toString(), "--target-dir", target.toString()), 0);
            List<String> nativeOutput = null;
            for (String host : List.of("probe", "java-probe")) {
                var command = new ArrayList<String>(List.of(target.resolve("debug/" + host).toString()));
                for (var value : row.getAsJsonArray("cases")) command.add(value.getAsJsonObject().get("input").getAsString());
                var output = run(command, 0).lines().toList();
                assertEquals(row.get("name") + " " + host, row.getAsJsonArray("cases").size() * 6, output.size());
                int index = 0;
                for (var value : row.getAsJsonArray("cases")) {
                    for (int option = 0; option < 6; option++) {
                        assertEquals(row.get("name") + " " + host + " " + value + " option=" + option,
                            expected(value.getAsJsonObject()), output.get(index++));
                    }
                }
                if (nativeOutput == null) nativeOutput = output;
                else assertEquals("independently executed Rust hosts", nativeOutput, output);
            }
        }
    }

    private static String nestedCatch(int depth) {
        String body = "LABEL(L, 'a')";
        for (int i = 0; i < depth; i++) body = "CATCH(" + body + ", 'b', L)";
        return "grammar Cut {\n  @package: cutprobe\n  @whitespace: none\n  @root @mapping(Doc)\n  Root ::= " + body + " ;\n}\n";
    }

    /** Nested CATCH stays linear: each operand is stored once on both hosts (40 levels, ~1 KB). */
    @Test(timeout = 120_000) public void deeplyNestedCatchGeneratesInLinearSpaceOnBothHosts() throws Exception {
        String source = nestedCatch(40);
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        assertFalse(new ParserGenerator().generate(grammar).source().isEmpty());
        var modules = new RustBackend().generate(grammar);
        if (!Boolean.getBoolean("rustConformance")) return;
        run(List.of("cargo", "build", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), 0);
        Path dir = temporary.newFolder().toPath(), input = dir.resolve("deep.ubnf"), out = dir.resolve("generated");
        Files.writeString(input, source);
        long started = System.nanoTime();
        run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar", input.toString(), "--output", out.toString()), 0);
        assertTrue("native generation must not grow with 2^depth", System.nanoTime() - started < 30_000_000_000L);
        for (var file : modules) assertEquals(file.relativePath(), file.content(), Files.readString(out.resolve(file.relativePath())));
    }

    private static final String RUST_PROBE = """
        #![allow(dead_code, unused_imports, unused_variables)]
        mod generated;
        use unlaxer_runtime::*;
        fn main() {
            for input in std::env::args().skip(1) {
                for memo in [Memoization::Off, Memoization::SafeFailures] {
                    for mode in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
                        let options = ParseOptions::with_memoization(memo).with_diagnostics(mode);
                        let line = match generated::parser::parse_tree_detailed_with_options(&input, options) {
                            Ok(tree) => match tree.recoveries().first() {
                                Some(recovery) => format!("recovery\\t{}\\t\\t", recovery.span.start),
                                None => "success\\t\\t\\t".to_owned(),
                            },
                            Err(error) if error.kind == "commit" || error.kind == "label" => {
                                format!("{}\\t{}\\t{}\\t", error.kind, error.offset, error.expected.join("|"))
                            }
                            Err(_) => "syntax\\t\\t\\t".to_owned(),
                        };
                        let mut context = ParseContext::with_options(&input, options);
                        let _ = generated::parser::parse_context(&mut context);
                        let label = context.raised_failure().and_then(|raised| raised.label).unwrap_or("");
                        if context.raised_failure().is_some() {
                            assert_eq!((context.position(), context.matched_position()), (0, 0));
                        }
                        println!("{line}{label}");
                    }
                }
            }
        }
        """;

    private static final String LSP_GRAMMAR = """
        grammar Cut {
          @package: cutprobe
          @whitespace: none
          @root @mapping(Doc)
          Root ::= { Item } ;
          Item ::= 'a' LABEL(semi, ';') | 'k' COMMIT('n', 'need n') ;
        }
        """;

    /** LSP diagnostics of both hosts report the declared message and kind at the raised offset. */
    @Test public void lspDiagnosticsCarryTheLabeledMessage() throws Exception {
        var row = new JsonObject();
        row.addProperty("name", "lsp");
        row.addProperty("grammar", LSP_GRAMMAR);
        Path classes = compileJava(row, true);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        try (var wire = new Wire(new ProcessBuilder(javaExecutable, "-cp", classes + File.pathSeparator + System.getProperty("java.class.path"),
                "cutprobe.DiagnosticMain").redirectError(temporary.newFile("java-stderr.log")).start())) {
            exercise(wire, "Java");
        }
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] Rust LSP cross-check requires -DrustConformance=true; Java LSP ran");
            return;
        }
        var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
        Path project = temporary.newFolder("rust-project").toPath();
        Path modules = Files.createDirectories(project.resolve("src/generated"));
        Path grammarFile = project.resolve("cut.ubnf");
        Files.writeString(grammarFile, row.get("grammar").getAsString());
        Path nativeOutput = project.resolve("native");
        run(List.of("cargo", "build", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), 0);
        run(List.of(ROOT.resolve("rust/target/debug/unlaxer").toString(), "generate", "--target", "rust", "--grammar", grammarFile.toString(),
            "--output", nativeOutput.toString(), "--lsp"), 0);
        for (var file : new RustBackend().generateWithLsp(grammar)) {
            assertEquals("both generator hosts: " + file.relativePath(), file.content(), Files.readString(nativeOutput.resolve(file.relativePath())));
            Files.writeString(modules.resolve(file.relativePath()), file.content());
        }
        Files.writeString(project.resolve("Cargo.toml"), """
            [package]
            name="cut-lsp-fixture"
            version="0.1.0"
            edition="2021"
            [dependencies]
            unlaxer-runtime={path="%s"}
            unlaxer-lsp={path="%s"}
            """.formatted(ROOT.resolve("rust/unlaxer-runtime"), ROOT.resolve("rust/unlaxer-lsp")));
        Files.writeString(project.resolve("src/main.rs"),
            "mod generated; fn main() { std::process::exit(generated::lsp::serve_stdio_status().unwrap()); }\n");
        Path target = ROOT.resolve("rust/target/cut-labeled-failure-probe");
        run(List.of("cargo", "build", "--quiet", "--offline", "--manifest-path", project.resolve("Cargo.toml").toString(),
            "--target-dir", target.toString()), 0);
        try (var wire = new Wire(new ProcessBuilder(target.resolve("debug/cut-lsp-fixture").toString())
                .redirectError(temporary.newFile("rust-stderr.log")).start())) {
            exercise(wire, "Rust");
        }
    }

    private void exercise(Wire wire, String backend) throws Exception {
        wire.request("initialize", Map.of("capabilities", Map.of()));
        wire.notify("initialized", Map.of());
        String uri = "file:///cut-fixture";
        int version = 0;
        for (var expected : List.of(List.<Object>of("a;ab", 3, "semi", "label"), List.<Object>of("kx", 1, "need n", "commit"))) {
            String text = (String) expected.get(0);
            String label = backend + " " + text;
            wire.notify("textDocument/didOpen", Map.of("textDocument", Map.of("uri", uri, "languageId", "cut", "version", ++version, "text", text)));
            var diagnostics = wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics");
            assertEquals(label + diagnostics, 1, diagnostics.size());
            var diagnostic = diagnostics.get(0).getAsJsonObject();
            var start = diagnostic.getAsJsonObject("range").getAsJsonObject("start");
            assertEquals(label + diagnostic, List.of(0, expected.get(1)), List.of(start.get("line").getAsInt(), start.get("character").getAsInt()));
            assertEquals(label + diagnostic, expected.get(2), diagnostic.get("message").getAsString());
            var data = diagnostic.getAsJsonObject("data");
            assertEquals(label + diagnostic, expected.get(3), data.get("kind").getAsString());
            assertEquals(label + diagnostic, (int) expected.get(1), data.get("offset").getAsInt());
            assertTrue(label + diagnostic, data.getAsJsonArray("expectedTokens").contains(new JsonPrimitive((String) expected.get(2))));
            wire.notify("textDocument/didChange", Map.of("textDocument", Map.of("uri", uri, "version", ++version),
                "contentChanges", List.of(Map.of("text", "a;kn"))));
            assertEquals(label, 0, wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics").size());
            wire.notify("textDocument/didClose", Map.of("textDocument", Map.of("uri", uri)));
            wire.notification("textDocument/publishDiagnostics");
        }
        wire.response("shutdown", null);
        wire.notify("exit", null);
        assertTrue(backend, wire.process.waitFor(5, TimeUnit.SECONDS));
    }

    private String run(List<String> command, int expected) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("CARGO_INCREMENTAL", "0");
        var process = builder.start();
        if (!process.waitFor(300, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout " + command); }
        String output = Files.readString(log);
        if (expected == 0) assertEquals(command + "\n" + output, 0, process.exitValue());
        return output;
    }

    private static JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return body; }
        };
    }

    /** Content-Length framed JSON-RPC over a child process, as in ClassicLspDiagnosticsConformanceTest. */
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
