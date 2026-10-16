package org.unlaxer.dsl.runtime;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.Test;
import org.unlaxer.CodePointLength;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WildCardCharacterParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.source.DocumentSnapshot;

public class RegexTokenParserTest {
    private static final class Regex extends RegexTokenParser {
        Regex(String pattern) { super(pattern); }
    }

    @Test public void sharedUnicodeTokensPreserveTextAndCodePointConsumption() throws Exception {
        for (String row : Files.readAllLines(Path.of("../spec-corpus/unicode-tokens/cases.tsv"))) {
            if (row.startsWith("#")) continue;
            String[] fields = row.split("\t");
            String text = fields[1];
            int length = Integer.parseInt(fields[2]);
            var snapshot = new DocumentSnapshot("fixture", 1, text);
            assertEquals(fields[0], Integer.parseInt(fields[3]), snapshot.utf16(length));
            assertEquals(fields[0], Integer.parseInt(fields[4]), snapshot.utf8(length));
            for (Parser parser : new Parser[]{new Regex(Pattern.quote(text)), new WordParser(text)}) {
                try (var context = new ParseContext(StringSource.createRootSource(text + "!"))) {
                    var parsed = parser.parse(context);
                    assertTrue(fields[0], parsed.isSucceeded());
                    assertEquals(fields[0], text, parsed.getRootToken().source.sourceAsString());
                    assertEquals(fields[0], length, context.getConsumedPosition().value());
                    assertTrue(new WordParser("!").parse(context).isSucceeded());
                    assertTrue(context.allConsumed());
                }
            }
            for (Parser parser : new Parser[]{new Regex("(?s)."), new WildCardCharacterParser()}) {
                try (var context = new ParseContext(StringSource.createRootSource(text))) {
                    var first = parser.parse(context);
                    assertTrue(fields[0], first.isSucceeded());
                    assertEquals(fields[0], fields[5], first.getRootToken().source.sourceAsString());
                    StringBuilder rebuilt = new StringBuilder(fields[5]);
                    for (int point = 1; point < length; point++) {
                        var parsed = parser.parse(context);
                        assertTrue(fields[0] + " cp=" + point, parsed.isSucceeded());
                        rebuilt.append(parsed.getRootToken().source.sourceAsString());
                        assertEquals(fields[0], point + 1, context.getConsumedPosition().value());
                    }
                    assertEquals(fields[0], text, rebuilt.toString());
                    assertTrue(context.allConsumed());
                    assertFalse(parser.parse(context).isSucceeded());
                }
            }
        }
    }

    @Test public void matchingStartsAtTheRequestedConsumedOrLookaheadCursor() {
        for (TokenKind kind : new TokenKind[]{TokenKind.consumed, TokenKind.matchOnly}) {
            try (var context = new ParseContext(StringSource.createRootSource("😀x!"))) {
                if (kind.isConsumed()) context.consume(new CodePointLength(1));
                else context.matchOnly(new CodePointLength(1));
                var parsed = new Regex("^x").parse(context, kind, false);
                assertTrue(kind.toString(), parsed.isSucceeded());
                assertEquals("x", parsed.getRootToken().source.sourceAsString());
                assertEquals(1, parsed.getRootToken().source.offsetFromRoot().value());
                assertEquals(2, context.getPosition(kind).value());
                assertEquals(kind.isConsumed() ? 2 : 0, context.getConsumedPosition().value());
            }
        }
    }

    @Test public void unmatchedInversionConsumesExactlyOneScalarAndNeverInventsAMatchLength() {
        try (var context = new ParseContext(StringSource.createRootSource("😀x"))) {
            var parsed = new Regex("a+").parse(context, TokenKind.consumed, true);
            assertTrue(parsed.isSucceeded());
            assertEquals("😀", parsed.getRootToken().source.sourceAsString());
            assertEquals(1, context.getConsumedPosition().value());
            assertTrue(new Regex("x").parse(context).isSucceeded());
            assertTrue(context.allConsumed());
        }
        try (var context = new ParseContext(StringSource.createRootSource("😀x"))) {
            assertFalse(new Regex("😀").parse(context, TokenKind.consumed, true).isSucceeded());
            assertEquals(0, context.getConsumedPosition().value());
            assertTrue(new Regex("😀").parse(context).isSucceeded());
        }
    }

    @Test public void invertedLookaheadUsesItsOwnCursorWithoutConsuming() {
        try (var context = new ParseContext(StringSource.createRootSource("p😀x"))) {
            context.matchOnly(new CodePointLength(1));
            var parsed = new Regex("a+").parse(context, TokenKind.matchOnly, true);
            assertTrue(parsed.isSucceeded());
            assertEquals("😀", parsed.getRootToken().source.sourceAsString());
            assertEquals(1, parsed.getRootToken().source.offsetFromRoot().value());
            assertEquals(0, context.getConsumedPosition().value());
            assertEquals(2, context.getMatchedPosition().value());
            assertTrue(new Regex("x").parse(context, TokenKind.matchOnly, false).isSucceeded());
            assertEquals(0, context.getConsumedPosition().value());
            assertEquals(3, context.getMatchedPosition().value());
        }
    }

    @Test public void failureZeroWidthAndEofKeepBothCursorsUnchanged() {
        for (String input : new String[]{"", "😀"}) {
            for (String pattern : new String[]{"a+", "", "(?=😀)"}) {
                for (boolean invert : new boolean[]{false, true}) {
                    if (invert && !input.isEmpty() && pattern.equals("a+")) continue;
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        assertFalse(input + ": " + pattern + ": " + invert,
                            new Regex(pattern).parse(context, TokenKind.consumed, invert).isSucceeded());
                        assertEquals(0, context.getConsumedPosition().value());
                        assertEquals(0, context.getMatchedPosition().value());
                    }
                }
            }
        }
    }
}
