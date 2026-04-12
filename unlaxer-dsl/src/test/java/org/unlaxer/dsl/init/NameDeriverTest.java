package org.unlaxer.dsl.init;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class NameDeriverTest {

    @Test
    public void testCamelInput() {
        var nd = NameDeriver.of("myLang");
        assertEquals("mylang", nd.lower());
        assertEquals("MyLang", nd.className());
        assertEquals("MY_LANG", nd.upper());
        assertEquals("org.example.mylang", nd.defaultPackage());
    }

    @Test
    public void testHyphenatedInput() {
        var nd = NameDeriver.of("my-lang");
        assertEquals("mylang", nd.lower());
        assertEquals("MyLang", nd.className());
        assertEquals("MY_LANG", nd.upper());
    }

    @Test
    public void testUnderscoreInput() {
        var nd = NameDeriver.of("my_lang");
        assertEquals("mylang", nd.lower());
        assertEquals("MyLang", nd.className());
    }

    @Test
    public void testSingleWord() {
        var nd = NameDeriver.of("calc");
        assertEquals("calc", nd.lower());
        assertEquals("Calc", nd.className());
        assertEquals("CALC", nd.upper());
    }

    @Test
    public void testRejectsBlank() {
        try {
            NameDeriver.of("");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
        try {
            NameDeriver.of("   ");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void testRejectsLeadingDigit() {
        try {
            NameDeriver.of("9lives");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { /* ok */ }
    }

    @Test
    public void testGroupIdFromPackage() {
        assertEquals("org.example", NameDeriver.groupIdFromPackage("org.example.mylang"));
        assertEquals("com.acme", NameDeriver.groupIdFromPackage("com.acme.foo"));
        // single-segment package: returned as-is
        assertEquals("solo", NameDeriver.groupIdFromPackage("solo"));
    }

    @Test
    public void testPackageToPath() {
        assertEquals("org/example/mylang", NameDeriver.packageToPath("org.example.mylang"));
    }

    @Test
    public void testNormalizeExtension() {
        assertEquals(".foo", NameDeriver.normalizeExtension("foo"));
        assertEquals(".foo", NameDeriver.normalizeExtension(".foo"));
        assertEquals(".tcalc", NameDeriver.normalizeExtension(".tcalc"));
    }
}
