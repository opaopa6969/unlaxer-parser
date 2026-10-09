package org.unlaxer.source;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.Edit;
import org.unlaxer.source.SourceEdits.*;

public class SourceEditsTest {
    public static final String SOURCE = "#日😀\r\nfoo  \"foo\" { foo foo } foo\r\n";
    public static Path fixture(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && false == Files.isDirectory(root.resolve("docs/fixtures/source-edits"))) { root = root.getParent(); }
        if (root == null) { throw new AssertionError("missing fixture"); }
        return root.resolve("docs/fixtures/source-edits/" + name);
    }
    public static String decode(String text) { return text.replace("\\r", "\r").replace("\\n", "\n").replace("\\t", "\t"); }
    public static List<Piece> pieces(int offset) throws Exception {
        List<Piece> result = new ArrayList<>();
        for (String line : Files.readAllLines(fixture("pieces.tsv"))) {
            if (line.startsWith("#")) { continue; }
            String[] fields = line.split("\t");
            result.add(new Piece(fields[0], new Span(Integer.parseInt(fields[1]) + offset, Integer.parseInt(fields[2]) + offset),
                    Kind.valueOf(fields[3]), fields[4].equals("-") ? "" : fields[4]));
        }
        return result;
    }
    @Test public void sharedSourcePreservingOperations() throws Exception {
        DocumentSnapshot snapshot = new DocumentSnapshot("java", 1, SOURCE);
        SourceEdits source = new SourceEdits(snapshot, pieces(0));
        assertArrayEquals(SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8), source.roundTrip().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for (String line : Files.readAllLines(fixture("edits.tsv"))) {
            if (line.startsWith("#")) { continue; }
            String[] fields = line.split("\t", -1);
            List<Edit> edits = List.of(new Edit(new Span(Integer.parseInt(fields[2]), Integer.parseInt(fields[3])), decode(fields[4])));
            if (fields[5].equals("-")) {
                assertThrows(fields[0], IllegalArgumentException.class, () -> source.plan(Operation.valueOf(fields[1]), new Span(0, 33), edits));
            } else {
                Plan plan = source.plan(Operation.valueOf(fields[1]), new Span(0, 33), edits);
                assertEquals(fields[0], decode(fields[5]), plan.apply(snapshot, 2).text());
            }
        }
    }
    @Test public void mapsChildPlanToHostAndRejectsStaleOrConflictingEdits() throws Exception {
        DocumentSnapshot child = new DocumentSnapshot("java", 1, SOURCE);
        DocumentSnapshot tiny = new DocumentSnapshot("tiny", 1, "<" + SOURCE + ">");
        DocumentSnapshot host = new DocumentSnapshot("formula", 1, "meta\r\n[<" + SOURCE + ">]\r\n");
        SegmentSourceMap first = copy(child, tiny, 1);
        SegmentSourceMap map = first.through(copy(tiny, host, 7));
        SourceEdits source = new SourceEdits(child, pieces(0));
        List<Piece> hostPieces = new ArrayList<>();
        hostPieces.add(new Piece("prefix", new Span(0, 8), Kind.TOKEN, "")); hostPieces.addAll(pieces(8));
        hostPieces.add(new Piece("suffix", new Span(41, host.length()), Kind.TOKEN, ""));
        SourceEdits target = new SourceEdits(host, hostPieces);
        Plan plan = source.plan(Operation.RENAME, new Span(0, 33), List.of(new Edit(new Span(5, 8), "bar")));
        Plan mapped = target.map(plan, map, new Span(8, 41));
        assertEquals("meta\r\n[<" + SOURCE.replaceFirst("foo", "bar") + ">]\r\n", mapped.apply(host, 2).text());
        assertThrows(IllegalArgumentException.class, () -> target.map(plan, map, new Span(14, 41)));
        assertThrows(IllegalArgumentException.class, () -> mapped.apply(new DocumentSnapshot("formula", 2, host.text()), 3));
        assertThrows(IllegalArgumentException.class, () -> source.plan(Operation.RENAME, new Span(0, 33),
                List.of(new Edit(new Span(5, 8), "x"), new Edit(new Span(5, 8), "y"))));
        assertThrows(IllegalArgumentException.class, () -> SourceEdits.applyAll(List.of(plan, plan), Map.of("java", child), Map.of("java", 2L)));
        assertEquals("bar", SourceEdits.applyAll(List.of(plan), Map.of("java", child), Map.of("java", 2L)).get("java").slice(new Span(5, 8)));
    }
    private static SegmentSourceMap copy(DocumentSnapshot output, DocumentSnapshot origin, int start) {
        return new SegmentSourceMap(output, List.of(new SegmentSourceMap.Segment(new Span(0, output.length()), SegmentSourceMap.Kind.COPY,
                new SegmentSourceMap.Location(origin, new Span(start, start + output.length())))));
    }
    @Test public void invalidOwnershipAndPartialInputAreExplicit() {
        DocumentSnapshot snapshot = new DocumentSnapshot("partial", 1, "x ?");
        List<Piece> pieces = List.of(new Piece("x", new Span(0, 1), Kind.TOKEN, ""),
                new Piece("space", new Span(1, 2), Kind.WHITESPACE, "x"), new Piece("unknown", new Span(2, 3), Kind.UNPARSED, ""));
        SourceEdits source = new SourceEdits(snapshot, pieces);
        assertEquals("x ?", source.roundTrip());
        assertThrows(IllegalArgumentException.class, () -> source.plan(Operation.RENAME, new Span(0, 3), List.of(new Edit(new Span(2, 3), "z"))));
        assertEquals("x z", source.plan(Operation.CODE_ACTION, new Span(2, 3), List.of(new Edit(new Span(2, 3), "z"))).apply(snapshot, 2).text());
        assertThrows(IllegalArgumentException.class, () -> new SourceEdits(snapshot, pieces.subList(0, 2)));
        assertThrows(IllegalArgumentException.class, () -> new SourceEdits(snapshot, List.of(
                new Piece("x", new Span(0, 1), Kind.TOKEN, ""), new Piece("space", new Span(1, 2), Kind.WHITESPACE, "missing"), pieces.get(2))));
    }
}
