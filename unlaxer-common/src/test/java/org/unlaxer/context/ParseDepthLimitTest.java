package org.unlaxer.context;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.Not;
import org.unlaxer.parser.elementary.WordParser;

public class ParseDepthLimitTest {
    private static final class State implements TransactionalState {
        int value = 19;
        public Runnable checkpoint() { int saved = value; return () -> value = saved; }
    }
    private static final class Recursive extends LazyChoice {
        final State state;
        Recursive(State state) { this.state = state; }
        public Parsers getLazyParsers() {
            var step = new WordParser("a") {
                @Override public Token getToken(ParseContext context, TokenKind kind, boolean invert) {
                    state.value++;
                    context.choose(Recursive.this, this);
                    return super.getToken(context, kind, invert);
                }
            };
            return Parsers.of(new Chain(step, this), new WordParser("x"));
        }
    }

    @Test public void resourceAbortClosesOwnedStateAndSelectionsWithoutASecondFailure() {
        for (var diagnostics : new ParseOptions.Diagnostics[]{ParseOptions.Diagnostics.DETAILED,
                ParseOptions.Diagnostics.DETAILED_ON_FAILURE}) {
            for (var memo : Memoization.values()) {
                State state = new State();
                Recursive parser = new Recursive(state);
                Parser previous = new WordParser("previous");
                AtomicInteger closed = new AtomicInteger();
                ParseContext context = ParseContext.withOptions(StringSource.createRootSource("a".repeat(10_000) + "x"),
                    ParseOptions.withMemoization(memo).withDiagnostics(diagnostics));
                context.choose(parser, previous);
                context.registerTransactionalState(state);
                context.addTransactionListener(Name.of("close"), new TransactionListener() {
                    public void setLevel(OutputLevel level) {}
                    public void onOpen(ParseContext ctx) {}
                    public void onBegin(ParseContext ctx, Parser owner) {}
                    public void onClose(ParseContext ctx) {
                        closed.incrementAndGet();
                        assertEquals(19, state.value);
                        assertSame(previous, ctx.chosenParserByChoice.get(parser));
                        assertEquals(1, ctx.getTokenStack().size());
                    }
                });
                ParseLimitException failure;
                try (context) {
                    failure = assertThrows(ParseLimitException.class, () -> parser.parse(context));
                    assertEquals(ParseLimitException.CODE, failure.code());
                    assertEquals(ParseContext.MAX_PARSE_DEPTH, failure.limit());
                    assertTrue(failure.offset() > 0 && failure.offset() < 10_000);
                    assertSame(failure, assertThrows(ParseLimitException.class, () -> new WordParser("a").parse(context)));
                }
                assertEquals(0, failure.getSuppressed().length);
                assertEquals(1, closed.get());
                assertNull(ParseContext.getParseContextWithCurrentThread());
                try (var fresh = new ParseContext(StringSource.createRootSource("aaax"))) {
                    assertTrue(parser.parse(fresh).isSucceeded());
                    assertTrue(fresh.allConsumed());
                }
            }
        }
    }

    @Test public void negativeAssertionCannotTreatAResourceAbortAsASyntaxMismatch() {
        String input = "a".repeat(10_000) + "x";
        Parser parser = new Chain(new Not(new Recursive(new State())), new WordParser(input));
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            assertThrows(ParseLimitException.class, () -> parser.parse(context));
        }
    }

    @Test public void unrelatedUnbalancedTransactionsStillFailOnClose() {
        ParseContext context = new ParseContext(StringSource.createRootSource("x"));
        context.begin(new WordParser("x"));
        assertThrows(IllegalStateException.class, context::close);
    }
}
