package org.unlaxer.parser.combinator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.context.SafeFailureMemoizable;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.SpaceParser;

public class PredictiveChoiceTest {

    @Test
    public void skipsOnlyProvablyImpossibleAlternatives() {
        Parser impossible = new WordParser("a") {
            private static final long serialVersionUID = 1L;
            @Override public org.unlaxer.Token getToken(
                    ParseContext context, org.unlaxer.TokenKind kind, boolean invert) {
                throw new AssertionError("an impossible branch was evaluated");
            }
        };
        WordParser winner = new WordParser("b");
        PredictiveChoice choice = new PredictiveChoice(
            List.of(ChoicePredictor.literal("a"), ChoicePredictor.literal("b")),
            impossible, winner);

        try (ParseContext context = context("b")) {
            assertTrue(choice.parse(context).isSucceeded());
            assertSame(winner, context.getChosen(choice).orElseThrow());
        }
    }

    @Test
    public void keepsDeclarationOrderForOverlappingPredictors() {
        WordParser first = new WordParser("a");
        PredictiveChoice choice = new PredictiveChoice(
            List.of(ChoicePredictor.literal("a"), ChoicePredictor.literal("ab")),
            first, new WordParser("ab"));

        try (ParseContext context = context("ab")) {
            var parsed = choice.parse(context);
            assertTrue(parsed.isSucceeded());
            assertEquals("a", parsed.getConsumed().source.toString());
            assertSame(first, context.getChosen(choice).orElseThrow());
        }
    }

    @Test
    public void retriesFullOrderedChoiceAfterNarrowFastPathFails() {
        WordParser fallbackWinner = new WordParser("ac");
        PredictiveChoice choice = new PredictiveChoice(
            List.of(ChoicePredictor.literal("a"), ChoicePredictor.literal("z")),
            new WordParser("ab"), fallbackWinner);

        try (ParseContext context = context("ac")) {
            assertTrue(choice.parse(context).isSucceeded());
            assertSame(fallbackWinner, context.getChosen(choice).orElseThrow());
        }
    }

    @Test
    public void unknownPredictorNeverPrunes() {
        WordParser first = new WordParser("x");
        PredictiveChoice choice = new PredictiveChoice(
            List.of(ChoicePredictor.any(), ChoicePredictor.literal("x")),
            first, new WordParser("x"));
        try (ParseContext context = context("x")) {
            assertTrue(choice.parse(context).isSucceeded());
            assertSame(first, context.getChosen(choice).orElseThrow());
        }
    }

    @Test
    public void leadingTriviaConservativelyKeepsLiteralAlternative() {
        Parser first = new Chain(new SpaceParser(), new WordParser("alpha"));
        PredictiveChoice choice = new PredictiveChoice(
            List.of(ChoicePredictor.literal("alpha"), ChoicePredictor.any()),
            first, new WordParser(" "));
        try (ParseContext context = context(" alpha")) {
            assertTrue(choice.parse(context).isSucceeded());
            assertSame(first, context.getChosen(choice).orElseThrow());
        }
    }

    @Test
    public void builtInTokenPredictorsUseOnlyNecessaryFirstCharacters() {
        assertPredictor(ChoicePredictor.number(), true, "+1", "-1", ".5", "9", " 1");
        assertPredictor(ChoicePredictor.number(), false, "a", "'1'");
        assertPredictor(ChoicePredictor.identifier(), true, "a", "Z", "_name", " name");
        assertPredictor(ChoicePredictor.identifier(), false, "9", "-name");
        assertPredictor(ChoicePredictor.quoted('\''), true, "'text'", " 'text'");
        assertPredictor(ChoicePredictor.quoted('\''), false, "\"text\"");
        assertPredictor(ChoicePredictor.quoted('"'), true, "\"text\"");
        assertPredictor(ChoicePredictor.quoted('"'), false, "'text'");
    }

    @Test
    public void failureFallbackKeepsChoiceDiagnostics() {
        var ordinaryDiagnostics = failureDiagnostics(
            new Choice(new WordParser("ab"), new WordParser("ac")));
        var predictiveDiagnostics = failureDiagnostics(new PredictiveChoice(
            List.of(ChoicePredictor.literal("a"), ChoicePredictor.literal("z")),
            new WordParser("ab"), new WordParser("ac")));
        assertEquals(ordinaryDiagnostics.getFarthestOffset(), predictiveDiagnostics.getFarthestOffset());
        assertEquals(ordinaryDiagnostics.getExpectedTokens(), predictiveDiagnostics.getExpectedTokens());
        assertEquals(ordinaryDiagnostics.getExpectedParsers(), predictiveDiagnostics.getExpectedParsers());
    }

    @Test
    public void safeFailureMemoizationShortCircuitsARepeatedFailure() {
        AtomicInteger attempts = new AtomicInteger();
        WordParser counted = new WordParser("a") {
            private static final long serialVersionUID = 1L;
            @Override public org.unlaxer.Token getToken(
                    ParseContext context, org.unlaxer.TokenKind kind, boolean invert) {
                attempts.incrementAndGet();
                return super.getToken(context, kind, invert);
            }
        };
        PredictiveChoice choice = new MemoSafePredictiveChoice(
            List.of(ChoicePredictor.any(), ChoicePredictor.literal("b")),
            counted, new WordParser("b"));
        try (ParseContext context = ParseContext.withOptions(
                StringSource.createRootSource("z"),
                ParseOptions.withMemoization(Memoization.SAFE_FAILURES))) {
            assertTrue(choice.parse(context).isFailed());
            int firstAttempts = attempts.get();
            assertTrue(choice.parse(context).isFailed());
            assertEquals(firstAttempts, attempts.get());
        }
    }

    private static org.unlaxer.context.ParseFailureDiagnostics failureDiagnostics(Parser parser) {
        try (ParseContext context = context("ax")) {
            assertTrue(parser.parse(context).isFailed());
            return context.getParseFailureDiagnostics();
        }
    }

    private static void assertPredictor(
            ChoicePredictor predictor, boolean expected, String... sources) {
        for (String source : sources) {
            try (ParseContext context = context(source)) {
                if (expected) assertTrue(source, predictor.mayMatch(context, org.unlaxer.TokenKind.consumed));
                else assertFalse(source, predictor.mayMatch(context, org.unlaxer.TokenKind.consumed));
            }
        }
    }

    private static final class MemoSafePredictiveChoice extends PredictiveChoice
            implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        MemoSafePredictiveChoice(List<ChoicePredictor> predictors, Parser... parsers) {
            super(predictors, parsers);
        }
    }

    private static ParseContext context(String source) {
        return new ParseContext(StringSource.createRootSource(source));
    }
}
