package org.unlaxer.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.DoConsumePropagationStopper;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.Not;
import org.unlaxer.parser.elementary.WordParser;

public class MemoDiagnosticFrameTest {

    static final class FarAtTwo extends LazyChain implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        @Override public Parsers getLazyParsers() {
            return new Parsers(new WordParser("a"), new WordParser("b"), new WordParser("z"));
        }
    }

    static final class LocalAtOne extends LazyChain implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        @Override public Parsers getLazyParsers() {
            return new Parsers(new WordParser("a"), new WordParser("x"));
        }
    }

    static final class UnsafeAtOne extends LazyChain {
        private static final long serialVersionUID = 1L;
        @Override public Parsers getLazyParsers() {
            return new Parsers(new WordParser("a"), new WordParser("x"));
        }
    }

    private ParseContext context() {
        return ParseContext.withOptions(StringSource.createRootSource("aby"),
            ParseOptions.withMemoization(Memoization.SAFE_FAILURES));
    }

    @Test
    public void localFrameDoesNotCaptureEarlierSiblingDiagnostic() {
        try (ParseContext context = context()) {
            assertTrue(new FarAtTwo().parse(context).isFailed());
            assertEquals(2, context.getParseFailureDiagnostics().getFarthestOffset());

            ParseContext.FailureDiagnostic local = context.beginMemoDiagnosticFrame();
            assertTrue(new LocalAtOne().parse(context).isFailed());
            context.discardMemoDiagnosticFrame(local);

            assertEquals("only the rule-local failure belongs to the frame", 1,
                local.farthestFailureOffset);
            assertTrue(local.expectedParsers.stream().anyMatch(expected -> expected.contains("x")));
            assertTrue(local.expectedParsers.stream().noneMatch("z"::equals));
        }
    }

    @Test
    public void offAndUnsafeRulesDoNotAllocateDiagnosticFrames() {
        try (ParseContext off = new ParseContext(StringSource.createRootSource("aby"))) {
            assertNull(PackratMemoTable.beginFailure(off, new LocalAtOne()));
        }
        try (ParseContext on = context()) {
            assertNull(PackratMemoTable.beginFailure(on, new UnsafeAtOne()));
        }
    }

    @Test
    public void successfulNegativeLookaheadDoesNotPolluteOuterMemoFrame() {
        try (ParseContext context = context()) {
            ParseContext.FailureDiagnostic outer = context.beginMemoDiagnosticFrame();
            assertTrue(new Not(new LocalAtOne()).parse(context).isSucceeded());
            context.discardMemoDiagnosticFrame(outer);

            assertEquals(-1, outer.farthestFailureOffset);
            assertTrue(outer.expectedParsers.isEmpty());
            assertEquals(-1, context.farthestFailureOffset);
        }
    }

    @Test
    public void memoHitRebasesLocalStackWithoutRevivingSuccessfulLookaheadParent() {
        LocalAtOne parser = new LocalAtOne();
        try (ParseContext context = context()) {
            assertTrue(new Not(new DoConsumePropagationStopper(parser)).parse(context).isSucceeded());
            assertTrue(parser.parse(context).isFailed());
            assertEquals(1, context.getPackratMemoTable().failureHits());

            var stack = context.getParseFailureDiagnostics().getMaxReachedStackElements();
            assertTrue(stack.stream().anyMatch(element ->
                element.getParserClassName().equals(LocalAtOne.class.getSimpleName())));
            assertTrue(stack.stream().noneMatch(element ->
                element.getParserClassName().equals(Not.class.getSimpleName())));
            for (int index = 0; index < stack.size(); index++) {
                assertEquals("rebased stack depth", index, stack.get(index).getDepth());
            }
        }
    }
}
