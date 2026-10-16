package org.unlaxer;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.unlaxer.source.DocumentSnapshot;

public class PositionResolverBoundaryTest {
    private Path fixture() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isDirectory(directory.resolve("docs/fixtures/source-maps"))) {
            directory = directory.getParent();
        }
        assertNotNull("repository fixtures", directory);
        return directory.resolve("docs/fixtures/source-maps/cursor-positions.tsv");
    }

    private String decode(String value) {
        return value.replace("\\r", "\r").replace("\\n", "\n");
    }

    @Test public void sharedCursorAndSnapshotBoundaries() throws Exception {
        for (String row : Files.readAllLines(fixture())) {
            if (row.startsWith("#")) continue;
            String[] fields = row.split("\t", -1);
            String text = decode(fields[1]);
            StringSource source = StringSource.createRootSource(text);
            DocumentSnapshot snapshot = new DocumentSnapshot("fixture", 1, text);
            int point = Integer.parseInt(fields[2]);
            int utf16 = Integer.parseInt(fields[3]);
            CodePointIndex cp = new CodePointIndex(point);
            String label = fields[0] + " cp=" + point;
            assertEquals(label, text, source.sourceAsString());
            assertEquals(label, text, source.subString(CodePointIndex.ZERO, source.codePointLength()));
            assertEquals(label, text, snapshot.slice(new DocumentSnapshot.Span(0, snapshot.length())));
            assertEquals(label, utf16, source.toStringIndex(cp).value());
            assertEquals(label, utf16, source.stringIndexInRootFrom(cp).value());
            assertEquals(label, point, source.toCodePointIndex(new StringIndex(utf16)).value());
            assertEquals(label, Integer.parseInt(fields[4]), source.lineNumberFrom(cp).value());
            assertEquals(label, Integer.parseInt(fields[5]), source.codePointIndexInLineFrom(cp).value());
            assertEquals(label, utf16, snapshot.utf16(point));
            assertEquals(label, point, snapshot.fromUtf16(utf16));
            int utf8 = Integer.parseInt(fields[8]);
            assertEquals(label, utf8, snapshot.utf8(point));
            assertEquals(label, point, snapshot.fromUtf8(utf8));
            if (fields[6].equals("-1")) {
                assertThrows(label, IllegalArgumentException.class, () -> snapshot.lsp(point));
            } else {
                var position = new DocumentSnapshot.Position(Integer.parseInt(fields[6]), Integer.parseInt(fields[7]));
                assertEquals(label, position, snapshot.lsp(point));
                assertEquals(label, point, snapshot.fromLsp(position));
            }
            if (point < source.codePointLength().value() && Character.charCount(text.codePointAt(utf16)) == 2) {
                assertNull(label, source.toCodePointIndex(new StringIndex(utf16 + 1)));
                assertThrows(label, IllegalArgumentException.class, () -> snapshot.fromUtf16(utf16 + 1));
            }
            if (point > 0) {
                assertEquals(label, text.codePointBefore(utf16), source.codePointBefore(cp).value());
            }
        }
    }

    @Test public void lineSlicesKeepFinalSingleCharacterAndReconstructInput() {
        for (String text : List.of("", "a", "abc", "a\nb", "a\rb", "a\r\nb", "a\r\n", "\r\n\r\nx", "😀\nx")) {
            StringSource source = StringSource.createRootSource(text);
            List<String> expected = switch (text) {
                case "" -> List.of();
                case "a", "abc", "a\r\n" -> List.of(text);
                case "a\nb" -> List.of("a\n", "b");
                case "a\rb" -> List.of("a\r", "b");
                case "a\r\nb" -> List.of("a\r\n", "b");
                case "\r\n\r\nx" -> List.of("\r\n", "\r\n", "x");
                default -> List.of("😀\n", "x");
            };
            var actual = source.lines(source).map(Source::sourceAsString).toList();
            assertEquals(text, expected, actual);
            assertEquals(text, expected.size(), source.lineSize().value());
            assertEquals(text, text, String.join("", actual));
        }
    }

    @Test public void resolverKeepsInvalidAndSurrogateInteriorPositionsAbsent() {
        StringSource source = StringSource.createRootSource("😀");
        assertNull(source.stringIndexInRootFrom(new CodePointIndex(-1)));
        assertNull(source.stringIndexInRootFrom(new CodePointIndex(2)));
        assertNull(source.toCodePointIndex(new StringIndex(1)));
        assertNull(source.toCodePointIndex(new StringIndex(3)));
        assertThrows(NullPointerException.class, () -> source.toStringIndex(new CodePointIndex(-1)));
        assertThrows(NullPointerException.class, () -> source.toStringIndex(new CodePointIndex(2)));
    }
}
