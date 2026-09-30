package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.EvaluatorGenerator;
import org.unlaxer.dsl.impact.ApiImpact;

public class ApiImpactCommandTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private record Run(int code, String out, String err) {}

    @Test
    public void javaImpactWritesOneJsonReportAndNoGeneratedArtifactsOrProviderSideEffects() throws Exception {
        Path directory = temporary.getRoot().toPath();
        Path before = directory.resolve("before.ubnf");
        Path after = directory.resolve("after.ubnf");
        String base = """
            grammar Api {
              @package: example.api
              @tokenAdapter: { id: 'example.word' version: '1'
                java: 'org.unlaxer.dsl.ApiImpactCommandTest.ExplosiveProvider'
                rust: 'crate::word_token' }
              token WORD = ADAPTER('example.word', version=1)
              @root @mapping(Value, params=[value]) Root ::= WORD @value;
            }
            """;
        Files.writeString(before, base);
        Files.writeString(after, base.replace("params=[value]", "params=[value,extra]")
            .replace("WORD @value;", "WORD @value [ '!' @extra ];"));
        System.clearProperty("api.impact.provider.loaded");

        Run run = run("impact", "--target", "java", "--before", before.toString(),
            "--after", after.toString());
        assertEquals(run.err(), 0, run.code());
        assertEquals("", run.err());
        assertEquals(1, run.out().lines().count());
        var report = JsonParser.parseString(run.out()).getAsJsonObject();
        assertEquals(1, report.get("schemaVersion").getAsInt());
        assertEquals("ast-semantics", report.get("scope").getAsString());
        assertEquals("java", report.get("target").getAsString());
        assertTrue(report.get("ok").getAsBoolean());
        assertTrue(report.get("hasChanges").getAsBoolean());
        assertTrue(report.getAsJsonArray("changes").asList().stream().anyMatch(change ->
            change.getAsJsonObject().get("kind").getAsString().equals("FIELD_ADDED")));
        assertNull("schema extraction must not initialize the declared parser provider",
            System.getProperty("api.impact.provider.loaded"));
        assertEquals(base, Files.readString(before));
        try (var files = Files.list(directory)) {
            assertEquals(2, files.count());
        }
    }

    @Test
    public void invalidArgumentsAndIoKeepStdoutEmpty() throws Exception {
        Path grammar = temporary.newFile("valid.ubnf").toPath();
        Files.writeString(grammar, "grammar G { @root @mapping(Node) Root ::= 'x'; }");
        for (String[] args : List.of(
            new String[]{"impact"},
            new String[]{"impact", "--target", "java", "--before", "", "--after", grammar.toString()},
            new String[]{"impact", "--target", "java", "--before", grammar.toString(), "--after", grammar.toString(), "--output", "x"},
            new String[]{"impact", "--target", "java", "--target", "rust", "--before", grammar.toString(), "--after", grammar.toString()},
            new String[]{"impact", "--target", "java", "--before", grammar.toString(), "--after", grammar.toString(), "--format", "xml"},
            new String[]{"impact", "--help", "--target", "java"}
        )) {
            Run run = run(args);
            assertEquals(List.of(args).toString(), 2, run.code());
            assertEquals("", run.out());
            assertFalse(run.err().isEmpty());
        }
        Run missing = run("impact", "--target", "java", "--before", grammar.toString(),
            "--after", grammar.resolveSibling("missing.ubnf").toString());
        assertEquals(4, missing.code());
        assertEquals("", missing.out());
        assertFalse(missing.err().isEmpty());
        Run help = run("impact", "--help");
        assertEquals(0, help.code());
        assertTrue(help.out().startsWith("Usage: impact "));
        assertEquals("", help.err());
    }

    @Test
    public void schemaFailureIsJsonAndJava17DeclarationLocationMatchesGeneratedProfile() {
        String source = """
            grammar Profile {
              @root @mapping(Value, params=[value]) Root ::= 'x' @value;
            }
            """;
        var snapshot = UBNFMapper.parseWithSource(source);
        var schema = ApiImpact.schema("java", source);
        var method = schema.methods().stream().filter(candidate -> candidate.name().equals("evalValue"))
            .findFirst().orElseThrow();
        String generated = new EvaluatorGenerator(17).generate(snapshot.ast().grammars().get(0)).source();
        int utf16Start = generated.indexOf("protected abstract T evalValue");
        assertTrue(utf16Start >= 0);
        assertEquals(generated.codePointCount(0, utf16Start), method.generated().span().start());
        assertEquals("generated/ProfileEvaluator.java", method.generated().path());

        var failure = ApiImpact.compare("java", source, "grammar Broken { @root Root ::= Missing; }");
        assertFalse(failure.ok());
        assertEquals("after", failure.diagnostics().get(0).side());
        assertEquals("I-SCHEMA", failure.diagnostics().get(0).code());
        assertTrue(failure.changes().isEmpty());
    }

    /** Deliberately never initialized by schema extraction. */
    public static final class ExplosiveProvider {
        static { System.setProperty("api.impact.provider.loaded", "true"); }
    }

    private static Run run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = ApiImpactCommand.run(args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
