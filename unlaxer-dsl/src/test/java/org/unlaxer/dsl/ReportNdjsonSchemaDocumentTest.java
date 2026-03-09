package org.unlaxer.dsl;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class ReportNdjsonSchemaDocumentTest {

    @Test
    public void testNdjsonSchemaDefinesAllExpectedEventVariants() throws Exception {
        String json = Files.readString(Path.of("docs/schema/report-v1.ndjson.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        List<Object> oneOf = JsonTestUtil.getArray(schema, "oneOf");

        Set<String> titles = new HashSet<>();
        for (Object o : oneOf) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) o;
            titles.add(JsonTestUtil.getString(item, "title"));
        }

        assertTrue(titles.contains("file-event"));
        assertTrue(titles.contains("validate-success"));
        assertTrue(titles.contains("validate-failure"));
        assertTrue(titles.contains("strict-failure"));
        assertTrue(titles.contains("warnings"));
        assertTrue(titles.contains("generate-summary"));
        assertTrue(titles.contains("cli-error"));
    }

    @Test
    public void testNdjsonSchemaFileEventIncludesCleanedAction() throws Exception {
        String json = Files.readString(Path.of("docs/schema/report-v1.ndjson.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        List<Object> oneOf = JsonTestUtil.getArray(schema, "oneOf");

        for (Object o : oneOf) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) o;
            if (!"file-event".equals(JsonTestUtil.getString(item, "title"))) {
                continue;
            }
            Map<String, Object> properties = JsonTestUtil.getObject(item, "properties");
            Map<String, Object> action = JsonTestUtil.getObject(properties, "action");
            List<Object> enums = JsonTestUtil.getArray(action, "enum");
            assertTrue(enums.contains("cleaned"));
            return;
        }
        throw new IllegalStateException("file-event schema not found");
    }

    @Test
    public void testNdjsonSchemaCliErrorCodeUsesStablePattern() throws Exception {
        String json = Files.readString(Path.of("docs/schema/report-v1.ndjson.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        List<Object> oneOf = JsonTestUtil.getArray(schema, "oneOf");

        for (Object o : oneOf) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) o;
            if (!"cli-error".equals(JsonTestUtil.getString(item, "title"))) {
                continue;
            }
            Map<String, Object> properties = JsonTestUtil.getObject(item, "properties");
            Map<String, Object> code = JsonTestUtil.getObject(properties, "code");
            assertTrue(code.containsKey("pattern"));
            assertTrue("^E-[A-Z0-9-]+$".equals(code.get("pattern")));
            return;
        }
        throw new IllegalStateException("cli-error schema not found");
    }
}
