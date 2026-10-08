package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.elementary.WordParser;

public class NameSnapshotTest {
    private static final NameSnapshot.Requirement REQUIREMENT = new NameSnapshot.Requirement("names", "v1");
    private Parser gate(String text, String kind) {
        var word = new WordParser(text);
        return new NamePredicateParser(word, "names", "v1", kind) {
            private static final long serialVersionUID = 1L;
            @Override protected List<Token> nameCaptureSites(Token root) { return List.of(root); }
        };
    }
    private Map<String,List<String>> bindings(String name) {
        return NameSnapshot.bindingsOf(List.of(new NameSnapshot("names", "v1", Map.of(name, NameSnapshot.Kind.TYPE))));
    }
    @Test public void typedFactoriesOwnInputAndRejectInvalidIdentityNamesAndAggregateBounds() {
        var source = new HashMap<String,NameSnapshot.Kind>(); source.put("𠮷", NameSnapshot.Kind.TYPE);
        var snapshot = new NameSnapshot("names", "v1", source); source.clear();
        assertEquals(java.util.Optional.of(NameSnapshot.Kind.TYPE), snapshot.lookup("𠮷"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.names().clear());
        for (String id : List.of("", "1bad", "with space", "a".repeat(129)))
            assertThrows(IllegalArgumentException.class, () -> new NameSnapshot(id,"v1",Map.of()));
        for (String version : List.of("", "v 1", "😀", "a".repeat(129)))
            assertThrows(IllegalArgumentException.class, () -> new NameSnapshot("names",version,Map.of()));
        for (String name : List.of("", "a b", "a\u007f", "\ud800", "a".repeat(257)))
            assertThrows(IllegalArgumentException.class, () -> new NameSnapshot("names","v1",Map.of(name,NameSnapshot.Kind.TYPE)));
        assertThrows(IllegalArgumentException.class, () -> NameSnapshot.bindingsOf(List.of(snapshot,snapshot)));
        assertThrows(IllegalArgumentException.class, () -> NameSnapshot.bindingsOf(Collections.nCopies(65,snapshot)));
        var many = new HashMap<String,NameSnapshot.Kind>();
        for(int i=0;i<4096;i++) many.put("name"+i,NameSnapshot.Kind.TYPE);
        var max = new NameSnapshot("max","v1",many);
        assertEquals(4096,max.names().size());
        assertThrows(IllegalArgumentException.class, () -> NameSnapshot.bindingsOf(List.of(max,snapshot)));
        many.put("excess",NameSnapshot.Kind.TYPE);
        assertThrows(IllegalArgumentException.class, () -> new NameSnapshot("max","v1",many));
    }
    @Test public void fatalChildSurvivesFallbackAndRecoveryButNextEntryStartsClean() {
        for(Memoization memo:Memoization.values()) {
            var child = new NameResolutionScope(gate("U","type"),List.of(REQUIREMENT));
            var choice = new Choice(child,new WordParser("U"));
            var parent = new NameResolutionScope(choice,List.of(REQUIREMENT));
            try(var context=ParseContext.withBindings(StringSource.createRootSource("U"),bindings("T"),ParseOptions.withMemoization(memo))) {
                assertFalse(parent.parse(context).isSucceeded());
                assertEquals("unresolved_name",NameResolution.failure(context).orElseThrow().kind());
                assertEquals(0,context.position()); assertEquals(0,context.matchedPosition());
                assertTrue(context.getCurrent().getTokens().isEmpty()); assertTrue(context.getChosen(choice).isEmpty());
                // A separate root entry may parse unrelated syntax in the same context.
                assertTrue(new NameResolutionScope(new WordParser("U"),List.of()).parse(context).isSucceeded());
                assertTrue(NameResolution.failure(context).isEmpty());
            }
        }
    }
    @Test public void rejectedCandidateRollsBackRegisteredStateAndKnownKindCanFallback() {
        int[] state={0};
        var rejected = new NamePredicateParser(new WordParser("T") {
            private static final long serialVersionUID=1L;
            @Override public Parsed parse(ParseContext context,TokenKind kind,boolean invert) {
                Parsed result=super.parse(context,kind,invert); if(result.isSucceeded())state[0]++; return result;
            }
        },"names","v1","value") {
            private static final long serialVersionUID=1L;
            @Override protected List<Token> nameCaptureSites(Token root) {return List.of(root);}
        };
        var choice=new Choice(rejected,gate("T","type"));
        try(var context=ParseContext.withBindings(StringSource.createRootSource("T"),bindings("T"),ParseOptions.DEFAULT)) {
            context.registerTransactionalState(() -> {int saved=state[0]; return ()->state[0]=saved;});
            assertTrue(new NameResolutionScope(choice,List.of(REQUIREMENT)).parse(context).isSucceeded());
            assertEquals(0,state[0]); assertTrue(NameResolution.failure(context).isEmpty());
        }
    }
    @Test public void undeclaredRequirementAndEmptyCaptureCannotBecomeSuccess() {
        try(var context=new ParseContext(StringSource.createRootSource("T"))) {
            assertFalse(gate("T","type").parse(context).isSucceeded());
            assertEquals("name_scope_missing",NameResolution.failure(context).orElseThrow().kind()); assertEquals(0,context.position());
        }
        try(var context=ParseContext.withBindings(StringSource.createRootSource("T"),bindings("T"),ParseOptions.DEFAULT)) {
            assertFalse(new NameResolutionScope(gate("T","type"),List.of()).parse(context).isSucceeded());
            assertEquals("name_snapshot_missing",NameResolution.failure(context).orElseThrow().kind());
        }
        try(var context=ParseContext.withBindings(StringSource.createRootSource(" "),bindings("T"),ParseOptions.DEFAULT)) {
            assertFalse(new NameResolutionScope(gate(" ","type"),List.of(REQUIREMENT)).parse(context).isSucceeded());
            assertEquals("name_capture",NameResolution.failure(context).orElseThrow().kind());
        }
    }
    @Test public void fatalNamesAreNotSyntaxRecoveryEvents() {
        var recovering = new SyncPointRecoveryParser(gate("U","type"), ";");
        try(var context=ParseContext.withNameSnapshots(StringSource.createRootSource("U;"),
                List.of(new NameSnapshot("names","v1",Map.of("T",NameSnapshot.Kind.TYPE))),ParseOptions.DEFAULT)) {
            assertFalse(new NameResolutionScope(recovering,List.of(REQUIREMENT)).parse(context).isSucceeded());
            assertEquals("unresolved_name",NameResolution.failure(context).orElseThrow().kind());
            assertEquals(0,context.position()); assertTrue(context.getCurrent().getTokens().isEmpty());
        }
    }

}
