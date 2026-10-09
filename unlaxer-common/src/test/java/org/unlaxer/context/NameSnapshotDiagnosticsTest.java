package org.unlaxer.context;

import static org.junit.Assert.*;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

/** Name wrappers preserve syntax roots and expose every child to diagnostic-safety preparation. */
public class NameSnapshotDiagnosticsTest {
    private static final class OpaqueWord extends WordParser {
        private static final long serialVersionUID=1L;
        OpaqueWord() {super("T");}
    }
    private NamePredicateParser gate(Parser child) {
        return new NamePredicateParser(child,"names","v1","type") {
            private static final long serialVersionUID=1L;
            @Override protected List<Token> nameCaptureSites(Token root) {return List.of(root);}
        };
    }
    @Test public void wrappersAddNoCstNodesAndPreserveTheCommittedChildIdentity() {
        var word=new WordParser("T"); var predicate=gate(word);
        var scope=new NameResolutionScope(predicate,List.of(new NameSnapshot.Requirement("names","v1")));
        assertFalse(predicate instanceof CollectingParser); assertFalse((Object)scope instanceof CollectingParser);
        try(var context=ParseContext.withNameSnapshots(StringSource.createRootSource("T"),
                List.of(new NameSnapshot("names","v1",Map.of("T",NameSnapshot.Kind.TYPE))),ParseOptions.DEFAULT)) {
            var parsed=scope.parse(context); assertTrue(parsed.isSucceeded());
            assertEquals(1,context.getCurrent().getTokens().size());
            assertSame(word,context.getCurrent().getTokens().get(0).parser);
            assertEquals("T",context.getCurrent().getTokens().get(0).getSource().toString());
            assertEquals(parsed.getConsumed().getSource(),context.getCurrent().getTokens().get(0).getSource());
        }
    }
    @Test public void opaqueChildrenCannotBeHiddenBehindTrustedNameWrappers() {
        var child=new OpaqueWord();
        assertFalse(DiagnosticsSafety.isDeferredDiagnosticsSafe(child));
        assertFalse(DiagnosticsSafety.isDeferredDiagnosticsSafe(gate(child)));
        assertFalse(DiagnosticsSafety.isDeferredDiagnosticsSafe(new NameResolutionScope(gate(child),List.of())));
    }
}
