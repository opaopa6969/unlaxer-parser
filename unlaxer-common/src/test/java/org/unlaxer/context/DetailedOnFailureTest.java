package org.unlaxer.context;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.context.ParseOptions.Diagnostics;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.listener.ParserListener;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.elementary.WordParser;

public class DetailedOnFailureTest {
    static final class FailedAlternative extends LazyChain implements SafeFailureMemoizable {
        @Override public Parsers getLazyParsers() {
            return new Parsers(new WordParser("a"), new WordParser("x"));
        }
    }

    static final class Body extends LazyChoice implements SafeFailureMemoizable {
        final FailedAlternative failure = new FailedAlternative();
        @Override public Parsers getLazyParsers() {
            // The repeated exact parser instance ensures a real memo hit on both success and failure.
            return new Parsers(failure, failure, new WordParser("ab"));
        }
    }

    static final class Root extends LazyChain implements SafeFailureMemoizable {
        @Override public Parsers getLazyParsers() {
            return new Parsers(new WordParser("["), new Body(), new WordParser("]"));
        }
    }

    private record Observation(boolean succeeded, boolean complete, int consumed, int matched,
            List<String> tokens, int hits, List<String> events, TransactionMetrics metrics) {}

    @Test public void optionsPreserveIndependentPoliciesAndValueSemantics() {
        assertSame(ParseOptions.DEFAULT, ParseOptions.defaults());
        assertEquals(Diagnostics.DETAILED, new ParseOptions(Memoization.OFF).diagnostics());
        for (Memoization memo : Memoization.values()) {
            var detailed = ParseOptions.withMemoization(memo);
            var deferred = detailed.withDiagnostics(Diagnostics.DETAILED_ON_FAILURE);
            assertEquals(Diagnostics.DETAILED, detailed.diagnostics());
            assertEquals(memo, deferred.memoization());
            assertNotEquals(detailed, deferred);
            assertEquals(detailed, deferred.withDiagnostics(Diagnostics.DETAILED));
            assertEquals(deferred.hashCode(), ParseOptions.DEFAULT
                .withDiagnostics(Diagnostics.DETAILED_ON_FAILURE).withMemoizationPolicy(memo).hashCode());
            assertSame(deferred, deferred.withDiagnostics(Diagnostics.DETAILED_ON_FAILURE));
            assertSame(deferred, deferred.withMemoizationPolicy(memo));
            assertTrue(deferred.toString().contains("diagnostics=DETAILED_ON_FAILURE"));
        }
        assertThrows(NullPointerException.class, () -> ParseOptions.DEFAULT.withDiagnostics(null));
        assertThrows(NullPointerException.class, () -> ParseOptions.DEFAULT.withMemoizationPolicy(null));
    }

    @Test public void directParsesKeepTokenTreesCursorsAndMemoHitsForAllInputOutcomes() {
        for (Memoization memo : Memoization.values()) {
            for (String input : List.of("[ab]", "[a", "[ab]@", "[ab)")) {
                Root root = new Root();
                var detailed = observe(root, input, ParseOptions.withMemoization(memo), false);
                var deferred = observe(root, input, ParseOptions.withMemoization(memo)
                    .withDiagnostics(Diagnostics.DETAILED_ON_FAILURE), false);
                assertEquals(input + " / " + memo, detailed, deferred);
                assertEquals(input.equals("[ab]") || input.equals("[ab]@"), deferred.succeeded());
                assertEquals(input.equals("[ab]"), deferred.complete());
                if (memo == Memoization.SAFE_FAILURES) assertTrue(deferred.hits() > 0);
            }
        }
    }

