package org.unlaxer.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.DoConsumePropagationStopper;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
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

    /** Fails at offset 1 expecting "q"; popped with a failure inside MiddleSucceeds. */
    static final class InnerFailsAtOne extends LazyChain implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        @Override public Parsers getLazyParsers() {
            return new Parsers(new WordParser("a"), new WordParser("q"));
        }
    }

    /** Succeeds on its second alternative, so its frame is popped by a success. */
    static final class MiddleSucceeds extends LazyChoice implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        @Override public Parsers getLazyParsers() {
            return new Parsers(new InnerFailsAtOne(), new WordParser("a"));
        }
    }

    /** Fails at offset 2 expecting "m"; popped with a failure. */
    static final class DeepFailsAtTwo extends LazyChain implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        @Override public Parsers getLazyParsers() {
            return new Parsers(new WordParser("b"), new WordParser("m"));
        }
    }

    static final class OuterNested extends LazyChain implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        @Override public Parsers getLazyParsers() {
            return new Parsers(new MiddleSucceeds(), new DeepFailsAtTwo());
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
            assertTrue(context.expectedParsersOf(local).stream().anyMatch(expected -> expected.contains("x")));
            assertTrue(context.expectedParsersOf(local).stream().noneMatch("z"::equals));
        }
    }

    @Test
    public void nestedFramesMergeIntoTheOuterFrameOnSuccessAndFailurePops() {
        String expectedM = new WordParser("m").expectedDisplayTexts().get(0);
        String expectedQ = new WordParser("q").expectedDisplayTexts().get(0);
        OuterNested outer = new OuterNested();
        try (ParseContext context = context()) {
            ParseContext.FailureDiagnostic frame = context.beginMemoDiagnosticFrame();
            assertTrue(outer.parse(context).isFailed());
            context.discardMemoDiagnosticFrame(frame);

            // The inner failure at 1 was superseded by the deeper failure at 2 inside the same frame,
            // whether the intermediate frames were popped by success (middle) or failure (deep).
            assertEquals(2, frame.farthestFailureOffset);
            assertEquals(2, frame.maxReachedOffset);
            java.util.List<String> expected = context.expectedParsersOf(frame);
            assertTrue(expected.toString(), expected.contains(expectedM));
            assertTrue(expected.toString(), !expected.contains(expectedQ));

            ParseFailureDiagnostics first = context.getParseFailureDiagnostics();
            assertEquals(2, first.getFarthestOffset());
            assertTrue(first.getExpectedParsers().contains(expectedM));

            // A memo hit replays the stored frame and must reproduce the same diagnostics.
            assertTrue(outer.parse(context).isFailed());
            assertEquals(1, context.getPackratMemoTable().failureHits());
            ParseFailureDiagnostics replayed = context.getParseFailureDiagnostics();
            assertEquals(first.getExpectedParsers(), replayed.getExpectedParsers());
            assertEquals(first.getFarthestOffset(), replayed.getFarthestOffset());
            assertEquals(first.getMaxReachedStackElements().size(), replayed.getMaxReachedStackElements().size());
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
            assertTrue(context.expectedParsersOf(outer).isEmpty());
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
