package org.unlaxer.parser.referencer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.elementary.WordParser;

/** L = {w#w | w in {a,b}*}; acceptance always requires the entire input. */
public class CopyLanguageTest {
    private boolean accepts(String input) {
        Parser capture = new OneOrMore(new Choice(new WordParser("a"), new WordParser("b")));
        // MatchedTokenParser skips empty captures: represent epsilon explicitly.
        Parser parser = new Choice(
            new Chain(capture, new WordParser("#"), new MatchedTokenParser(capture)),
            new WordParser("#"));
        try (ParseContext context = new ParseContext(StringSource.createRootSource(input))) {
            Parsed parsed = parser.parse(context);
            return parsed.isSucceeded()
                && parsed.getConsumed().getSource().sourceAsString().equals(input);
        }
    }

    @Test
    public void exhaustiveShortInputsAgreeWithEqualityOracle() {
        int checked = 0;
        for (int length = 0; length <= 7; length++) {
            int count = (int) Math.pow(3, length);
            for (int encoded = 0; encoded < count; encoded++) {
                int value = encoded;
                StringBuilder input = new StringBuilder();
                for (int i = 0; i < length; i++, value /= 3) input.append("ab#".charAt(value % 3));
                String text = input.toString();
                int separator = text.indexOf('#');
                boolean expected = separator >= 0 && separator == text.lastIndexOf('#')
                    && text.substring(0, separator).equals(text.substring(separator + 1));
                assertEquals(text, expected, accepts(text));
                checked++;
            }
        }
        assertEquals(3280, checked);
    }

    @Test
    public void copyIsNotReverseAndTrailingInputIsRejected() {
        for (String input : new String[]{"#", "ab#ab", "abba#abba", "a".repeat(80) + "#" + "a".repeat(80)}) {
            assertEquals(input, true, accepts(input));
        }
        for (String input : new String[]{"ab#ba", "ab#aba", "ab#ab#", "a", "abc#abc", "a##a"}) {
            assertEquals(input, false, accepts(input));
        }
    }
}
