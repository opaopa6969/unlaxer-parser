package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.unlaxer.dsl.CodegenTestHelper.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.Test;

import org.unlaxer.dsl.CodegenTestHelper.RunResult;

public class CodegenMainReportTest {

    @Test
    public void testReportFileWriteFailureReturnsGenerationError() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-report-write-failure", ".ubnf");
        Files.writeString(grammarFile, source);
        Path blocker = Files.createTempFile("codegen-main-report-blocker", ".tmp");
        Path reportFile = blocker.resolve("report.json");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json",
            "--report-file", reportFile.toString()
        );

        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("I/O error:"));
    }

    @Test
    public void testReportSchemaCheckOptionWithValidationFailureJson() throws Exception {
        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-schema-check-invalid", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json",
            "--report-schema-check"
        );
        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        String payload = result.err().trim();
        assertTrue(payload.startsWith("{\"reportVersion\":1,"));
        assertTrue(payload.contains("\"mode\":\"validate\""));
        assertTrue(payload.contains("\"ok\":false"));
    }

    @Test
    public void testReportSchemaCheckOptionWithGenerationJson() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-schema-check-generate", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-schema-check-generate-out");
        Path reportFile = Files.createTempFile("codegen-main-schema-check-generate-report", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json",
            "--report-file", reportFile.toString(),
            "--report-schema-check"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("\"mode\":\"generate\""));
        assertTrue(Files.readString(reportFile).contains("\"mode\":\"generate\""));
    }

    @Test
    public void testOutputManifestIsWritten() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-manifest", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-manifest-out");
        Path manifest = Files.createTempFile("codegen-main-manifest", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--output-manifest", manifest.toString()
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String payload = Files.readString(manifest);
        assertTrue(payload.contains("\"mode\":\"generate\""));
        assertTrue(payload.contains("\"writtenCount\":1"));
        assertTrue(payload.contains("\"files\":["));
    }

    @Test
    public void testOutputManifestNdjsonIsWritten() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-manifest-ndjson", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-manifest-ndjson-out");
        Path manifest = Files.createTempFile("codegen-main-manifest-ndjson", ".ndjson");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--output-manifest", manifest.toString(),
            "--manifest-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String payload = Files.readString(manifest);
        assertTrue(payload.contains("\"event\":\"file\""));
        assertTrue(payload.contains("\"event\":\"manifest-summary\""));
        assertTrue(payload.contains("\"failReasonCode\":null"));
    }

    @Test
    public void testManifestSchemaCheckValidationRunsForNdjson() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-manifest-schema-check", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-manifest-schema-check-out");
        Path manifest = Files.createTempFile("codegen-main-manifest-schema-check", ".ndjson");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--output-manifest", manifest.toString(),
            "--manifest-format", "ndjson",
            "--report-schema-check"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(Files.readString(manifest).contains("\"manifest-summary\""));
    }

    @Test
    public void testArgsHashIgnoresReportAndManifestDestinationPaths() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-argshash", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-argshash-out");
        Path report1 = Files.createTempFile("codegen-main-argshash-1", ".json");
        Path report2 = Files.createTempFile("codegen-main-argshash-2", ".json");
        Path manifest1 = Files.createTempFile("codegen-main-argshash-1", ".manifest.json");
        Path manifest2 = Files.createTempFile("codegen-main-argshash-2", ".manifest.json");
        Files.writeString(grammarFile, source);

        RunResult first = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json",
            "--report-file", report1.toString(),
            "--output-manifest", manifest1.toString()
        );
        RunResult second = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json",
            "--report-file", report2.toString(),
            "--output-manifest", manifest2.toString()
        );

        assertEquals(CodegenMain.EXIT_OK, first.exitCode());
        assertEquals(CodegenMain.EXIT_OK, second.exitCode());
        String hash1 = extractJsonStringField(first.out(), "argsHash");
        String hash2 = extractJsonStringField(second.out(), "argsHash");
        assertEquals(hash1, hash2);
    }

    @Test
    public void testArgsHashChangesWhenFailOnPolicyChanges() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-argshash-failon", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-argshash-failon-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult conflict = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json",
            "--fail-on", "conflict"
        );
        RunResult skipped = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json",
            "--fail-on", "skipped"
        );

        assertEquals(CodegenMain.EXIT_OK, conflict.exitCode());
        assertEquals(CodegenMain.EXIT_OK, skipped.exitCode());
        String hash1 = extractJsonStringField(conflict.out(), "argsHash");
        String hash2 = extractJsonStringField(skipped.out(), "argsHash");
        assertFalse(hash1.equals(hash2));
    }

    @Test
    public void testArgsHashChangesWhenManifestFormatChanges() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-argshash-manifest-format", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-argshash-manifest-format-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult jsonManifest = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json",
            "--manifest-format", "json"
        );
        RunResult ndjsonManifest = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json",
            "--manifest-format", "ndjson"
        );

        assertEquals(CodegenMain.EXIT_OK, jsonManifest.exitCode());
        assertEquals(CodegenMain.EXIT_OK, ndjsonManifest.exitCode());
        String hash1 = extractJsonStringField(jsonManifest.out(), "argsHash");
        String hash2 = extractJsonStringField(ndjsonManifest.out(), "argsHash");
        assertFalse(hash1.equals(hash2));
    }

    @Test
    public void testArgsHashIgnoresHelpAndVersionFlagsInSemanticConfig() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-argshash-help-version", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-argshash-help-version-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        var base = CodegenCliParser.parse(new String[] {
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json"
        });
        var withHelpVersion = CodegenCliParser.parse(new String[] {
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json",
            "--help",
            "--version"
        });

        String hashBase = ArgsHashUtil.fromOptions(base);
        String hashWithFlags = ArgsHashUtil.fromOptions(withHelpVersion);
        assertEquals(hashBase, hashWithFlags);
    }

    @Test
    public void testGeneratedAtUsesProvidedClock() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-clock", ".ubnf");
        Files.writeString(grammarFile, source);

        Clock fixedClock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
        RunResult result = runCodegenWithClock(
            fixedClock,
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("\"generatedAt\":\"2026-01-02T03:04:05Z\""));
    }
}
