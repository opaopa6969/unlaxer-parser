package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import org.unlaxer.source.*;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.*;
import org.unlaxer.source.LanguageQueries.Project;
import org.unlaxer.source.SegmentSourceMap.*;
import org.unlaxer.dsl.semantic.ProjectSymbolIndex.Module;

public class LanguageQueriesTest {
    private static final DocumentSnapshot CHILD = new DocumentSnapshot("virtual", 1, "foo far foo f ");
    private static final DocumentSnapshot TINY = new DocumentSnapshot("tiny", 1, "<" + CHILD.text() + ">");
    private static final DocumentSnapshot HOST = new DocumentSnapshot("host", 1, "#日😀\r\n[" + TINY.text() + "]\r\n");
    private static final DocumentSnapshot LIBRARY = new DocumentSnapshot("library", 2, "bar");
    private static final Language LANGUAGE = language("typed");
    private static Language language(String name) { return new Language(name, "local", "1", name, "Document"); }
    private static SegmentSourceMap copy(DocumentSnapshot output, DocumentSnapshot origin, int start) {
        return new SegmentSourceMap(output, List.of(new Segment(new Span(0, output.length()), Kind.COPY,
                new Location(origin, new Span(start, start + output.length())))));
    }
    private static LanguageRegions regions() {
        return new LanguageRegions(HOST, List.of(
                new Region("formula", null, language("formula"), new Span(0, 25), new Span(0, 25), copy(HOST, HOST, 0), State.COMPLETE),
                new Region("tiny", "formula", language("tiny"), new Span(5, 23), new Span(6, 22), copy(TINY, HOST, 6), State.COMPLETE),
                new Region("child", "tiny", LANGUAGE, new Span(6, 22), new Span(7, 21), copy(CHILD, TINY, 1).through(copy(TINY, HOST, 6)), State.PARTIAL)));
    }
    private static SemanticModel model(DocumentSnapshot source, String symbol, String name, int end) {
        SemanticModel.Span zero = new SemanticModel.Span(0, 0);
        return new SemanticModel(source.uri(), source.version(), source.text(),
                List.of(new SemanticModel.Type("T", SemanticModel.TypeKind.BUILTIN, List.of(), List.of(), zero)),
                List.of(new SemanticModel.Scope("root", null, new SemanticModel.Span(0, source.length()))),
                List.of(new SemanticModel.Symbol(symbol, name, "T", "root", new SemanticModel.Span(0, end), end)), List.of(), List.of());
    }
    private static ProjectSymbolIndex index() {
        Module child = new Module("child", model(CHILD, "local", "foo", 3), Set.of(), List.of(
                new ProjectSymbolIndex.Import("far", new ProjectSymbolIndex.ModuleRef("", "library"), "bar", "root", new SemanticModel.Span(0, 0), 0)));
        Module library = new Module("library", model(LIBRARY, "export", "bar", 3), Set.of("export"), List.of());
        return new ProjectSymbolIndex("project", 4, List.of(child, library), List.of());
    }
    private static Project project() { return new Project("project", 4, Map.of("host", HOST, "library", LIBRARY), Map.of("sourceLevel", "21")); }
    private static Path fixture() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && false == Files.isDirectory(root.resolve("docs/fixtures/language-queries"))) { root = root.getParent(); }
        if (root == null) { throw new AssertionError("missing fixture"); }
        return root.resolve("docs/fixtures/language-queries/queries.tsv");
    }
    @Test public void sharedSemanticQueriesMapOnlyTheirOwningDocument() throws Exception {
        LanguageQueries queries = new LanguageQueries(regions(), project(), Map.of(LANGUAGE, new ProjectQueryProvider(index())));
        var views = Files.readAllLines(fixture().resolveSibling("views.jsonl"));
        int viewIndex = 0;
        for (String line : Files.readAllLines(fixture())) {
            if (line.startsWith("#")) { continue; }
            String[] fields = line.split("\t");
            LanguageQueries.Result result = queries.query(HOST, project(), Integer.parseInt(fields[2]), Operation.valueOf(fields[1]), Map.of(fields[3], fields[4], "expectedType", "T"));
            var view = queries.view(HOST, project(), Integer.parseInt(fields[2]), Operation.valueOf(fields[1]), Map.of(fields[3], fields[4], "expectedType", "T"));
            assertEquals(fields[0], views.get(viewIndex++), view.canonicalJson());
            assertEquals(HOST, queries.host()); assertEquals(project(), queries.project());
            assertEquals(fields[0], fields[5], result.state().name());
            String labels = result.items().stream().map(LanguageQueries.MappedItem::label).collect(Collectors.joining(","));
            String locations = result.items().stream().flatMap(item -> item.locations().stream()).map(mapping -> mapping.location().snapshot().uri()
                    + ":" + mapping.location().span().start() + ":" + mapping.location().span().end()).collect(Collectors.joining(","));
            String edits = result.items().stream().flatMap(item -> item.edits().stream()).map(edit -> edit.span().start() + ":" + edit.span().end() + ":" + edit.replacement()).collect(Collectors.joining(","));
            assertEquals(fields[0], fields[6].equals("-") ? "" : fields[6], labels);
            assertEquals(fields[0], fields[7].equals("-") ? "" : fields[7], locations);
            assertEquals(fields[0], fields[8].equals("-") ? "" : fields[8], edits);
            if (fields[0].equals("completion-alternatives")) {
                assertEquals("#日😀\r\n[<foo far foo far >]\r\n", regions().apply(HOST, 2, result.items().get(0).edits()).text());
            }
        }
    }
    private static LanguageQueries.Provider malicious(String mode) {
        return new LanguageQueries.Provider() {
            public Set<Operation> capabilities() { return Set.of(Operation.HOVER); }
            public LanguageQueries.Response query(LanguageQueries.Request request) {
                assertEquals(4, request.cursor()); assertEquals("21", request.project().configuration().get("sourceLevel"));
                DocumentSnapshot source = mode.equals("stale-response") ? new DocumentSnapshot(CHILD.uri(), 0, CHILD.text()) : CHILD;
                DocumentSnapshot foreign = mode.equals("stale-location") ? new DocumentSnapshot(LIBRARY.uri(), 1, LIBRARY.text()) : LIBRARY;
                List<LanguageQueries.TextEdit> edits = mode.equals("foreign-edit")
                        ? List.of(new LanguageQueries.TextEdit(new Location(foreign, new Span(0, 1)), "x")) : List.of();
                return new LanguageQueries.Response(source, "project", mode.equals("stale-project") ? 3 : 4, State.COMPLETE,
                        List.of(new LanguageQueries.Item("foreign", "T", List.of(new Location(foreign, new Span(0, 1))), edits)));
            }
        };
    }
    @Test public void staleContextResponsesAndForeignEditsAreRejected() {
        for (String mode : List.of("stale-response", "stale-location", "stale-project", "foreign-edit")) {
            LanguageQueries queries = new LanguageQueries(regions(), project(), Map.of(LANGUAGE, malicious(mode)));
            assertThrows(mode, IllegalArgumentException.class, () -> queries.query(HOST, project(), 11, Operation.HOVER, Map.of()));
        }
        LanguageQueries queries = new LanguageQueries(regions(), project(), Map.of(LANGUAGE, new ProjectQueryProvider(index())));
        assertThrows(IllegalArgumentException.class, () -> queries.query(HOST,
                new Project("project", 5, project().documents(), project().configuration()), 11, Operation.HOVER, Map.of("name", "far")));
        assertThrows(IllegalArgumentException.class, () -> queries.query(new DocumentSnapshot("host", 2, HOST.text()), project(), 11, Operation.HOVER, Map.of("name", "far")));
    }
    @Test public void cursorMappingRejectsDeletedTransformedAndDuplicateOrigins() {
        DocumentSnapshot origin = new DocumentSnapshot("origin", 1, "a#b");
        DocumentSnapshot output = new DocumentSnapshot("output", 1, "ab");
        SegmentSourceMap deleted = new SegmentSourceMap(output, List.of(
                new Segment(new Span(0, 1), Kind.COPY, new Location(origin, new Span(0, 1))),
                new Segment(new Span(1, 2), Kind.COPY, new Location(origin, new Span(2, 3)))));
        assertTrue(deleted.cursor(new Location(origin, new Span(1, 1))).isEmpty());
        assertTrue(deleted.cursor(new Location(origin, new Span(2, 2))).isEmpty());
        assertEquals(0, deleted.cursor(new Location(origin, new Span(0, 0))).orElseThrow());
        DocumentSnapshot one = new DocumentSnapshot("one", 1, "a");
        SegmentSourceMap duplicate = new SegmentSourceMap(new DocumentSnapshot("two", 1, "aa"), List.of(
                new Segment(new Span(0, 1), Kind.COPY, new Location(one, new Span(0, 1))),
                new Segment(new Span(1, 2), Kind.COPY, new Location(one, new Span(0, 1)))));
        assertTrue(duplicate.cursor(new Location(one, new Span(0, 0))).isEmpty());
        SegmentSourceMap transformed = new SegmentSourceMap(output, List.of(new Segment(new Span(0, 2), Kind.TRANSFORMED, new Location(origin, new Span(0, 3)))));
        assertTrue(transformed.cursor(new Location(origin, new Span(1, 1))).isEmpty());
    }
}
