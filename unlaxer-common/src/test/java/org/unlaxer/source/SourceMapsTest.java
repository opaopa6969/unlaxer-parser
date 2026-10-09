package org.unlaxer.source;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.SegmentSourceMap.Kind;
import org.unlaxer.source.SegmentSourceMap.Location;
import org.unlaxer.source.SegmentSourceMap.Segment;
import org.unlaxer.source.LanguageRegions.*;

public class SourceMapsTest {
    private static Path fixture(String name) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && false == Files.isDirectory(directory.resolve("docs/fixtures/source-maps"))) {
            directory = directory.getParent();
        }
        if (directory == null) { throw new AssertionError("fixture directory missing"); }
        return directory.resolve("docs/fixtures/source-maps/" + name);
    }
    private static String decode(String text) { return text.replace("\\r", "\r").replace("\\n", "\n").replace("\\\\", "\\"); }
    private static String span(Span span) { return span.start() + ":" + span.end(); }
    private static SegmentSourceMap copy(DocumentSnapshot output, DocumentSnapshot origin, int start) {
        return new SegmentSourceMap(output, List.of(new Segment(new Span(0, output.length()), Kind.COPY,
                new Location(origin, new Span(start, start + output.length())))));
    }
    @Test public void sharedMaps() throws Exception {
        for (String line : Files.readAllLines(fixture("maps.tsv"))) {
            if (line.startsWith("#")) { continue; }
            String[] columns = line.split("\t", -1);
            DocumentSnapshot output = new DocumentSnapshot("virtual", 1, decode(columns[1]));
            DocumentSnapshot origin = new DocumentSnapshot("host", 1, decode(columns[2]));
            List<Segment> segments = new ArrayList<>();
            for (String specification : columns[3].split(",")) {
                String[] fields = specification.split(":");
                Kind kind = fields[2].equals("C") ? Kind.COPY : fields[2].equals("T") ? Kind.TRANSFORMED : Kind.GENERATED;
                Location location = fields[3].equals("-") ? null : new Location(origin,
                        new Span(Integer.parseInt(fields[3]), Integer.parseInt(fields[4])));
                segments.add(new Segment(new Span(Integer.parseInt(fields[0]), Integer.parseInt(fields[1])), kind, location));
            }
            SegmentSourceMap map = new SegmentSourceMap(output, segments);
            Span query = new Span(Integer.parseInt(columns[4]), Integer.parseInt(columns[5]));
            String actual = map.diagnostics(query).stream().map(value -> span(value.location().span()) + ":" + value.exact())
                    .collect(Collectors.joining(","));
            assertEquals(columns[0], columns[6].equals("-") ? "" : columns[6], actual);
            if (columns[7].equals("REJECT")) { assertThrows(columns[0], IllegalArgumentException.class, () -> map.edit(query)); }
            else { assertEquals(columns[0], columns[7], span(map.edit(query).span())); }
        }
    }
    @Test public void sharedPositions() throws Exception {
        DocumentSnapshot source = new DocumentSnapshot("host", 1, "日😀\r\nx\ry\nz");
        for (String line : Files.readAllLines(fixture("positions.tsv"))) {
            if (line.startsWith("#")) { continue; }
            String[] fields = line.split("\t");
            int point = Integer.parseInt(fields[0]);
            int utf16 = Integer.parseInt(fields[1]);
            int utf8 = Integer.parseInt(fields[2]);
            assertEquals(utf16, source.utf16(point)); assertEquals(utf8, source.utf8(point));
            assertEquals(point, source.fromUtf16(utf16)); assertEquals(point, source.fromUtf8(utf8));
            if (fields[3].equals("-1")) { assertThrows(IllegalArgumentException.class, () -> source.lsp(point)); }
            else {
                DocumentSnapshot.Position position = new DocumentSnapshot.Position(Integer.parseInt(fields[3]), Integer.parseInt(fields[4]));
                assertEquals(position, source.lsp(point)); assertEquals(point, source.fromLsp(position));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> source.fromUtf16(2));
        assertThrows(IllegalArgumentException.class, () -> source.fromUtf8(4));
        assertThrows(IllegalArgumentException.class, () -> source.fromLsp(new DocumentSnapshot.Position(0, 2)));
        assertThrows(IllegalArgumentException.class, () -> new DocumentSnapshot("host", 1, "\uD800"));
    }
    @Test public void compositionAndMultipleOrigins() {
        DocumentSnapshot host = new DocumentSnapshot("host", 1, "[日😀x]");
        DocumentSnapshot middle = new DocumentSnapshot("tiny", 1, "日😀x");
        DocumentSnapshot child = new DocumentSnapshot("java", 1, "😀x");
        SegmentSourceMap composed = copy(child, middle, 1).through(copy(middle, host, 1));
        assertEquals(new Location(host, new Span(2, 3)), composed.edit(new Span(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> composed.through(copy(middle, host, 1)));
        DocumentSnapshot second = new DocumentSnapshot("include", 2, "x");
        SegmentSourceMap multiple = new SegmentSourceMap(child, List.of(
                new Segment(new Span(0, 1), Kind.COPY, new Location(host, new Span(2, 3))),
                new Segment(new Span(1, 2), Kind.COPY, new Location(second, new Span(0, 1)))));
        assertEquals(List.of(host, second), multiple.diagnostics(new Span(0, 2)).stream()
                .map(value -> value.location().snapshot()).collect(Collectors.toList()));
        assertThrows(IllegalArgumentException.class, () -> multiple.edit(new Span(0, 2)));
        assertThrows(IllegalArgumentException.class, () -> copy(child, host, 0));
        assertThrows(IllegalArgumentException.class, () -> new SegmentSourceMap(child, List.of()));
    }
    @Test public void composedAliasesAreNotEditable() {
        DocumentSnapshot host = new DocumentSnapshot("host", 1, "x");
        DocumentSnapshot first = new DocumentSnapshot("first", 1, "x");
        DocumentSnapshot second = new DocumentSnapshot("second", 1, "x");
        DocumentSnapshot output = new DocumentSnapshot("output", 1, "xx");
        SegmentSourceMap map = new SegmentSourceMap(output, List.of(
                new Segment(new Span(0, 1), Kind.COPY, new Location(first, new Span(0, 1))),
                new Segment(new Span(1, 2), Kind.COPY, new Location(second, new Span(0, 1)))))
                .through(copy(first, host, 0)).through(copy(second, host, 0));
        assertEquals(2, map.diagnostics(new Span(0, 2)).size());
        assertThrows(IllegalArgumentException.class, () -> map.edit(new Span(0, 1)));
    }
    private static final DocumentSnapshot HOST = new DocumentSnapshot("file:///formula", 7, "#日😀\r\n{<abc><def>}\r\n");
    private static final Language FORMULA = new Language("formula", "local", "1", "FormulaInfo", "Document");
    private static final Language TINY = new Language("tiny", "local", "1", "TinyExpression", "Expression");
    private static final Language JAVA = new Language("java", "local", "1", "Java", "CompilationUnit");
    private static Region region(String id, String parent, Language language, Span full, Span body) {
        DocumentSnapshot output = new DocumentSnapshot("virtual:///" + id, 7, HOST.slice(body));
        return new Region(id, parent, language, full, body, copy(output, HOST, body.start()), State.PARTIAL);
    }
    private static LanguageRegions regions() {
        return new LanguageRegions(HOST, List.of(
                region("formula", null, FORMULA, new Span(0, 19), new Span(0, 19)),
                region("tiny", "formula", TINY, new Span(5, 17), new Span(6, 16)),
                region("java1", "tiny", JAVA, new Span(6, 11), new Span(7, 10)),
                region("java2", "tiny", JAVA, new Span(11, 16), new Span(12, 15))));
    }
    private static Provider provider(State state, boolean stale, List<Edit> edits) {
        return new Provider() {
            public Set<Operation> capabilities() { return Set.of(Operation.PARSE, Operation.COMPLETION); }
            public Response invoke(Region region, Operation operation) {
                DocumentSnapshot snapshot = region.sourceMap().output();
                if (stale) { snapshot = new DocumentSnapshot(snapshot.uri(), 6, snapshot.text()); }
                return new Response(snapshot, state, List.of(new Span(1, 2)), edits);
            }
        };
    }
    @Test public void nestedDispatchAndSourcePreservingEdits() {
        LanguageRegions regions = regions();
        assertEquals("java1", regions.at(8).id()); assertEquals("java2", regions.at(13).id());
        assertEquals("tiny", regions.at(10).id()); assertEquals("formula", regions.at(5).id());
        assertEquals("formula",regions.at(19).id());
        assertEquals(State.UNAVAILABLE, regions.dispatch("java1", Operation.PARSE, Map.of(), HOST).state());
        Map<Language, Provider> providers = Map.of(JAVA, provider(State.PARTIAL, false, List.of(new Edit(new Span(1, 2), "名前😀"))));
        assertEquals(State.UNSUPPORTED, regions.dispatch("java1", Operation.FORMAT, providers, HOST).state());
        Dispatch response = regions.dispatch("java1", Operation.COMPLETION, providers, HOST);
        assertEquals(State.PARTIAL, response.state());
        assertEquals(new Span(8, 9), response.diagnostics().get(0).location().span());
        assertEquals("#日😀\r\n{<a名前😀c><def>}\r\n", regions.apply(HOST, 8, response.edits()).text());
        assertEquals(HOST.text(), regions.apply(HOST, 8, List.of()).text());
        for (State state : List.of(State.FAILED, State.TIMEOUT, State.UNSUPPORTED)) {
            assertEquals(state, regions.dispatch("java1", Operation.PARSE, Map.of(JAVA, provider(state, false, List.of())), HOST).state());
        }
        assertThrows(IllegalArgumentException.class, () -> regions.dispatch("java1", Operation.PARSE,
                Map.of(JAVA, provider(State.COMPLETE, true, List.of())), HOST));
        assertThrows(IllegalArgumentException.class, () -> regions.dispatch("java1", Operation.PARSE, providers,
                new DocumentSnapshot(HOST.uri(), 8, HOST.text())));
        assertThrows(IllegalArgumentException.class, () -> regions.apply(HOST, 8,
                List.of(new Edit(new Span(8, 9), "x"), new Edit(new Span(8, 9), "y"))));
        assertThrows(IllegalArgumentException.class, () -> regions.apply(HOST, 7, List.of()));
        assertThrows(IllegalArgumentException.class, () -> regions.dispatch("java1", Operation.PARSE,
                Map.of(JAVA, provider(State.COMPLETE, false, List.of(new Edit(new Span(0, 4), "oops")))), HOST));
    }
    @Test public void actualParserCallbackIsBoundedAndSiblingFailureIsIsolated() {
        Provider parser = new Provider() {
            public Set<Operation> capabilities() { return Set.of(Operation.PARSE); }
            public Response invoke(Region region, Operation operation) {
                DocumentSnapshot snapshot = region.sourceMap().output();
                try (org.unlaxer.context.ParseContext context = new org.unlaxer.context.ParseContext(
                        org.unlaxer.StringSource.createRootSource(snapshot.text()),
                        org.unlaxer.context.CreateMetaTokenSpecifier.createMetaOn)) {
                    org.unlaxer.Parsed parsed = new org.unlaxer.parser.elementary.WordParser("abc").parse(context);
                    State state = parsed.isSucceeded() && context.allConsumed() ? State.COMPLETE : State.FAILED;
                    return new Response(snapshot, state, state == State.FAILED ? List.of(new Span(0, 0)) : List.of(), List.of());
                }
            }
        };
        LanguageRegions regions = regions();
        Map<Language, Provider> providers = Map.of(JAVA, parser);
        assertEquals(State.COMPLETE, regions.dispatch("java1", Operation.PARSE, providers, HOST).state());
        Dispatch failed = regions.dispatch("java2", Operation.PARSE, providers, HOST);
        assertEquals(State.FAILED, failed.state());
        assertEquals(new Span(12, 12), failed.diagnostics().get(0).location().span());
        assertEquals(State.COMPLETE, regions.dispatch("java1", Operation.PARSE, providers, HOST).state());
    }
    @Test public void malformedRegionTreesFail() {
        Region first = region("one", "missing", JAVA, new Span(6, 11), new Span(7, 10));
        assertThrows(IllegalArgumentException.class, () -> new LanguageRegions(HOST, List.of(first)));
        Region cycle = region("cycle", "cycle", JAVA, new Span(7, 10), new Span(7, 10));
        assertThrows(IllegalArgumentException.class, () -> new LanguageRegions(HOST, List.of(cycle)));
        Region overlap = region("two", null, JAVA, new Span(6, 11), new Span(7, 10));
        assertThrows(IllegalArgumentException.class, () -> new LanguageRegions(HOST, List.of(overlap, overlap)));
    }
    @Test public void emptyCopyAnchorsComposeAndDoNotInventAmbiguousPositions() {
        var host = new DocumentSnapshot("host", 1, "日😀[");
        var middle = new DocumentSnapshot("middle", 1, "😀[");
        var empty = new DocumentSnapshot("empty", 1, "");
        var point = new Span(0, 0);
        var expected = new Location(host, new Span(3, 3));
        var map = copy(empty, middle, 2).through(copy(middle, host, 1));
        assertEquals(expected, map.edit(point));
        assertEquals(0, map.cursor(expected).orElseThrow());
        assertEquals(new DocumentSnapshot.Position(0, 4), host.lsp(map.edit(point).span().start()));
        var nested = copy(new DocumentSnapshot("nested", 1, ""), empty, 0).through(map);
        assertEquals(expected, nested.edit(point)); assertEquals(0, nested.cursor(expected).orElseThrow());
        assertTrue(nested.cursor(new Location(new DocumentSnapshot("host", 2, host.text()), new Span(3, 3))).isEmpty());
        assertTrue(new SegmentSourceMap(empty, List.of()).cursor(expected).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new SegmentSourceMap(empty, List.of()).edit(point));
        var anchor = new Segment(point, Kind.COPY, expected);
        assertThrows(IllegalArgumentException.class, () -> new SegmentSourceMap(empty, List.of(anchor, anchor)));
        assertThrows(IllegalArgumentException.class, () -> new SegmentSourceMap(empty, List.of(new Segment(point, Kind.GENERATED, expected))));
        assertThrows(IllegalArgumentException.class, () -> new SegmentSourceMap(empty, List.of(new Segment(point, Kind.TRANSFORMED, expected))));
        assertThrows(IllegalArgumentException.class, () -> new SegmentSourceMap(middle, List.of(anchor)));
        var splitHost = new DocumentSnapshot("split-host", 1, "a#a");
        var split = new DocumentSnapshot("split", 1, "aa");
        var parent = new SegmentSourceMap(split, List.of(
            new Segment(new Span(0, 1), Kind.COPY, new Location(splitHost, new Span(0, 1))),
            new Segment(new Span(1, 2), Kind.COPY, new Location(splitHost, new Span(2, 3)))));
        var ambiguous = copy(empty, split, 1).through(parent);
        assertTrue(ambiguous.cursor(new Location(splitHost, new Span(1, 1))).isEmpty());
        assertTrue(ambiguous.cursor(new Location(splitHost, new Span(2, 2))).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> ambiguous.edit(point));
    }

    @Test public void sharedPartialEofOwnership() throws Exception {
        DocumentSnapshot host=new DocumentSnapshot("host",1,"😀ABC");
        assertEquals(4,host.length());assertEquals(5,host.utf16(4));
        for(String line:Files.readAllLines(fixture("partial-eof.tsv"))) {
            if(line.startsWith("#"))continue;
            String[] columns=line.split("\t");List<Region> input=new ArrayList<>();
            for(String encoded:columns[2].split(";")) {
                String[] f=encoded.split(",");Span full=new Span(Integer.parseInt(f[2]),Integer.parseInt(f[3]));Span body=new Span(Integer.parseInt(f[4]),Integer.parseInt(f[5]));
                DocumentSnapshot child=new DocumentSnapshot(f[0],1,host.slice(body));
                SegmentSourceMap map=child.length()==0?new SegmentSourceMap(child,List.of()):copy(child,host,body.start());
                input.add(new Region(f[0],f[1].equals("-")?null:f[1],new Language("x","local","1","X","Document"),full,body,map,State.valueOf(f[6])));
            }
            LanguageRegions regions=new LanguageRegions(host,input);int cursor=Integer.parseInt(columns[1]);
            if(columns[3].equals("REJECT"))assertThrows(columns[0],IllegalArgumentException.class,()->regions.at(cursor));
            else {Region region=regions.at(cursor);assertEquals(columns[0],columns[3],region==null?"NONE":region.id());}
        }
    }

}
