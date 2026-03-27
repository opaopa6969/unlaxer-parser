package org.unlaxer.scope;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.Test;
import org.unlaxer.Name;
import org.unlaxer.StringSource;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParserContextScopeTree;
import org.unlaxer.parser.GlobalScopeTree;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for the scope tree system: GlobalScopeTree and ParserContextScopeTree.
 * ParseContext implements both interfaces, providing global and parser-scoped storage.
 */
public class ScopeTreeTest {

    // --- GlobalScopeTree tests ---

    @Test
    public void globalScopePutAndGet() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Name key = Name.of("myVar");
            ctx.put(key, "hello");

            Optional<Object> result = ctx.get(key);
            assertTrue("should find value", result.isPresent());
            assertEquals("hello", result.get());
        }
    }

    @Test
    public void globalScopeTypedGet() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Name key = Name.of("counter");
            ctx.put(key, Integer.valueOf(42));

            Optional<Integer> result = ctx.get(key, Integer.class);
            assertTrue("should find typed value", result.isPresent());
            assertEquals(Integer.valueOf(42), result.get());
        }
    }

    @Test
    public void globalScopeRemove() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Name key = Name.of("temp");
            ctx.put(key, "temporary");
            assertTrue("should contain key", ctx.containsKey(key));

            ctx.remove(key);
            assertFalse("should no longer contain key", ctx.containsKey(key));
        }
    }

    @Test
    public void globalScopeContainsValue() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Name key = Name.of("val");
            ctx.put(key, "findMe");

            assertTrue("should contain value", ctx.containsValue("findMe"));
            assertFalse("should not contain absent value", ctx.containsValue("notHere"));
        }
    }

    @Test
    public void globalScopeOverwrite() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Name key = Name.of("dup");
            ctx.put(key, "first");
            ctx.put(key, "second");

            assertEquals("second", ctx.get(key).get());
        }
    }

    // --- ParserContextScopeTree tests ---

    @Test
    public void parserScopePutAndGet() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Parser parser = new WordParser("test");
            Name key = Name.of("binding");

            ctx.put(parser, key, "value1");
            Optional<Object> result = ctx.get(parser, key);
            assertTrue("should find parser-scoped value", result.isPresent());
            assertEquals("value1", result.get());
        }
    }

    @Test
    public void parserScopeNameless() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Parser parser = new WordParser("test");

            ctx.put(parser, "default-value");
            Optional<Object> result = ctx.get(parser);
            assertTrue("should find nameless value", result.isPresent());
            assertEquals("default-value", result.get());
        }
    }

    @Test
    public void parserScopeIsolation() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Parser p1 = new WordParser("parser1");
            Parser p2 = new WordParser("parser2");
            Name key = Name.of("shared-name");

            ctx.put(p1, key, "from-p1");
            ctx.put(p2, key, "from-p2");

            assertEquals("from-p1", ctx.get(p1, key).get());
            assertEquals("from-p2", ctx.get(p2, key).get());
        }
    }

    @Test
    public void parserScopeRemove() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Parser parser = new WordParser("test");
            Name key = Name.of("removeMe");

            ctx.put(parser, key, "data");
            assertTrue("should contain key", ctx.containsKey(parser, key));

            ctx.remove(parser, key);
            assertFalse("should not contain key after remove", ctx.containsKey(parser, key));
        }
    }

    @Test
    public void parserScopeRemoveAll() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Parser parser = new WordParser("test");
            Name k1 = Name.of("k1");
            Name k2 = Name.of("k2");

            ctx.put(parser, k1, "v1");
            ctx.put(parser, k2, "v2");
            assertTrue("should contain parser", ctx.containsParser(parser));

            ctx.removeAll(parser);
            assertFalse("should not contain parser after removeAll", ctx.containsParser(parser));
        }
    }

    @Test
    public void parserScopeContainsValue() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Parser parser = new WordParser("test");
            Name key = Name.of("cv");

            ctx.put(parser, key, "target");
            assertTrue("should contain value", ctx.containsValue(parser, "target"));
            assertFalse("should not contain absent value", ctx.containsValue(parser, "missing"));
        }
    }

    @Test
    public void parserScopeTypedGet() {
        StringSource source = StringSource.createRootSource("x");
        try (ParseContext ctx = new ParseContext(source)) {
            Parser parser = new WordParser("test");
            Name key = Name.of("typed");

            ctx.put(parser, key, Double.valueOf(3.14));
            Optional<Double> result = ctx.get(parser, key, Double.class);
            assertTrue("should find typed value", result.isPresent());
            assertEquals(Double.valueOf(3.14), result.get());
        }
    }
}
