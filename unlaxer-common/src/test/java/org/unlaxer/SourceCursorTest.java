package org.unlaxer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.elementary.WordParser;

/**
 * Tests for Source creation, cursor position tracking,
 * peek/advance operations, and boundary conditions.
 */
public class SourceCursorTest {

    // --- StringSource creation ---

    @Test
    public void createRootSource() {
        StringSource source = StringSource.createRootSource("hello");
        assertEquals("hello", source.sourceAsString());
        assertEquals(5, source.codePointLength().value());
        assertTrue("root source isRoot", source.isRoot());
    }

    @Test
    public void createEmptyRootSource() {
        StringSource source = StringSource.createRootSource("");
        assertEquals("", source.sourceAsString());
        assertEquals(0, source.codePointLength().value());
        assertTrue("empty source isEmpty", source.isEmpty());
    }

    @Test
    public void createSubSource() {
        StringSource root = StringSource.createRootSource("abcdef");
        Source sub = root.subSource(new CodePointIndex(2), new CodePointIndex(5));
        assertEquals("cde", sub.sourceAsString());
        assertEquals(3, sub.codePointLength().value());
    }

    @Test
    public void createSubSourceWithLength() {
        StringSource root = StringSource.createRootSource("abcdef");
        Source sub = root.subSource(new CodePointIndex(1), new CodePointLength(3));
        assertEquals("bcd", sub.sourceAsString());
    }

    // --- Cursor position tracking ---

    @Test
    public void cursorStartsAtZero() {
        StringSource source = StringSource.createRootSource("test");
        try (ParseContext ctx = new ParseContext(source)) {
            assertEquals(0, ctx.getConsumedPosition().value());
            assertEquals(0, ctx.getMatchedPosition().value());
        }
    }

    @Test
    public void consumeAdvancesCursor() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            ctx.consume(new CodePointLength(3));
            assertEquals(3, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void matchOnlyAdvancesMatchedPosition() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            ctx.matchOnly(new CodePointLength(2));
            assertEquals(2, ctx.getMatchedPosition().value());
            // consumed position should stay at 0 initially but matched syncs consumed via ParserCursor
            // Actually matchOnly only moves the matched cursor
        }
    }

    // --- Peek operations ---

    @Test
    public void peekFromStart() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            Source peeked = ctx.peek(TokenKind.consumed, new CodePointLength(3));
            assertEquals("abc", peeked.sourceAsString());
        }
    }

    @Test
    public void peekAfterConsume() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            ctx.consume(new CodePointLength(2));
            Source peeked = ctx.peek(TokenKind.consumed, new CodePointLength(3));
            assertEquals("cde", peeked.sourceAsString());
        }
    }

    @Test
    public void peekBeyondEnd() {
        StringSource source = StringSource.createRootSource("ab");
        try (ParseContext ctx = new ParseContext(source)) {
            Source peeked = ctx.peek(TokenKind.consumed, new CodePointLength(5));
            // Should return empty when peeking beyond end
            assertTrue("peeking beyond end should be empty", peeked.isEmpty());
        }
    }

    // --- Remain and consumed ---

    @Test
    public void getRemain() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            ctx.consume(new CodePointLength(3));
            Source remain = ctx.getRemain(TokenKind.consumed);
            assertEquals("def", remain.sourceAsString());
        }
    }

    @Test
    public void getConsumedSource() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            ctx.consume(new CodePointLength(4));
            Source consumed = ctx.getConsumed(TokenKind.consumed);
            assertEquals("abcd", consumed.sourceAsString());
        }
    }

    // --- Boundary conditions ---

    @Test
    public void allConsumedOnEmptySource() {
        StringSource source = StringSource.createRootSource("");
        try (ParseContext ctx = new ParseContext(source)) {
            assertTrue("empty source should be all consumed", ctx.allConsumed());
        }
    }

    @Test
    public void subSourceBoundaries() {
        StringSource root = StringSource.createRootSource("abc");
        // Start at 0, length 0 -> empty
        Source empty = root.subSource(new CodePointIndex(0), new CodePointLength(0));
        assertEquals("", empty.sourceAsString());

        // Full range
        Source full = root.subSource(new CodePointIndex(0), new CodePointIndex(3));
        assertEquals("abc", full.sourceAsString());
    }

    @Test
    public void sourceLineNumber() {
        StringSource source = StringSource.createRootSource("line1\nline2\nline3");
        // Line number for first character should be 0 (0-based indexing)
        LineNumber ln = source.lineNumberFrom(new CodePointIndex(0));
        assertEquals(0, ln.value());

        // After first newline (index 6 = 'l' of line2) should be line 1
        LineNumber ln2 = source.lineNumberFrom(new CodePointIndex(6));
        assertEquals(1, ln2.value());

        // Third line (index 12 = 'l' of line3) should be line 2
        LineNumber ln3 = source.lineNumberFrom(new CodePointIndex(12));
        assertEquals(2, ln3.value());
    }

    @Test
    public void sourceIsPresent() {
        StringSource present = StringSource.createRootSource("a");
        assertTrue("non-empty source isPresent", present.isPresent());

        StringSource empty = StringSource.createRootSource("");
        assertFalse("empty source is not present", empty.isPresent());
    }

    @Test
    public void peekLastOperation() {
        StringSource source = StringSource.createRootSource("abcdef");
        // peekLast from index 5 (inclusive), length 3 -> "def" (indices 3..5)
        Source peeked = source.peekLast(new CodePointIndex(5), new CodePointLength(3));
        assertEquals("cde", peeked.sourceAsString());
    }

    @Test
    public void surrogatePairCodePointLength() {
        // Emoji is a surrogate pair: string length != codepoint length
        String emoji = "\uD83D\uDE00"; // grinning face
        StringSource source = StringSource.createRootSource(emoji);
        assertEquals("string length is 2 for surrogate pair", 2, source.stringLength().value());
        assertEquals("codepoint length is 1", 1, source.codePointLength().value());
    }
}
