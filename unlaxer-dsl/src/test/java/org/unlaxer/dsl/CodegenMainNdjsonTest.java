package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.unlaxer.dsl.CodegenTestHelper.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import org.unlaxer.dsl.CodegenTestHelper.RunResult;

public class CodegenMainNdjsonTest {

    @Test
    public void testNdjsonValidateOnlyOutput() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-validate", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String out = result.out().trim();
        assertTrue(out.startsWith("{\"event\":\"validate-success\",\"payload\":{"));
        assertTrue(out.contains("\"mode\":\"validate\""));
        assertTrue(out.contains("\"warningsCount\":0"));
    }

    @Test
    public void testNdjsonWarningsEventUsesJsonOnlyStderr() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-warnings", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.WARN_ONLY_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--fail-on", "none",
            "--report-format", "ndjson"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String err = result.err().trim();
        assertTrue(err.contains("\"event\":\"warnings\""));
        for (String line : err.split("\\R")) {
            String trimmed = line.trim();
            assertTrue("ndjson stderr line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }

        String out = result.out().trim();
        assertTrue(out.contains("\"event\":\"validate-success\""));
        for (String line : out.split("\\R")) {
            String trimmed = line.trim();
            assertTrue("ndjson stdout line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
    }

    @Test
    public void testNdjsonValidateFailureStderrIsJsonOnly() throws Exception {
        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-validate-failure", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        String err = result.err().trim();
        assertTrue(err.contains("\"event\":\"validate-failure\""));
        for (String line : err.split("\\R")) {
            String trimmed = line.trim();
            assertTrue("ndjson stderr line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
    }

    @Test
    public void testNdjsonStrictFailureStderrIsJsonOnly() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-strict-failure", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.WARN_ONLY_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--fail-on", "warning",
            "--report-format", "ndjson"
        );

        assertEquals(CodegenMain.EXIT_STRICT_VALIDATION_ERROR, result.exitCode());
        String err = result.err().trim();
        assertTrue(err.contains("\"event\":\"strict-failure\""));
        for (String line : err.split("\\R")) {
            String trimmed = line.trim();
            assertTrue("ndjson stderr line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
    }

    @Test
    public void testNdjsonGenerationOutputIncludesFileEventsAndSummary() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-generate", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-ndjson-generate-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String out = result.out();
        assertTrue(out.contains("\"event\":\"file\""));
        assertTrue(out.contains("\"action\":\"written\""));
        assertTrue(out.contains("\"event\":\"generate-summary\""));
        assertTrue(out.contains("\"writtenCount\":1"));
        for (String line : out.trim().split("\\R")) {
            String trimmed = line.trim();
            assertTrue("ndjson stdout line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
    }

    @Test
    public void testNdjsonGenerationIncludesCleanedEvent() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-cleaned", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-ndjson-cleaned-out");
        Files.writeString(grammarFile, source);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// stale");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--clean-output",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("\"action\":\"cleaned\""));
    }

    @Test
    public void testNdjsonGenerationSkipPathKeepsStdoutJsonOnly() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-skip-json-only", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-ndjson-skip-json-only-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult first = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST"
        );
        assertEquals(CodegenMain.EXIT_OK, first.exitCode());

        RunResult second = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--overwrite", "if-different",
            "--report-format", "ndjson",
            "--fail-on", "none"
        );
        assertEquals(CodegenMain.EXIT_OK, second.exitCode());
        assertFalse(second.out().contains("Skipped (unchanged):"));
        assertTrue(second.out().contains("\"action\":\"skipped\""));
        for (String line : second.out().trim().split("\\R")) {
            String trimmed = line.trim();
            assertTrue("ndjson stdout line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
    }

    @Test
    public void testNdjsonGenerationDryRunPathKeepsStdoutJsonOnly() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-dryrun-json-only", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-ndjson-dryrun-json-only-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--dry-run",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertFalse(result.out().contains("Dry-run: would generate"));
        assertTrue(result.out().contains("\"action\":\"dry-run\""));
        for (String line : result.out().trim().split("\\R")) {
            String trimmed = line.trim();
            assertTrue("ndjson stdout line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
    }

    @Test
    public void testNdjsonConflictFailureDoesNotEmitHumanErrorText() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-conflict-failure", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-ndjson-conflict-failure-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// existing");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--overwrite", "never",
            "--fail-on", "conflict",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertFalse(result.err().contains("Conflict (not overwritten):"));
        assertFalse(result.err().contains("Fail-on policy triggered:"));
        assertTrue(result.err().isBlank());

        List<String> outLines = List.of(result.out().trim().split("\\R"));
        for (String line : outLines) {
            String trimmed = line.trim();
            assertTrue("ndjson stdout line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
        assertTrue(result.out().contains("\"event\":\"file\""));
        assertTrue(result.out().contains("\"action\":\"conflict\""));
        assertTrue(result.out().contains("\"event\":\"generate-summary\""));
        assertTrue(result.out().contains("\"failReasonCode\":\"FAIL_ON_CONFLICT\""));
    }

    @Test
    public void testNdjsonCleanedFailureEmitsOnlyJsonEventsAndEmptyStderr() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-cleaned-failure", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-ndjson-cleaned-failure-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// stale");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--clean-output",
            "--fail-on", "cleaned",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertTrue(result.err().isBlank());

        List<String> outLines = List.of(result.out().trim().split("\\R"));
        for (String line : outLines) {
            String trimmed = line.trim();
            assertTrue("ndjson stdout line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
        assertTrue(result.out().contains("\"event\":\"file\""));
        assertTrue(result.out().contains("\"action\":\"cleaned\""));
        assertTrue(result.out().contains("\"event\":\"generate-summary\""));
        assertTrue(result.out().contains("\"failReasonCode\":\"FAIL_ON_CLEANED\""));
    }

    @Test
    public void testNdjsonSkippedFailureEmitsOnlyJsonEventsAndEmptyStderr() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-skipped-failure", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-ndjson-skipped-failure-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult first = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST"
        );
        assertEquals(CodegenMain.EXIT_OK, first.exitCode());

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--overwrite", "if-different",
            "--fail-on", "skipped",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertTrue(result.err().isBlank());

        List<String> outLines = List.of(result.out().trim().split("\\R"));
        for (String line : outLines) {
            String trimmed = line.trim();
            assertTrue("ndjson stdout line must be JSON: " + trimmed, trimmed.startsWith("{") && trimmed.endsWith("}"));
        }
        assertTrue(result.out().contains("\"event\":\"file\""));
        assertTrue(result.out().contains("\"action\":\"skipped\""));
        assertTrue(result.out().contains("\"event\":\"generate-summary\""));
        assertTrue(result.out().contains("\"failReasonCode\":\"FAIL_ON_SKIPPED\""));
    }

    @Test
    public void testUnknownGeneratorNdjsonEmitsCliErrorEventOnStdout() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-unknown-gen-ndjson", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-unknown-gen-ndjson-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "Nope",
            "--report-format", "ndjson"
        );

        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.out()));
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
        assertEquals("E-CLI-UNKNOWN-GENERATOR", JsonTestUtil.getString(event, "code"));
        assertEquals(null, event.get("detail"));
        List<Object> generators = JsonTestUtil.getArray(event, "availableGenerators");
        assertEquals(List.of("AST", "DAP", "DAPLauncher", "Evaluator", "LSP", "Launcher", "Mapper", "Parser"), generators);
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testBrokenGrammarReturnsNdjsonCliErrorEventWhenRequested() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-broken-grammar-ndjson", ".ubnf");
        Files.writeString(grammarFile, "grammar Broken {");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson"
        );

        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertTrue(result.out().contains("\"event\":\"cli-error\""));
        assertTrue(result.out().contains("\"code\":\"E-RUNTIME\""));
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testReportFileWriteFailureReturnsNdjsonCliErrorEvent() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-report-write-failure-ndjson", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);
        Path blocker = Files.createTempFile("codegen-main-report-blocker-ndjson", ".tmp");
        Path reportFile = blocker.resolve("report.json");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson",
            "--report-file", reportFile.toString()
        );

        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.out()));
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
        assertEquals("E-IO", JsonTestUtil.getString(event, "code"));
        assertFalse(JsonTestUtil.getString(event, "message").isBlank());
        assertEquals(null, event.get("detail"));
        assertEquals(List.of(), JsonTestUtil.getArray(event, "availableGenerators"));
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testUnknownOptionReturnsNdjsonCliUsageErrorEvent() {
        RunResult result = runCodegen("--unknown-option", "--report-format", "ndjson");
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.out()));
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
        assertEquals("E-CLI-USAGE", JsonTestUtil.getString(event, "code"));
        assertFalse(JsonTestUtil.getString(event, "message").isBlank());
        assertTrue(event.get("detail") == null || "Use --help to view usage.".equals(event.get("detail")));
        assertEquals(List.of(), JsonTestUtil.getArray(event, "availableGenerators"));
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testMissingGrammarReturnsNdjsonCliErrorEventWhenRequested() {
        RunResult result = runCodegen("--validate-only", "--report-format", "ndjson");
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        Map<String, Object> event = JsonTestUtil.parseObject(result.out().trim());
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
        assertEquals("E-CLI-USAGE", JsonTestUtil.getString(event, "code"));
        assertFalse(JsonTestUtil.getString(event, "message").isBlank());
        assertTrue(event.get("detail") == null || "Use --help to view usage.".equals(event.get("detail")));
        assertEquals(List.of(), JsonTestUtil.getArray(event, "availableGenerators"));
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testUnsupportedReportVersionReturnsNdjsonCliErrorEvent() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-report-version-invalid-ndjson", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson",
            "--report-version", "2"
        );

        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        Map<String, Object> event = JsonTestUtil.parseObject(result.out().trim());
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
        assertEquals("E-CLI-USAGE", JsonTestUtil.getString(event, "code"));
        assertTrue(JsonTestUtil.getString(event, "message").contains("Unsupported --report-version"));
        assertTrue(event.get("detail") == null || "Use --help to view usage.".equals(event.get("detail")));
        assertEquals(List.of(), JsonTestUtil.getArray(event, "availableGenerators"));
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testCleanOutputRejectsUnsafeRootPathInNdjson() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-clean-unsafe-ndjson", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", "/",
            "--generators", "AST",
            "--clean-output",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        Map<String, Object> event = JsonTestUtil.parseObject(result.out().trim());
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
        assertEquals("E-CLI-UNSAFE-CLEAN-OUTPUT", JsonTestUtil.getString(event, "code"));
        assertEquals("/", JsonTestUtil.getString(event, "detail"));
        assertEquals(List.of(), JsonTestUtil.getArray(event, "availableGenerators"));
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testNdjsonValidateFailureReportFileStoresRawJsonPayload() throws Exception {
        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-validate-failure-report-file", ".ubnf");
        Path reportFile = Files.createTempFile("codegen-main-ndjson-validate-failure-report-file", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson",
            "--report-file", reportFile.toString()
        );
        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        String stderr = result.err().trim();
        assertTrue(stderr.startsWith("{\"event\":\"validate-failure\",\"payload\":{"));

        String saved = Files.readString(reportFile).trim();
        assertTrue(saved.startsWith("{\"reportVersion\":1,"));
        assertFalse(saved.contains("\"event\":\"validate-failure\""));
        assertTrue(saved.contains("\"mode\":\"validate\""));
        assertTrue(saved.contains("\"ok\":false"));
    }

    @Test
    public void testNdjsonValidateSuccessReportFileStoresRawJsonPayload() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-validate-success-report-file", ".ubnf");
        Path reportFile = Files.createTempFile("codegen-main-ndjson-validate-success-report-file", ".json");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson",
            "--report-file", reportFile.toString()
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String stdout = result.out().trim();
        assertTrue(stdout.startsWith("{\"event\":\"validate-success\",\"payload\":{"));

        String saved = Files.readString(reportFile).trim();
        assertTrue(saved.startsWith("{\"reportVersion\":1,"));
        assertFalse(saved.contains("\"event\":\"validate-success\""));
        assertTrue(saved.contains("\"mode\":\"validate\""));
        assertTrue(saved.contains("\"ok\":true"));
    }

    @Test
    public void testNdjsonGenerateFailureReportFileStoresRawJsonPayload() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-generate-failure-report-file", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-ndjson-generate-failure-report-file-out");
        Path reportFile = Files.createTempFile("codegen-main-ndjson-generate-failure-report-file", ".json");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// existing");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--overwrite", "never",
            "--fail-on", "conflict",
            "--report-format", "ndjson",
            "--report-file", reportFile.toString()
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertTrue(result.out().contains("\"event\":\"generate-summary\""));

        String saved = Files.readString(reportFile).trim();
        assertTrue(saved.startsWith("{\"reportVersion\":1,"));
        assertFalse(saved.contains("\"event\":\"generate-summary\""));
        assertTrue(saved.contains("\"mode\":\"generate\""));
        assertTrue(saved.contains("\"ok\":false"));
        assertTrue(saved.contains("\"failReasonCode\":\"FAIL_ON_CONFLICT\""));
    }

    @Test
    public void testNdjsonGenerateSuccessReportFileStoresRawJsonPayload() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-generate-success-report-file", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-ndjson-generate-success-report-file-out");
        Path reportFile = Files.createTempFile("codegen-main-ndjson-generate-success-report-file", ".json");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "ndjson",
            "--report-file", reportFile.toString()
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("\"event\":\"generate-summary\""));

        String saved = Files.readString(reportFile).trim();
        assertTrue(saved.startsWith("{\"reportVersion\":1,"));
        assertFalse(saved.contains("\"event\":\"generate-summary\""));
        assertTrue(saved.contains("\"mode\":\"generate\""));
        assertTrue(saved.contains("\"ok\":true"));
        assertTrue(saved.contains("\"failReasonCode\":null"));
    }

    @Test
    public void testNdjsonWarningsPathReportFileStoresFinalSuccessPayload() throws Exception {
        Path grammarFile = Files.createTempFile("codegen-main-ndjson-warnings-report-file", ".ubnf");
        Path reportFile = Files.createTempFile("codegen-main-ndjson-warnings-report-file", ".json");
        Files.writeString(grammarFile, CliFixtureData.WARN_ONLY_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--fail-on", "none",
            "--report-format", "ndjson",
            "--report-file", reportFile.toString()
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.err().contains("\"event\":\"warnings\""));
        assertTrue(result.out().contains("\"event\":\"validate-success\""));

        String saved = Files.readString(reportFile).trim();
        assertTrue(saved.startsWith("{\"reportVersion\":1,"));
        assertFalse(saved.contains("\"event\":\"warnings\""));
        assertFalse(saved.contains("\"event\":\"validate-success\""));
        assertTrue(saved.contains("\"mode\":\"validate\""));
        assertTrue(saved.contains("\"ok\":true"));
        assertTrue(saved.contains("\"warningsCount\":1"));
    }
}
