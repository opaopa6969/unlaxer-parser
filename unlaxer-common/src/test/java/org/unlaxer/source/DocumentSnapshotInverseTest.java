package org.unlaxer.source;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.unlaxer.source.DocumentSnapshot.Position;

public class DocumentSnapshotInverseTest {
    private static Path fixture() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path file = directory.resolve("docs/fixtures/source-maps/inverse-positions.tsv");
            if (Files.isRegularFile(file)) { return file; }
            directory = directory.getParent();
        }
        throw new AssertionError("fixture missing");
    }

    @Test public void sharedScalarBoundariesAndInvalidInteriors() throws Exception {
        for (String row : Files.readAllLines(fixture())) {
            if (row.startsWith("#")) { continue; }
            String[] fields = row.split("\t", -1);
            String text = fields[1].replace("\\r", "\r").replace("\\n", "\n");
            DocumentSnapshot snapshot = new DocumentSnapshot(fields[0], 1, text);
            Map<Integer, Integer> utf16 = new HashMap<>();
            Map<Integer, Integer> utf8 = new HashMap<>();
            Map<Position, Integer> lsp = new HashMap<>();
            int maxLine = 0;
            for (String boundary : fields[2].split(",")) {
                String[] values = boundary.split(":");
                int point = Integer.parseInt(values[0]);
                int units = Integer.parseInt(values[1]);
                int bytes = Integer.parseInt(values[2]);
                int line = Integer.parseInt(values[3]);
                int column = Integer.parseInt(values[4]);
                utf16.put(units, point);
                utf8.put(bytes, point);
                assertEquals(fields[0], units, snapshot.utf16(point));
                assertEquals(fields[0], bytes, snapshot.utf8(point));
                if (line < 0) {
                    assertThrows(fields[0], IllegalArgumentException.class, () -> snapshot.lsp(point));
                } else {
                    Position position = new Position(line, column);
                    lsp.put(position, point);
                    assertEquals(fields[0], position, snapshot.lsp(point));
                    maxLine = Math.max(maxLine, line);
                }
            }
            int maxUnits = utf16.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
            int maxBytes = utf8.keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
            for (int offset = -1; offset <= maxUnits + 1; offset++) {
                int candidate = offset;
                if (utf16.containsKey(offset)) { assertEquals(fields[0], (int) utf16.get(offset), snapshot.fromUtf16(offset)); }
                else { assertThrows(fields[0], IllegalArgumentException.class, () -> snapshot.fromUtf16(candidate)); }
            }
            for (int offset = -1; offset <= maxBytes + 1; offset++) {
                int candidate = offset;
                if (utf8.containsKey(offset)) { assertEquals(fields[0], (int) utf8.get(offset), snapshot.fromUtf8(offset)); }
                else { assertThrows(fields[0], IllegalArgumentException.class, () -> snapshot.fromUtf8(candidate)); }
            }
            for (int line = 0; line <= maxLine + 1; line++) {
                for (int column = 0; column <= maxUnits + 1; column++) {
                    Position position = new Position(line, column);
                    if (lsp.containsKey(position)) { assertEquals(fields[0], (int) lsp.get(position), snapshot.fromLsp(position)); }
                    else { assertThrows(fields[0], IllegalArgumentException.class, () -> snapshot.fromLsp(position)); }
                }
            }
            assertThrows(IllegalArgumentException.class, () -> snapshot.fromUtf8(Integer.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> snapshot.fromUtf16(Integer.MAX_VALUE));
            assertThrows(IllegalArgumentException.class, () -> snapshot.fromLsp(new Position(Integer.MAX_VALUE, Integer.MAX_VALUE)));
            assertThrows(IllegalArgumentException.class, () -> snapshot.fromLsp(null));
        }
    }

    @Test public void ordinaryDocumentEndAndInvalidOffsets() {
        // Large enough to exercise the former prefix-rescan path without timing assertions.
        DocumentSnapshot snapshot = new DocumentSnapshot("large", 1, "日😀\r\n".repeat(20_000));
        assertEquals(80_000, snapshot.fromUtf8(180_000));
        assertEquals(80_000, snapshot.fromUtf16(100_000));
        assertEquals(80_000, snapshot.fromLsp(new Position(20_000, 0)));
        assertThrows(IllegalArgumentException.class, () -> snapshot.fromUtf8(179_996));
        assertThrows(IllegalArgumentException.class, () -> snapshot.fromUtf16(99_997));
        assertThrows(IllegalArgumentException.class, () -> snapshot.fromLsp(new Position(20_000, 1)));
    }
}
