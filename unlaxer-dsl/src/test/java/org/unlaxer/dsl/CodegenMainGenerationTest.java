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

public class CodegenMainGenerationTest {

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
}
