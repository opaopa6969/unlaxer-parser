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

public class ReportNdjsonSchemaDocumentConsistencyTest {

    @Test
    public void testValidateSuccessEventMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Set<String> expectedKeys = requiredKeys(schemaVariant(schema, "validate-success"));

        Path grammarFile = Files.createTempFile("ndjson-schema-doc-validate-success", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());

        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.out()));
        assertEquals(expectedKeys, event.keySet());
        ReportJsonSchemaValidator.validate(1, JsonTestUtil.toJson(JsonTestUtil.getObject(event, "payload")));
    }

    @Test
    public void testValidateFailureEventMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Set<String> expectedKeys = requiredKeys(schemaVariant(schema, "validate-failure"));

        String source = """
            grammar Invalid {
              @package: org.example.invalid
              @root
              @mapping(RootNode, params=[value, missing])
              Invalid ::= 'x' @value ;
            }
            """;
        Path grammarFile = Files.createTempFile("ndjson-schema-doc-validate-failure", ".ubnf");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());

        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.err()));
        assertEquals(expectedKeys, event.keySet());
        ReportJsonSchemaValidator.validate(1, JsonTestUtil.toJson(JsonTestUtil.getObject(event, "payload")));
    }

    @Test
    public void testStrictFailureEventMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Set<String> expectedKeys = requiredKeys(schemaVariant(schema, "strict-failure"));

        Path grammarFile = Files.createTempFile("ndjson-schema-doc-strict-failure", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.WARN_ONLY_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--fail-on", "warning",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_STRICT_VALIDATION_ERROR, result.exitCode());

        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.err()));
        assertEquals(expectedKeys, event.keySet());
        ReportJsonSchemaValidator.validate(1, JsonTestUtil.toJson(JsonTestUtil.getObject(event, "payload")));
    }

    @Test
    public void testWarningsEventMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Set<String> expectedKeys = requiredKeys(schemaVariant(schema, "warnings"));

        Path grammarFile = Files.createTempFile("ndjson-schema-doc-warnings", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.WARN_ONLY_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--fail-on", "none",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());

        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.err()));
        assertEquals(expectedKeys, event.keySet());
        assertEquals("warnings", JsonTestUtil.getString(event, "event"));
        ReportJsonSchemaValidator.validate(1, JsonTestUtil.toJson(JsonTestUtil.getObject(event, "payload")));
    }

    @Test
    public void testGenerateSummaryAndFileEventMatchSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Set<String> fileKeys = requiredKeys(schemaVariant(schema, "file-event"));
        Set<String> summaryKeys = requiredKeys(schemaVariant(schema, "generate-summary"));

        Path grammarFile = Files.createTempFile("ndjson-schema-doc-generate", ".ubnf");
        Path outputDir = Files.createTempDirectory("ndjson-schema-doc-generate-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());

        List<String> jsonLines = jsonLines(result.out());
        Map<String, Object> first = JsonTestUtil.parseObject(jsonLines.get(0));
        assertEquals(fileKeys, first.keySet());
        assertEquals("file", JsonTestUtil.getString(first, "event"));

        Map<String, Object> last = JsonTestUtil.parseObject(jsonLines.get(jsonLines.size() - 1));
        assertEquals(summaryKeys, last.keySet());
        assertEquals("generate-summary", JsonTestUtil.getString(last, "event"));
        ReportJsonSchemaValidator.validate(1, JsonTestUtil.toJson(JsonTestUtil.getObject(last, "payload")));
    }

    @Test
    public void testFileEventCleanedActionMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Set<String> fileKeys = requiredKeys(schemaVariant(schema, "file-event"));

        Path grammarFile = Files.createTempFile("ndjson-schema-doc-cleaned", ".ubnf");
        Path outputDir = Files.createTempDirectory("ndjson-schema-doc-cleaned-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);
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

        List<String> jsonLines = jsonLines(result.out());
        Map<String, Object> cleaned = null;
        for (String line : jsonLines) {
            Map<String, Object> event = JsonTestUtil.parseObject(line);
            if ("file".equals(event.get("event")) && "cleaned".equals(event.get("action"))) {
                cleaned = event;
                break;
            }
        }
        assertTrue("cleaned file event should exist", cleaned != null);
        assertEquals(fileKeys, cleaned.keySet());
    }

    @Test
    public void testCliErrorEventMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Set<String> expectedKeys = requiredKeys(schemaVariant(schema, "cli-error"));

        Path grammarFile = Files.createTempFile("ndjson-schema-doc-cli-error", ".ubnf");
        Path outputDir = Files.createTempDirectory("ndjson-schema-doc-cli-error-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "Nope",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());

        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.out()));
        assertEquals(expectedKeys, event.keySet());
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
    }

    @Test
    public void testCliUsageErrorEventMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadSchemaDocument();
        Set<String> expectedKeys = requiredKeys(schemaVariant(schema, "cli-error"));

        RunResult result = runCodegen(
            "--validate-only",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());

        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(result.out()));
        assertEquals(expectedKeys, event.keySet());
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
        assertEquals("E-CLI-USAGE", JsonTestUtil.getString(event, "code"));
    }

    private static Set<String> requiredKeys(Map<String, Object> variant) {
        List<Object> required = JsonTestUtil.getArray(variant, "required");
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : required) {
            keys.add((String) item);
        }
        return keys;
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

    private static List<String> jsonLines(String text) {
        List<String> lines = List.of(text.trim().split("\\R"));
        List<String> out = new java.util.ArrayList<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static Map<String, Object> loadSchemaDocument() throws Exception {
        String json = Files.readString(Path.of("docs/schema/report-v1.ndjson.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        assertTrue(schema.containsKey("oneOf"));
        return schema;
    }

    private static RunResult runCodegen(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.run(args, new PrintStream(out), new PrintStream(err));
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    private record RunResult(int exitCode, String out, String err) {}
}