    @Test public void parserAndTransactionListenersKeepIdenticalCallbacksIncludingCommit() {
        for (Memoization memo : Memoization.values()) {
            for (String input : List.of("[ab]", "[a", "[ab]@", "[ab)")) {
                Root root = new Root();
                var detailed = observe(root, input, ParseOptions.withMemoization(memo), true);
                var deferred = observe(root, input, ParseOptions.withMemoization(memo)
                    .withDiagnostics(Diagnostics.DETAILED_ON_FAILURE), true);
                assertEquals(detailed, deferred);
                if (deferred.succeeded()) {
                    assertTrue(deferred.events().stream().anyMatch(event -> event.startsWith("commit")));
                }
                assertEquals(0, deferred.hits()); // Ordinary listeners disable memoization in both modes.
            }
        }
    }

    private Observation observe(Parser root, String input, ParseOptions options, boolean listeners) {
        List<String> events = new ArrayList<>();
        try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
            if (listeners) {
                context.addParserListener(Name.of("parses"), new ParserListener() {
                    @Override public void setLevel(OutputLevel level) {}
                    @Override public void onStart(Parser p, ParseContext c, TokenKind k, boolean i) {
                        events.add("start:" + p.getClass().getName() + ":" + c.getConsumedPosition().value());
                    }
                    @Override public void onEnd(Parser p, Parsed r, ParseContext c, TokenKind k, boolean i) {
                        events.add("end:" + p.getClass().getName() + ":" + r.isSucceeded());
                    }
                });
                context.addTransactionListener(Name.of("transactions"), listener(events));
            }
            context.enableTransactionMetrics();
            var parsed = root.parse(context);
            List<String> tokens = new ArrayList<>();
            for (Token token : context.getCurrent().getTokens()) appendTree(token, 0, tokens);
            appendTree(parsed.getRootToken(false), 0, tokens);
            if (options.diagnostics() == Diagnostics.DETAILED_ON_FAILURE) assertEmpty(context);
            return new Observation(parsed.isSucceeded(), parsed.isSucceeded() && context.allConsumed(),
                context.getConsumedPosition().value(), context.getMatchedPosition().value(), tokens,
                context.isMemoizeEnabled() ? context.getPackratMemoTable().failureHits() : 0,
                events, context.snapshotTransactionMetrics());
        }
    }

    private TransactionListener listener(List<String> events) {
        return new TransactionListener() {
            @Override public void setLevel(OutputLevel level) {}
            @Override public void onOpen(ParseContext c) {}
            @Override public void onBegin(ParseContext c, Parser p) { events.add("begin:" + p.getClass().getName()); }
            @Override public void onCommit(ParseContext c, Parser p, TokenList tokens) {
                events.add("commit:" + p.getClass().getName());
                for (Token token : tokens) appendTree(token, 0, events);
            }
            @Override public void onRollback(ParseContext c, Parser p, TokenList tokens) {
                events.add("rollback:" + p.getClass().getName());
            }
            @Override public void onClose(ParseContext c) {}
        };
    }

    private static void appendTree(Token token, int depth, List<String> result) {
        if (token == null) return;
        var range = token.source.cursorRange();
        result.add(depth + ":" + token.parser.getClass().getName() + ":"
            + range.startIndexInclusive.position().value() + ":" + range.endIndexExclusive.position().value());
        token.getChildren(t -> true, Token.ChildrenKind.original).forEach(t -> appendTree(t, depth + 1, result));
    }

    @Test public void memoHitsReplayTransactionalStateAndKeepKeysWithoutDiagnostics() {
        List<List<String>> observations = new ArrayList<>();
        for (Diagnostics diagnostics : Diagnostics.values()) {
            List<String> events = new ArrayList<>();
            FailedAlternative root = new FailedAlternative();
            try (var context = ParseContext.withOptions(StringSource.createRootSource("ab"),
                    ParseOptions.withMemoization(Memoization.SAFE_FAILURES).withDiagnostics(diagnostics))) {
                context.addMemoizationTransparentTransactionListener(Name.of("transparent"), listener(events));
                assertTrue(root.parse(context).isFailed());
                context.registerTransactionalState(() -> {
                    events.add("checkpoint");
                    return () -> events.add("restore");
                });
                for (int i = 0; i < 2; i++) assertTrue(root.parse(context).isFailed());
                assertEquals(2, context.getPackratMemoTable().failureHits());
                assertEquals(2, events.stream().filter("checkpoint"::equals).count());
                assertEquals(2, events.stream().filter("restore"::equals).count());
                context.begin(root);
                context.markMemoizationStateChanged();
                assertTrue(root.parse(context).isFailed()); // A different state version must miss.
                context.rollback(root);
                assertTrue(root.parse(context).isFailed()); // The old version hits again.
                assertEquals(3, context.getPackratMemoTable().failureHits());
                if (diagnostics == Diagnostics.DETAILED_ON_FAILURE) assertEmpty(context);
            }
            observations.add(events);
        }
        assertEquals(observations.get(0), observations.get(1));
    }

    @Test public void populatedMemoDiagnosticReplayIsInertInDeferredContext() {
        ParseContext.FailureDiagnostic saved;
        try (var detailed = new ParseContext(StringSource.createRootSource("ab"))) {
            saved = detailed.beginMemoDiagnosticFrame();
            assertTrue(new FailedAlternative().parse(detailed).isFailed());
            detailed.discardMemoDiagnosticFrame(saved);
            assertEquals(1, saved.farthestFailureOffset);
        }
        try (var deferred = ParseContext.withOptions(StringSource.createRootSource("ab"),
                ParseOptions.DEFAULT.withDiagnostics(Diagnostics.DETAILED_ON_FAILURE))) {
            deferred.replayFailureDiagnostic(saved);
            assertEmpty(deferred);
            assertEquals(0, deferred.maxReachedOffset);
            assertEquals(-1, deferred.farthestFailureOffset);
            assertSame(ParseContext.StackSnapshot.EMPTY, deferred.maxReachedStackElements);
        }
    }

    @Test public void explicitTrialsRemainAvailableButFailureDiagnosticsStayEmpty() {
        try (var context = ParseContext.withOptions(StringSource.createRootSource("[ab)"),
                ParseOptions.DEFAULT.withDiagnostics(Diagnostics.DETAILED_ON_FAILURE))) {
            context.startTrialRecording();
            assertTrue(new Root().parse(context).isFailed());
            assertFalse(context.getTrialHistory().isEmpty());
            assertEmpty(context);
        }
    }

    @SuppressWarnings("deprecation")
    @Test public void legacyMemoizationEffectorPreservesDiagnosticsPolicy() {
        try (var context = ParseContext.withOptions(StringSource.createRootSource("ab"),
                ParseOptions.DEFAULT.withDiagnostics(Diagnostics.DETAILED_ON_FAILURE), ParseContext.memoize())) {
            assertEquals(Diagnostics.DETAILED_ON_FAILURE, context.getOptions().diagnostics());
            FailedAlternative root = new FailedAlternative();
            assertTrue(root.parse(context).isFailed());
            assertTrue(root.parse(context).isFailed());
            assertEquals(1, context.getPackratMemoTable().failureHits());
            assertEmpty(context);
        }
    }

    private static void assertEmpty(ParseContext context) {
        var diagnostic = context.getParseFailureDiagnostics();
        assertEquals(0, diagnostic.getFarthestOffset());
        assertEquals(0, diagnostic.getFarthestConsumedOffset());
        assertEquals(0, diagnostic.getFarthestMatchedOffset());
        assertFalse(diagnostic.hasFailureCandidate());
        assertTrue(diagnostic.getExpectedTokens().isEmpty());
        assertTrue(diagnostic.getExpectedParsers().isEmpty());
        assertTrue(diagnostic.getExpectedHintCandidates().isEmpty());
        assertTrue(diagnostic.getMaxReachedStackElements().isEmpty());
        assertTrue(diagnostic.getTrialHistory().isEmpty());
        assertEquals("", diagnostic.getDeepestMatchedRule());
        assertEquals(0, diagnostic.getDeepestConsumedPosition());
    }
}
