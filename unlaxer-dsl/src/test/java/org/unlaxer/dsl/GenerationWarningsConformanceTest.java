package org.unlaxer.dsl;

import static org.junit.Assert.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.unlaxer.dsl.codegen.GenerationWarnings;

/**
 * The shared generation warnings (#715): {@code spec-corpus/generation-warnings} fixes the code,
 * span, text and CLI line of every warning that leaves generation unchanged, and the strict /
 * fail-on-warning failure of the Java flag CLI and the Java-host {@code generate --target rust}.
 * The native library and {@code unlaxer generate} read the same corpus in
 * {@code rust/unlaxer-generator/tests/generation_warnings.rs}.
 */
public class GenerationWarningsConformanceTest {
    private static final Path CORPUS = Path.of("..").toAbsolutePath().normalize().resolve("spec-corpus/generation-warnings/corpus.json");

    @Test public void libraryMatchesTheSharedCorpus() throws Exception {
        var rows = corpus();
        rows.addAll(JsonParser.parseString(Files.readString(CORPUS.resolveSibling("ordering.json"))).getAsJsonArray());
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            var out = new JsonArray();
            for (var warning : GenerationWarnings.collect(row.get("grammar").getAsString())) {
                var value = new JsonObject();
                value.addProperty("code", warning.code());
                value.addProperty("grammar", warning.grammar());
                value.addProperty("rule", warning.rule());
                var span = new JsonArray();
                span.add(warning.span().orElseThrow().start());
                span.add(warning.span().orElseThrow().end());
                value.add("span", span);
                value.addProperty("message", warning.message());
                value.addProperty("line", warning.line());
                assertEquals("warning", warning.severity());
                out.add(value);
            }
            assertEquals(row.get("name").getAsString(), row.get("warnings"), out);
        }
        assertEquals(List.of(), GenerationWarnings.collect("grammar G { @custom(x) R ::= 'x'; }"));
    }

    @Test public void javaHostRustCliPrintsTheLinesAndFailsWhenStrict() throws Exception {
        Path work = Files.createTempDirectory("generation-warnings-host");
        for (JsonElement element : corpus()) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString(), lines = lines(row);
            Path grammar = work.resolve(name + ".ubnf");
            Files.writeString(grammar, row.get("grammar").getAsString());
            Path output = work.resolve(name);
            var result = run("generate", "--target", "rust", "--grammar", grammar.toString(), "--output", output.toString());
            assertEquals(name + " " + result, CodegenMain.EXIT_OK, result.code());
            assertEquals(name, lines.isEmpty() ? "" : lines + "\n", result.err());
            assertTrue(name, Files.isDirectory(output));
            for (List<String> policy : List.of(List.of("--strict"), List.of("--fail-on", "warning"))) {
                Path strict = work.resolve(name + "-strict");
                var args = new ArrayList<>(List.of("generate", "--target", "rust", "--grammar", grammar.toString(),
                    "--output", strict.toString()));
                args.addAll(policy);
                var failed = run(args.toArray(String[]::new));
                if (lines.isEmpty()) {
                    assertEquals(name + " " + failed, CodegenMain.EXIT_OK, failed.code());
                    continue;
                }
                assertEquals(name + " " + failed, GenerationWarnings.STRICT_EXIT, failed.code());
                assertEquals(CodegenMain.EXIT_STRICT_VALIDATION_ERROR, GenerationWarnings.STRICT_EXIT);
                assertEquals(name, lines + "\nstrict: " + row.getAsJsonArray("warnings").size()
                    + " warning(s) fail generation\n", failed.err());
                assertFalse(name + " nothing written", Files.exists(strict));
            }
        }
        assertEquals(CodegenMain.EXIT_CLI_ERROR, run("generate", "--target", "rust", "--grammar", "g.ubnf",
            "--output", "out", "--fail-on", "skipped").code());
    }

    @Test public void javaFlagCliReportsEveryWarningAndFailsWhenStrict() throws Exception {
        Path work = Files.createTempDirectory("generation-warnings-flag");
        for (JsonElement element : corpus()) {
            JsonObject row = element.getAsJsonObject();
            String name = row.get("name").getAsString();
            Path grammar = work.resolve(name + ".ubnf");
            Files.writeString(grammar, row.get("grammar").getAsString());
            var plain = run("--grammar", grammar.toString(), "--validate-only");
            assertEquals(name + " " + plain, CodegenMain.EXIT_OK, plain.code());
            for (List<String> policy : List.of(List.of("--strict"), List.of("--fail-on", "warning"))) {
                Path output = work.resolve(name + "-" + policy.get(0).substring(2));
                var args = new ArrayList<>(List.of("--grammar", grammar.toString(), "--output", output.toString(),
                    "--generators", "Parser"));
                args.addAll(policy);
                var result = run(args.toArray(String[]::new));
                var warnings = row.getAsJsonArray("warnings");
                assertEquals(name + " " + result, warnings.isEmpty() ? CodegenMain.EXIT_OK
                    : CodegenMain.EXIT_STRICT_VALIDATION_ERROR, result.code());
                // The same `warning: CP s..e: <message>` line as the Rust CLIs, in each issue row.
                for (JsonElement warning : warnings) {
                    var value = warning.getAsJsonObject();
                    String row_ = " - grammar " + value.get("grammar").getAsString() + ": " + value.get("line").getAsString() + " [code: "
                        + value.get("code").getAsString() + "]";
                    assertTrue(name + " " + result, result.err().contains(row_));
                    assertTrue(name + " " + plain, plain.err().contains(row_));
                }
                assertEquals(name + " " + plain, warnings.size(), plain.err().lines().filter(l -> l.contains("warning: CP ")).count());
                assertEquals(name + " no duplicate warnings: " + result, warnings.size(),
                    result.err().lines().filter(l -> l.startsWith(" - grammar ") && l.contains("[code: W-")).count());
                if (!warnings.isEmpty()) assertFalse(name + " nothing written", Files.exists(output));
            }
        }
    }

    static String lines(JsonObject row) {
        var lines = new ArrayList<String>();
        for (JsonElement warning : row.getAsJsonArray("warnings")) lines.add(warning.getAsJsonObject().get("line").getAsString());
        return String.join("\n", lines);
    }

    private static JsonArray corpus() throws Exception {
        var rows = JsonParser.parseString(Files.readString(CORPUS)).getAsJsonArray();
        var ignored = JsonParser.parseString(Files.readString(
            CORPUS.getParent().getParent().resolve("global-comment/ignored.json"))).getAsJsonArray();
        for (JsonElement element : ignored) {
            var row = element.getAsJsonObject().deepCopy();
            row.addProperty("name", "global-comment-" + row.get("name").getAsString());
            row.add("warnings", row.get("generationWarnings"));
            rows.add(row);
        }
        return rows;
    }

    private record Run(int code, String out, String err) {}

    private static Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = CodegenMain.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
