package org.unlaxer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Tests for ParserCursor position bookkeeping (issue #37: the Index overload
 * of addMatchedPosition recursed into itself instead of converting to a
 * CodePointOffset).
 */
public class ParserCursorTest {

    @Test
    public void addMatchedPositionWithIndexAdvancesMatchedCursor() {
        ParserCursor cursor = new ParserCursor(StringSource.createRootSource("abcdef"));

        cursor.addMatchedPosition(new Index(2));

        assertEquals(2, cursor.getPosition(TokenKind.matchOnly).value());
        assertEquals(0, cursor.getPosition(TokenKind.consumed).value());
    }

    @Test
    public void addMatchedPositionWithOffsetAndIndexAgree() {
        ParserCursor byOffset = new ParserCursor(StringSource.createRootSource("abcdef"));
        ParserCursor byIndex = new ParserCursor(StringSource.createRootSource("abcdef"));

        byOffset.addMatchedPosition(new CodePointOffset(3));
        byIndex.addMatchedPosition(new Index(3));

        assertEquals(
            byOffset.getPosition(TokenKind.matchOnly).value(),
            byIndex.getPosition(TokenKind.matchOnly).value()
        );
    }
}
