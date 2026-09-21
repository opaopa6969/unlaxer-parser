package org.unlaxer.context;

import static org.junit.Assert.*;

import java.util.List;
import java.util.function.Supplier;
import org.junit.Test;
import org.unlaxer.CodePointLength;
import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.AbstractParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.WordParser;

public class SafeSuccessMemoizationTest {
    static final class CountingWord extends WordParser {
        int calls;
        CountingWord(String word) { super(word); }
        @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
            calls++;
            return super.getToken(context, kind, invert);
        }
    }

    static class SafeChain extends LazyChain implements SafeSuccessMemoizable {
        final Parsers children;
        SafeChain(Parser... children) { this.children = new Parsers(children); }
        @Override public Parsers getLazyParsers() { return children; }
    }

    static final class SafeAbstract extends AbstractParser implements SafeSuccessMemoizable {
        final Parser child;
        SafeAbstract(Parser child) { this.child = child; }
        @Override public Parser createParser() { return child; }
        @Override public void prepareChildren(Parsers children) {}
        @Override public org.unlaxer.parser.ChildOccurs getChildOccurs() { return org.unlaxer.parser.ChildOccurs.single; }
    }

    static final class SafeChoice extends LazyChoice implements SafeSuccessMemoizable {
        final Parsers children;
        SafeChoice(Parser... children) { this.children = new Parsers(children); }
        @Override public Parsers getLazyParsers() { return children; }
    }

    static final class SafeLongest extends LazyLongestChoice implements SafeSuccessMemoizable {
        final Parsers children;
        SafeLongest(Parser... children) { this.children = new Parsers(children); }
        @Override public Parsers getLazyParsers() { return children; }
    }

    static final class SafePredictive extends LazyPredictiveChoice implements SafeSuccessMemoizable {
        final Parsers children;
        SafePredictive(Parser... children) { this.children = new Parsers(children); }
        @Override public Parsers getLazyParsers() { return children; }
        @Override public List<ChoicePredictor> getChoicePredictors() {
            return List.of(ChoicePredictor.literal("z"), ChoicePredictor.literal("a"));
        }
    }

    static final class SafeSpaces extends LazyZeroOrMore implements SafeSuccessMemoizable {
        final Parser space = new WordParser(" ");
        @Override public Supplier<Parser> getLazyParser() { return () -> space; }
        @Override public java.util.Optional<Parser> getLazyTerminatorParser() { return java.util.Optional.empty(); }
    }

    static final class OverrideSubclass extends SafeChain {
        int calls;
        OverrideSubclass(Parser child) { super(child); }
        @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
            calls++;
            return calls == 1 ? super.parse(context, kind, invert) : Parsed.FAILED;
        }
    }

    // Deliberately overmarked to verify the runtime's direct listener veto as well as the generator proof.
    static final class ListenerRule extends LazyChain implements SafeSuccessMemoizable, TransactionListener {
        int commits;
        @Override public Parsers getLazyParsers() { return new Parsers(new WordParser("a")); }
        @Override public void setLevel(OutputLevel level) {}
        @Override public void onOpen(ParseContext context) {}
        @Override public void onBegin(ParseContext context, Parser parser) {}
        @Override public void onCommit(ParseContext context, Parser parser, TokenList tokens) { commits++; }
        @Override public void onClose(ParseContext context) {}
    }

    static final class ListenerAncestor extends LazyChain implements SafeFailureMemoizable {
        final ListenerRule child = new ListenerRule();
        @Override public Parsers getLazyParsers() { return new Parsers(child); }
    }

    private static ParseContext context(String source, Memoization memo) {
        return ParseContext.withOptions(StringSource.createRootSource(source), ParseOptions.withMemoization(memo));
    }

    private static void sameTree(Token expected, Token actual) {
        assertNotSame(expected, actual);
        assertSame(expected.parser, actual.parser);
        assertEquals(expected.tokenKind, actual.tokenKind);
        assertEquals(expected.source.sourceAsString(), actual.source.sourceAsString());
        assertEquals(expected.source.offsetFromRoot(), actual.source.offsetFromRoot());
        assertEquals(expected.source.codePointLength(), actual.source.codePointLength());
        assertEquals(expected.getOriginalChildren().size(), actual.getOriginalChildren().size());
        assertEquals(expected.filteredChildren.size(), actual.filteredChildren.size());
        for (int i = 0; i < expected.getOriginalChildren().size(); i++) {
            Token child = actual.getOriginalChildren().get(i);
            assertSame(actual, child.parent.orElseThrow());
            sameTree(expected.getOriginalChildren().get(i), child);
        }
    }

    private static String diagnostics(ParseFailureDiagnostics diagnostic) {
        return List.of(diagnostic.getFarthestOffset(), diagnostic.getFarthestConsumedOffset(),
            diagnostic.getFarthestMatchedOffset(), diagnostic.getExpectedParsers(),
            diagnostic.getExpectedTokens(), diagnostic.getExpectedHintCandidates().stream().map(hint ->
                List.of(hint.getDisplayHint(), hint.getParserQualifiedClassName(), hint.getParserDepth())).toList(),
            diagnostic.getMaxReachedStackElements().stream().map(frame -> List.of(frame.getParserClassName(),
                frame.getDepth(), frame.getStartOffset(), frame.getMaxConsumedOffset(), frame.getMaxMatchedOffset())).toList(),
            diagnostic.getTrialHistory()).toString();
    }

    @Test public void allFiveEntryPointsReplayTreesParsedRangesAndDiagnosticsLikeOff() {
        CountingWord word = new CountingWord("a😀");
        for (Parser parser : List.of(new SafeAbstract(word), new SafeChain(word),
                new SafeChoice(new WordParser("z"), word),
                new SafeLongest(new WordParser("a"), word), new SafePredictive(new WordParser("z"), word))) {
            try (ParseContext off = context("a😀!", Memoization.OFF);
                 ParseContext on = context("a😀!", Memoization.SAFE_FAILURES)) {
                Parsed expected = parser.parse(off);
                on.begin(parser);
                assertTrue(parser.parse(on).isSucceeded());
                Token first = on.getCurrent().getTokens().get(0);
                on.rollback(parser);
                int calls = word.calls;
                Parsed actual = parser.parse(on);
                assertEquals(calls, word.calls);
                assertEquals(1, on.getPackratMemoTable().successHits());
                assertEquals(expected.status, actual.status);
                assertEquals(off.getConsumedPosition(), on.getConsumedPosition());
                assertEquals(off.getMatchedPosition(), on.getMatchedPosition());
                sameTree(off.getCurrent().getTokens().get(0), on.getCurrent().getTokens().get(0));
                sameTree(first, on.getCurrent().getTokens().get(0));
                assertEquals(expected.getConsumed().source.sourceAsString(), actual.getConsumed().source.sourceAsString());
                assertEquals(diagnostics(off.getParseFailureDiagnostics()), diagnostics(on.getParseFailureDiagnostics()));
            }
        }
    }

    @Test public void snapshotIsIndependentOfReturnedTokenMutationsAndParentPointers() {
        SafeChain parser = new SafeChain(new WordParser("a"));
        try (ParseContext context = context("a", Memoization.SAFE_FAILURES)) {
            context.begin(parser);
            parser.parse(context);
            Token first = context.getCurrent().getTokens().get(0);
            Token child = first.getOriginalChildren().get(0);
            child.putExtraObject(Name.of("changed"), "changed");
            first.getOriginalChildren().clear();
            context.rollback(parser);
            parser.parse(context);
            Token replayed = context.getCurrent().getTokens().get(0);
            assertEquals(1, replayed.getOriginalChildren().size());
            assertFalse(replayed.getOriginalChildren().get(0).getExtraObject(Name.of("changed")).isPresent());
            assertSame(first, child.parent.orElseThrow());
            assertSame(replayed, replayed.getOriginalChildren().get(0).parent.orElseThrow());
        }
    }

    @Test public void successfulFrameReplaysInnerFailuresWithoutEarlierSiblingPollution() {
        SafeChoice parser = new SafeChoice(new Chain(new WordParser("a"), new WordParser("x")), new WordParser("a"));
        try (ParseContext off = context("aby", Memoization.OFF);
             ParseContext on = context("aby", Memoization.SAFE_FAILURES)) {
            var speculation = on.beginDiagnosticSpeculation();
            new Chain(new WordParser("a"), new WordParser("b"), new WordParser("z")).parse(on);
            on.begin(parser);
            parser.parse(on);
            on.rollback(parser);
            on.discardDiagnosticSpeculation(speculation);
            // A fresh enclosing frame sees only the memoized rule-local contribution.
            var frame = on.beginMemoDiagnosticFrame();
            parser.parse(on);
            on.discardMemoDiagnosticFrame(frame);
            parser.parse(off);
            assertEquals(1, frame.farthestFailureOffset);
            assertEquals(diagnostics(off.getParseFailureDiagnostics()), diagnostics(on.getParseFailureDiagnostics()));
            assertTrue(on.expectedParsersOf(frame).stream().noneMatch(hint -> hint.contains("z")));
            assertEquals(1, on.getPackratMemoTable().successHits());
        }
    }

    @Test public void replayKeepsEnclosingFailureTransactionTraceBalancedWithoutDuplicateHooks() {
        SafeChoice parser = new SafeChoice(new WordParser("z"), new WordParser("a"));
        try (ParseContext context = context("a", Memoization.SAFE_FAILURES)) {
            context.begin(parser);
            parser.parse(context);
            context.rollback(parser);
            int[] checkpoints = {0};
            int[] restores = {0};
            context.registerTransactionalState(() -> {
                checkpoints[0]++;
                return () -> restores[0]++;
            });
            var frame = context.beginMemoDiagnosticFrame();
            parser.parse(context);
            context.discardMemoDiagnosticFrame(frame);
            assertEquals("only the replay's real begin checkpoints", 1, checkpoints[0]);
            assertEquals("safe success does not replay failed alternatives' restores", 0, restores[0]);
            context.replayMemoTransactionEvents(frame);
            assertEquals(2, checkpoints[0]);
            assertEquals(0, restores[0]);
        }
    }

    @Test public void descendantChoiceSelectionsReplayAndRollbackWithTheTokenTree() {
        SafeChoice child = new SafeChoice(new WordParser("z"), new WordParser("a"));
        SafeChain parser = new SafeChain(child);
        try (ParseContext context = context("a", Memoization.SAFE_FAILURES)) {
            for (int i = 0; i < 2; i++) {
                context.begin(parser);
                parser.parse(context);
                assertSame(child.children.get(1), context.getChosen(child).orElseThrow());
                context.rollback(parser);
                assertTrue(context.getChosen(child).isEmpty());
            }
            assertEquals(1, context.getPackratMemoTable().successHits());
        }
    }

    @Test public void listenersAndTheirUnmarkedAncestorsAreAlwaysEvaluated() {
        ListenerAncestor parser = new ListenerAncestor();
        try (ParseContext context = context("a", Memoization.SAFE_FAILURES)) {
            for (int i = 0; i < 2; i++) {
                context.begin(parser);
                parser.parse(context);
                context.rollback(parser);
            }
            assertEquals(2, parser.child.commits);
            assertEquals(0, context.getPackratMemoTable().successHits());
        }
    }

    @Test public void subclassOverrideDoesNotInheritEitherSafetyProof() {
        CountingWord word = new CountingWord("a");
        OverrideSubclass parser = new OverrideSubclass(word);
        try (ParseContext context = context("a", Memoization.SAFE_FAILURES)) {
            context.begin(parser);
            assertTrue(parser.parse(context).isSucceeded());
            context.rollback(parser);
            assertTrue(parser.parse(context).isFailed());
            assertFalse(PackratMemoTable.isExactSafeClass(parser));
            assertFalse(PackratMemoTable.isExactSuccessSafeClass(parser));
            assertEquals(0, context.getPackratMemoTable().successHits());
        }
    }

    @Test public void tokenKindInvertFlagMatchedPositionAndStateVersionSeparateSuccessKeys() {
        SafeChain parser = new SafeChain(new WordParser("a"));
        try (ParseContext context = context("aa", Memoization.SAFE_FAILURES)) {
            context.getCurrent().setResetMatchedWithConsumed(false);
            context.begin(parser);
            parser.parse(context);
            context.rollback(parser);
            var key = PackratMemoTable.positionKeyOf(context, TokenKind.consumed, false);
            assertNotNull(context.getPackratMemoTable().get(parser, key));
            assertNull(PackratMemoTable.lookup(context, parser, TokenKind.matchOnly, false));
            assertNull(PackratMemoTable.lookup(context, parser, TokenKind.consumed, true));
            context.begin(parser);
            context.matchOnly(new CodePointLength(1));
            assertNull(PackratMemoTable.lookup(context, parser, TokenKind.consumed, false));
            context.rollback(parser);
            context.begin(parser);
            context.markMemoizationStateChanged();
            assertNull(PackratMemoTable.lookup(context, parser, TokenKind.consumed, false));
            context.rollback(parser);
            parser.parse(context);
            assertEquals(1, context.getPackratMemoTable().successHits());
        }
    }

    @Test public void matchedOnlyAndEmptySuccessesKeepIndependentCursors() {
        for (Parser parser : List.of(new SafeChain(new WordParser("a")), new SafeSpaces())) {
            try (ParseContext off = context("a", Memoization.OFF);
                 ParseContext on = context("a", Memoization.SAFE_FAILURES)) {
                off.getCurrent().setResetMatchedWithConsumed(false);
                on.getCurrent().setResetMatchedWithConsumed(false);
                parser.parse(off, TokenKind.matchOnly, false);
                on.begin(parser);
                parser.parse(on, TokenKind.matchOnly, false);
                on.rollback(parser);
                parser.parse(on, TokenKind.matchOnly, false);
                assertEquals(off.getConsumedPosition(), on.getConsumedPosition());
                assertEquals(off.getMatchedPosition(), on.getMatchedPosition());
                sameTree(off.getCurrent().getTokens().get(0), on.getCurrent().getTokens().get(0));
                assertEquals(1, on.getPackratMemoTable().successHits());
            }
        }
    }

    @Test public void trialRecordingMatchesOffAndDisablesEvenExistingSuccesses() {
        CountingWord word = new CountingWord("a");
        SafeChain parser = new SafeChain(word);
        try (ParseContext off = context("a", Memoization.OFF);
             ParseContext on = context("a", Memoization.SAFE_FAILURES)) {
            on.begin(parser);
            parser.parse(on);
            on.rollback(parser);
            off.startTrialRecording();
            on.startTrialRecording();
            parser.parse(off);
            parser.parse(on);
            assertFalse(on.getTrialHistory().isEmpty());
            assertEquals(diagnostics(off.getParseFailureDiagnostics()), diagnostics(on.getParseFailureDiagnostics()));
            assertEquals(0, on.getPackratMemoTable().successHits());
            assertEquals(3, word.calls);
        }
    }

    @Test public void zeroWidthConsumedSuccessResetsAnAdvancedMatchedCursorJustLikeOff() {
        SafeChain parser = new SafeChain(new ZeroOrMore(new WordParser("")));
        try (ParseContext off = context("a", Memoization.OFF);
             ParseContext on = context("a", Memoization.SAFE_FAILURES)) {
            off.getCurrent().setResetMatchedWithConsumed(false);
            on.getCurrent().setResetMatchedWithConsumed(false);
            off.matchOnly(new CodePointLength(1));
            on.matchOnly(new CodePointLength(1));
            on.begin(parser);
            parser.parse(on);
            on.rollback(parser);
            parser.parse(on);
            parser.parse(off);
            assertEquals(off.getConsumedPosition(), on.getConsumedPosition());
            assertEquals(off.getMatchedPosition(), on.getMatchedPosition());
            assertEquals(1, on.getPackratMemoTable().successHits());
        }
    }
}
