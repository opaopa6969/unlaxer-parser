package org.unlaxer.dsl;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class ManifestSchemaDocumentTest {

    @Test
    public void testManifestJsonSchemaIncludesFailReasonCode() throws Exception {
        String json = Files.readString(Path.of("docs/schema/manifest-v1.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        Map<String, Object> properties = JsonTestUtil.getObject(schema, "properties");
        assertTrue(properties.containsKey("failReasonCode"));
        assertTrue(schema.containsKey("allOf"));
    }

    @Test
    public void testManifestNdjsonSchemaDefinesExpectedVariants() throws Exception {
        String json = Files.readString(Path.of("docs/schema/manifest-v1.ndjson.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        List<Object> oneOf = JsonTestUtil.getArray(schema, "oneOf");
        Set<String> titles = new HashSet<>();
        for (Object o : oneOf) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) o;
            titles.add(JsonTestUtil.getString(item, "title"));
        }
        assertTrue(titles.contains("file-event"));
        assertTrue(titles.contains("manifest-summary"));

        for (Object o : oneOf) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) o;
            if ("manifest-summary".equals(JsonTestUtil.getString(item, "title"))) {
                assertTrue(item.containsKey("allOf"));
                return;
            }
        }
        throw new IllegalStateException("manifest-summary schema not found");
    }
}
