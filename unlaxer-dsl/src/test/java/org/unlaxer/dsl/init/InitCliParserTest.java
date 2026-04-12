package org.unlaxer.dsl.init;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class InitCliParserTest {

    @Test
    public void testNameOnly() throws Exception {
        var opts = InitCliParser.parse(new String[] { "mylang" });
        assertEquals("mylang", opts.name());
        assertNull(opts.packageName());
        assertNull(opts.groupId());
        assertNull(opts.extension());
        assertNull(opts.outputDir());
        assertNull(opts.fromGrammar());
        assertFalse("DAP defaults to off", opts.withDap());
        assertFalse(opts.syntaxOnly());
        assertFalse(opts.force());
    }

    @Test
    public void testAllFlags() throws Exception {
        var opts = InitCliParser.parse(new String[] {
            "myLang",
            "--package", "com.acme.mylang",
            "--group-id", "com.acme",
            "--extension", ".ml",
            "--output-dir", "/tmp/out",
            "--from", "g.ubnf",
            "--with-dap",
            "--force"
        });
        assertEquals("myLang", opts.name());
        assertEquals("com.acme.mylang", opts.packageName());
        assertEquals("com.acme", opts.groupId());
        assertEquals(".ml", opts.extension());
        assertEquals("/tmp/out", opts.outputDir());
        assertEquals("g.ubnf", opts.fromGrammar());
        assertTrue(opts.withDap());
        assertTrue(opts.force());
    }

    @Test
    public void testSyntaxOnly() throws Exception {
        var opts = InitCliParser.parse(new String[] { "x", "--syntax-only", "--from", "g.ubnf" });
        assertTrue(opts.syntaxOnly());
        assertEquals("g.ubnf", opts.fromGrammar());
    }

    @Test
    public void testMissingNameFails() {
        try {
            InitCliParser.parse(new String[] {});
            fail("expected InitUsageException");
        } catch (InitCliParser.InitUsageException expected) {
            assertTrue(expected.showUsage());
        }
    }

    @Test
    public void testUnknownOptionFails() {
        try {
            InitCliParser.parse(new String[] { "x", "--bogus" });
            fail("expected InitUsageException");
        } catch (InitCliParser.InitUsageException expected) { /* ok */ }
    }

    @Test
    public void testMissingValueFails() {
        try {
            InitCliParser.parse(new String[] { "x", "--package" });
            fail("expected InitUsageException");
        } catch (InitCliParser.InitUsageException expected) { /* ok */ }
    }

    @Test
    public void testTwoNamesFails() {
        try {
            InitCliParser.parse(new String[] { "a", "b" });
            fail("expected InitUsageException");
        } catch (InitCliParser.InitUsageException expected) { /* ok */ }
    }
}
