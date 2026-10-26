package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.*;
import org.junit.Test;

public class BoundedQuantifiersTest {
    @Test public void asciiBoundsKeepLeadingZeroesAndOverflowHandling() {
        assertEquals(0, BoundedQuantifiers.bound("0000"));
        assertEquals(1234567890, BoundedQuantifiers.bound("01234567890"));
        assertEquals(Integer.MAX_VALUE, BoundedQuantifiers.bound("2147483647"));
        assertEquals(Integer.MIN_VALUE, BoundedQuantifiers.bound("2147483648"));
        assertEquals(1, BoundedQuantifiers.bound("0".repeat(1000) + "1"));
    }

    @Test public void nonAsciiBoundsCannotBeConvertedToNumbers() {
        for (String bound : new String[] {"１", "２", "٣", "1２", "２1", "/", ":"}) {
            assertThrows(bound, NumberFormatException.class, () -> BoundedQuantifiers.bound(bound));
        }
    }
}
