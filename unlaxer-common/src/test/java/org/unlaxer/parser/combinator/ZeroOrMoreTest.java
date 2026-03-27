package org.unlaxer.parser.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for ZeroOrMore repetition combinator.
 */
public class ZeroOrMoreTest extends ParserTestBase {

    @Test
    public void testZeroOccurrencesSucceeds() {
        // ZeroOrMore should succeed even when the inner parser matches nothing.
        Parser parser = new ZeroOrMore(new MappedSingleCharacterParser('x'));
        testAllMatch(parser, "");
    }

    @Test
    public void testOneOccurrence() {
        Parser parser = new ZeroOrMore(new MappedSingleCharacterParser('x'));
        testAllMatch(parser, "x");
    }

    @Test
    public void testMultipleOccurrences() {
        Parser parser = new ZeroOrMore(new MappedSingleCharacterParser('x'));
        testAllMatch(parser, "xxx");
    }

    @Test
    public void testMixedContentAfterRepetition() {
        // "xxx" is consumed, "yz" remains
        Parser parser = new ZeroOrMore(new MappedSingleCharacterParser('x'));
        testPartialMatch(parser, "xxxyz", "xxx");
    }

    @Test
    public void testZeroOrMoreDigits() {
        Parser parser = new ZeroOrMore(DigitParser.class);
        testAllMatch(parser, "");
        testAllMatch(parser, "5");
        testAllMatch(parser, "123");
        testPartialMatch(parser, "12abc", "12");
    }

    @Test
    public void testZeroOrMoreInChain() {
        // WordParser("a") followed by ZeroOrMore("b") followed by WordParser("c")
        Parser parser = new Chain(
            new WordParser("a"),
            new ZeroOrMore(new MappedSingleCharacterParser('b')),
            new WordParser("c")
        );
        testAllMatch(parser, "ac");
        testAllMatch(parser, "abc");
        testAllMatch(parser, "abbc");
    }
}
