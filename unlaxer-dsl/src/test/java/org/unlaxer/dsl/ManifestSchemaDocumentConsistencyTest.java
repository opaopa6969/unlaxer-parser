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

public class ManifestSchemaDocumentConsistencyTest {

    @Test
    public void testManifestJsonPayloadMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadJsonSchema();
        Set<String> requiredKeys = requiredKeys(schema);

        Path grammarFile = Files.createTempFile("manifest-schema-doc-json", ".ubnf");
        Path outputDir = Files.createTempDirectory("manifest-schema-doc-json-out");
        Path manifest = Files.createTempFile("manifest-schema-doc-json", ".json");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--output-manifest", manifest.toString(),
            "--manifest-format", "json"
        );
        assertEquals(CodegenMain.EXIT_OK, result.exitCode());

        Map<String, Object> payload = JsonTestUtil.parseObject(Files.readString(manifest));
        assertEquals(requiredKeys, payload.keySet());
    }

    @Test
    public void testManifestNdjsonSummaryMatchesSchemaDocument() throws Exception {
        Map<String, Object> schema = loadNdjsonSchema();
        Map<String, Object> summaryVariant = schemaVariant(schema, "manifest-summary");
        Set<String> requiredKeys = requiredKeys(summaryVariant);

        Path grammarFile = Files.createTempFile("manifest-schema-doc-ndjson", ".ubnf");
        Path outputDir = Files.createTempDirectory("manifest-schema-doc-ndjson-out");
        Path manifest = Files.createTempFile("manifest-schema-doc-ndjson", ".ndjson");
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
            "--output-manifest", manifest.toString(),
            "--manifest-format", "ndjson",
            "--report-format", "json"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());

        Map<String, Object> summary = findSummary(Files.readString(manifest));
        assertEquals(requiredKeys, summary.keySet());
    }

    private static Set<String> requiredKeys(Map<String, Object> schemaVariant) {
        List<Object> required = JsonTestUtil.getArray(schemaVariant, "required");
        Set<String> keys = new LinkedHashSet<>();
        for (Object item : required) {
            keys.add((String) item);
        }
        return keys;
    }

    private static Map<String, Object> schemaVariant(Map<String, Object> schema, String title) {
        List<Object> oneOf = JsonTestUtil.getArray(schema, "oneOf");
        for (Object item : oneOf) {
            @SuppressWarnings("unchecked")
            Map<String, Object> variant = (Map<String, Object>) item;
            if (title.equals(JsonTestUtil.getString(variant, "title"))) {
                return variant;
            }
        }
        throw new IllegalStateException("schema variant not found: " + title);
    }

    private static Map<String, Object> loadJsonSchema() throws Exception {
        String json = Files.readString(Path.of("docs/schema/manifest-v1.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        assertTrue(schema.containsKey("required"));
        return schema;
    }

    private static Map<String, Object> loadNdjsonSchema() throws Exception {
        String json = Files.readString(Path.of("docs/schema/manifest-v1.ndjson.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        assertTrue(schema.containsKey("oneOf"));
        return schema;
    }

    private static Map<String, Object> findSummary(String ndjson) {
        String[] lines = ndjson.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Map<String, Object> obj = JsonTestUtil.parseObject(trimmed);
            if ("manifest-summary".equals(obj.get("event"))) {
                return obj;
            }
        }
        throw new IllegalStateException("manifest-summary not found");
    }

    private static RunResult runCodegen(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.run(args, new PrintStream(out), new PrintStream(err));
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    private record RunResult(int exitCode, String out, String err) {}
}
