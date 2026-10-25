package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/**
 * Global {@code @comment: { line: '//' }} (#712): the shared positive corpus runs through the
 * inherited Java / Java-host Rust / native Rust consumers, including their explicit Classic
 * entries; invalid settings are rejected with the same code, subject and code-point span by Java
 * and the native generator.
 */
public class GlobalCommentConformanceTest extends AtomicTriviaConformanceTest {
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final List<String> CODES =
        List.of("E-COMMENT-SETTING", "E-COMMENT-WHITESPACE", "E-TOKEN-STREAM-TRIVIA");

    @Override protected Path fixtureCorpus() {
        return REPO.resolve("spec-corpus/global-comment/corpus.json");
    }

    @Override protected String reportName() { return "rust-global-comment"; }

    @Test public void javaRejectsInvalidSettingsWithCodeSubjectAndSpan() throws Exception {
        for (JsonElement element : invalid()) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString(), code = row.get("code").getAsString();
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            assertTrue(name + " validator", GrammarValidator.validate(grammar).stream()
                .anyMatch(issue -> issue.code().equals(code)));
            assertTrue(name + " Java generator", assertThrows(IllegalArgumentException.class,
                () -> new ParserGenerator().generate(grammar)).getMessage().startsWith(code));
            assertTrue(name + " Java-host Rust generator", assertThrows(IllegalArgumentException.class,
                () -> new RustBackend().generate(grammar)).getMessage().contains(code));
            var selected = selected(row);
            assertEquals(name + " portability code/subject", 1, selected.size());
            assertEquals(name + " portability subject", row.get("subject").getAsString(),
                selected.get(0).subject());
            assertEquals(name + " portability code-point span", row.get("span"),
                spanArray(selected.get(0).span().start(), selected.get(0).span().end()));
        }
    }

    @Test public void nativeGeneratorRejectsInvalidSettingsLikeJava() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",
            Boolean.getBoolean("rustConformance"));
        assertEquals(0, process(List.of("cargo", "build", "--locked", "--manifest-path",
            REPO.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path generator = REPO.resolve("rust/target/debug/unlaxer");
        Path work = Files.createTempDirectory("global-comment-invalid");
        var report = new ArrayList<>(List.of("case\tcode\tspan\tnative_check\tnative_generate"));
        for (JsonElement element : invalid()) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString(), code = row.get("code").getAsString();
            Path ubnf = work.resolve(name + ".ubnf");
            Files.writeString(ubnf, row.get("grammar").getAsString());
            var check = process(List.of(generator.toString(), "check", "--target", "rust",
                "--grammar", ubnf.toString(), "--format", "json"), true);
            assertEquals(name + " native check exit: " + check.output(), 3, check.code());
            var nativeSelected = new JsonArray();
            for (JsonElement diagnostic : JsonParser.parseString(check.output()).getAsJsonObject()
                    .getAsJsonArray("diagnostics")) {
                JsonObject value = diagnostic.getAsJsonObject().deepCopy();
                if (!CODES.contains(value.get("code").getAsString())) continue;
                assertEquals("error", value.remove("severity").getAsString());
                nativeSelected.add(value);
            }
            assertEquals(name + " Java/native portability diagnostics",
                new Gson().toJsonTree(selected(row)), nativeSelected);

            Path output = work.resolve(name + "-must-not-exist");
            var generate = process(List.of(generator.toString(), "generate", "--grammar",
                ubnf.toString(), "--output", output.toString()), true);
            assertNotEquals(name + " native generate must fail", 0, generate.code());
            assertTrue(name + " native generate code: " + generate.output(), generate.output().contains(code));
            assertFalse(name + " no partial output", Files.exists(output));
            report.add(name + "\t" + code + "\t" + row.get("span") + "\t" + nativeSelected + "\t"
                + generate.output().strip().replace('\n', ' '));
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/rust-global-comment-invalid.tsv"), report, StandardCharsets.UTF_8);
    }

    /**
     * Next to a global javaStyle, every form rejected above generated javaStyle alone in 3.3.0:
     * it stays accepted, generates the same code and is reported as W-COMMENT-IGNORED with the
     * code-point span that the native {@code global_comment::warnings} reports (same corpus).
     */
    @Test public void javaStyleKeepsIgnoredSettingsAsWarnings() throws Exception {
        JsonArray rows = corpus("ignored.json");
        assertEquals(8, rows.size());
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString();
            var snapshot = UBNFMapper.parseWithSource(row.get("grammar").getAsString());
            var grammar = snapshot.ast().grammars().get(0);
            var baseline = UBNFMapper.parse(row.get("baseline").getAsString()).grammars().get(0);
            var warnings = new JsonArray();
            for (var warning : GlobalComment.warnings(grammar)) {
                var value = new JsonObject();
                value.addProperty("code", warning.code());
                value.addProperty("subject", warning.subject());
                var span = snapshot.spanOf(warning.node()).orElseThrow();
                value.add("span", spanArray(span.start(), span.end()));
                warnings.add(value);
            }
            assertEquals(name + " warnings", row.get("warnings"), warnings);
            var issues = GrammarValidator.validate(grammar);
            assertEquals(name + " validator errors", List.of(), issues.stream()
                .filter(issue -> issue.severity().equals("ERROR")).toList());
            // The validator API keeps the warning; the flag CLI and the UBNF LSP show it once, from
            // GenerationWarnings (GenerationWarningsConformanceTest, #777; UBNFLanguageServerExtTest, #798).
            assertEquals(name + " validator warnings", warnings.size(), issues.stream()
                .filter(issue -> issue.code().equals(GlobalComment.IGNORED)
                    && issue.severity().equals("WARNING")).count());
            assertEquals(name + " Java parser", new ParserGenerator().generate(baseline),
                new ParserGenerator().generate(grammar));
            assertEquals(name + " Java mapper", new MapperGenerator().generate(baseline),
                new MapperGenerator().generate(grammar));
            assertEquals(name + " Java-host Rust", new RustBackend().generate(baseline),
                new RustBackend().generate(grammar));
            var portability = PortabilityCheck.check(row.get("grammar").getAsString());
            assertTrue(name + " portable: " + portability, portability.portable()
                && portability.diagnostics().isEmpty());
        }
    }

    @Test public void nativeGeneratorGeneratesIgnoredSettingsAsJavaStyle() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",
            Boolean.getBoolean("rustConformance"));
        assertEquals(0, process(List.of("cargo", "build", "--locked", "--manifest-path",
            REPO.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path generator = REPO.resolve("rust/target/debug/unlaxer");
        Path work = Files.createTempDirectory("global-comment-ignored");
        for (JsonElement element : corpus("ignored.json")) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString();
            var trees = new ArrayList<java.util.Map<String, String>>();
            for (String key : List.of("grammar", "baseline")) {
                Path ubnf = work.resolve(name + "-" + key + ".ubnf"), output = work.resolve(name + "-" + key);
                Files.writeString(ubnf, row.get(key).getAsString());
                var generate = process(List.of(generator.toString(), "generate", "--grammar",
                    ubnf.toString(), "--output", output.toString()), true);
                assertEquals(name + " " + key + " native generate: " + generate.output(), 0, generate.code());
                var files = new java.util.TreeMap<String, String>();
                try (var paths = Files.walk(output)) {
                    for (Path path : paths.filter(Files::isRegularFile).toList())
                        files.put(output.relativize(path).toString(), Files.readString(path));
                }
                trees.add(files);
            }
            assertFalse(name + " native output", trees.get(0).isEmpty());
            assertEquals(name + " native output like javaStyle alone", trees.get(1), trees.get(0));
        }
    }

    private static JsonArray invalid() throws Exception {
        return corpus("invalid.json");
    }

    private static JsonArray corpus(String file) throws Exception {
        return JsonParser.parseString(Files.readString(
            REPO.resolve("spec-corpus/global-comment/" + file))).getAsJsonArray();
    }

    private static List<PortabilityCheck.Diagnostic> selected(JsonObject row) {
        return PortabilityCheck.check(row.get("grammar").getAsString()).diagnostics().stream()
            .filter(diagnostic -> CODES.contains(diagnostic.code())).toList();
    }

    private static JsonArray spanArray(int start, int end) {
        var span = new JsonArray();
        span.add(start);
        span.add(end);
        return span;
    }

    private record Result(int code, String output) {}

    private static Result process(List<String> command, boolean withoutJava) throws Exception {
        Path log = Files.createTempFile("global-comment", ".log");
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/nonexistent-unlaxer-java");
        }
        Process started = builder.start();
        started.getOutputStream().close();
        if (!started.waitFor(600, TimeUnit.SECONDS)) {
            started.destroyForcibly();
            fail("process timed out: " + command);
        }
        return new Result(started.exitValue(), Files.readString(log));
    }
}
