package org.unlaxer.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.Source;
import org.unlaxer.StringSource;

/**
 * Tests for ParseContext.peek overloads (issue #32: the (from, to) overload
 * recursed into itself instead of delegating to the source).
 */
public class ParseContextPeekTest {

    @Test
    public void peekWithStartAndEndDelegatesToSource() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext context = new ParseContext(source)) {
            Source peeked = context.peek(CodePointIndex.of(1), CodePointIndex.of(4));
            assertEquals("bcd", peeked.sourceAsString());
        }
    }

    @Test
    public void peekWithStartAndEndMatchesLengthOverload() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext context = new ParseContext(source)) {
            Source byRange = context.peek(CodePointIndex.of(0), CodePointIndex.of(3));
            Source byLength = context.peek(CodePointIndex.of(0), new CodePointLength(3));
            assertEquals(byLength.sourceAsString(), byRange.sourceAsString());
        }
    }

    @Test
    public void peekWithEmptyRangeReturnsEmptySource() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext context = new ParseContext(source)) {
            Source peeked = context.peek(CodePointIndex.of(2), CodePointIndex.of(2));
            assertFalse(peeked.isPresent());
        }
    }
}
