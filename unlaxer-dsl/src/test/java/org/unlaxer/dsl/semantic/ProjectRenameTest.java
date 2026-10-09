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
    private static SourceEdits lexical(String uri,String text) {
        var pieces=new ArrayList<SourceEdits.Piece>();int[] cp=text.codePoints().toArray();int i=0;
        while(i<cp.length) {
            int start=i;boolean ws=" \t\r\n".indexOf(cp[i])>=0;
            if(ws) {while(i<cp.length&&" \t\r\n".indexOf(cp[i])>=0)i++;}
            else if(Character.isLetter(cp[i])) {while(i<cp.length&&Character.isLetter(cp[i]))i++;}
            else if(cp[i]=='"') {i++;while(i<cp.length&&cp[i]!='"')i++;if(i<cp.length)i++;}
            else i++;
            pieces.add(new SourceEdits.Piece("p"+start,new DocumentSnapshot.Span(start,i),ws?SourceEdits.Kind.WHITESPACE:SourceEdits.Kind.TOKEN,""));
        }
        return new SourceEdits(new DocumentSnapshot(uri,1,text),pieces);
    }
    private record ImportFixture(ProjectSymbolIndex index,List<ProjectRename.Inventory> inventories,List<ProjectRename.ImportSite> sites,Definition target) {}
    private static ImportFixture importedFixture(String alias) {
        String a="foo foo",b="import foo as "+alias+";\r\n"+alias+" { "+alias+" "+alias+" } "+alias+" \"foo\" 😀",c="import foo;\r\nfoo \"foo\"";
        int open=b.indexOf('{'),close=b.indexOf('}'),first=b.indexOf('\n')+1,decl=open+2,inner=decl+alias.length()+1,last=close+2;
        var type=List.of(new SemanticModel.Type("T",SemanticModel.TypeKind.BUILTIN,List.of(),List.of(),span(0,0)));
        var am=new SemanticModel("a",1,a,type,List.of(new SemanticModel.Scope("root",null,span(0,7))),List.of(new SemanticModel.Symbol("exported","foo","T","root",span(0,3),3)),List.of(),List.of());
        var bm=new SemanticModel("b",1,b,type,List.of(new SemanticModel.Scope("root",null,span(0,b.codePointCount(0,b.length()))),new SemanticModel.Scope("inner","root",span(open,close+1))),List.of(new SemanticModel.Symbol("shadow",alias,"T","inner",span(decl,decl+alias.length()),decl+alias.length())),List.of(),List.of());
        var cm=new SemanticModel("c",1,c,type,List.of(new SemanticModel.Scope("root",null,span(0,c.length()))),List.of(),List.of(),List.of());
        var modules=List.of(new Module("a",am,Set.of("exported"),List.of()),new Module("b",bm,Set.of(),List.of(new Import(alias,new ModuleRef("","a"),"foo","root",span(0,first),first))),new Module("c",cm,Set.of(),List.of(new Import("foo",new ModuleRef("","a"),"foo","root",span(0,13),13))),second());
        var inventories=List.of(new ProjectRename.Inventory("a",lexical("a",a),List.of("p4"),true),new ProjectRename.Inventory("b",lexical("b",b),List.of("p"+first,"p"+inner,"p"+last),true),new ProjectRename.Inventory("c",lexical("c",c),List.of("p13"),true),new ProjectRename.Inventory("second",lexical("second","foo foo"),List.of("p4"),true));
        var sites=List.of(new ProjectRename.ImportSite("b",0,"p7","p14"),new ProjectRename.ImportSite("c",0,"p7",""));
        return new ImportFixture(new ProjectSymbolIndex("project",1,modules,List.of()),inventories,sites,new Definition(new Identity("project","","","a","exported"),"a",1,span(0,3)));
    }
    @Test public void sharedImportAndAliasRenamesUseDifferentIdentities() throws Exception {
        for(String line:Files.readAllLines(fixture("import-rename.tsv"))) {
            if(line.startsWith("#"))continue;String[] f=line.split("\t");var data=importedFixture(f[2]);
            var plans=f[1].equals("definition")?ProjectRename.prepare(data.index,data.target,f[3],name->!name.isEmpty()&&name.codePoints().allMatch(Character::isLetter),data.inventories,data.sites):List.of(ProjectRename.prepareAlias(data.index,data.sites.get(0),f[3],name->!name.isEmpty()&&name.codePoints().allMatch(Character::isLetter),data.inventories,data.sites));
            if(f[1].equals("shadow")) {
                var symbol=data.index.modules().get(1).model().symbols().get("shadow");
                var shadowTarget=new Definition(new Identity("project","","","b","shadow"),"b",1,symbol.declaration());
                plans=ProjectRename.prepare(data.index,shadowTarget,f[3],ProjectRenameTest::identifier,data.inventories,data.sites);
            }
            Map<String,DocumentSnapshot> current=new java.util.HashMap<>();Map<String,Long> versions=new java.util.HashMap<>();
            for(var inv:data.inventories){current.put(inv.source().snapshot().uri(),inv.source().snapshot());versions.put(inv.source().snapshot().uri(),2L);}
            var finalPlans=plans;if(plans.size()>1)assertThrows(IllegalArgumentException.class,()->SourceEdits.singleDocument(finalPlans));
            else assertSame(plans.get(0),SourceEdits.singleDocument(plans));
            var modified=SourceEdits.applyAll(plans,current,versions);
            for(int i=0;i<3;i++){String uri=List.of("a","b","c").get(i);assertEquals(f[0]+" "+uri,f[4+i].replace("\\r","\r").replace("\\n","\n"),modified.getOrDefault(uri,current.get(uri)).text());}
            assertFalse(modified.containsKey("second"));
            assertThrows(IllegalArgumentException.class,()->ProjectRename.prepare(data.index,data.target,"bar",ProjectRenameTest::identifier,data.inventories));
            assertThrows(IllegalArgumentException.class,()->ProjectRename.prepareAlias(data.index,data.sites.get(1),"bar",ProjectRenameTest::identifier,data.inventories,data.sites));
            assertThrows(IllegalArgumentException.class,()->ProjectRename.prepare(data.index,data.target,"bar",ProjectRenameTest::identifier,data.inventories,List.of(data.sites.get(0),data.sites.get(0))));
            assertThrows(IllegalArgumentException.class,()->ProjectRename.prepareAlias(data.index,data.sites.get(0),"foo",ProjectRenameTest::identifier,data.inventories,List.of(new ProjectRename.ImportSite("b",0,"p7","p7"),data.sites.get(1))));
        }
    }

}
