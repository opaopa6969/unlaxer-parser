package org.unlaxer.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.SafeFailureMemoizable;
import org.unlaxer.context.TransactionalState;
import org.unlaxer.Name;
import org.unlaxer.Parsed;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.listener.ParserListener;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.TokenList;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.elementary.WordParser;

public class SafeFailureMemoizationPolicyTest {

    static class CountingWord extends WordParser {
        int calls;
        CountingWord(String word) { super(word); }
        @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
            calls++;
            return super.getToken(context, kind, invert);
        }
    }

    static final class MutableCountingWord extends CountingWord {
        String expected;
        MutableCountingWord(String expected) {
            super(expected);
            this.expected = expected;
        }
        @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
            calls++;
            return new WordParser(expected).getToken(context, kind, invert);
        }
    }

    static class SafeRule extends LazyChain implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        final CountingWord child;
        SafeRule(CountingWord child) { this.child = child; }
        @Override public Parsers getLazyParsers() { return new Parsers(child); }
    }

    static final class UnmarkedSubclass extends SafeRule {
        private static final long serialVersionUID = 1L;
        UnmarkedSubclass(CountingWord child) { super(child); }
    }

    private ParseContext safeContext(String source) {
        return ParseContext.withOptions(StringSource.createRootSource(source),
            ParseOptions.withMemoization(Memoization.SAFE_FAILURES));
    }

    @Test
    public void safeFailureHitsButSuccessIsNeverCached() {
        CountingWord failingChild = new CountingWord("no");
        SafeRule failing = new SafeRule(failingChild);
        try (ParseContext context = safeContext("x")) {
            assertFalse(failing.parse(context).isSucceeded());
            var first = context.getParseFailureDiagnostics();
            assertFalse(failing.parse(context).isSucceeded());
            var second = context.getParseFailureDiagnostics();
            assertEquals(1, failingChild.calls);
            assertEquals(1, context.getPackratMemoTable().failureHits());
            assertEquals(first.getFarthestOffset(), second.getFarthestOffset());
            assertEquals(first.getExpectedTokens(), second.getExpectedTokens());
        }

        CountingWord successfulChild = new CountingWord("x");
        SafeRule successful = new SafeRule(successfulChild);
        try (ParseContext context = safeContext("x")) {
            for (int i = 0; i < 2; i++) {
                context.begin(successful);
                assertTrue(successful.parse(context).isSucceeded());
                context.rollback(successful);
            }
            assertEquals("successes must be re-derived", 2, successfulChild.calls);
            assertEquals(0, context.getPackratMemoTable().failureHits());
        }
    }

    @Test
    public void subclassDoesNotInheritExactClassSafetyMarker() {
        CountingWord child = new CountingWord("no");
        UnmarkedSubclass parser = new UnmarkedSubclass(child);
        try (ParseContext context = safeContext("x")) {
            assertFalse(parser.parse(context).isSucceeded());
            assertFalse(parser.parse(context).isSucceeded());
            assertEquals(2, child.calls);
            assertEquals(0, context.getPackratMemoTable().failureHits());
        }
    }

    @Test
    public void defaultsAndDeprecatedAdapterRemainSafelyOffOrRestricted() {
        assertEquals(Memoization.OFF, ParseOptions.DEFAULT.memoization());
        try (ParseContext context = new ParseContext(StringSource.createRootSource("x"))) {
            assertFalse(context.isMemoizeEnabled());
        }
        CountingWord child = new CountingWord("no");
        UnmarkedSubclass parser = new UnmarkedSubclass(child);
        try (ParseContext context = new ParseContext(
                StringSource.createRootSource("x"), ParseContext.memoize())) {
            assertFalse(parser.parse(context).isSucceeded());
            assertFalse(parser.parse(context).isSucceeded());
            assertEquals("legacy opt-in must still obey exact safe markers", 2, child.calls);
        }
    }

    @Test
    public void lateListenerDisablesExistingCacheAndPreservesLifecycle() {
        MutableCountingWord child = new MutableCountingWord("no");
        SafeRule parser = new SafeRule(child);
        try (ParseContext context = safeContext("x")) {
            assertFalse(parser.parse(context).isSucceeded()); // populate cache
            int[] starts = {0};
            int[] ends = {0};
            context.addParserListener(Name.of("memo-listener"), new ParserListener() {
                @Override public void setLevel(OutputLevel level) {}
                @Override public void onStart(Parser p, ParseContext c, TokenKind k, boolean i) {
                    starts[0]++;
                    if (p == parser) child.expected = "x";
                }
                @Override public void onEnd(Parser p, Parsed r, ParseContext c, TokenKind k, boolean i) { ends[0]++; }
            });
            context.begin(parser);
            assertTrue(parser.parse(context).isSucceeded());
            context.rollback(parser);
            assertEquals(2, child.calls);
            assertTrue(starts[0] > 0);
            assertEquals(starts[0], ends[0]);
            context.removeParserListerner(Name.of("memo-listener"));
            context.begin(parser);
            assertTrue(parser.parse(context).isSucceeded());
            context.rollback(parser);
            assertEquals("removing an unsafe facility must not revive stale cache", 3, child.calls);
            assertEquals(0, context.getPackratMemoTable().failureHits());
        }
    }

    @Test
    public void persistentActionAndTrialRecordingDisableMemoHits() {
        CountingWord actionChild = new CountingWord("no");
        SafeRule actionParser = new SafeRule(actionChild);
        try (ParseContext context = safeContext("x")) {
            assertFalse(actionParser.parse(context).isSucceeded());
            context.addActions((org.unlaxer.context.Transaction.AdditionalPreCommitAction) (p, c) -> {});
            assertFalse(actionParser.parse(context).isSucceeded());
            context.getActions().clear();
            assertFalse(actionParser.parse(context).isSucceeded());
            assertEquals(3, actionChild.calls);
            assertEquals(0, context.getPackratMemoTable().failureHits());
        }

        CountingWord trialChild = new CountingWord("no");
        SafeRule trialParser = new SafeRule(trialChild);
        try (ParseContext context = safeContext("x")) {
            assertFalse(trialParser.parse(context).isSucceeded());
            context.startTrialRecording();
            assertFalse(trialParser.parse(context).isSucceeded());
            context.stopTrialRecording();
            assertFalse(trialParser.parse(context).isSucceeded());
            assertEquals(3, trialChild.calls);
            assertFalse(context.getTrialHistory().isEmpty());
            assertEquals(0, context.getPackratMemoTable().failureHits());
        }
    }

    @Test
    public void lateTransactionListenerDisablesExistingCache() {
        CountingWord child = new CountingWord("no");
        SafeRule parser = new SafeRule(child);
        try (ParseContext context = safeContext("x")) {
            assertFalse(parser.parse(context).isSucceeded());
            int[] begins = {0};
            int[] rollbacks = {0};
            context.addTransactionListener(Name.of("memo-transaction-listener"), new TransactionListener() {
                @Override public void setLevel(OutputLevel level) {}
                @Override public void onOpen(ParseContext c) {}
                @Override public void onBegin(ParseContext c, Parser p) { begins[0]++; }
                @Override public void onCommit(ParseContext c, Parser p, TokenList tokens) {}
                @Override public void onRollback(ParseContext c, Parser p, TokenList tokens) { rollbacks[0]++; }
                @Override public void onClose(ParseContext c) {}
            });
            assertFalse(parser.parse(context).isSucceeded());
            assertEquals(2, child.calls);
            assertTrue(begins[0] > 0);
            assertEquals(begins[0], rollbacks[0]);
            context.removeTransactionListerner(Name.of("memo-transaction-listener"));
            assertFalse(parser.parse(context).isSucceeded());
            assertEquals(3, child.calls);
            assertEquals(0, context.getPackratMemoTable().failureHits());
        }
    }

    @Test
    public void transactionalStatePermanentlyDisablesExistingCache() {
        CountingWord child = new CountingWord("no");
        SafeRule parser = new SafeRule(child);
        try (ParseContext context = safeContext("x")) {
            assertFalse(parser.parse(context).isSucceeded());
            int[] checkpoints = {0};
            TransactionalState state = () -> {
                checkpoints[0]++;
                return () -> {};
            };
            context.registerTransactionalState(state);
            assertFalse(parser.parse(context).isSucceeded());
            assertFalse(parser.parse(context).isSucceeded());
            assertEquals(3, child.calls);
            assertTrue("normal lifecycle must checkpoint registered state", checkpoints[0] > 0);
            assertEquals(0, context.getPackratMemoTable().failureHits());
        }
    }
}
