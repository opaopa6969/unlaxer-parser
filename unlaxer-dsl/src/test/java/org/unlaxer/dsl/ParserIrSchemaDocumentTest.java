package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ParserIrSchemaDocumentTest {

    @Test
    public void testParserIrSchemaIncludesCoreTopLevelContract() throws Exception {
        String json = Files.readString(Path.of("docs/schema/parser-ir-v1.draft.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);

        assertEquals("https://unlaxer.dev/schema/parser-ir-v1.draft.json", JsonTestUtil.getString(schema, "$id"));
        assertEquals("object", JsonTestUtil.getString(schema, "type"));

        List<Object> required = JsonTestUtil.getArray(schema, "required");
        assertTrue(required.contains("irVersion"));
        assertTrue(required.contains("source"));
        assertTrue(required.contains("nodes"));
        assertTrue(required.contains("diagnostics"));

        Map<String, Object> properties = JsonTestUtil.getObject(schema, "properties");
        Map<String, Object> nodes = JsonTestUtil.getObject(properties, "nodes");
        assertEquals(1L, JsonTestUtil.getLong(nodes, "minItems"));
    }

    @Test
    public void testParserIrSchemaDefinesKeyDefs() throws Exception {
        String json = Files.readString(Path.of("docs/schema/parser-ir-v1.draft.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        Map<String, Object> defs = JsonTestUtil.getObject(schema, "$defs");

        assertTrue(defs.containsKey("node"));
        assertTrue(defs.containsKey("span"));
        assertTrue(defs.containsKey("scopeEvent"));
        assertTrue(defs.containsKey("annotation"));
        assertTrue(defs.containsKey("diagnostic"));
    }

    @Test
    public void testParserIrSchemaDefinesAnnotationNamePattern() throws Exception {
        String json = Files.readString(Path.of("docs/schema/parser-ir-v1.draft.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        Map<String, Object> defs = JsonTestUtil.getObject(schema, "$defs");
        Map<String, Object> annotation = JsonTestUtil.getObject(defs, "annotation");
        Map<String, Object> props = JsonTestUtil.getObject(annotation, "properties");
        Map<String, Object> name = JsonTestUtil.getObject(props, "name");
        assertEquals("^[a-z][a-zA-Z0-9-]*$", JsonTestUtil.getString(name, "pattern"));
    }

    @Test
    public void testParserIrSchemaDefinesAnnotationPayloadMinProperties() throws Exception {
        String json = Files.readString(Path.of("docs/schema/parser-ir-v1.draft.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        Map<String, Object> defs = JsonTestUtil.getObject(schema, "$defs");
        Map<String, Object> annotation = JsonTestUtil.getObject(defs, "annotation");
        Map<String, Object> props = JsonTestUtil.getObject(annotation, "properties");
        Map<String, Object> payload = JsonTestUtil.getObject(props, "payload");
        assertEquals(1L, JsonTestUtil.getLong(payload, "minProperties"));
    }

    @Test
    public void testParserIrSchemaDefinesNonBlankSourcePattern() throws Exception {
        String json = Files.readString(Path.of("docs/schema/parser-ir-v1.draft.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        Map<String, Object> properties = JsonTestUtil.getObject(schema, "properties");
        Map<String, Object> source = JsonTestUtil.getObject(properties, "source");
        assertEquals(".*\\S.*", JsonTestUtil.getString(source, "pattern"));
    }

    @Test
    public void testParserIrSchemaDefineEventRequiresKind() throws Exception {
        String json = Files.readString(Path.of("docs/schema/parser-ir-v1.draft.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        Map<String, Object> defs = JsonTestUtil.getObject(schema, "$defs");
        Map<String, Object> scopeEvent = JsonTestUtil.getObject(defs, "scopeEvent");
        List<Object> allOf = JsonTestUtil.getArray(scopeEvent, "allOf");

        @SuppressWarnings("unchecked")
        Map<String, Object> defineRule = (Map<String, Object>) allOf.get(0);
        Map<String, Object> thenObj = JsonTestUtil.getObject(defineRule, "then");
        List<Object> required = JsonTestUtil.getArray(thenObj, "required");
        assertTrue(required.contains("symbol"));
        assertTrue(required.contains("kind"));
    }

    @Test
    public void testParserIrSchemaUseEventForbidsKind() throws Exception {
        String json = Files.readString(Path.of("docs/schema/parser-ir-v1.draft.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        Map<String, Object> defs = JsonTestUtil.getObject(schema, "$defs");
        Map<String, Object> scopeEvent = JsonTestUtil.getObject(defs, "scopeEvent");
        List<Object> allOf = JsonTestUtil.getArray(scopeEvent, "allOf");

        @SuppressWarnings("unchecked")
        Map<String, Object> useRule = (Map<String, Object>) allOf.get(1);
        Map<String, Object> thenObj = JsonTestUtil.getObject(useRule, "then");
        Map<String, Object> notObj = JsonTestUtil.getObject(thenObj, "not");
        List<Object> anyOf = JsonTestUtil.getArray(notObj, "anyOf");
        assertEquals(2, anyOf.size());
    }

    @Test
    public void testParserIrSchemaEnterLeaveForbidSymbolKindAndTargetScopeId() throws Exception {
        String json = Files.readString(Path.of("docs/schema/parser-ir-v1.draft.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        Map<String, Object> defs = JsonTestUtil.getObject(schema, "$defs");
        Map<String, Object> scopeEvent = JsonTestUtil.getObject(defs, "scopeEvent");
        List<Object> allOf = JsonTestUtil.getArray(scopeEvent, "allOf");

        @SuppressWarnings("unchecked")
        Map<String, Object> enterLeaveRule = (Map<String, Object>) allOf.get(2);
        Map<String, Object> thenObj = JsonTestUtil.getObject(enterLeaveRule, "then");
        Map<String, Object> notObj = JsonTestUtil.getObject(thenObj, "not");
        List<Object> anyOf = JsonTestUtil.getArray(notObj, "anyOf");
        assertEquals(3, anyOf.size());
    }

    @Test
    public void testParserIrSchemaDefinesScopeModeEnum() throws Exception {
        String json = Files.readString(Path.of("docs/schema/parser-ir-v1.draft.json"));
        Map<String, Object> schema = JsonTestUtil.parseObject(json);
        Map<String, Object> defs = JsonTestUtil.getObject(schema, "$defs");
        Map<String, Object> scopeEvent = JsonTestUtil.getObject(defs, "scopeEvent");
        Map<String, Object> props = JsonTestUtil.getObject(scopeEvent, "properties");
        Map<String, Object> scopeMode = JsonTestUtil.getObject(props, "scopeMode");
        List<Object> values = JsonTestUtil.getArray(scopeMode, "enum");
        assertTrue(values.contains("lexical"));
        assertTrue(values.contains("dynamic"));
    }
}
