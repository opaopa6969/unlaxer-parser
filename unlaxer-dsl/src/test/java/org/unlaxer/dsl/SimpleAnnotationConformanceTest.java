package org.unlaxer.dsl;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.codegen.ASTGenerator;
import org.unlaxer.dsl.codegen.CodeGenerator;
import org.unlaxer.dsl.codegen.DAPGenerator;
import org.unlaxer.dsl.codegen.EvaluatorGenerator;
import org.unlaxer.dsl.codegen.GrammarValidator;
import org.unlaxer.dsl.codegen.LSPGenerator;
import org.unlaxer.dsl.codegen.MapperGenerator;
import org.unlaxer.dsl.codegen.ParserGenerator;
import org.unlaxer.dsl.codegen.SimpleAnnotations;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.ir.GrammarToParserIrExporter;

/**
 * Simple annotations (#715): the shared corpus {@code spec-corpus/simple-annotations} fixes the
 * 3.3.0 Java behaviour (accepted, generated as if absent, except that {@code @autoCommit} leaves the
 * rule out of its analysis) and the {@code W-ANNOTATION-IGNORED}
 * warnings with their code-point spans, suggestions and messages. The native generator reads the
 * same corpus in {@code rust/unlaxer-generator/tests/simple_annotations.rs}.
 */
public class SimpleAnnotationConformanceTest {
    private static final Path CORPUS = Path.of("..").toAbsolutePath().normalize().resolve("spec-corpus/simple-annotations");

