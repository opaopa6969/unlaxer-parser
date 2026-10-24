package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/**
 * Independent UBNF {@code ''} corpus (#713): Java generated parsers, then the Rust files from
 * the Java and native frontends, each compiled and executed separately.
 */
public class EmptyLiteralConformanceTest {
    private static final String PACKAGE = "org.example.emptyliteral";
    private static final List<String> KEYS = List.of("prefix", "classic", "diagnostic", "ruleStack", "ast", "cst", "classicCst", "classicFailure");

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    /** #768: the lexical assertion has Not's truth table and retains its own zero-width token. */
    @Test public void lexicalInversionIsANegativeAssertionWithALeaf() {
        for (String input : List.of("", "a", "ab", "xy", "😀x")) {
            for (TokenKind kind : List.of(TokenKind.consumed, TokenKind.matchOnly)) {
                try (var context = new ParseContext(StringSource.createRootSource(input));
                     var lookahead = new ParseContext(StringSource.createRootSource(input))) {
                    var literal = new org.unlaxer.dsl.runtime.Lexing.LiteralParser("ab");
                    var parsed = literal.parse(context, kind, true);
                    boolean expected = new org.unlaxer.parser.combinator.Not(literal).parse(lookahead).isSucceeded();
                    assertEquals(input + "/" + kind, expected, parsed.isSucceeded());
                    assertEquals(!input.startsWith("ab"), parsed.isSucceeded());
                    assertEquals(0, context.getConsumedPosition().value());
                    assertEquals(0, context.getMatchedPosition().value());
                    if (parsed.isSucceeded()) {
                        Token leaf = parsed.getRootToken();
                        assertSame(literal, leaf.parser);
                        assertEquals(kind, leaf.tokenKind);
                        assertEquals(0, leaf.source.codePointLength().value());
                    }
                }
            }
        }
    }

