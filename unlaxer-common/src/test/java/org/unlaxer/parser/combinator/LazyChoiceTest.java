package org.unlaxer.parser.combinator;

import org.junit.Test;
import org.unlaxer.ParserTestBase;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.elementary.WordParser;

/**
 * Tests for LazyChoice (ordered choice / PEG alternation).
 */
public class LazyChoiceTest extends ParserTestBase {

    static LazyChoice lazyChoiceOf(Parser... children) {
        Parsers parsers = new Parsers(children);
        return new LazyChoice() {
            private static final long serialVersionUID = 1L;
            @Override
            public Parsers getLazyParsers() {
                return parsers;
            }
        };
    }

    @Test
    public void testFirstAlternativeMatches() {
        Parser parser = lazyChoiceOf(new WordParser("hello"), new WordParser("world"));
        testAllMatch(parser, "hello");
    }

    @Test
    public void testSecondAlternativeMatchesWhenFirstFails() {
        Parser parser = lazyChoiceOf(new WordParser("hello"), new WordParser("world"));
        testAllMatch(parser, "world");
    }

    @Test
    public void testNoAlternativeMatchesGivesFailure() {
        Parser parser = lazyChoiceOf(new WordParser("hello"), new WordParser("world"));
        testUnMatch(parser, "xyz");
    }

    @Test
    public void testOrderMatters_PEGSemantics() {
        // PEG ordered choice: first match wins.
        // "ab" is tried first and matches, so "abc" is NOT fully consumed.
        Parser parser = lazyChoiceOf(new WordParser("ab"), new WordParser("abc"));
        // "ab" matches as a partial match of "abc"
        testPartialMatch(parser, "abc", "ab");
    }

    @Test
    public void testLongestMatchNotGuaranteed() {
        // In PEG, "a" matches first even though "ab" would be longer.
        Parser parser = lazyChoiceOf(new WordParser("a"), new WordParser("ab"));
        testPartialMatch(parser, "ab", "a");
    }

    @Test
    public void testEmptyInputFailsAllAlternatives() {
        Parser parser = lazyChoiceOf(new WordParser("hello"), new WordParser("world"));
        testUnMatch(parser, "");
    }

    @Test
    public void testThreeAlternatives() {
        Parser parser = lazyChoiceOf(
            new WordParser("alpha"),
            new WordParser("beta"),
            new WordParser("gamma")
        );
        testAllMatch(parser, "alpha");
        testAllMatch(parser, "beta");
        testAllMatch(parser, "gamma");
        testUnMatch(parser, "delta");
    }
}