    @Test public void warningsMatchTheSharedCorpus() throws Exception {
        for (JsonElement element : corpus("corpus.json")) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString(), source = row.get("grammar").getAsString();
            // The tree before the @autoCommit rewrite shows whether that analysis runs (#715).
            assertEquals(name, row.get("warnings"), warnings(UBNFMapper.parseWithSourceBeforeAutoCommit(source)));
            var lines = new JsonArray();
            for (JsonElement warning : row.getAsJsonArray("warnings")) lines.add(line(warning.getAsJsonObject()));
            var collected = new JsonArray();
            for (var warning : org.unlaxer.dsl.codegen.GenerationWarnings.collect(source))
                if (warning.code().equals(SimpleAnnotations.IGNORED)) collected.add(warning.line());
            assertEquals(name + " generation warnings", lines, collected);
        }
    }

    @Test public void everyJavaConsumerGeneratesIgnoredAnnotationsAsAbsent() throws Exception {
        List<CodeGenerator> generators = List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(),
            new EvaluatorGenerator(), new LSPGenerator(), new DAPGenerator());
        for (JsonElement element : corpus("corpus.json")) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString();
            String source = row.get("grammar").getAsString(), baselineSource = row.get("baseline").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            GrammarDecl baseline = UBNFMapper.parse(baselineSource).grammars().get(0);
            var issues = GrammarValidator.validate(grammar);
            assertEquals(name + " validator errors", List.of(), issues.stream().filter(i -> i.severity().equals("ERROR")).toList());
            // The validator sees the rewritten tree; GenerationWarnings reports these (with spans).
            assertEquals(name + " validator", 0, issues.stream().filter(i -> i.code().equals(SimpleAnnotations.IGNORED)).count());
            for (CodeGenerator generator : generators) {
                assertEquals(name + " " + generator.getClass().getSimpleName(), generator.generate(baseline), generator.generate(grammar));
            }
            assertEquals(name + " Java-host Rust", new RustBackend().generate(baseline), new RustBackend().generate(grammar));
            if (row.has("contrast")) {
                // With @autoCommit the annotation is not inert: removing only it changes the output.
                GrammarDecl contrast = UBNFMapper.parse(row.get("contrast").getAsString()).grammars().get(0);
                assertNotEquals(name + " contrast", new ParserGenerator().generate(contrast), new ParserGenerator().generate(grammar));
                assertNotEquals(name + " contrast Rust", new RustBackend().generate(contrast), new RustBackend().generate(grammar));
            }
            var portability = PortabilityCheck.check(source);
            assertEquals(name + " portability", PortabilityCheck.check(baselineSource), portability);
            assertTrue(name + " portable: " + portability, portability.portable());
        }
    }

    /** The same table as the native {@code suggestions_follow_the_documented_distance}. */
    @Test public void suggestionsFollowTheDocumentedDistance() {
        String[][] table = {
            {"leftAssoc", "leftAssoc"}, {"LEFTASSOC", "leftAssoc"}, {"evl", "eval"}, {"dco", "doc"},
            {"foo", null}, {"a", null}, {"nestedCaptures", null}, {"autocommit", "autoCommit"},
            {"rooted", "root"}, {"custom", null}};
        for (String[] row : table) assertEquals(row[0], row[1], SimpleAnnotations.suggestion(row[0]));
    }

    /** The parser IR export keeps the ignored name as metadata, as 3.3.0 did. */
    @Test public void parserIrKeepsTheNameAsMetadata() throws Exception {
        JsonObject row = corpus("corpus.json").get(0).getAsJsonObject();
        assertEquals("unknown", row.get("name").getAsString());
        var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
        String ir = GrammarToParserIrExporter.export(grammar, "simple.ubnf").payload().toString();
        assertTrue(ir, ir.contains("name=simple") && ir.contains("name=custom"));
    }

    @Test public void rejectedFormsFailInEveryJavaConsumer() throws Exception {
        for (JsonElement element : corpus("rejected.json")) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString(), source = row.get("grammar").getAsString();
            assertThrows(name, IllegalArgumentException.class, () -> UBNFMapper.parse(source));
            var portability = PortabilityCheck.check(source);
            assertEquals(name, "unavailable", portability.structure());
            assertEquals(name, List.of("P-SYNTAX"), portability.diagnostics().stream().map(PortabilityCheck.Diagnostic::code).toList());
        }
    }

    /** Java CLI: a warning by default, a failure under --strict and --fail-on warning. */
    @Test public void cliWarnsAndStrictOrFailOnWarningFails() throws Exception {
        Path work = Files.createTempDirectory("simple-annotations-cli");
        for (JsonElement element : corpus("corpus.json")) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString();
            int warnings = row.getAsJsonArray("warnings").size();
            Path grammar = work.resolve(name + ".ubnf");
            Files.writeString(grammar, row.get("grammar").getAsString());

            Path java = work.resolve(name + "-java");
            var generated = run("--grammar", grammar.toString(), "--output", java.toString(), "--generators", "Parser,AST,Mapper");
            assertEquals(name + " " + generated, CodegenMain.EXIT_OK, generated.code());
            assertTrue(name + " Java output", Files.isDirectory(java));
            for (int i = 0; i < warnings; i++) {
                String line = line(row.getAsJsonArray("warnings").get(i).getAsJsonObject());
                assertTrue(name + " " + generated, generated.err().contains("Validation warnings:")
                    && generated.err().contains(" - grammar " + grammarName(row) + ": " + line + " [code: "));
            }
            for (String[] policy : List.of(new String[] {"--strict"}, new String[] {"--fail-on", "warning"})) {
                var args = new ArrayList<>(List.of("--grammar", grammar.toString(), "--validate-only"));
                args.addAll(List.of(policy));
                var result = run(args.toArray(String[]::new));
                assertEquals(name + " " + String.join(" ", policy) + " " + result,
                    warnings == 0 ? CodegenMain.EXIT_OK : CodegenMain.EXIT_STRICT_VALIDATION_ERROR, result.code());
                if (warnings > 0) assertTrue(name + " " + result, result.err().contains(SimpleAnnotations.IGNORED));
            }

            Path rust = work.resolve(name + "-rust"), baselineRust = work.resolve(name + "-rust-baseline");
            Path baseline = work.resolve(name + "-baseline.ubnf");
            Files.writeString(baseline, row.get("baseline").getAsString());
            for (var pair : List.of(List.of(grammar, rust), List.of(baseline, baselineRust))) {
                var result = run("generate", "--target", "rust", "--grammar", pair.get(0).toString(), "--output", pair.get(1).toString());
                assertEquals(name + " Java-host Rust CLI " + result, CodegenMain.EXIT_OK, result.code());
            }
            assertEquals(name + " Java-host Rust CLI output", tree(baselineRust), tree(rust));
            var check = run("check", "--target", "rust", "--grammar", grammar.toString(), "--format", "json");
            assertEquals(name + " check " + check, CodegenMain.EXIT_OK, check.code());
        }
    }

    /** The CLI line of a corpus warning, from its span and message. */
    private static String line(JsonObject warning) {
        var span = warning.getAsJsonArray("span");
        return "warning: CP " + span.get(0).getAsInt() + ".." + span.get(1).getAsInt() + ": " + warning.get("message").getAsString();
    }

    private static String grammarName(JsonObject row) {
        return UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0).name();
    }

    private static JsonArray warnings(UBNFSourceSnapshot snapshot) {
        var out = new JsonArray();
        for (var warning : SimpleAnnotations.warnings(snapshot.ast().grammars().get(0))) {
            var value = new JsonObject();
            value.addProperty("rule", warning.rule());
            value.addProperty("code", warning.code());
            value.addProperty("subject", warning.subject());
            var span = snapshot.spanOf(warning.node()).orElseThrow();
            var array = new JsonArray();
            array.add(span.start());
            array.add(span.end());
            value.add("span", array);
            if (warning.suggestion() == null) value.add("suggestion", JsonNull.INSTANCE);
            else value.addProperty("suggestion", warning.suggestion());
            value.addProperty("message", warning.message());
            value.addProperty("excludesAutoCommit", warning.excludesAutoCommit());
            out.add(value);
        }
        return out;
    }

    private static JsonArray corpus(String file) throws Exception {
        return JsonParser.parseString(Files.readString(CORPUS.resolve(file))).getAsJsonArray();
    }

    private static Map<String, String> tree(Path root) throws Exception {
        var files = new TreeMap<String, String>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList())
                files.put(root.relativize(path).toString(), Files.readString(path));
        }
        assertFalse(root.toString(), files.isEmpty());
        return files;
    }

    private record Run(int code, String out, String err) {}

    private static Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = CodegenMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
