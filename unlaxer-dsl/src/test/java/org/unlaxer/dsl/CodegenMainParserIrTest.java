package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.unlaxer.dsl.CodegenTestHelper.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

import org.unlaxer.dsl.CodegenTestHelper.RunResult;

public class CodegenMainParserIrTest {

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
}
