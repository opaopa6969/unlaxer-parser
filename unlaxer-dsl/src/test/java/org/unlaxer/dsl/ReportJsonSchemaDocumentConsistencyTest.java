package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class ReportJsonSchemaDocumentConsistencyTest {

    @Test
    public void testValidateSuccessPayloadMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Map<String, Object> variant = schemaVariant(schema, "validate-success");

        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("schema-doc-validate-success", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        Map<String, Object> payload = JsonTestUtil.parseObject(result.out().trim());
        assertPayloadMatchesVariant(variant, payload);
    }

    @Test
    public void testValidateFailurePayloadMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Map<String, Object> variant = schemaVariant(schema, "validate-failure");

        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("schema-doc-validate-failure", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        Map<String, Object> payload = JsonTestUtil.parseObject(result.err().trim());
        assertPayloadMatchesVariant(variant, payload);
    }

    @Test
    public void testGenerateSuccessPayloadMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Map<String, Object> variant = schemaVariant(schema, "generate-result");
        assertTrue(variant.containsKey("allOf"));

        String source = """
            grammar Valid {
              @package: org.example.valid
              @root
              @mapping(RootNode, params=[value])
              Valid ::= 'ok' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("schema-doc-generate-success", ".ubnf");
        Path outputDir = Files.createTempDirectory("schema-doc-generate-success-out");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "json"
        );

        assertEquals(CodegenMain.EXIT_OK, result.exitCode());
        String[] lines = result.out().trim().split("\\R");
        Map<String, Object> payload = JsonTestUtil.parseObject(lines[lines.length - 1]);
        assertPayloadMatchesVariant(variant, payload);
    }

    private static void assertPayloadMatchesVariant(Map<String, Object> variant, Map<String, Object> payload) {
        List<Object> required = JsonTestUtil.getArray(variant, "required");
        Set<String> requiredKeys = new LinkedHashSet<>();
        for (Object item : required) {
            requiredKeys.add((String) item);
        }
        assertEquals(requiredKeys, payload.keySet());

        Map<String, Object> properties = JsonTestUtil.getObject(variant, "properties");
        assertConst(properties, payload, "reportVersion");
        assertConst(properties, payload, "schemaVersion");
        assertConst(properties, payload, "schemaUrl");
    }

    private static void assertConst(Map<String, Object> properties, Map<String, Object> payload, String key) {
        Map<String, Object> property = JsonTestUtil.getObject(properties, key);
        if (!property.containsKey("const")) {
            return;
        }
        Object expected = property.get("const");
        Object actual = payload.get(key);
        assertEquals(expected, actual);
    }

    private static Map<String, Object> schemaVariant(Map<String, Object> schema, String title) {
        List<Object> variants = JsonTestUtil.getArray(schema, "oneOf");
        for (Object item : variants) {
            @SuppressWarnings("unchecked")
            Map<String, Object> variant = (Map<String, Object>) item;
            if (title.equals(JsonTestUtil.getString(variant, "title"))) {
                return variant;
            }
        }
        throw new IllegalStateException("schema variant not found: " + title);
    }

    private static Map<String, Object> loadSchemaDocument() throws Exception {
        String json = Files.readString(Path.of("docs/schema/report-v1.json"));
        Map<String, Object> obj = JsonTestUtil.parseObject(json);
        assertTrue(obj.containsKey("oneOf"));
        return obj;
    }

    private static RunResult runCodegen(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.run(args, new PrintStream(out), new PrintStream(err));
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    private record RunResult(int exitCode, String out, String err) {}
}
