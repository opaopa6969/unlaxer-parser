package org.unlaxer.parser.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for OneOrMore repetition combinator.
 */
public class OneOrMoreTest extends ParserTestBase {

    @Test
    public void testZeroOccurrencesFails() {
        // Unlike ZeroOrMore, OneOrMore MUST have at least one match.
        Parser parser = new OneOrMore(new MappedSingleCharacterParser('x'));
        testUnMatch(parser, "");
    }

    @Test
    public void testOneOccurrenceSucceeds() {
        Parser parser = new OneOrMore(new MappedSingleCharacterParser('x'));
        testAllMatch(parser, "x");
    }

    @Test
    public void testMultipleOccurrences() {
        Parser parser = new OneOrMore(new MappedSingleCharacterParser('x'));
        testAllMatch(parser, "xxx");
    }

    @Test
    public void testNonMatchingInputFails() {
        Parser parser = new OneOrMore(new MappedSingleCharacterParser('x'));
        testUnMatch(parser, "abc");
    }

    @Test
    public void testPartialConsumption() {
        Parser parser = new OneOrMore(DigitParser.class);
        testPartialMatch(parser, "123abc", "123");
    }

    @Test
    public void testOneOrMoreDigits() {
        Parser parser = new OneOrMore(DigitParser.class);
        testUnMatch(parser, "");
        testAllMatch(parser, "0");
        testAllMatch(parser, "42");
        testAllMatch(parser, "999");
    }

    @Test
    public void testOneOrMoreInChain() {
        // OneOrMore(digit) then word "end"
        Parser parser = new Chain(
            new OneOrMore(DigitParser.class),
            new org.unlaxer.parser.elementary.WordParser("end")
        );
        testAllMatch(parser, "1end");
        testAllMatch(parser, "123end");
        testUnMatch(parser, "end");
    }
}
