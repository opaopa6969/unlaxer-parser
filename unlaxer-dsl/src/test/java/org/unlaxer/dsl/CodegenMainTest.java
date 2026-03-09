package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

public class CodegenMainTest {

    @Test
    public void testGeneratesAllGrammarBlocks() throws Exception {
        String source = """
            grammar First {
              @package: org.example.first
              @root
              @mapping(RootNode, params=[value])
              First ::= 'a' @value ;
            }

            grammar Second {
              @package: org.example.second
              @root
              @mapping(RootNode, params=[value])
              Second ::= 'b' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.err().isEmpty());

        Path firstAst = outputDir.resolve("org/example/first/FirstAST.java");
        Path secondAst = outputDir.resolve("org/example/second/SecondAST.java");

        assertTrue(Files.exists(firstAst));
        assertTrue(Files.exists(secondAst));
    }

    @Test
    public void testFailsOnInvalidMappingContract() throws Exception {
        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-invalid", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-invalid-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("has no matching capture"));
    }

    @Test
    public void testFailsOnNonCanonicalRightAssoc() throws Exception {
        String source = """
            grammar InvalidRightAssoc {
              @package: org.example.invalid
              @root
              @mapping(PowNode, params=[left, op, right])
              @rightAssoc
              @precedence(level=30)
              Expr ::= Atom @left { '^' @op Atom @right } ;
              Atom ::= 'n' ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-invalid-rightassoc", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-invalid-rightassoc-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "Parser"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("body is not canonical"));
    }

    @Test
    public void testAggregatesValidationErrorsAcrossGrammarBlocks() throws Exception {
        String source = """
            grammar InvalidA {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              A ::= 'x' @value ;
            }

            grammar InvalidB {
              @package: org.example.invalid
              @root
              @mapping(PowNode, params=[left, op, right])
              @rightAssoc
              @precedence(level=30)
              Expr ::= Atom @left { '^' @op Atom @right } ;
              Atom ::= 'n' ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-invalid-multi", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-invalid-multi-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "Parser"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("grammar InvalidA:"));
        assertTrue(result.err().contains("grammar InvalidB:"));
        assertTrue(result.err().contains("[code:"));
    }

    @Test
    public void testValidateOnlySkipsGeneration() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-validate-only", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-validate-only-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());

        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        assertTrue(!Files.exists(ast));
    }

    @Test
    public void testValidateOnlyStillFailsOnInvalidGrammar() throws Exception {
        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-validate-only-invalid", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("Grammar validation failed"));
        assertTrue(result.err().contains("E-MAPPING-MISSING-CAPTURE"));
    }

    @Test
    public void testValidateOnlyJsonSuccessReport() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-validate-only-json", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String out = result.out().trim();
        assertTrue(out.startsWith("{\"reportVersion\":1,"));
        assertTrue(out.contains("\"schemaVersion\":\"1.0\""));
        assertTrue(out.contains("\"schemaUrl\":\"https://unlaxer.dev/schema/report-v1.json\""));
        assertTrue(out.contains("\"toolVersion\":\""));
        assertTrue(out.contains("\"argsHash\":\""));
        assertTrue(out.contains("\"generatedAt\":\""));
        assertHasNonEmptyJsonField(out, "toolVersion");
        assertGeneratedAtIsIsoInstant(out);
        assertTrue(out.contains("\"mode\":\"validate\""));
        assertTrue(out.contains("\"ok\":true"));
        assertTrue(out.contains("\"grammarCount\":1"));
        assertTrue(out.contains("\"warningsCount\":0"));
        assertTrue(out.endsWith("\"issues\":[]}"));

        Map<String, Object> obj = JsonTestUtil.parseObject(out);
        assertEquals(1L, JsonTestUtil.getLong(obj, "reportVersion"));
        assertEquals("1.0", JsonTestUtil.getString(obj, "schemaVersion"));
        assertEquals("https://unlaxer.dev/schema/report-v1.json", JsonTestUtil.getString(obj, "schemaUrl"));
        assertHasNonEmptyJsonField(out, "argsHash");
        assertEquals("validate", JsonTestUtil.getString(obj, "mode"));
        assertTrue(JsonTestUtil.getBoolean(obj, "ok"));
        assertEquals(1L, JsonTestUtil.getLong(obj, "grammarCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "warningsCount"));
        assertEquals(List.of(), JsonTestUtil.getArray(obj, "issues"));
    }

    @Test
    public void testValidateOnlyJsonFailureReport() throws Exception {
        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-validate-only-json-invalid", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        String msg = result.err().trim();
        assertTrue(msg.startsWith("{\"reportVersion\":1,"));
        assertTrue(msg.contains("\"schemaVersion\":\"1.0\""));
        assertTrue(msg.contains("\"schemaUrl\":\"https://unlaxer.dev/schema/report-v1.json\""));
        assertTrue(msg.contains("\"toolVersion\":\""));
        assertTrue(msg.contains("\"argsHash\":\""));
        assertTrue(msg.contains("\"generatedAt\":\""));
        assertHasNonEmptyJsonField(msg, "toolVersion");
        assertGeneratedAtIsIsoInstant(msg);
        assertTrue(msg.contains("\"mode\":\"validate\""));
        assertTrue(msg.contains("\"ok\":false"));
        assertTrue(msg.contains("\"warningsCount\":0"));
        assertTrue(msg.contains("\"severityCounts\":{\"ERROR\":1}"));
        assertTrue(msg.contains("\"categoryCounts\":{\"MAPPING\":1}"));
        assertTrue(msg.contains("\"grammar\":\"Invalid\""));
        assertTrue(msg.contains("\"rule\":\"Invalid\""));
        assertTrue(msg.contains("\"code\":\"E-MAPPING-MISSING-CAPTURE\""));
        assertTrue(msg.contains("\"severity\":\"ERROR\""));
        assertTrue(msg.contains("\"category\":\"MAPPING\""));
        assertTrue(msg.contains("\"issues\":["));

        Map<String, Object> obj = JsonTestUtil.parseObject(msg);
        assertEquals(1L, JsonTestUtil.getLong(obj, "reportVersion"));
        assertEquals("1.0", JsonTestUtil.getString(obj, "schemaVersion"));
        assertEquals("https://unlaxer.dev/schema/report-v1.json", JsonTestUtil.getString(obj, "schemaUrl"));
        assertHasNonEmptyJsonField(msg, "argsHash");
        assertEquals("validate", JsonTestUtil.getString(obj, "mode"));
        assertFalse(JsonTestUtil.getBoolean(obj, "ok"));
        assertEquals(1L, JsonTestUtil.getLong(obj, "issueCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "warningsCount"));
        Map<String, Object> severityCounts = JsonTestUtil.getObject(obj, "severityCounts");
        assertEquals(1L, JsonTestUtil.getLong(severityCounts, "ERROR"));
        List<Object> issues = JsonTestUtil.getArray(obj, "issues");
        assertEquals(1, issues.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> issue = (Map<String, Object>) issues.get(0);
        assertEquals("E-MAPPING-MISSING-CAPTURE", JsonTestUtil.getString(issue, "code"));
        assertEquals("ERROR", JsonTestUtil.getString(issue, "severity"));
        assertEquals("MAPPING", JsonTestUtil.getString(issue, "category"));
    }

    @Test
    public void testValidateOnlyJsonFailureReportIsSortedByGrammar() throws Exception {
        String source = """
            grammar InvalidB {
              @package: org.example.invalid
              @root
              @mapping(PowNode, params=[left, op, right])
              @rightAssoc
              @precedence(level=30)
              Expr ::= Atom @left { '^' @op Atom @right } ;
              Atom ::= 'n' ;
            }

            grammar InvalidA {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-validate-only-json-invalid-sort", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        String msg = result.err();
        int idxA = msg.indexOf("\"grammar\":\"InvalidA\"");
        int idxB = msg.indexOf("\"grammar\":\"InvalidB\"");
        assertTrue(idxA >= 0);
        assertTrue(idxB >= 0);
        assertTrue(idxA < idxB);
    }

    @Test
    public void testValidateOnlyJsonFailureReportIncludesAggregateCounts() throws Exception {
        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              @whitespace(custom)
              Invalid ::= 'x' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-validate-only-json-invalid-counts", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        String msg = result.err();
        assertTrue(msg.contains("\"issueCount\":2"));
        assertTrue(msg.contains("\"severityCounts\":{\"ERROR\":2}"));
        assertTrue(msg.contains("\"categoryCounts\":{\"MAPPING\":1,\"WHITESPACE\":1}"));
    }

    @Test
    public void testValidateOnlyJsonWritesReportFile() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-validate-only-json-file", ".ubnf");
        Path reportFile = Files.createTempFile("codegen-main-report", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json",
            "--report-file", reportFile.toString()
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String report = Files.readString(reportFile).trim();
        assertTrue(report.startsWith("{\"reportVersion\":1,"));
        assertTrue(report.contains("\"schemaVersion\":\"1.0\""));
        assertTrue(report.contains("\"schemaUrl\":\"https://unlaxer.dev/schema/report-v1.json\""));
        assertTrue(report.contains("\"toolVersion\":\""));
        assertTrue(report.contains("\"argsHash\":\""));
        assertTrue(report.contains("\"generatedAt\":\""));
        assertHasNonEmptyJsonField(report, "toolVersion");
        assertGeneratedAtIsIsoInstant(report);
        assertTrue(report.contains("\"mode\":\"validate\""));
        assertTrue(report.contains("\"ok\":true"));
        assertTrue(report.contains("\"grammarCount\":1"));
        assertTrue(report.contains("\"warningsCount\":0"));
        assertTrue(report.endsWith("\"issues\":[]}"));
    }

    @Test
    public void testGenerationJsonReportIncludesGeneratedFiles() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-generate-json", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-generate-json-out");
        Path reportFile = Files.createTempFile("codegen-main-generate-report", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json",
            "--report-file", reportFile.toString()
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String report = Files.readString(reportFile);
        assertTrue(report.contains("\"ok\":true"));
        assertTrue(report.contains("\"reportVersion\":1"));
        assertTrue(report.contains("\"schemaVersion\":\"1.0\""));
        assertTrue(report.contains("\"schemaUrl\":\"https://unlaxer.dev/schema/report-v1.json\""));
        assertTrue(report.contains("\"toolVersion\":\""));
        assertTrue(report.contains("\"argsHash\":\""));
        assertTrue(report.contains("\"generatedAt\":\""));
        assertHasNonEmptyJsonField(report, "toolVersion");
        assertGeneratedAtIsIsoInstant(report);
        assertTrue(report.contains("\"mode\":\"generate\""));
        assertTrue(report.contains("\"generatedCount\":1"));
        assertTrue(report.contains("\"warningsCount\":0"));
        assertTrue(report.contains("\"writtenCount\":1"));
        assertTrue(report.contains("\"skippedCount\":0"));
        assertTrue(report.contains("\"conflictCount\":0"));
        assertTrue(report.contains("\"dryRunCount\":0"));
        assertTrue(report.contains("\"generatedFiles\":["));
        assertTrue(report.contains("ValidAST.java"));

        Map<String, Object> obj = JsonTestUtil.parseObject(report);
        assertEquals(1L, JsonTestUtil.getLong(obj, "reportVersion"));
        assertEquals("1.0", JsonTestUtil.getString(obj, "schemaVersion"));
        assertEquals("https://unlaxer.dev/schema/report-v1.json", JsonTestUtil.getString(obj, "schemaUrl"));
        assertHasNonEmptyJsonField(report, "argsHash");
        assertEquals("generate", JsonTestUtil.getString(obj, "mode"));
        assertTrue(JsonTestUtil.getBoolean(obj, "ok"));
        assertEquals(1L, JsonTestUtil.getLong(obj, "generatedCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "warningsCount"));
        assertEquals(1L, JsonTestUtil.getLong(obj, "writtenCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "skippedCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "conflictCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "dryRunCount"));
        List<Object> files = JsonTestUtil.getArray(obj, "generatedFiles");
        assertEquals(1, files.size());
    }

    @Test
    public void testUnknownGeneratorReturnsCliErrorCode() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-unknown-gen", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-unknown-gen-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "Nope"
        );

        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertTrue(result.err().contains("Unknown generator"));
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
    public void testEmptyGeneratorsValueReturnsCliErrorCode() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-empty-gens", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-empty-gens-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", " ,  , "
        );

        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertTrue(result.err().contains("No generators specified"));
    }

    @Test
    public void testHelpReturnsOkAndPrintsUsage() {
        RunResult result = runCodegen("--help");
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("Usage: CodegenMain"));
        assertTrue(result.out().contains("--help"));
        assertTrue(result.out().contains("--version"));
        assertTrue(result.out().contains("--strict"));
        assertTrue(result.out().contains("--dry-run"));
        assertTrue(result.out().contains("--clean-output"));
        assertTrue(result.out().contains("--overwrite"));
        assertTrue(result.out().contains("--fail-on"));
        assertTrue(result.out().contains("--output-manifest"));
        assertTrue(result.out().contains("--manifest-format"));
        assertTrue(result.out().contains("--validate-parser-ir"));
        assertTrue(result.out().contains("--export-parser-ir"));
        assertTrue(result.out().contains("text|json|ndjson"));
        assertTrue(result.out().contains("--warnings-as-json"));
    }

    @Test
    public void testVersionReturnsOkAndPrintsVersion() {
        RunResult result = runCodegen("--version");
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertFalse(result.out().isBlank());
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testBrokenGrammarReturnsGenerationError() throws Exception {
        String source = "grammar Broken {";
        Path grammarFile = Files.createTempFile("codegen-main-broken-grammar", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only"
        );

        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("Generation failed:"));
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
    public void testUnknownGeneratorWithReportOptionsReturnsCliErrorAndNoReport() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-unknown-gen-report", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-unknown-gen-report-out");
        Path reportFile = outputDir.resolve("report.json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "Nope",
            "--report-format", "json",
            "--report-file", reportFile.toString(),
            "--report-schema-check"
        );

        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertTrue(result.err().contains("Unknown generator"));
        assertFalse(Files.exists(reportFile));
    }

