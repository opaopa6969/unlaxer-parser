package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.Test;

public class JsonMiniParserTest {

    @Test
    public void testParseObjectWithNestedValues() {
        Map<String, Object> obj = JsonMiniParser.parseObject(
            "{\"a\":1,\"b\":[true,false,null],\"c\":{\"x\":\"y\"}}",
            "E-TEST"
        );
        assertEquals(1L, obj.get("a"));
        List<Object> b = castList(obj.get("b"));
        assertEquals(true, b.get(0));
        assertEquals(false, b.get(1));
        assertEquals(null, b.get(2));
        Map<String, Object> c = castMap(obj.get("c"));
        assertEquals("y", c.get("x"));
    }

    @Test
    public void testParseHandlesEscapesAndUnicode() {
        Map<String, Object> obj = JsonMiniParser.parseObject(
            "{\"s\":\"line1\\nline2 \\\"q\\\" \\u2603\"}",
            "E-TEST"
        );
        assertEquals("line1\nline2 \"q\" \u2603", obj.get("s"));
    }

    @Test
    public void testParseRejectsTrailingCharacters() {
        try {
            JsonMiniParser.parseObject("{\"a\":1} xx", "E-TEST");
            fail("expected parser error");
        } catch (ReportSchemaValidationException e) {
            assertEquals("E-TEST-PARSE", e.code());
            assertTrue(e.getMessage().contains("trailing"));
        }
    }

    @Test
    public void testParseRejectsInvalidUnicodeEscape() {
        try {
            JsonMiniParser.parseObject("{\"a\":\"\\uZZZZ\"}", "E-TEST");
            fail("expected parser error");
        } catch (ReportSchemaValidationException e) {
            assertEquals("E-TEST-PARSE", e.code());
            assertTrue(e.getMessage().contains("unicode"));
        }
    }

    @Test
    public void testParseRejectsNonObjectRoot() {
        try {
            JsonMiniParser.parseObject("[]", "E-TEST");
            fail("expected parser error");
        } catch (ReportSchemaValidationException e) {
            assertEquals("E-TEST-PARSE", e.code());
            assertTrue(e.getMessage().contains("JSON object"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object value) {
        return (List<Object>) value;
    }
}
