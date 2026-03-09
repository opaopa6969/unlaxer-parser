package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.Test;

public class NdjsonErrorEventWriterTest {

    @Test
    public void testCliErrorEventWithNullDetailAndNoGenerators() {
        String json = NdjsonErrorEventWriter.cliErrorEvent(
            "E-CLI-USAGE",
            "Missing required: --grammar <file.ubnf>",
            null,
            List.of()
        );

        Map<String, Object> obj = JsonTestUtil.parseObject(json);
        assertEquals("cli-error", JsonTestUtil.getString(obj, "event"));
        assertEquals("E-CLI-USAGE", JsonTestUtil.getString(obj, "code"));
        assertEquals("Missing required: --grammar <file.ubnf>", JsonTestUtil.getString(obj, "message"));
        assertEquals(null, obj.get("detail"));
        assertEquals(List.of(), JsonTestUtil.getArray(obj, "availableGenerators"));
    }

    @Test
    public void testCliErrorEventEscapesTextAndIncludesGenerators() {
        String json = NdjsonErrorEventWriter.cliErrorEvent(
            "E-CLI-UNKNOWN-GENERATOR",
            "Unknown generator: \"Nope\"",
            "line1\nline2",
            List.of("AST", "Parser")
        );

        Map<String, Object> obj = JsonTestUtil.parseObject(json);
        assertEquals("cli-error", JsonTestUtil.getString(obj, "event"));
        assertEquals("E-CLI-UNKNOWN-GENERATOR", JsonTestUtil.getString(obj, "code"));
        assertEquals("Unknown generator: \"Nope\"", JsonTestUtil.getString(obj, "message"));
        assertEquals("line1\nline2", JsonTestUtil.getString(obj, "detail"));
        assertEquals(List.of("AST", "Parser"), JsonTestUtil.getArray(obj, "availableGenerators"));
    }
}