    @Test public void javaGeneratedParsersMatchTheSharedOracle() throws Exception {
        var mismatches = new ArrayList<String>();
        var report = new ArrayList<>(List.of("fixture\tcase\tinput_json\tjava"));
        for (JsonElement fixtureElement : corpus()) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            // #749: '' is nullable only under @tokenStream / @lexicalContext, where it succeeds.
            assertTrue(name, org.unlaxer.dsl.PortabilityCheck.check(fixture.get("grammar").getAsString()).diagnostics()
                .stream().noneMatch(diagnostic -> diagnostic.code().equals("E-RAISING-REPETITION")));
            try (URLClassLoader loader = compileJava(grammar)) {
                Class<?> parsers = loader.loadClass(PACKAGE + "." + name + "Parsers");
                Parser parser = (Parser) parsers.getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + name + "Mapper");
                Set<String> rules = grammar.rules().stream().map(rule -> rule.name() + "Parser")
                    .collect(Collectors.toSet());
                for (JsonElement caseElement : fixture.getAsJsonArray("cases")) {
                    JsonObject row = caseElement.getAsJsonObject();
                    String input = row.get("input").getAsString();
                    JsonObject actual = javaObservation(parser, parsers, rules, mapper, input, row);
                    compare(name + "/" + row.get("id").getAsString() + " Java", row, true, actual, mismatches);
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input") + "\t" + actual);
                }
            }
        }
        write("empty-literal-java.tsv", report);
        assertEquals(String.join("\n", mismatches), List.of(), mismatches);
    }

    @Test public void bothRustFrontendsMatchTheSharedOracle() throws Exception {
        if (false == Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] EmptyLiteralConformanceTest Rust hosts skipped:"
                + " requires -DrustConformance=true and rustc/cargo on PATH.");
        }
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",
            Boolean.getBoolean("rustConformance"));
        Path root = temporary.getRoot().toPath();
        Path runtime = root.resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()), "", false));
        Path nativeTarget = root.resolve("native-target");
        success(run(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--target-dir",
            nativeTarget.toString()), "", false));
        Path nativeGenerator = nativeTarget.resolve("debug/unlaxer").toAbsolutePath();

        var mismatches = new ArrayList<String>();
        var report = new ArrayList<>(List.of("fixture\tcase\tinput_json\tjava_host_rust\tnative_host_rust"));
        int index = 0;
        for (JsonElement fixtureElement : corpus()) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String source = fixture.get("grammar").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            List<RustBackend.GeneratedFile> javaFrontend = new RustBackend().generate(grammar);
            assertEquals(name + " five Rust modules", 5, javaFrontend.size());
            Path directory = Files.createDirectory(root.resolve("fixture-" + index++));
            Path ubnf = directory.resolve(name + ".ubnf");
            Files.writeString(ubnf, source);
            Path nativeGenerated = directory.resolve("native-generated");
            success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", ubnf.toString(),
                "--output", nativeGenerated.toString()), "", true));
            Path generated = Files.createDirectory(directory.resolve("generated"));
            for (var file : javaFrontend) {
                assertArrayEquals(name + " native frontend byte parity: " + file.relativePath(),
                    file.content().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(nativeGenerated.resolve(file.relativePath())));
                Files.writeString(generated.resolve(file.relativePath()), file.content());
            }
            // Byte parity is necessary but not sufficient: each host's files run in their own binary.
            List<List<String>> hosts = new ArrayList<>();
            for (String host : List.of("generated", "native-generated")) {
                Path main = directory.resolve(host + "-main.rs");
                Files.writeString(main, rustProbe().replace("mod generated;",
                    "#[path = \"" + host + "/mod.rs\"] mod generated;"));
                Path binary = directory.resolve(host + "-probe");
                success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime,
                    main.toString(), "-o", binary.toString()), "", false));
                JsonArray cases = fixture.getAsJsonArray("cases");
                String framed = cases.asList().stream().map(row -> HexFormat.of().formatHex(
                    row.getAsJsonObject().get("input").getAsString().getBytes(StandardCharsets.UTF_8)) + ":"
                    + classicStart(row.getAsJsonObject(), 0) + ":" + classicStart(row.getAsJsonObject(), 1) + "\n")
                    .collect(Collectors.joining());
                ProcessResult result = run(List.of(binary.toString()), framed, false);
                success(result);
                List<String> lines = result.output().lines().toList();
                assertEquals(name + " " + host + " result count", cases.size(), lines.size());
                hosts.add(lines);
            }
            JsonArray cases = fixture.getAsJsonArray("cases");
            for (int i = 0; i < cases.size(); i++) {
                JsonObject row = cases.get(i).getAsJsonObject();
                String label = name + "/" + row.get("id").getAsString();
                compare(label + " Java-host Rust", row, false,
                    JsonParser.parseString(hosts.get(0).get(i)).getAsJsonObject(), mismatches);
                compare(label + " native-host Rust", row, false,
                    JsonParser.parseString(hosts.get(1).get(i)).getAsJsonObject(), mismatches);
                report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input") + "\t"
                    + hosts.get(0).get(i) + "\t" + hosts.get(1).get(i));
            }
        }
        write("rust-empty-literal.tsv", report);
        assertEquals(String.join("\n", mismatches), List.of(), mismatches);
    }

    /**
     * #750: the empty expected candidate is readable in the editor message. The Labeled fixture
     * fails at {@code a😀} expecting {@code ''} and {@code b}; Java's generated server shows
     * {@code Expected '' or 'b' (in WordParser)}, the Rust one {@code Expected '' or b}. The range, code, kind and
     * {@code data.expectedTokens} (the contract value {@code ""}) are unchanged.
     */
    @Test public void generatedLanguageServersShowTheEmptyExpectedCandidate() throws Exception {
        JsonObject fixture = corpus().asList().stream().map(JsonElement::getAsJsonObject)
            .filter(row -> row.get("name").getAsString().equals("Labeled")).findFirst().orElseThrow();
        String source = fixture.get("grammar").getAsString();
        GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator : List.<CodeGenerator>of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(),
                new LSPGenerator(), new LSPLauncherGenerator())) {
            GeneratedSource generated = generator.generate(grammar);
            units.add(source(generated.packageName() + "." + generated.className(), generated.source()));
        }
        units.add(source(PACKAGE + ".DiagnosticMain", """
            package org.example.emptyliteral;
            public final class DiagnosticMain extends LabeledLspLauncher {
                @Override protected LabeledLanguageServer createServer() { return new LabeledLanguageServer() {}; }
                public static void main(String[] args) throws Exception { System.exit(new DiagnosticMain().launchStdio()); }
            }
            """));
        Path classes = temporary.newFolder("lsp-classes").toPath();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()),
                null, units).call());
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        try (var wire = new LspWire(new ProcessBuilder(java, "-cp", classes + File.pathSeparator + System.getProperty("java.class.path"),
                PACKAGE + ".DiagnosticMain").redirectError(temporary.newFile("java-lsp-stderr.log")).start())) {
            exerciseLabeledServer(wire, "Java", "Expected '' or 'b' (in WordParser)", List.of("''", "'b'"));
        }
        if (false == Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] EmptyLiteralConformanceTest Rust LSP skipped: requires -DrustConformance=true");
            return;
        }
        Path project = temporary.newFolder("rust-lsp-project").toPath();
        Path modules = Files.createDirectories(project.resolve("src/generated"));
        Path ubnf = project.resolve("Labeled.ubnf");
        Files.writeString(ubnf, source);
        Path nativeOutput = project.resolve("native");
        Path target = repo.resolve("rust/target");
        success(run(List.of("cargo", "run", "--quiet", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
            "--target-dir", target.toString(), "-p", "unlaxer-generator", "--bin", "unlaxer", "--",
            "generate", "--target", "rust", "--grammar", ubnf.toString(), "--output", nativeOutput.toString(), "--lsp"), "", false));
        for (var file : new RustBackend().generateWithLsp(grammar)) {
            assertEquals("both generator hosts: " + file.relativePath(), file.content(),
                Files.readString(nativeOutput.resolve(file.relativePath())));
            Files.writeString(modules.resolve(file.relativePath()), file.content());
        }
        Files.writeString(project.resolve("Cargo.toml"), """
            [package]
            name="empty-literal-lsp-fixture"
            version="0.1.0"
            edition="2021"
            [dependencies]
            unlaxer-runtime={path="%s"}
            unlaxer-lsp={path="%s"}
            """.formatted(repo.resolve("rust/unlaxer-runtime"), repo.resolve("rust/unlaxer-lsp")));
        Files.writeString(project.resolve("src/main.rs"),
            "mod generated; fn main() { std::process::exit(generated::lsp::serve_stdio_status().unwrap()); }\n");
        success(run(List.of("cargo", "build", "--quiet", "--offline", "--manifest-path", project.resolve("Cargo.toml").toString(),
            "--target-dir", target.toString()), "", false));
        try (var wire = new LspWire(new ProcessBuilder(target.resolve("debug/empty-literal-lsp-fixture").toString())
                .redirectError(temporary.newFile("rust-lsp-stderr.log")).start())) {
            exerciseLabeledServer(wire, "Rust", "Expected '' or b", List.of("", "b"));
        }
    }

    private static void exerciseLabeledServer(LspWire wire, String backend, String message, List<String> expectedTokens) throws Exception {
        wire.request("initialize", java.util.Map.of("capabilities", java.util.Map.of()));
        wire.notify("initialized", java.util.Map.of());
        String uri = "file:///empty-literal-labeled";
        wire.notify("textDocument/didOpen", java.util.Map.of("textDocument", java.util.Map.of(
            "uri", uri, "languageId", "labeled", "version", 1, "text", "a\uD83D\uDE00")));
        var diagnostics = wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics");
        assertEquals(backend + " " + diagnostics, 1, diagnostics.size());
        var diagnostic = diagnostics.get(0).getAsJsonObject();
        var range = diagnostic.getAsJsonObject("range");
        // The supplementary scalar is two UTF-16 units: the range covers both.
        assertEquals(backend + " " + diagnostic, List.of(0, 1, 0, 3), List.of(
            range.getAsJsonObject("start").get("line").getAsInt(), range.getAsJsonObject("start").get("character").getAsInt(),
            range.getAsJsonObject("end").get("line").getAsInt(), range.getAsJsonObject("end").get("character").getAsInt()));
        assertEquals(backend + " " + diagnostic, "ULX-PARSE-001", diagnostic.get("code").getAsString());
        assertEquals(backend + " " + diagnostic, message, diagnostic.get("message").getAsString());
        var data = diagnostic.getAsJsonObject("data");
        assertEquals(backend + " " + diagnostic, "syntax", data.get("kind").getAsString());
        assertEquals(backend + " " + diagnostic, 1, data.get("offset").getAsInt());
        assertEquals(backend + " " + diagnostic, new Gson().toJsonTree(expectedTokens), data.getAsJsonArray("expectedTokens"));
        wire.notify("textDocument/didClose", java.util.Map.of("textDocument", java.util.Map.of("uri", uri)));
        wire.notification("textDocument/publishDiagnostics");
        wire.request("shutdown", null);
        wire.notify("exit", null);
        assertTrue(backend, wire.process.waitFor(5, TimeUnit.SECONDS));
    }

    private static JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return body; }
        };
    }

    /** Content-Length framed JSON-RPC over a child process, as in ClassicLspDiagnosticsConformanceTest. */
    private static final class LspWire implements AutoCloseable {
        private static final Gson JSON = new com.google.gson.GsonBuilder().serializeNulls().create();
        final Process process;
        final java.io.BufferedInputStream input;
        final java.io.OutputStream output;
        final java.util.concurrent.ExecutorService reader = java.util.concurrent.Executors.newSingleThreadExecutor();
        final List<JsonObject> pending = new ArrayList<>();
        int next;

        LspWire(Process process) {
            this.process = process;
            input = new java.io.BufferedInputStream(process.getInputStream());
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
                    if (unit < 0) throw new java.io.EOFException();
                    line.append((char) unit);
                    if (unit == '\n') {
                        String header = line.toString();
                        line.setLength(0);
                        if (header.equals("\r\n")) break;
                        if (header.startsWith("Content-Length:")) length = Integer.parseInt(header.substring(15).trim());
                    }
                }
                if (length < 0 || length > 4 * 1024 * 1024) throw new java.io.IOException("invalid frame");
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new java.io.EOFException();
                return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            }).get(20, TimeUnit.SECONDS);
        }
        JsonObject request(String method, Object params) throws Exception {
            int id = ++next;
            send(method, params, id);
            while (true) {
                var result = read();
                if (result.has("id") && result.get("id").getAsInt() == id) {
                    assertFalse(result.toString(), result.has("error"));
                    return result;
                }
                pending.add(result);
            }
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

    private JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(repo.resolve("spec-corpus/empty-literal/corpus.json")))
            .getAsJsonArray();
    }

    /** A host-specific key records an existing display difference; it never relaxes the shared one. */
    private void compare(String label, JsonObject row, boolean java, JsonObject actual, List<String> mismatches) {
        if (actual.has("variance")) mismatches.add(label + " memo/diagnostics variance: " + actual.get("variance"));
        for (String key : KEYS) {
            String override = (java ? "java" : "rust") + Character.toUpperCase(key.charAt(0)) + key.substring(1);
            JsonElement expected = row.has(override) ? row.get(override) : row.get(key);
            if (expected == null && (key.equals("classicCst") || key.equals("classicFailure"))) continue;
            if (expected == null) {
                mismatches.add(label + " missing oracle " + key + "; actual " + actual.get(key));
            } else if (false == expected.equals(actual.get(key))) {
                mismatches.add(label + " " + key + ": expected " + expected + " actual " + actual.get(key));
            }
        }
    }

    private JsonObject javaObservation(Parser parser, Class<?> parsers, Set<String> rules, Class<?> mapper,
            String input, JsonObject row) throws Exception {
        var result = new JsonObject();
        var variance = new ArrayList<String>();
        JsonArray prefix = null;
        JsonArray classic = null;
        JsonArray classicCst = null;
        JsonArray classicFailure = null;
        for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
            ParseOptions options = ParseOptions.withMemoization(memo);
            JsonArray memoPrefix;
            try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                memoPrefix = cursors(parser.parse(context).isSucceeded(), context);
            }
            var memoClassic = new JsonArray();
            var memoCst = new JsonArray();
            var memoFailure = new JsonArray();
            for (TokenKind kind : List.of(TokenKind.consumed, TokenKind.matchOnly)) {
                for (boolean invert : new boolean[] {false, true}) {
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                        for (int cp = 0; cp < classicStart(row, 0); cp++) {
                            assertTrue(new org.unlaxer.parser.elementary.WildCardCharacterParser()
                                .parse(context, TokenKind.consumed, false).isSucceeded());
                        }
                        for (int cp = classicStart(row, 0); cp < classicStart(row, 1); cp++) {
                            assertTrue(new org.unlaxer.parser.elementary.WildCardCharacterParser()
                                .parse(context, TokenKind.matchOnly, false).isSucceeded());
                        }
                        var parsed = parser.parse(context, kind, invert);
                        memoClassic.add(cursors(parsed.isSucceeded(), context));
                        memoCst.add(parsed.isSucceeded()
                            ? new JsonPrimitive(String.join(",", cst(parsed.getRootToken(), parsers, rules))) : JsonNull.INSTANCE);
                        memoFailure.add(parsed.isSucceeded() ? JsonNull.INSTANCE
                            : new JsonPrimitive(context.getParseFailureDiagnostics().getFarthestOffset()));
                    }
                }
            }
            if (prefix == null) {
                prefix = memoPrefix;
                classic = memoClassic;
                classicCst = memoCst;
                classicFailure = memoFailure;
            } else if (false == prefix.equals(memoPrefix) || false == classic.equals(memoClassic)
                    || false == classicCst.equals(memoCst) || false == classicFailure.equals(memoFailure)) {
                variance.add("memo " + memo + " cursors " + memoPrefix + " " + memoClassic);
            }
        }
        result.add("prefix", prefix);
        result.add("classic", classic);
        result.add("classicCst", classicCst);
        result.add("classicFailure", classicFailure);
        // Rule stacks (#703) exist only under DETAILED: the failed and the unconsumed-input stack.
        JsonArray ruleStack = null;
        for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
            ParseOptions options = ParseOptions.withMemoization(memo).withDiagnostics(ParseOptions.Diagnostics.DETAILED);
            var stacks = new JsonArray();
            try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                parser.parse(context);
                stacks.add(ruleStack(context.getRuleStack()));
                stacks.add(ruleStack(context.getUnconsumedInputRuleStack()));
            }
            if (ruleStack == null) ruleStack = stacks;
            else if (false == ruleStack.equals(stacks)) variance.add("memo " + memo + " rule stacks " + stacks);
        }
        JsonElement diagnostic = null;
        for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
            for (ParseOptions.Diagnostics policy : ParseOptions.Diagnostics.values()) {
                ParseOptions options = ParseOptions.withMemoization(memo).withDiagnostics(policy);
                Optional<?> found = (Optional<?>) mapper.getMethod("diagnose", String.class, ParseOptions.class)
                    .invoke(null, input, options);
                JsonElement value = found.isPresent() ? diagnosticJson(found.get()) : JsonNull.INSTANCE;
                if (diagnostic == null) diagnostic = value;
                else if (false == diagnostic.equals(value)) variance.add(memo + "/" + policy + " " + value);
            }
        }
        result.add("diagnostic", diagnostic);
        result.add("ruleStack", ruleStack);
        JsonElement ast = JsonNull.INSTANCE;
        JsonElement cst = JsonNull.INSTANCE;
        if (diagnostic.isJsonNull()) {
            Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
            ast = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
            try (var context = new ParseContext(StringSource.createRootSource(input))) {
                List<String> nodes = cst(parser.parse(context).getRootToken(), parsers, rules);
                cst = new JsonPrimitive(String.join(",", nodes));
            }
        }
        result.add("ast", ast);
        result.add("cst", cst);
        if (false == variance.isEmpty()) result.addProperty("variance", String.join("; ", variance));
        return result;
    }

    /** {@code offset:Root@0>Item@1|omitted}, or JSON null without a stack. */
    private static JsonElement ruleStack(Optional<org.unlaxer.context.RuleStack> stack) {
        if (stack.isEmpty()) return JsonNull.INSTANCE;
        return new JsonPrimitive(stack.get().offset() + ":" + stack.get().frames().stream()
            .map(frame -> frame.rule() + "@" + frame.start()).collect(Collectors.joining(">"))
            + "|" + stack.get().omitted());
    }

    private static int classicStart(JsonObject row, int index) {
        return row.has("classicStart") ? row.getAsJsonArray("classicStart").get(index).getAsInt() : 0;
    }

    private static JsonArray cursors(boolean succeeded, ParseContext context) {
        var result = new JsonArray();
        result.add(succeeded);
        result.add(context.getConsumedPosition().value());
        result.add(context.getMatchedPosition().value());
        return result;
    }

    /** Rule-level CST: rule tokens with code-point spans; helper and terminal tokens are transparent. */
    private static List<String> cst(Token token, Class<?> parsers, Set<String> rules) {
        var children = new ArrayList<String>();
        for (Token child : token.getOriginalChildren()) children.addAll(cst(child, parsers, rules));
        Class<?> type = token.parser.getClass();
        if (type.getEnclosingClass() != parsers || false == rules.contains(type.getSimpleName())) return children;
        int start = token.source.offsetFromRoot().value();
        String name = type.getSimpleName();
        String node = name.substring(0, name.length() - "Parser".length()) + "[" + start + ","
            + (start + token.source.codePointLength().value()) + ")";
        return List.of(children.isEmpty() ? node : node + "{" + String.join(",", children) + "}");
    }

    private static JsonObject diagnosticJson(Object value) throws Exception {
        var result = new JsonObject();
        for (String property : List.of("kind", "offset", "expected", "farthestOffset", "farthestExpected")) {
            result.add(property, new Gson().toJsonTree(value.getClass().getMethod(property).invoke(value)));
        }
        return result;
    }

    private static JsonElement canonical(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonical(optional.orElse(null), mapped);
        if (value instanceof List<?> values) {
            var array = new JsonArray();
            for (Object item : values) array.add(canonical(item, mapped));
            return array;
        }
        var node = new JsonObject();
        node.addProperty("type", value.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class)
            .invoke(mapped, value)).orElseThrow();
        var pair = new JsonArray();
        pair.add(span[0]);
        pair.add(span[1]);
        node.add("span", pair);
        var fields = new JsonObject();
        for (var component : value.getClass().getRecordComponents()) {
            fields.add(component.getName(), canonical(component.getAccessor().invoke(value), mapped));
        }
        node.add("fields", fields);
        return node;
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            sources.add(generator.generate(grammar));
        }
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/" + source.className() + ".java"),
            JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
        }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("tests require a JDK, not a JRE", compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics, List.of("--release", "17",
                "-classpath", System.getProperty("java.class.path") + File.pathSeparator, "-d", output.toString()),
                null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }

    private static String rustProbe() {
        return """
            mod generated;
            use std::io::{self, BufRead};
            use unlaxer_runtime::token::TokenKind;
            use unlaxer_runtime::{json_string, Diagnostics, Memoization, ParseContext, ParseOptions, Tree};
            fn stack(stack: Option<unlaxer_runtime::RuleStack>) -> String {
                match stack {
                    None => "null".to_owned(),
                    Some(stack) => json_string(&format!("{}:{}|{}", stack.offset,
                        stack.frames.iter().map(|frame| format!("{}@{}", frame.rule, frame.start))
                            .collect::<Vec<_>>().join(">"), stack.omitted)),
                }
            }
            fn cursors(ok: bool, context: &ParseContext<'_>) -> String {
                format!("[{},{},{}]", ok, context.position(), context.matched_position())
            }
            // Rule-level CST: rule nodes with code-point spans; other nodes are transparent.
            fn cst(tree: &Tree, id: usize, out: &mut Vec<String>) {
                let node = &tree.nodes[id];
                let mut children = Vec::new();
                for &child in &node.children {
                    cst(tree, child, &mut children);
                }
                match generated::parser::grammar().get(node.rule) {
                    Some(rule) => {
                        let text = format!("{}[{},{})", rule.name, node.span.start, node.span.end);
                        out.push(if children.is_empty() { text } else { format!("{}{{{}}}", text, children.join(",")) });
                    }
                    None => out.extend(children),
                }
            }
            fn main() {
                let memos = [Memoization::Off, Memoization::SafeFailures];
                let modes = [(TokenKind::Consumed, false), (TokenKind::Consumed, true),
                    (TokenKind::MatchOnly, false), (TokenKind::MatchOnly, true)];
                for line in io::stdin().lock().lines() {
                    let framed = line.unwrap();
                    let fields = framed.split(':').collect::<Vec<_>>();
                    let encoded = fields[0];
                    let consumed: usize = fields[1].parse().unwrap();
                    let matched: usize = fields[2].parse().unwrap();
                    let bytes = (0..encoded.len()).step_by(2)
                        .map(|i| u8::from_str_radix(&encoded[i..i + 2], 16).unwrap()).collect();
                    let input = String::from_utf8(bytes).unwrap();
                    let mut variance = Vec::new();
                    let mut cursor_results: Option<(String, String, String, String)> = None;
                    for memo in memos {
                        let options = ParseOptions::with_memoization(memo);
                        let mut context = ParseContext::with_options(&input, options);
                        let ok = generated::parser::parse_context(&mut context).is_ok();
                        let prefix = cursors(ok, &context);
                        let mut classic_cst = Vec::new();
                        let mut classic_failure = Vec::new();
                        let classic = modes.iter().map(|&(kind, invert)| {
                            let mut context = ParseContext::with_options(&input, options);
                            for _ in 0..consumed {
                                context.parse_with_token_mode(&unlaxer_runtime::Expr::Any, TokenKind::Consumed, false).unwrap();
                            }
                            for _ in consumed..matched {
                                context.parse_with_token_mode(&unlaxer_runtime::Expr::Any, TokenKind::MatchOnly, false).unwrap();
                            }
                            let parsed = context.parse_with_token_mode(&generated::parser::GeneratedParser, kind, invert);
                            let mut nodes = Vec::new();
                            match &parsed {
                                Ok(parsed) => {
                                    let tree = context.tree(parsed.nodes[0]).unwrap();
                                    cst(&tree, tree.root, &mut nodes);
                                    classic_cst.push(json_string(&nodes.join(",")));
                                    classic_failure.push("null".to_owned());
                                }
                                Err(error) => {
                                    classic_cst.push("null".to_owned());
                                    classic_failure.push(error.offset.to_string());
                                }
                            }
                            cursors(parsed.is_ok(), &context)
                        }).collect::<Vec<_>>().join(",");
                        match &cursor_results {
                            None => cursor_results = Some((prefix, classic, classic_cst.join(","), classic_failure.join(","))),
                            Some((p, c, t, f)) if *p != prefix || *c != classic
                                || *t != classic_cst.join(",") || *f != classic_failure.join(",") =>
                                variance.push(format!("memo {:?} cursors {} {}", memo, prefix, classic)),
                            Some(_) => {}
                        }
                    }
                    let mut rule_stack: Option<String> = None;
                    for memo in memos {
                        let options = ParseOptions::with_memoization(memo).with_diagnostics(Diagnostics::Detailed);
                        let mut context = ParseContext::with_options(&input, options);
                        let _ = generated::parser::parse_context(&mut context);
                        let value = format!("[{},{}]", stack(context.rule_stack()),
                            stack(context.unconsumed_input_rule_stack()));
                        match &rule_stack {
                            None => rule_stack = Some(value),
                            Some(first) if *first != value => variance.push(format!("memo {:?} rule stacks {}", memo, value)),
                            Some(_) => {}
                        }
                    }
                    let mut diagnostic: Option<String> = None;
                    for memo in memos {
                        for policy in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
                            let options = ParseOptions::with_memoization(memo).with_diagnostics(policy);
                            let value = match generated::parser::parse_tree_detailed_with_options(&input, options) {
                                Ok(_) => "null".to_owned(),
                                Err(error) => error.canonical_json(),
                            };
                            match &diagnostic {
                                None => diagnostic = Some(value),
                                Some(first) if *first != value => variance.push(format!("{:?}/{:?} {}", memo, policy, value)),
                                Some(_) => {}
                            }
                        }
                    }
                    let (ast, cst_text) = match generated::parser::parse_tree_detailed(&input) {
                        Ok(tree) => {
                            let ast = generated::mapper::map(&tree).unwrap().canonical_json();
                            let mut nodes = Vec::new();
                            cst(&tree, tree.root, &mut nodes);
                            (ast, json_string(&nodes.join(",")))
                        }
                        Err(_) => ("null".to_owned(), "null".to_owned()),
                    };
                    let (prefix, classic, classic_cst, classic_failure) = cursor_results.unwrap();
                    let variance = if variance.is_empty() { String::new() }
                        else { format!(",\\"variance\\":{}", json_string(&variance.join("; "))) };
                    println!("{{\\"prefix\\":{},\\"classic\\":[{}],\\"classicCst\\":[{}],\\"classicFailure\\":[{}],\\"diagnostic\\":{},\\"ruleStack\\":{},\\"ast\\":{},\\"cst\\":{}{}}}",
                        prefix, classic, classic_cst, classic_failure, diagnostic.unwrap(), rule_stack.unwrap(), ast, cst_text, variance);
                }
            }
            """;
    }

    private static void write(String name, List<String> rows) throws Exception {
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve(name), rows, StandardCharsets.UTF_8);
    }

    private record ProcessResult(int code, String output) {}

    private ProcessResult run(List<String> command, String input, boolean withoutJava) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/nonexistent-unlaxer-java");
        }
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            if (false == process.waitFor(300, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                fail("process timed out: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static void success(ProcessResult result) {
        assertEquals(result.output(), 0, result.code());
    }
}
