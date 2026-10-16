package org.unlaxer.source;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.Test;
import org.unlaxer.CodePointIndex;
import org.unlaxer.GraphemeIndex;
import org.unlaxer.GraphemeLength;

public class GraphemeTest {
    public static Path fixture(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && false == Files.isDirectory(root.resolve("spec-corpus/grapheme"))) { root = root.getParent(); }
        if (root == null) { throw new AssertionError("fixture missing"); }
        return root.resolve("spec-corpus/grapheme/" + name);
    }
    @Test public void officialUnicode17Suite() throws Exception {
        int cases = 0;
        for (String line : Files.readAllLines(fixture("unicode-17.0.0/GraphemeBreakTest.txt"))) {
            String data = line.split("#", -1)[0].trim();
            if (data.isEmpty()) { continue; }
            StringBuilder text = new StringBuilder();
            var expected = new ArrayList<Integer>();
            int point = 0;
            for (String token : data.split("\\s+")) {
                if (token.equals("÷")) { expected.add(point); }
                else if (false == token.equals("×")) { text.appendCodePoint(Integer.parseInt(token, 16)); point++; }
            }
            var table = new DocumentSnapshot("official", 1, text.toString()).graphemes();
            var actual = new ArrayList<Integer>();
            for (int index = 0; index <= table.length().value(); index++) {
                actual.add(table.codePoint(new GraphemeIndex(index)).value());
            }
            assertEquals(line, expected, actual);
            cases++;
        }
        assertEquals(766, cases);
        assertEquals("17.0.0", GraphemeBoundaryTable.UNICODE_VERSION);
    }
    public static String decode(String hex) {
        if (hex.equals("~")) { return ""; }
        StringBuilder text = new StringBuilder();
        for (String scalar : hex.split(" ")) { text.appendCodePoint(Integer.parseInt(scalar, 16)); }
        return text.toString();
    }
    @Test public void independentCoordinatesStrictInteriorsRoundingAndSnapshotIdentity() throws Exception {
        for (String row : Files.readAllLines(fixture("boundaries.tsv"))) {
            if (row.startsWith("#")) { continue; }
            String[] fields = row.split("\t");
            DocumentSnapshot source = new DocumentSnapshot(fields[0], 1, decode(fields[1]));
            var table = source.graphemes();
            int[][] expected = java.util.Arrays.stream(fields[2].split(",")).map(boundary ->
                java.util.Arrays.stream(boundary.split(":")).mapToInt(Integer::parseInt).toArray()).toArray(int[][]::new);
            assertEquals(new GraphemeLength(expected.length - 1), table.length());
            for (int index = 0; index < expected.length; index++) {
                var point = new GraphemeIndex(index);
                assertEquals(expected[index][0], table.codePoint(point).value());
                assertEquals(expected[index][1], table.utf16(point));
                assertEquals(expected[index][2], table.utf8(point));
            }
            for (int unit = 0; unit < 3; unit++) {
                final int coordinate = unit;
                int total = expected[expected.length - 1][unit];
                for (int offset = -1; offset <= total + 1; offset++) {
                    final int candidate = offset;
                    int exact = -1, before = -1, after = -1;
                    for (int index = 0; index < expected.length; index++) {
                        if (expected[index][unit] == offset) { exact = index; }
                        if (expected[index][unit] <= offset) { before = index; }
                        if (after == -1 && expected[index][unit] >= offset) { after = index; }
                    }
                    java.util.function.Supplier<GraphemeIndex> strict = () -> switch (coordinate) {
                        case 0 -> table.fromCodePoint(new CodePointIndex(candidate));
                        case 1 -> table.fromUtf16(candidate);
                        default -> table.fromUtf8(candidate);
                    };
                    java.util.function.Supplier<GraphemeIndex> floor = () -> switch (coordinate) {
                        case 0 -> table.floorCodePoint(new CodePointIndex(candidate));
                        case 1 -> table.floorUtf16(candidate);
                        default -> table.floorUtf8(candidate);
                    };
                    java.util.function.Supplier<GraphemeIndex> ceiling = () -> switch (coordinate) {
                        case 0 -> table.ceilingCodePoint(new CodePointIndex(candidate));
                        case 1 -> table.ceilingUtf16(candidate);
                        default -> table.ceilingUtf8(candidate);
                    };
                    if (exact < 0) { assertThrows(fields[0], IllegalArgumentException.class, strict::get); }
                    else { assertEquals(fields[0], exact, strict.get().value()); }
                    if (offset < 0 || offset > total) {
                        assertThrows(IllegalArgumentException.class, floor::get);
                        assertThrows(IllegalArgumentException.class, ceiling::get);
                    } else {
                        assertEquals(before, floor.get().value());
                        assertEquals(after, ceiling.get().value());
                    }
                }
            }
            assertThrows(IllegalArgumentException.class, () -> table.codePoint(new GraphemeIndex(expected.length)));
            assertThrows(IllegalArgumentException.class, () -> table.utf16(new GraphemeIndex(Integer.MAX_VALUE)));
            assertThrows(IllegalArgumentException.class, () -> table.utf8(new GraphemeIndex(Integer.MAX_VALUE)));
            table.requireSnapshot(source);
            for (var changed : java.util.List.of(new DocumentSnapshot("other", 1, source.text()),
                    new DocumentSnapshot(source.uri(), 2, source.text()), new DocumentSnapshot(source.uri(), 1, source.text() + "x"))) {
                assertThrows(IllegalArgumentException.class, () -> table.requireSnapshot(changed));
            }
            assertEquals(source, table.snapshot());
        }
        assertThrows(IllegalArgumentException.class, () -> new GraphemeIndex(-1));
        assertThrows(IllegalArgumentException.class, () -> new GraphemeLength(-1));
    }
    @Test public void sharedParserCursorSpanRollbackAndLookaheadOracle() throws Exception {
        for (var memo : org.unlaxer.context.Memoization.values()) {
            for (var diagnostics : org.unlaxer.context.ParseOptions.Diagnostics.values()) {
                for (String row : Files.readAllLines(fixture("parser.tsv"))) {
                    if (row.startsWith("#")) { continue; }
                    String[] fields = row.split("\t");
                    String input = decode(fields[1]);
                    var g = new org.unlaxer.parser.elementary.GraphemeParser();
                    var cp = new org.unlaxer.parser.elementary.WildCardCharacterParser();
                    org.unlaxer.parser.Parser parser = switch (fields[2]) {
                        case "G" -> g;
                        case "CG" -> new org.unlaxer.parser.combinator.Chain(cp, g);
                        case "GC" -> new org.unlaxer.parser.combinator.Chain(g, cp);
                        case "GG" -> new org.unlaxer.parser.combinator.Chain(g, g);
                        case "RETRY" -> new org.unlaxer.parser.combinator.Choice(new org.unlaxer.parser.combinator.Chain(cp, g), g);
                        case "FAIL" -> new org.unlaxer.parser.combinator.Chain(g, new org.unlaxer.parser.elementary.WordParser("!"));
                        case "AHEAD" -> new org.unlaxer.parser.combinator.MatchOnly(g);
                        case "AHEAD_G" -> new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.combinator.MatchOnly(g), g);
                        default -> throw new AssertionError(fields[2]);
                    };
                    try (var context = org.unlaxer.context.ParseContext.withOptions(org.unlaxer.StringSource.createRootSource(input),
                            org.unlaxer.context.ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
                        var result = parser.parse(context);
                        assertEquals(fields[0], Boolean.parseBoolean(fields[3]), result.isSucceeded());
                        assertEquals(fields[0], Integer.parseInt(fields[4]), context.getConsumedPosition().value());
                        assertEquals(fields[0], Integer.parseInt(fields[5]), context.getMatchedPosition().value());
                        if (result.isSucceeded()) {
                            var token = result.getRootToken();
                            assertEquals(fields[0], 0, token.source.offsetFromRoot().value());
                            assertEquals(fields[0], Integer.parseInt(fields[7]), token.source.codePointLength().value());
                            assertEquals(fields[0], input.substring(0, input.offsetByCodePoints(0, Integer.parseInt(fields[7]))), token.source.toString());
                        } else {
                            assertTrue(context.getCurrent().getTokens().isEmpty());
                            if (false == input.isEmpty()) { assertTrue(g.parse(context).isSucceeded()); }
                        }
                    }
                }
            }
        }
    }
    @Test public void longClustersAndRepeatedParsingDoNotRescanPrefixes() {
        var table = new DocumentSnapshot("long", 1, "a" + "\u0301".repeat(20_000)).graphemes();
        assertEquals(new GraphemeLength(1), table.length());
        assertEquals(20_001, table.codePoint(new GraphemeIndex(1)).value());
        try (var context = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource("e\u0301".repeat(10_000)))) {
            var parser = new org.unlaxer.parser.combinator.ZeroOrMore(new org.unlaxer.parser.elementary.GraphemeParser());
            assertTrue(parser.parse(context).isSucceeded());
            assertEquals(20_000, context.getConsumedPosition().value());
        }
    }
    @Test public void lspAndMalformedUtf16RemainSeparateContracts() {
        for (String malformed : java.util.List.of("\ud800", "\udc00", "x\ud800y")) {
            assertThrows(IllegalArgumentException.class, () -> new DocumentSnapshot("invalid", 1, malformed).graphemes());
            try (var context = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(malformed))) {
                var parser = new org.unlaxer.parser.elementary.GraphemeParser();
                assertThrows(IllegalArgumentException.class, () -> parser.parse(context));
            }
        }
        var source = new DocumentSnapshot("lines", 1, "👨‍👩‍👧‍👦\r\nx");
        var table = source.graphemes();
        assertEquals(new DocumentSnapshot.Position(0, 11), source.lsp(table.codePoint(new GraphemeIndex(1)).value()));
        assertEquals(new DocumentSnapshot.Position(1, 0), source.lsp(table.codePoint(new GraphemeIndex(2)).value()));
        assertEquals(new GraphemeIndex(1), table.fromCodePoint(new CodePointIndex(source.fromLsp(new DocumentSnapshot.Position(0, 11)))));
        assertThrows(IllegalArgumentException.class, () -> table.fromCodePoint(new CodePointIndex(8)));
        assertThrows(IllegalArgumentException.class, () -> source.lsp(8));
        assertEquals(new GraphemeLength(2), new DocumentSnapshot("a", 1, "ab").graphemes().length());
        assertEquals(new GraphemeLength(1), new DocumentSnapshot("b", 1, "e\u0301").graphemes().length());
    }
}
