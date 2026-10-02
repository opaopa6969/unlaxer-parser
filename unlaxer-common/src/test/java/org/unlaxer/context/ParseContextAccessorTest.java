package org.unlaxer.context;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.unlaxer.CodePointLength;
import org.unlaxer.StringSource;

/** Java half of the UBNF format-2 read-only adapter accessor contract. */
public class ParseContextAccessorTest {
    @Test
    public void readOnlyFormat2AccessorsUseUnicodeCodePointOffsets() {
        try (ParseContext context = new ParseContext(StringSource.createRootSource("a😀bc"))) {
            assertEquals("a😀bc", context.sourceText());
            assertEquals("a😀bc", context.remainingText());
            assertEquals(0, context.position());
            assertEquals(0, context.matchedPosition());
            context.consume(new CodePointLength(2));
            assertEquals("bc", context.remainingText());
            assertEquals(2, context.position());
            assertEquals(2, context.matchedPosition());
        }
    }
}
