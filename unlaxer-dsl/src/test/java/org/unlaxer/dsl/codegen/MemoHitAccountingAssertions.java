package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

/** #827: memo accounting may differ; parsing observations must not depend on memo policy. */
final class MemoHitAccountingAssertions {
    private MemoHitAccountingAssertions() {}

    static void assertMemoTransparency(String label, List<String> rows) {
        var disabled = new LinkedHashMap<String, String[]>();
        int compared = 0;
        for (String row : rows) {
            String[] cells = row.split("\t");
            assertEquals(label + "/columns", 17, cells.length);
            String key = String.join("\t", cells[0], cells[1], cells[2], cells[4]);
            if (cells[3].equals("0")) {
                assertEquals(key + "/OFF failure hits", "0", cells[10]);
                assertEquals(key + "/OFF success hits", "0", cells[11]);
                // Rust emits ordinary/mapping and Chain controls as consecutive full graphs.
                disabled.put(key, cells);
            } else {
                assertEquals(key + "/memo policy", "1", cells[3]);
                String[] off = disabled.remove(key);
                assertNotNull(label + "/missing OFF observation " + key, off);
                assertArrayEquals(label + "/memo changes result/cursors/reset/diagnostic " + key,
                        Arrays.copyOfRange(off, 5, 10), Arrays.copyOfRange(cells, 5, 10));
                assertArrayEquals(label + "/memo changes follower " + key,
                        Arrays.copyOfRange(off, 12, 17), Arrays.copyOfRange(cells, 12, 17));
                compared++;
            }
        }
        assertTrue(label + "/no OFF/SAFE_FAILURES pairs", compared > 0);
        assertTrue(label + "/unpaired OFF observations", disabled.isEmpty());
    }
}
