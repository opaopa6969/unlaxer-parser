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
 * inherited Java / Java-host Rust / native Rust consumers; invalid settings are rejected with the
 * same code, subject and code-point span by Java and the native generator.
 */
public class GlobalCommentConformanceTest extends RuleTriviaConformanceTest {
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

    private static JsonArray invalid() throws Exception {
        return JsonParser.parseString(Files.readString(
            REPO.resolve("spec-corpus/global-comment/invalid.json"))).getAsJsonArray();
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
