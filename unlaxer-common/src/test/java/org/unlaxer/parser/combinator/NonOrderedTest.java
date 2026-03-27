package org.unlaxer.parser.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;

/**
 * Tests for NonOrdered (interleave) combinator.
 * All elements must appear exactly once, but in any order.
 */
public class NonOrderedTest extends ParserTestBase {

    @Test
    public void testAllElementsInOrder() {
        Parser parser = new NonOrdered(
            new MappedSingleCharacterParser('a'),
            new MappedSingleCharacterParser('b'),
            new MappedSingleCharacterParser('c')
        );
        testAllMatch(parser, "abc");
    }

    @Test
    public void testAllElementsInReverseOrder() {
        Parser parser = new NonOrdered(
            new MappedSingleCharacterParser('a'),
            new MappedSingleCharacterParser('b'),
            new MappedSingleCharacterParser('c')
        );
        testAllMatch(parser, "cba");
    }

    @Test
    public void testAllElementsInDifferentOrder() {
        Parser parser = new NonOrdered(
            new MappedSingleCharacterParser('a'),
            new MappedSingleCharacterParser('b'),
            new MappedSingleCharacterParser('c')
        );
        testAllMatch(parser, "bac");
        testAllMatch(parser, "bca");
        testAllMatch(parser, "acb");
        testAllMatch(parser, "cab");
    }

    @Test
    public void testMissingElementFails() {
        Parser parser = new NonOrdered(
            new MappedSingleCharacterParser('a'),
            new MappedSingleCharacterParser('b'),
            new MappedSingleCharacterParser('c')
        );
        testUnMatch(parser, "ab");
        testUnMatch(parser, "a");
        testUnMatch(parser, "");
    }

    @Test
    public void testDuplicateElementFails() {
        Parser parser = new NonOrdered(
            new MappedSingleCharacterParser('a'),
            new MappedSingleCharacterParser('b'),
            new MappedSingleCharacterParser('c')
        );
        // "aab" has 'a' twice — fails because after consuming 'a', 'a' again
        // doesn't match 'b' or 'c'
        testUnMatch(parser, "aab");
    }

    @Test
    public void testTwoElements() {
        Parser parser = new NonOrdered(
            new MappedSingleCharacterParser('x'),
            new MappedSingleCharacterParser('y')
        );
        testAllMatch(parser, "xy");
        testAllMatch(parser, "yx");
        testUnMatch(parser, "x");
        testUnMatch(parser, "xx");
    }
}
