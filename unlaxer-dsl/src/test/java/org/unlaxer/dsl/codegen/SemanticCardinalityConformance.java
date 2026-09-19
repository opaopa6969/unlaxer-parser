package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import com.google.gson.*;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Shared semantic-cardinality corpus; called by the existing CI conformance entry point. */
final class SemanticCardinalityConformance {
    private final Path root;
    private final Path repo;
    private final Path fixtures;
    SemanticCardinalityConformance(Path root, Path repo) {
        this.root = root;
        this.repo = repo;
        fixtures = repo.resolve("unlaxer-dsl/src/test/resources/semantic-cardinality");
    }

    void verify(boolean java, boolean rust) throws Exception {
        verify(java, rust, false);
    }

    void verifyKnownDivergences() throws Exception {
        verify(true, true, true);
    }

    private void verify(boolean java, boolean rust, boolean knownDivergence) throws Exception {
        Path library = root.resolve("libunlaxer_runtime.rlib");
        Path nativeBinary = root.resolve("native/debug/unlaxer");
        if (rust) {
            success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
                repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
            success(run(List.of("cargo", "build", "--offline", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
                "-p", "unlaxer-generator", "--target-dir", root.resolve("native").toString()), ""));
        }
        String template = Files.readString(fixtures.resolve("Grammar.ubnf.txt"));
        var corpus = JsonParser.parseString(Files.readString(fixtures.resolve(knownDivergence ? "known-divergences.json" : "corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of("grammar\tinput\tjava\trust"));
        var failures = new ArrayList<String>();
        for (var entry : corpus) {
            var fixture = entry.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            try {
            String source = template.replace("ROOT_BODY", fixture.has("root") ? fixture.get("root").getAsString() : "Helper @values")
                .replace("HELPER_BODY", fixture.get("body").getAsString())
                .replace("EXTRA_RULES", fixture.has("extra") ? fixture.get("extra").getAsString() : "");
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            Path dir = Files.createDirectory(root.resolve(name));
            var cases = fixture.getAsJsonArray("cases");
            List<JsonObject> results = new ArrayList<>();
            if (rust) {
                Path generated = Files.createDirectory(dir.resolve("generated"));
                for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
                Path ubnf = dir.resolve("grammar.ubnf");
                Files.writeString(ubnf, source);
                Path nativeGenerated = dir.resolve("native-generated");
                success(run(List.of(nativeBinary.toString(), "generate", "--grammar", ubnf.toString(), "--output", nativeGenerated.toString()), ""));
                for (var file : new RustBackend().generate(grammar)) {
                    assertEquals(name + " frontend parity " + file.relativePath(), file.content(), Files.readString(nativeGenerated.resolve(file.relativePath())));
                }
                Files.writeString(dir.resolve("main.rs"), probe(fixture));
                success(run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
                    dir.resolve("main.rs").toString(), "-o", dir.resolve("probe").toString()), ""));
                var output = run(List.of(dir.resolve("probe").toString()), String.join("\n", cases.asList().stream()
                    .map(row -> HexFormat.of().formatHex(row.getAsJsonObject().get("input").getAsString().getBytes(StandardCharsets.UTF_8)))
                    .toList()) + "\n");
                success(output);
                for (String line : output.output().lines().toList()) results.add(JsonParser.parseString(line).getAsJsonObject());
                assertEquals(name, cases.size(), results.size());
                for (int i = 0; i < cases.size(); i++) {
                    var row = cases.get(i).getAsJsonObject();
                    verifyResult(fixture, row, results.get(i), true);
                    if (!java) report.add(name + "\t" + row.get("input") + "\tnull\t" + results.get(i));
                }
            }
            if (java) {
                try (var loader = compileJava(grammar, dir)) {
                    var mapper = loader.loadClass("org.example.semantic.SemanticMapper");
                    var parser = (org.unlaxer.parser.Parser) loader.loadClass("org.example.semantic.SemanticParsers").getMethod("getRootParser").invoke(null);
                    var type = loader.loadClass("org.example.semantic.SemanticAST$Box").getMethod("values").getReturnType();
                    if (knownDivergence) assertEquals(name + " known legacy Java lexical API", String.class, type);
                    switch (fixture.get("cardinality").getAsString()) {
                        case "optional" -> assertEquals(name + " Optional field", Optional.class, type);
                        case "many" -> assertEquals(name + " Many field", List.class, type);
                        case "one" -> assertFalse(name + " One field", type == Optional.class || type == List.class);
                        default -> throw new AssertionError(fixture);
                    }
                    for (int i = 0; i < cases.size(); i++) {
                        var row = cases.get(i).getAsJsonObject();
                        String input = row.get("input").getAsString();
                        var result = new JsonObject();
                        var prefix = new JsonArray();
                        try (var context = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                            prefix.add(parser.parse(context).isSucceeded());
                            prefix.add(context.getConsumedPosition().value());
                            prefix.add(context.getMatchedPosition().value());
                        }
                        result.add("prefix", prefix);
                        var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                        if (diagnostic.isEmpty()) {
                            Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                            JsonElement ast = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                            result.add("ast", ast);
                            result.addProperty("value", String.join(",", values(ast.getAsJsonObject().getAsJsonObject("fields").get("values"))));
                        } else result.add("ast", JsonNull.INSTANCE);
                        verifyResult(fixture, row, result, false);
                        JsonElement actualRust = JsonNull.INSTANCE;
                        if (rust) {
                            var actual = results.get(i);
                            actualRust = actual;
                            assertEquals(name + " " + row + " both cursors", result.get("prefix"), actual.get("prefix"));
                            if (knownDivergence && row.has("value")) {
                                assertNotEquals(name + " explicit known alias API difference", result.get("ast"), actual.get("ast"));
                            } else {
                                assertEquals(name + " " + row + " all fields/node spans", result.get("ast"), actual.get("ast"));
                            }
                        }
                        report.add(name + "\t" + row.get("input") + "\t" + result + "\t" + actualRust);
                    }
                }
            }
            } catch (AssertionError | Exception error) {
                failures.add(name + ": " + error);
            }
        }
        String mode = java && rust ? "both" : java ? "java" : "rust";
        Files.write(Path.of("target/semantic-cardinality-" + (knownDivergence ? "known-divergence-" : "") + mode + ".tsv"), report, StandardCharsets.UTF_8);
        assertTrue(String.join("\n", failures), failures.isEmpty());
    }

    private void verifyResult(JsonObject fixture, JsonObject row, JsonObject result, boolean rust) {
        String context = fixture.get("name") + " " + row;
        assertEquals(context + " acceptance", row.has("value"), !result.get("ast").isJsonNull());
        if (!row.has("value")) return;
        assertEquals(context + " independent value/order", !rust && row.has("javaValue") ? row.get("javaValue") : row.get("value"), result.get("value"));
        String input = row.get("input").getAsString();
        var ast = result.getAsJsonObject("ast");
        assertEquals("Box", ast.get("type").getAsString());
        var span = ast.getAsJsonArray("span");
        assertEquals(0, span.get(0).getAsInt());
        assertEquals(input.codePointCount(0, input.length()), span.get(1).getAsInt());
        assertSpans(ast, input, 0, input.codePointCount(0, input.length()));
        if (row.has("nodes")) {
            var nodes = new JsonArray();
            collectNodeSpans(ast, nodes);
            assertEquals(context + " independent node spans (equal records stay distinct)", row.get("nodes"), nodes);
        }
        var field = ast.getAsJsonObject("fields").get("values");
        if (fixture.get("cardinality").getAsString().equals("many")) assertTrue(context, field.isJsonArray());
        else assertFalse(context, field.isJsonArray());
        if (rust) assertEquals(context + " independent Text spans", row.get("texts"), result.get("texts"));
        for (var item : row.getAsJsonArray("texts")) {
            var text = item.getAsJsonArray();
            String slice = input.substring(input.offsetByCodePoints(0, text.get(0).getAsInt()), input.offsetByCodePoints(0, text.get(1).getAsInt()));
            assertEquals(context + " text span fixture", text.get(2).getAsString(), slice.strip());
        }
    }

    private void assertSpans(JsonElement value, String input, int start, int end) {
        if (value.isJsonArray()) { for (var item : value.getAsJsonArray()) assertSpans(item, input, start, end); return; }
        if (!value.isJsonObject()) return;
        var ast = value.getAsJsonObject();
        var span = ast.getAsJsonArray("span");
        int left = span.get(0).getAsInt(), right = span.get(1).getAsInt();
        assertTrue(ast.toString(), start <= left && left <= right && right <= end);
        if (ast.get("type").getAsString().equals("Leaf")) {
            String source = input.substring(input.offsetByCodePoints(0, left), input.offsetByCodePoints(0, right));
            assertTrue(ast.toString(), source.contains(ast.getAsJsonObject("fields").get("text").getAsString()));
        }
        for (var field : ast.getAsJsonObject("fields").entrySet()) assertSpans(field.getValue(), input, left, right);
    }

    private void collectNodeSpans(JsonElement value, JsonArray result) {
        if (value.isJsonArray()) { for (var child : value.getAsJsonArray()) collectNodeSpans(child, result); return; }
        if (!value.isJsonObject()) return;
        var ast = value.getAsJsonObject();
        if (ast.get("type").getAsString().equals("Leaf")) {
            var entry = ast.getAsJsonArray("span").deepCopy();
            entry.add(ast.getAsJsonObject("fields").get("text"));
            result.add(entry);
        }
        for (var field : ast.getAsJsonObject("fields").entrySet()) collectNodeSpans(field.getValue(), result);
    }

    private List<String> values(JsonElement value) {
        if (value.isJsonNull()) return List.of();
        if (value.isJsonPrimitive()) return List.of("T:" + value.getAsString());
        if (value.isJsonArray()) return value.getAsJsonArray().asList().stream().flatMap(item -> values(item).stream()).toList();
        var node = value.getAsJsonObject();
        assertEquals("Leaf", node.get("type").getAsString());
        return List.of("L:" + node.getAsJsonObject("fields").get("text").getAsString());
    }

    private String probe(JsonObject fixture) throws Exception {
        boolean mixed = fixture.get("kind").getAsString().equals("value");
        String kind = mixed ? "AstValue" : "Ast";
        String cardinality = fixture.get("cardinality").getAsString();
        String type = switch (cardinality) { case "one" -> "&" + kind; case "optional" -> "Option<&" + kind + ">"; default -> "&[" + kind + "]"; };
        String iterator = cardinality.equals("one") ? "std::iter::once(values)" : "values.iter()";
        String spans = mixed ? """
            let texts = VALUE_ITER.filter_map(|value| match value {
                AstValue::Text { text, span } => Some(format!("[{},{},{}]", span.start, span.end, json_string(text))),
                AstValue::Node(_) => None,
            }).collect::<Vec<_>>().join(",");
            format!("[{texts}]")
            """ : "let _ = values; \"[]\".into()";
        return Files.readString(fixtures.resolve("probe.rs.txt"))
            .replace("VALUE_IMPORT", mixed ? "use generated::ast::AstValue;" : "")
            .replace("FIELD_TYPE", type).replace("TEXT_SPANS", spans).replace("VALUE_ITER", iterator)
            .replace("EVAL_ITEM", mixed ? "match value { AstValue::Text { text, .. } => vec![format!(\"T:{text}\")], AstValue::Node(node) => evaluate(node, self) }" : "evaluate(value, self)");
    }

    private URLClassLoader compileJava(org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl grammar, Path dir) throws Exception {
        var sources = new ArrayList<CodeGenerator.GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator())) sources.add(generator.generate(grammar));
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/" + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
            }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = Files.createDirectory(dir.resolve("java"));
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JsonElement canonical(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonical(optional.orElse(null), mapped);
        if (value instanceof List<?> list) { var array = new JsonArray(); for (var item : list) array.add(canonical(item, mapped)); return array; }
        var ast = new JsonObject(); ast.addProperty("type", value.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, value)).orElseThrow();
        var position = new JsonArray(); position.add(span[0]); position.add(span[1]); ast.add("span", position);
        var fields = new JsonObject();
        for (var component : value.getClass().getRecordComponents()) fields.add(component.getName(), canonical(component.getAccessor().invoke(value), mapped));
        ast.add("fields", fields); return ast;
    }

    private record Result(int code, String output) {}
    private Result run(List<String> command, String input) throws Exception {
        Path log = Files.createTempFile(root, "process", ".log");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(command.toString(), process.waitFor(60, TimeUnit.SECONDS));
            return new Result(process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private void success(Result result) { assertEquals(result.output(), 0, result.code()); }
}
