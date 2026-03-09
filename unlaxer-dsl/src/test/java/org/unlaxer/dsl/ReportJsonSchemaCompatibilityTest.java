package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class ReportJsonSchemaCompatibilityTest {

    @Test
    public void testValidateSuccessTopLevelSchemaV1() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("schema-validate-success", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        var obj = JsonTestUtil.parseObject(result.out().trim());
        assertEquals(
            List.of(
                "reportVersion",
                "schemaVersion",
                "schemaUrl",
                "toolVersion",
                "argsHash",
                "generatedAt",
                "mode",
                "ok",
                "grammarCount",
                "warningsCount",
                "issues"
            ),
            List.copyOf(obj.keySet())
        );
        assertEquals(1L, JsonTestUtil.getLong(obj, "reportVersion"));
        assertEquals("1.0", JsonTestUtil.getString(obj, "schemaVersion"));
        assertEquals("https://unlaxer.dev/schema/report-v1.json", JsonTestUtil.getString(obj, "schemaUrl"));
        assertTrue(!JsonTestUtil.getString(obj, "argsHash").isBlank());
        assertEquals(true, JsonTestUtil.getBoolean(obj, "ok"));
        assertEquals(1L, JsonTestUtil.getLong(obj, "grammarCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "warningsCount"));
        assertEquals(List.of(), JsonTestUtil.getArray(obj, "issues"));
    }

    @Test
    public void testValidateFailureTopLevelSchemaV1() throws Exception {
        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("schema-validate-failure", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        var obj = JsonTestUtil.parseObject(result.err().trim());
        assertEquals(
            List.of(
                "reportVersion",
                "schemaVersion",
                "schemaUrl",
                "toolVersion",
                "argsHash",
                "generatedAt",
                "mode",
                "ok",
                "failReasonCode",
                "issueCount",
                "warningsCount",
                "severityCounts",
                "categoryCounts",
                "issues"
            ),
            List.copyOf(obj.keySet())
        );
        assertEquals(1L, JsonTestUtil.getLong(obj, "reportVersion"));
        assertEquals("1.0", JsonTestUtil.getString(obj, "schemaVersion"));
        assertEquals("https://unlaxer.dev/schema/report-v1.json", JsonTestUtil.getString(obj, "schemaUrl"));
        assertTrue(!JsonTestUtil.getString(obj, "argsHash").isBlank());
        assertEquals(false, JsonTestUtil.getBoolean(obj, "ok"));
        assertEquals(null, obj.get("failReasonCode"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "warningsCount"));
        var severityCounts = JsonTestUtil.getObject(obj, "severityCounts");
        assertEquals(1L, JsonTestUtil.getLong(severityCounts, "ERROR"));
        var issues = JsonTestUtil.getArray(obj, "issues");
        assertEquals(1, issues.size());
    }

    @Test
    public void testGenerateSuccessTopLevelSchemaV1() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("schema-generate-success", ".ubnf");
        Path outputDir = Files.createTempDirectory("schema-generate-success-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String[] lines = result.out().trim().split("\\R");
        String json = lines[lines.length - 1];
        var obj = JsonTestUtil.parseObject(json);
        assertEquals(
            List.of(
                "reportVersion",
                "schemaVersion",
                "schemaUrl",
                "toolVersion",
                "argsHash",
                "generatedAt",
                "mode",
                "ok",
                "failReasonCode",
                "grammarCount",
                "generatedCount",
                "warningsCount",
                "writtenCount",
                "skippedCount",
                "conflictCount",
                "dryRunCount",
                "generatedFiles"
            ),
            List.copyOf(obj.keySet())
        );
        assertEquals(1L, JsonTestUtil.getLong(obj, "reportVersion"));
        assertEquals("1.0", JsonTestUtil.getString(obj, "schemaVersion"));
        assertEquals("https://unlaxer.dev/schema/report-v1.json", JsonTestUtil.getString(obj, "schemaUrl"));
        assertTrue(!JsonTestUtil.getString(obj, "argsHash").isBlank());
        assertEquals(true, JsonTestUtil.getBoolean(obj, "ok"));
        assertEquals(null, obj.get("failReasonCode"));
        assertEquals(1L, JsonTestUtil.getLong(obj, "generatedCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "warningsCount"));
        assertEquals(1L, JsonTestUtil.getLong(obj, "writtenCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "skippedCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "conflictCount"));
        assertEquals(0L, JsonTestUtil.getLong(obj, "dryRunCount"));
    }

    @Test
    public void testGenerateFailureTopLevelSchemaV1() throws Exception {
        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("schema-generate-failure", ".ubnf");
        Path outputDir = Files.createTempDirectory("schema-generate-failure-out");
        Files.writeString(grammarFile, source);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// existing");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--overwrite", "never",
            "--fail-on", "conflict",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        String[] lines = result.err().trim().split("\\R");
        var obj = JsonTestUtil.parseObject(lines[lines.length - 1]);
        assertEquals("generate", JsonTestUtil.getString(obj, "mode"));
        assertEquals(false, JsonTestUtil.getBoolean(obj, "ok"));
        assertEquals("FAIL_ON_CONFLICT", JsonTestUtil.getString(obj, "failReasonCode"));
    }

    private static RunResult runCodegen(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.run(args, new PrintStream(out), new PrintStream(err));
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    private record RunResult(int exitCode, String out, String err) {}
}
