package org.unlaxer.parser.combinator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.SafeFailureMemoizable;
import org.unlaxer.context.TransactionalState;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.elementary.WordParser;

public class LongestChoiceTest {

    @Test
    public void choosesLongestAndKeepsDeclarationOrderOnTie() {
        WordParser shortAlternative = new WordParser("a");
        WordParser firstLongest = new WordParser("abc");
        WordParser tiedLongest = new WordParser("abc");
        LongestChoice choice =
            new LongestChoice(shortAlternative, firstLongest, tiedLongest);

        try (ParseContext context = context("abcd")) {
            Parsed parsed = choice.parse(context);
            assertTrue(parsed.isSucceeded());
            assertEquals("abc", parsed.getConsumed().source.toString());
            assertSame(firstLongest, context.getChosen(choice).orElseThrow());
        }
    }

    @Test
    public void comparesUnicodeByCodePointCursor() {
        LongestChoice choice = new LongestChoice(
            new WordParser("😀"),
            new WordParser("😀文"));

        try (ParseContext context = context("😀文字")) {
            Parsed parsed = choice.parse(context);
            assertTrue(parsed.isSucceeded());
            assertEquals("😀文", parsed.getConsumed().source.toString());
            assertEquals(2, context.getConsumedPosition().value());
        }
    }

    @Test
    public void allFailureLeavesCursorAtStart() {
        LongestChoice choice =
            new LongestChoice(new WordParser("ab"), new WordParser("ac"));

        try (ParseContext context = context("ax")) {
            assertFalse(choice.parse(context).isSucceeded());
            assertEquals(0, context.getConsumedPosition().value());
            assertTrue(context.getChosen(choice).isEmpty());
        }
    }

    @Test
    public void matchOnlyUsesMatchedCursor() {
        LongestChoice choice =
            new LongestChoice(new WordParser("a"), new WordParser("abc"));

        try (ParseContext context = context("abcd")) {
            assertTrue(choice.parse(context, TokenKind.matchOnly, false).isSucceeded());
            assertEquals(0, context.getConsumedPosition().value());
            assertEquals(3, context.getMatchedPosition().value());
        }
    }

    @Test
    public void enclosingRollbackRemovesNestedChoiceAndNonOrderedMetadata() {
        Choice nestedChoice = new Choice(new WordParser("a"), new WordParser("z"));
        NonOrdered nestedNonOrdered =
            new NonOrdered(new WordParser("a"), new WordParser("b"));
        Parser failedChoiceBranch = new Chain(nestedChoice, new WordParser("x"));
        Parser failedNonOrderedBranch = new Chain(nestedNonOrdered, new WordParser("x"));
        LongestChoice choice = new LongestChoice(
            failedChoiceBranch,
            failedNonOrderedBranch,
            new WordParser("ab"));

        try (ParseContext context = context("ab")) {
            assertTrue(choice.parse(context).isSucceeded());
            assertTrue(context.getChosen(nestedChoice).isEmpty());
            assertNull(context.getOrdered(nestedNonOrdered));
        }
    }

    @Test
    public void winnerReplayIsTheOnlyTransactionalStateThatCommits() {
        State state = new State();
        Parser losing = new MutatingChain(state, 100, "a");
        Parser winner = new MutatingChain(state, 1, "abc");

        try (ParseContext context = context("abc")) {
            context.registerTransactionalState(state);
            assertTrue(new LongestChoice(losing, winner).parse(context).isSucceeded());
            assertEquals(1, state.value);
        }
    }

    @Test
    public void lazyFormAndSafeFailureMemoizationPreserveSemantics() {
        LazyLongestChoice lazy = new LazyLongestChoice() {
            private static final long serialVersionUID = 1L;
            @Override public Parsers getLazyParsers() {
                return new Parsers(new WordParser("a"), new WordParser("abc"));
            }
        };
        assertLongest(lazy, ParseOptions.DEFAULT);
        assertLongest(new MemoSafeLongestChoice(
            new WordParser("a"), new WordParser("abc")),
            ParseOptions.withMemoization(Memoization.SAFE_FAILURES));
    }

    private static void assertLongest(Parser parser, ParseOptions options) {
        try (ParseContext context = ParseContext.withOptions(
                StringSource.createRootSource("abc"), options)) {
            Parsed parsed = parser.parse(context);
            assertTrue(parsed.isSucceeded());
            assertEquals("abc", parsed.getConsumed().source.toString());
        }
    }

    private static ParseContext context(String source) {
        return new ParseContext(StringSource.createRootSource(source));
    }

    private static final class MemoSafeLongestChoice extends LongestChoice
            implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        MemoSafeLongestChoice(Parser... parsers) { super(parsers); }
    }

    private static final class State implements TransactionalState {
        int value;
        @Override public Runnable checkpoint() {
            int saved = value;
            return () -> value = saved;
        }
    }

    private static final class MutatingChain extends Chain implements TransactionListener {
        private static final long serialVersionUID = 1L;
        private final State state;
        private final int amount;

        MutatingChain(State state, int amount, String word) {
            super(new WordParser(word));
            this.state = state;
            this.amount = amount;
        }

        @Override public void setLevel(OutputLevel level) {}
        @Override public void onOpen(ParseContext context) {}
        @Override public void onClose(ParseContext context) {}
        @Override public void onBegin(ParseContext context, Parser parser) {
            if (parser == this) state.value += amount;
        }
        @Override public void onCommit(ParseContext context, Parser parser, TokenList tokens) {}
        @Override public void onRollback(ParseContext context, Parser parser, TokenList tokens) {}
    }
}