    @Test
    public void testMissingGrammarReturnsCliErrorCode() {
        RunResult result = runCodegen("--validate-only");
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertTrue(result.err().contains("Usage: CodegenMain"));
        assertTrue(result.err().contains("--validate-parser-ir"));
        assertTrue(result.err().contains("--export-parser-ir"));
        assertTrue(result.err().contains("--report-version 1"));
        assertTrue(result.err().contains("--strict"));
        assertTrue(result.err().contains("--dry-run"));
        assertTrue(result.err().contains("--clean-output"));
        assertTrue(result.err().contains("--overwrite"));
        assertTrue(result.err().contains("--fail-on"));
        assertTrue(result.err().contains("--output-manifest"));
        assertTrue(result.err().contains("--manifest-format"));
        assertTrue(result.err().contains("--report-schema-check"));
        assertTrue(result.err().contains("--warnings-as-json"));
    }

    @Test
    public void testExportParserIrWritesFile() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              @interleave(profile=javaStyle)
              @scopeTree(mode=lexical)
              Start ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-export-parser-ir", ".ubnf");
        Path exportFile = Files.createTempFile("codegen-main-export-parser-ir-out", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--export-parser-ir", exportFile.toString()
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("Parser IR export succeeded"));
        String payload = Files.readString(exportFile);
        Map<String, Object> obj = JsonTestUtil.parseObject(payload);
        assertEquals("1.0", JsonTestUtil.getString(obj, "irVersion"));
        assertEquals(grammarFile.toString(), JsonTestUtil.getString(obj, "source"));
        assertTrue(!JsonTestUtil.getArray(obj, "nodes").isEmpty());
        assertTrue(!JsonTestUtil.getArray(obj, "annotations").isEmpty());
    }

    @Test
    public void testExportParserIrSupportsMultipleGrammarBlocks() throws Exception {
        String source = """
            grammar A {
              @package: org.example.a
              @root
              @mapping(NodeA, params=[v])
              Start ::= 'a' @v ;
            }
            grammar B {
              @package: org.example.b
              @root
              @mapping(NodeB, params=[v])
              Start ::= 'b' @v ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-export-parser-ir-multi", ".ubnf");
        Path exportFile = Files.createTempFile("codegen-main-export-parser-ir-multi-out", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--export-parser-ir", exportFile.toString()
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String payload = Files.readString(exportFile);
        Map<String, Object> obj = JsonTestUtil.parseObject(payload);
        assertEquals(2, JsonTestUtil.getArray(obj, "nodes").size());
    }

    @Test
    public void testExportParserIrNdjsonSuccessEventContainsCounts() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              @interleave(profile=javaStyle)
              @scopeTree(mode=lexical)
              Start ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-export-parser-ir-ndjson", ".ubnf");
        Path exportFile = Files.createTempFile("codegen-main-export-parser-ir-ndjson-out", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--export-parser-ir", exportFile.toString(),
            "--report-format", "ndjson"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.out()));
        assertEquals("parser-ir-export", JsonTestUtil.getString(event, "event"));
        assertTrue(JsonTestUtil.getBoolean(event, "ok"));
        assertEquals(grammarFile.toString(), JsonTestUtil.getString(event, "source"));
        assertEquals(exportFile.toString(), JsonTestUtil.getString(event, "output"));
        assertEquals(1L, JsonTestUtil.getLong(event, "grammarCount"));
        assertTrue(JsonTestUtil.getLong(event, "nodeCount") > 0);
        assertTrue(JsonTestUtil.getLong(event, "annotationCount") > 0);
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testExportParserIrNdjsonFailureEmitsCliErrorEvent() throws Exception {
        String source = "grammar Broken {";
        Path grammarFile = Files.createTempFile("codegen-main-export-parser-ir-ndjson-fail", ".ubnf");
        Path exportFile = Files.createTempFile("codegen-main-export-parser-ir-ndjson-fail-out", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--export-parser-ir", exportFile.toString(),
            "--report-format", "ndjson"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.out()));
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
        assertEquals("E-PARSER-IR-EXPORT", JsonTestUtil.getString(event, "code"));
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testValidateParserIrReturnsOkForValidFixture() throws Exception {
        String payload = Files.readString(Path.of("src/test/resources/schema/parser-ir/valid-minimal.json"));
        Path parserIrFile = Files.createTempFile("codegen-main-parser-ir-valid", ".json");
        Files.writeString(parserIrFile, payload);

        RunResult result = runCodegen(
            "--validate-parser-ir", parserIrFile.toString()
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("Parser IR validation succeeded"));
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testValidateParserIrReturnsValidationErrorForInvalidFixture() throws Exception {
        String payload = Files.readString(Path.of("src/test/resources/schema/parser-ir/invalid-source-blank.json"));
        Path parserIrFile = Files.createTempFile("codegen-main-parser-ir-invalid", ".json");
        Files.writeString(parserIrFile, payload);

        RunResult result = runCodegen(
            "--validate-parser-ir", parserIrFile.toString()
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("E-PARSER-IR-CONSTRAINT"));
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
    public void testUnsupportedReportVersionReturnsCliErrorCode() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-report-version-invalid", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json",
            "--report-version", "2"
        );
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertTrue(result.err().contains("Unsupported --report-version"));
    }

    @Test
    public void testReportVersion1OptionIsAccepted() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-report-version-v1", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json",
            "--report-version", "1"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        Map<String, Object> obj = JsonTestUtil.parseObject(result.out().trim());
        assertEquals(1L, JsonTestUtil.getLong(obj, "reportVersion"));
    }

    @Test
    public void testReportSchemaCheckOptionIsAccepted() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-schema-check", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json",
            "--report-schema-check"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testStrictOptionIsAccepted() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-strict", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--strict"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("Validation succeeded"));
    }

    @Test
    public void testWarningsDoNotFailWithoutStrict() throws Exception {
        String source = """
            grammar WarnOnly {
              @package: org.example.warn
              @mapping(RootNode, params=[value])
              Start ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-warning-nonstrict", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.err().contains("Validation warnings:"));
        assertTrue(result.err().contains("W-GENERAL-NO-ROOT"));
    }

    @Test
    public void testWarningsCanBeEmittedAsJsonInTextMode() throws Exception {
        String source = """
            grammar WarnOnly {
              @package: org.example.warn
              @mapping(RootNode, params=[value])
              Start ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-warning-json", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--warnings-as-json"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("Validation succeeded"));
        String warningPayload = result.err().trim();
        assertTrue(warningPayload.startsWith("{\"reportVersion\":1,"));
        assertTrue(warningPayload.contains("\"severity\":\"WARNING\""));
        assertTrue(warningPayload.contains("\"code\":\"W-GENERAL-NO-ROOT\""));
        assertTrue(warningPayload.contains("\"warningsCount\":1"));
    }

    @Test
    public void testDryRunDoesNotWriteGeneratedFiles() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-dry-run", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-dry-run-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--dry-run"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("Dry-run: would generate"));
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        assertFalse(Files.exists(ast));
    }

    @Test
    public void testOverwriteNeverRefusesExistingFile() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-overwrite-never", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-overwrite-never-out");
        Files.writeString(grammarFile, source);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// existing");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--overwrite", "never"
        );

        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("Conflict (not overwritten):"));
        assertTrue(result.err().contains("Fail-on policy triggered: conflict=1"));
        assertEquals("// existing", Files.readString(ast));
    }

    @Test
    public void testOverwriteNeverCanPassWithFailOnNone() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-overwrite-never-pass", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-overwrite-never-pass-out");
        Files.writeString(grammarFile, source);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// existing");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--overwrite", "never",
            "--fail-on", "none",
            "--report-format", "json"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("\"conflictCount\":1"));
    }

    @Test
    public void testOverwriteIfDifferentSkipsUnchangedFile() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-overwrite-if-different", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-overwrite-if-different-out");
        Files.writeString(grammarFile, source);

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
            "--overwrite", "if-different"
        );
        assertEquals(CodegenMain.EXIT_OK, second.exitCode());
        assertTrue(second.out().contains("Skipped (unchanged):"));
    }

    @Test
    public void testCleanOutputAllowsOverwriteNever() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-clean-output", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-clean-output-out");
        Files.writeString(grammarFile, source);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// stale content");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--clean-output",
            "--overwrite", "never"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        assertTrue(result.out().contains("Generated: "));
        assertFalse(Files.readString(ast).contains("stale content"));
    }

    @Test
    public void testWarningsFailWithStrictMode() throws Exception {
        String source = """
            grammar WarnOnly {
              @package: org.example.warn
              @mapping(RootNode, params=[value])
              Start ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-warning-strict", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--strict",
            "--report-format", "json"
        );
        assertEquals(CodegenMain.EXIT_STRICT_VALIDATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("\"ok\":false"));
        assertTrue(result.err().contains("\"severity\":\"WARNING\""));
        assertTrue(result.err().contains("\"code\":\"W-GENERAL-NO-ROOT\""));
    }

    @Test
    public void testFailOnWarningWithoutStrict() throws Exception {
        String source = """
            grammar WarnOnly {
              @package: org.example.warn
              @mapping(RootNode, params=[value])
              Start ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-warning-failon", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--fail-on", "warning",
            "--report-format", "json"
        );
        assertEquals(CodegenMain.EXIT_STRICT_VALIDATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("\"severity\":\"WARNING\""));
    }

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
    public void testFailOnSkippedReturnsGenerationError() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-failon-skipped", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-failon-skipped-out");
        Files.writeString(grammarFile, source);

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
            "--fail-on", "skipped",
            "--report-format", "json"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, second.exitCode());
        assertTrue(second.err().contains("Fail-on policy triggered: skipped=1"));
        assertTrue(second.err().contains("\"failReasonCode\":\"FAIL_ON_SKIPPED\""));
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
    public void testFailOnWarningsThresholdReturnsStrictValidationError() throws Exception {
        String source = """
            grammar WarnOnly {
              @package: org.example.warn
              @mapping(RootNode, params=[value])
              Start ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-failon-warning-threshold", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--fail-on", "warnings-count>=1",
            "--report-format", "json"
        );
        assertEquals(CodegenMain.EXIT_STRICT_VALIDATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("\"warningsCount\":1"));
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
    public void testFailOnCleanedReturnsReasonCodeInJson() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-failon-cleaned", ".ubnf");
        Path outputDir = Files.createTempDirectory("codegen-main-failon-cleaned-out");
        Files.writeString(grammarFile, source);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// stale");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--clean-output",
            "--fail-on", "cleaned",
            "--report-format", "json"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("\"mode\":\"generate\""));
        assertTrue(result.err().contains("\"ok\":false"));
        assertTrue(result.err().contains("\"failReasonCode\":\"FAIL_ON_CLEANED\""));
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
    public void testCleanOutputRejectsUnsafeRootPath() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("codegen-main-clean-unsafe", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", "/",
            "--generators", "AST",
            "--clean-output"
        );
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertTrue(result.err().contains("Refusing --clean-output for unsafe path"));
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

    private static RunResult runCodegen(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.run(args, new PrintStream(out), new PrintStream(err));
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    private static RunResult runCodegenWithClock(Clock clock, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.runWithClock(args, new PrintStream(out), new PrintStream(err), clock);
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    private static void assertHasNonEmptyJsonField(String json, String fieldName) {
        String value = extractJsonStringField(json, fieldName);
        assertTrue(fieldName + " should be non-empty", value != null && !value.isBlank());
    }

    private static void assertGeneratedAtIsIsoInstant(String json) {
        String value = extractJsonStringField(json, "generatedAt");
        assertTrue("generatedAt should exist", value != null);
        Instant.parse(value);
    }

    private static String extractJsonStringField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\":\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private static String lastJsonLine(String text) {
        String[] lines = text.trim().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        throw new IllegalStateException("JSON line not found");
    }

    private record RunResult(int exitCode, String out, String err) {}
}
