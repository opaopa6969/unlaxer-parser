package org.unlaxer.context;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseOptions.Diagnostics;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.elementary.WordParser;

public class AutoDiagnosticsTest {
    static class CustomWord extends WordParser {
        CustomWord() { super("ab"); }
    }

    static class MarkedWord extends CustomWord implements DiagnosticsAgnostic {}

    static class Graph extends LazyChain implements DiagnosticsAgnostic {
        Parsers children;
        int visits;
        @Override public Parsers getLazyParsers() { return children; }
        @Override public Parsers getChildren() { visits++; return children; }
    }

    @Test public void autoDefaultsResolveWithoutChangingExplicitPoliciesOrMemoization() {
        assertSame(ParseOptions.DEFAULT, ParseOptions.defaults());
        for (Memoization memo : Memoization.values()) {
            var auto = ParseOptions.withMemoization(memo);
            assertEquals(Diagnostics.AUTO, auto.diagnostics());
            assertEquals(new ParseOptions(memo), auto);
            assertEquals(new ParseOptions(memo).hashCode(), auto.hashCode());
            assertTrue(auto.toString().contains("diagnostics=AUTO"));
            assertSame(auto, auto.withDiagnostics(Diagnostics.AUTO));
            assertEquals(auto, ParseOptions.DEFAULT.withMemoizationPolicy(memo));
            for (boolean safe : List.of(false, true)) {
                var resolved = auto.resolveDiagnostics(safe);
                assertEquals(safe ? Diagnostics.DETAILED_ON_FAILURE : Diagnostics.DETAILED,
                    resolved.diagnostics());
                assertEquals(memo, resolved.memoization());
                assertEquals(auto, resolved.withDiagnostics(Diagnostics.AUTO));
                for (Diagnostics explicit : List.of(Diagnostics.DETAILED, Diagnostics.DETAILED_ON_FAILURE)) {
                    var options = auto.withDiagnostics(explicit);
                    assertSame(options, options.resolveDiagnostics(safe));
                }
            }
        }
    }

    @Test public void safetyRequiresEveryCustomNodeToDeclareItsContract() {
        assertTrue(DiagnosticsSafety.isDeferredDiagnosticsSafe(new Chain(new WordParser("ab"))));
        assertFalse(DiagnosticsSafety.isDeferredDiagnosticsSafe(new Chain(new CustomWord())));
        assertTrue(DiagnosticsSafety.isDeferredDiagnosticsSafe(new Chain(new MarkedWord())));
        Graph markedParent = new Graph();
        markedParent.children = new Parsers(new CustomWord());
        assertFalse(DiagnosticsSafety.isDeferredDiagnosticsSafe(markedParent));
        assertThrows(NullPointerException.class, () -> DiagnosticsSafety.isDeferredDiagnosticsSafe(null));
    }

    @Test public void safetyVisitsSharedAndRecursiveNodesOnceByIdentity() {
        Graph first = new Graph();
        Graph second = new Graph();
        first.children = new Parsers(second, second);
        second.children = new Parsers(first, new MarkedWord());
        assertTrue(DiagnosticsSafety.isDeferredDiagnosticsSafe(first));
        assertEquals(1, first.visits);
        assertEquals(1, second.visits);
    }

    @Test public void lowLevelDefaultsKeepDetailedFailureDiagnosticsAndExposeResolvedOptions() {
        var root = new Chain(new WordParser("a"), new WordParser("b"));
        try (var legacy = new ParseContext(StringSource.createRootSource("ax"));
             var auto = ParseContext.withOptions(StringSource.createRootSource("ax"), ParseOptions.DEFAULT,
                 context -> assertEquals(Diagnostics.DETAILED, context.getOptions().diagnostics()));
             var explicit = ParseContext.withOptions(StringSource.createRootSource("ax"),
                 ParseOptions.DEFAULT.withDiagnostics(Diagnostics.DETAILED))) {
            for (var context : List.of(legacy, auto, explicit)) {
                assertEquals(Diagnostics.DETAILED, context.getOptions().diagnostics());
                assertFalse(root.parse(context).isSucceeded());
                assertTrue(context.getParseFailureDiagnostics().getFarthestOffset() > 0);
                assertFalse(context.getParseFailureDiagnostics().getExpectedTokens().isEmpty());
            }
            var expected = explicit.getParseFailureDiagnostics();
            for (var context : List.of(legacy, auto)) {
                var actual = context.getParseFailureDiagnostics();
                assertEquals(expected.getFarthestOffset(), actual.getFarthestOffset());
                assertEquals(expected.getExpectedTokens(), actual.getExpectedTokens());
            }
        }
    }
}
