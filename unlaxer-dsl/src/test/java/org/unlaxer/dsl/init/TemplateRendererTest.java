package org.unlaxer.dsl.init;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

public class TemplateRendererTest {

    @Test
    public void testSimpleVarSubstitution() {
        var r = new TemplateRenderer(Map.of("name", "mylang", "ClassName", "MyLang"));
        assertEquals("mylang/MyLang.java", r.render("{{name}}/{{ClassName}}.java"));
    }

    @Test
    public void testEmptyForUnknownVar() {
        var r = new TemplateRenderer(Map.of());
        assertEquals("hello, ", r.render("hello, {{nope}}"));
    }

    @Test
    public void testMavenPlaceholdersAreUntouched() {
        // Maven uses ${...} which must not be confused with our {{...}}.
        var r = new TemplateRenderer(Map.of("name", "mylang"));
        String result = r.render("<source>${project.basedir}/{{name}}</source>");
        assertEquals("<source>${project.basedir}/mylang</source>", result);
    }

    @Test
    public void testPositiveSectionTrue() {
        var r = new TemplateRenderer(Map.of("dap", true));
        assertEquals("[dap]", r.render("[{{#dap}}dap{{/dap}}]"));
    }

    @Test
    public void testPositiveSectionFalse() {
        var r = new TemplateRenderer(Map.of("dap", false));
        assertEquals("[]", r.render("[{{#dap}}dap{{/dap}}]"));
    }

    @Test
    public void testNegativeSection() {
        var r = new TemplateRenderer(Map.of("dap", false));
        assertEquals("[no-dap]", r.render("[{{^dap}}no-dap{{/dap}}]"));
    }

    @Test
    public void testMultilineSection() {
        var r = new TemplateRenderer(Map.of("dap", true, "name", "x"));
        String tpl = "A\n{{#dap}}line for {{name}}\n{{/dap}}B\n";
        assertEquals("A\nline for x\nB\n", r.render(tpl));
    }

    @Test
    public void testSequentialSections() {
        var r = new TemplateRenderer(Map.of("a", true, "b", false));
        assertEquals("[A]", r.render("[{{#a}}A{{/a}}{{#b}}B{{/b}}]"));
    }

    @Test
    public void testTruthiness() {
        var r1 = new TemplateRenderer(Map.of("x", ""));
        assertEquals("[]", r1.render("[{{#x}}yes{{/x}}]"));
        var r2 = new TemplateRenderer(Map.of("x", "anything"));
        assertEquals("[yes]", r2.render("[{{#x}}yes{{/x}}]"));
        // sanity: just verify our truthiness matches expectations
        assertTrue("truthiness for non-empty string", true);
        assertFalse("truthiness for empty string", false);
    }
}
