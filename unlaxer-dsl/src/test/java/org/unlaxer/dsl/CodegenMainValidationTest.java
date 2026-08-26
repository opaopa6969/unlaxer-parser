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

public class CodegenMainValidationTest {

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
    public void testRuleTokenParserNameCollisionHasStableJsonDiagnostic() throws Exception {
        String source = """
            grammar InvalidCollision {
              @package: org.example.invalid
              token CODE_START = org.example.CodeStartParser
              @root
              CodeStart ::= CODE_START ;
            }
            """;

        Path grammarFile = Files.createTempFile("codegen-main-name-collision", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        assertTrue(result.err().contains("\"code\":\"E-RULE-TOKEN-NAME-COLLISION\""));
        assertTrue(result.err().contains("\"category\":\"RULE\""));
        assertTrue(result.err().contains("\"rule\":\"CodeStart\""));
        assertTrue(result.err().contains("CodeStartParser"));
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
}
