package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.unlaxer.dsl.semantic.ProjectSymbolIndex.*;
import org.unlaxer.dsl.semantic.ProjectSymbolIndex.Module;
import org.unlaxer.source.DocumentSnapshot;
import org.unlaxer.source.SourceEdits;

public class ProjectRenameTest {
    private static final String SOURCE = "#日😀\r\nfoo  \"foo\" { foo foo } foo\r\n";
    private static SemanticModel.Span span(int start, int end) { return new SemanticModel.Span(start, end); }
    private static Path fixture(String name) {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && false == Files.isDirectory(root.resolve("docs/fixtures/source-edits"))) { root = root.getParent(); }
        if (root == null) { throw new AssertionError("missing fixture"); }
        return root.resolve("docs/fixtures/source-edits/" + name);
    }
    private static Module first() {
        SemanticModel model = new SemanticModel("first", 1, SOURCE,
                List.of(new SemanticModel.Type("T", SemanticModel.TypeKind.BUILTIN, List.of(), List.of(), span(0, 0))),
                List.of(new SemanticModel.Scope("root", null, span(0, 33)), new SemanticModel.Scope("inner", "root", span(16, 27))),
                List.of(new SemanticModel.Symbol("outer", "foo", "T", "root", span(5, 8), 8),
                        new SemanticModel.Symbol("inner", "foo", "T", "inner", span(18, 21), 21)), List.of(), List.of());
        return new Module("first", model, Set.of("outer"), List.of());
    }
    private static Module second() {
        return new Module("second", new SemanticModel("second", 1, "foo foo",
                List.of(new SemanticModel.Type("T", SemanticModel.TypeKind.BUILTIN, List.of(), List.of(), span(0, 0))),
                List.of(new SemanticModel.Scope("root", null, span(0, 7))),
                List.of(new SemanticModel.Symbol("local", "foo", "T", "root", span(0, 3), 3)), List.of(), List.of()), Set.of(), List.of());
    }
    private static List<ProjectRename.Inventory> inventories(boolean complete) throws Exception {
        List<SourceEdits.Piece> pieces = new ArrayList<>();
        for (String line : Files.readAllLines(fixture("pieces.tsv"))) {
            if (line.startsWith("#")) { continue; }
            String[] fields = line.split("\t");
            pieces.add(new SourceEdits.Piece(fields[0], new DocumentSnapshot.Span(Integer.parseInt(fields[1]), Integer.parseInt(fields[2])),
                    SourceEdits.Kind.valueOf(fields[3]), fields[4].equals("-") ? "" : fields[4]));
        }
        SourceEdits first = new SourceEdits(new DocumentSnapshot("first", 1, SOURCE), pieces);
        SourceEdits second = new SourceEdits(new DocumentSnapshot("second", 1, "foo foo"), List.of(
                new SourceEdits.Piece("decl", new DocumentSnapshot.Span(0, 3), SourceEdits.Kind.TOKEN, ""),
                new SourceEdits.Piece("space", new DocumentSnapshot.Span(3, 4), SourceEdits.Kind.WHITESPACE, "decl"),
                new SourceEdits.Piece("ref", new DocumentSnapshot.Span(4, 7), SourceEdits.Kind.TOKEN, "")));
        return List.of(new ProjectRename.Inventory("first", first, List.of("innerRef", "outerRef"), complete),
                new ProjectRename.Inventory("second", second, List.of("ref"), true));
    }
    private static Definition target(String symbol) {
        return new Definition(new Identity("project", "", "", "first", symbol), "first", 1,
                symbol.equals("outer") ? span(5, 8) : span(18, 21));
    }
    private static boolean identifier(String name) { return name.matches("[A-Za-z_][A-Za-z0-9_]*"); }
    @Test public void commonIdentityRenamePreservesShadowingStringsAndOtherModules() throws Exception {
        ProjectSymbolIndex index = new ProjectSymbolIndex("project", 1, List.of(first(), second()), List.of());
        for (String line : Files.readAllLines(fixture("rename.tsv"))) {
            if (line.startsWith("#")) { continue; }
            String[] fields = line.split("\t");
            List<SourceEdits.Plan> plans = ProjectRename.prepare(index, target(fields[0]), fields[1], ProjectRenameTest::identifier, inventories(true));
            assertEquals(1, plans.size()); assertEquals(2, plans.get(0).edits().size());
            Map<String, DocumentSnapshot> result = SourceEdits.applyAll(plans,
                    Map.of("first", new DocumentSnapshot("first", 1, SOURCE), "second", new DocumentSnapshot("second", 1, "foo foo")),
                    Map.of("first", 2L, "second", 2L));
            assertEquals(fields[2].replace("\\r", "\r").replace("\\n", "\n"), result.get("first").text());
            assertFalse(result.containsKey("second"));
        }
    }
    @Test public void staleIncompleteInvalidAndImportedRenamesAreRejected() throws Exception {
        ProjectSymbolIndex index = new ProjectSymbolIndex("project", 1, List.of(first(), second()), List.of());
        List<ProjectRename.Inventory> inventories = inventories(true);
        assertThrows(IllegalArgumentException.class, () -> ProjectRename.prepare(index, target("outer"), "x-y", ProjectRenameTest::identifier, inventories));
        assertThrows(IllegalArgumentException.class, () -> ProjectRename.prepare(index, target("outer"), "bar", null, inventories));
        List<ProjectRename.Inventory> incomplete = inventories(false);
        assertThrows(IllegalArgumentException.class, () -> ProjectRename.prepare(index, target("outer"), "bar", ProjectRenameTest::identifier, incomplete));
        Definition stale = new Definition(target("outer").identity(), "first", 0, span(5, 8));
        assertThrows(IllegalArgumentException.class, () -> ProjectRename.prepare(index, stale, "bar", ProjectRenameTest::identifier, inventories));
        Module imported = new Module("second", second().model(), Set.of(), List.of(new Import("alias", new ModuleRef("", "first"), "foo", "root", span(0, 0), 0)));
        ProjectSymbolIndex withImport = new ProjectSymbolIndex("project", 1, List.of(first(), imported), List.of());
        assertThrows(IllegalArgumentException.class, () -> ProjectRename.prepare(withImport, target("outer"), "bar", ProjectRenameTest::identifier, inventories));
    }
    @Test public void capturingAnotherBindingAndChangedSourceAreRejected() throws Exception {
        String changedSource = SOURCE.replace("{ foo foo }", "{ bar bar }");
        SemanticModel previous = first().model();
        SemanticModel changed = new SemanticModel("first", 1, changedSource, new ArrayList<>(previous.types().values()),
                new ArrayList<>(previous.scopes().values()), List.of(previous.symbols().get("outer"),
                        new SemanticModel.Symbol("inner", "bar", "T", "inner", span(18, 21), 21)), List.of(), List.of());
        ProjectSymbolIndex index = new ProjectSymbolIndex("project", 1, List.of(new Module("first", changed, Set.of("outer"), List.of()), second()), List.of());
        List<ProjectRename.Inventory> original = inventories(true);
        assertThrows(IllegalArgumentException.class, () -> ProjectRename.prepare(index, target("outer"), "baz", ProjectRenameTest::identifier, original));
        List<ProjectRename.Inventory> current = List.of(new ProjectRename.Inventory("first",
                new SourceEdits(new DocumentSnapshot("first", 1, changedSource), original.get(0).source().pieces()), List.of("innerRef", "outerRef"), true), original.get(1));
        assertThrows(IllegalArgumentException.class, () -> ProjectRename.prepare(index, target("outer"), "bar", ProjectRenameTest::identifier, current));
    }
}
