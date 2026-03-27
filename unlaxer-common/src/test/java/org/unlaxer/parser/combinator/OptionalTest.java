package org.unlaxer.parser.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for Optional combinator (zero or one).
 */
public class OptionalTest extends ParserTestBase {

    @Test
    public void testPresentMatches() {
        // Optional("-") with "-" present
        Parser parser = new Chain(
            new Optional(MinusParser.class),
            new OneOrMore(DigitParser.class)
        );
        testAllMatch(parser, "-42");
    }

    @Test
    public void testAbsentSucceedsWithEmptyMatch() {
        // Optional("-") without "-" still succeeds
        Parser parser = new Chain(
            new Optional(MinusParser.class),
            new OneOrMore(DigitParser.class)
        );
        testAllMatch(parser, "42");
    }

    @Test
    public void testOptionalAlone_Present() {
        Parser parser = new Optional(new WordParser("yes"));
        testAllMatch(parser, "yes");
    }

    @Test
    public void testOptionalAlone_Absent() {
        Parser parser = new Optional(new WordParser("yes"));
        testAllMatch(parser, "");
    }

    @Test
    public void testOptionalInMiddleOfChain() {
        // "a" + optional("b") + "c"
        Parser parser = new Chain(
            new WordParser("a"),
            new Optional(new WordParser("b")),
            new WordParser("c")
        );
        testAllMatch(parser, "abc");
        testAllMatch(parser, "ac");
    }

    @Test
    public void testOptionalDoesNotConsumeMoreThanOne() {
        // Optional matches at most one occurrence
        Parser parser = new Chain(
            new Optional(new WordParser("x")),
            new WordParser("x"),
            new WordParser("y")
        );
        // First "x" consumed by Optional, second "x" by WordParser, then "y"
        testAllMatch(parser, "xxy");
    }
}
