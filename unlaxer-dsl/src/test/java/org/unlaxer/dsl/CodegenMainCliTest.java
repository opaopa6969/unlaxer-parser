package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.unlaxer.dsl.CodegenTestHelper.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

import org.unlaxer.dsl.CodegenTestHelper.RunResult;

public class CodegenMainCliTest {

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
}
