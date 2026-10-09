package org.unlaxer.dsl.runtime;

import static org.junit.Assert.assertEquals;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Independent bitset oracle from the pinned Unicode data, including every range boundary. */
public class UnicodeXidTest {
    @Test public void tablesMatchUnicodePropertyBoundaries() throws Exception {
        boolean[] starts = new boolean[0x110000];
        boolean[] continues = new boolean[0x110000];
        Set<Integer> boundaries = new HashSet<>();
        for (String line : Files.readAllLines(Path.of("../spec-corpus/xid-identifier/unicode-17.0.0.txt"))) {
            if (line.startsWith("#")) { continue; }
            String[] parts = line.split(";");
            String[] range = parts[0].split("\\.\\.");
            int start = Integer.parseInt(range[0], 16);
            int end = Integer.parseInt(range[range.length - 1], 16);
            boolean[] expected = parts[1].equals("XID_Start") ? starts : continues;
            java.util.Arrays.fill(expected, start, end + 1, true);
            for (int codePoint : new int[] {start - 1, start, end, end + 1}) {
                if (codePoint >= 0 && codePoint <= 0x10ffff) { boundaries.add(codePoint); }
            }
        }
        for (int codePoint : boundaries) {
            String scalar = new String(Character.toChars(codePoint));
            String label = "U+" + Integer.toHexString(codePoint);
            assertEquals(label + " start", starts[codePoint] ? scalar.length() : -1,
                UnicodeXid.identifierEnd(scalar, 0));
            assertEquals(label + " continue", continues[codePoint] ? 1 + scalar.length() : 1,
                UnicodeXid.identifierEnd("A" + scalar, 0));
        }
    }
}
