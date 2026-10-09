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
    @Test public void sharedEditProviderMapsNestedRegionsAndPreservesPartialText() throws Exception {
        var child=new DocumentSnapshot("java",1,"x  ?\r\n😀");var tiny=new DocumentSnapshot("tiny",1,"<"+child.text()+">");var host=new DocumentSnapshot("formula",1,"F["+tiny.text()+"] tail");
        var source=new SourceEdits(child,List.of(new Piece("x",new Span(0,1),Kind.TOKEN,""),new Piece("space",new Span(1,3),Kind.WHITESPACE,"x"),new Piece("unknown",new Span(3,4),Kind.UNPARSED,""),new Piece("line",new Span(4,6),Kind.WHITESPACE,""),new Piece("emoji",new Span(6,7),Kind.UNPARSED,"")));
        var map=copy(child,tiny,1).through(copy(tiny,host,2));
        var language=new LanguageRegions.Language("java","example/java","1","Java","Root");
        var project=new LanguageQueries.Project("p",1,Map.of(host.uri(),host),Map.of());
        for(String line:Files.readAllLines(fixture("provider.tsv"))) {
            if(line.startsWith("#"))continue;String[] f=line.split("\t",-1);
            var operation=LanguageRegions.Operation.valueOf(f[2]);
            var region=new LanguageRegions.Region("java",null,language,new Span(2,11),new Span(3,10),map,LanguageRegions.State.valueOf(f[1]));
            Map<Operation,java.util.function.Function<LanguageQueries.Request,Plan>> policies=new java.util.HashMap<>();
            if(operation!=LanguageRegions.Operation.HOVER) {
                Operation editOperation=Operation.valueOf(f[2]);
                policies.put(editOperation,request->source.plan(editOperation,new Span(0,7),List.of(new Edit(new Span(Integer.parseInt(f[3]),Integer.parseInt(f[4])),decode(f[5])))));
            }
            var provider=new SourceEdits.QueryProvider(source,language,project,policies);
            var layer=new LanguageQueries(new LanguageRegions(host,List.of(region)),project,Map.of(language,provider));
            var result=layer.query(host,project,3,operation,Map.of());assertEquals(f[0],f[6],result.state().name());
            List<Edit> edits=result.items().isEmpty()?List.of():result.items().get(0).edits();
            if(!f[7].equals("-"))assertEquals(new Span(Integer.parseInt(f[7]),Integer.parseInt(f[8])),edits.get(0).span());else assertTrue(edits.isEmpty());
            assertEquals(f[0],decode(f[9]),new LanguageRegions(host,List.of()).apply(host,2,edits).text());
            assertEquals(policies.size(),provider.capabilities().size());
        }
        var region=new LanguageRegions.Region("java",null,language,new Span(2,11),new Span(3,10),map,LanguageRegions.State.PARTIAL);
        var request=new LanguageQueries.Request(region,LanguageRegions.Operation.FORMAT,1,project,Map.of());
        var wrongOperation=new SourceEdits.QueryProvider(source,language,project,Map.of(Operation.FORMAT,r->source.plan(Operation.CODE_ACTION,new Span(0,7),List.of(new Edit(new Span(0,1),"z")))));
        assertThrows(IllegalArgumentException.class,()->wrongOperation.query(request));
        var other=new SourceEdits(new DocumentSnapshot("other",1,child.text()),source.pieces());
        var foreign=new SourceEdits.QueryProvider(source,language,project,Map.of(Operation.FORMAT,r->other.plan(Operation.FORMAT,new Span(0,7),List.of(new Edit(new Span(1,3)," ")))));
        assertThrows(IllegalArgumentException.class,()->foreign.query(request));
        var oldProject=new LanguageQueries.Project("p",0,project.documents(),Map.of());
        assertThrows(IllegalArgumentException.class,()->wrongOperation.query(new LanguageQueries.Request(region,request.operation(),1,oldProject,Map.of())));
        var otherLanguage=new LanguageRegions.Language("java","example/java","2","Java","Root");
        var wrongIdentity=new LanguageRegions.Region("java",null,otherLanguage,region.full(),region.body(),map,region.parseState());
        assertThrows(IllegalArgumentException.class,()->wrongOperation.query(new LanguageQueries.Request(wrongIdentity,request.operation(),1,project,Map.of())));

    }

}
