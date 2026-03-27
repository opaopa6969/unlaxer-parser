package org.unlaxer.parser.combinator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.ParserTestBase;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for Not (negative lookahead) combinator.
 */
public class NotTest extends ParserTestBase {

    @Test
    public void testNotSucceedsWhenChildFails() {
        // Not("hello") succeeds when input doesn't start with "hello"
        Parser parser = new Not(new WordParser("hello"));
        testSucceededOnly(parser, "world");
    }

    @Test
    public void testNotFailsWhenChildSucceeds() {
        // Not("hello") fails when input starts with "hello"
        Parser parser = new Not(new WordParser("hello"));
        testUnMatch(parser, "hello");
    }

    @Test
    public void testNotDoesNotConsumeInput() {
        // Not is a lookahead: it should not consume any input.
        // We verify by chaining: Not("x") then digits should still parse digits.
        Parser parser = new Chain(
            new Not(new WordParser("x")),
            new OneOrMore(DigitParser.class)
        );
        testAllMatch(parser, "123");
    }

    @Test
    public void testNotBlocksChainWhenMatched() {
        // Not("1") blocks parsing of "123" because "1" matches → Not fails
        Parser parser = new Chain(
            new Not(new WordParser("1")),
            new OneOrMore(DigitParser.class)
        );
        testUnMatch(parser, "123");
    }

    @Test
    public void testNotOnEmptyInput() {
        // Not("hello") on empty input: "hello" fails → Not succeeds
        Parser parser = new Not(new WordParser("hello"));
        testSucceededOnly(parser, "");
    }

    @Test
    public void testNotWithDigit() {
        // Not(digit) succeeds on letters
        Parser parser = new Not(new OneOrMore(DigitParser.class));
        testSucceededOnly(parser, "abc");
    }

    @Test
    public void testNotWithDigitFailsOnNumber() {
        Parser parser = new Not(new OneOrMore(DigitParser.class));
        testUnMatch(parser, "123");
    }
}
