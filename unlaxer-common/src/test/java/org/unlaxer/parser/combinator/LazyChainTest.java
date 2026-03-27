package org.unlaxer.parser.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for LazyChain (sequence combinator).
 */
public class LazyChainTest extends ParserTestBase {

    // --- Helper: inline LazyChain from Parsers ---

    static LazyChain lazyChainOf(Parser... children) {
        Parsers parsers = new Parsers(children);
        return new LazyChain() {
            private static final long serialVersionUID = 1L;
            @Override
            public Parsers getLazyParsers() {
                return parsers;
            }
        };
    }

    @Test
    public void testSimpleSequence() {
        // "hello" followed by "world" matches "helloworld"
        Parser parser = lazyChainOf(new WordParser("hello"), new WordParser("world"));
        testAllMatch(parser, "helloworld");
    }

    @Test
    public void testSequenceFailsIfSecondChildFails() {
        Parser parser = lazyChainOf(new WordParser("hello"), new WordParser("world"));
        testUnMatch(parser, "helloxyz");
    }

    @Test
    public void testSequenceFailsIfFirstChildFails() {
        Parser parser = lazyChainOf(new WordParser("hello"), new WordParser("world"));
        testUnMatch(parser, "xyzworld");
    }

    @Test
    public void testPartialMatchDoesNotSucceedAsAllMatch() {
        // "hello" matches but "world" is missing — partial
        Parser parser = lazyChainOf(new WordParser("hello"), new WordParser("world"));
        testUnMatch(parser, "hello");
    }

    @Test
    public void testEmptyInputFails() {
        Parser parser = lazyChainOf(new WordParser("hello"), new WordParser("world"));
        testUnMatch(parser, "");
    }

    @Test
    public void testThreeElementChain() {
        Parser parser = lazyChainOf(
            new WordParser("a"),
            new WordParser("b"),
            new WordParser("c")
        );
        testAllMatch(parser, "abc");
    }

    @Test
    public void testThreeElementChainFailsOnMissingMiddle() {
        Parser parser = lazyChainOf(
            new WordParser("a"),
            new WordParser("b"),
            new WordParser("c")
        );
        testUnMatch(parser, "ac");
    }

    @Test
    public void testChainWithRepetition() {
        // Chain of OneOrMore(digit) + WordParser("end")
        Parser parser = lazyChainOf(
            new OneOrMore(DigitParser.class),
            new WordParser("end")
        );
        testAllMatch(parser, "123end");
        testUnMatch(parser, "end");
        testUnMatch(parser, "123");
    }
}
