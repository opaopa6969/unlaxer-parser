package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.elementary.WordParser;

public class UniqueLongestChoiceTest {
    private static class MemoChoice extends UniqueLongestChoice implements SafeFailureMemoizable {
        private static final long serialVersionUID = 1L;
        MemoChoice(Parser... children) { super(children); }
    }
    @Test public void tiedMemoFailuresLeaveNoSelectionOrCursorAndReplayDiagnostics() {
        for (Memoization memo : Memoization.values()) {
            var parser = new MemoChoice(new WordParser("😀"), new WordParser("😀"));
            try (var context = ParseContext.withOptions(StringSource.createRootSource("😀"),
                    ParseOptions.withMemoization(memo).withDiagnostics(ParseOptions.Diagnostics.DETAILED))) {
                for (int run = 0; run < 2; run++) {
                    assertFalse(parser.parse(context).isSucceeded());
                    assertEquals(0, context.getConsumedPosition().value());
                    assertEquals(0, context.getMatchedPosition().value());
                    assertTrue(context.getChosen(parser).isEmpty());
                    assertEquals(1, context.getParseFailureDiagnostics().getFarthestOffset());
                    assertTrue(context.getParseFailureDiagnostics().getExpectedTokens().contains("unique longest alternative"));
                }
            }
        }
    }
    @Test public void matchOnlyAndLongerWinnerClearTheShorterTie() {
        var parser = new UniqueLongestChoice(new WordParser("😀"), new WordParser("😀"), new WordParser("😀x"));
        try (var context = new ParseContext(StringSource.createRootSource("😀x"))) {
            assertTrue(parser.parse(context, TokenKind.matchOnly, false).isSucceeded());
            assertEquals(0, context.getConsumedPosition().value());
            assertEquals(2, context.getMatchedPosition().value());
        }
    }
    @Test public void tiedSuccessesRollbackRegisteredStateAndRetainNoCaptures() {
        int[] state = {0};
        Parser mutating = new WordParser("a") {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                Parsed parsed = super.parse(context, kind, invert);
                if (parsed.isSucceeded()) state[0]++;
                return parsed;
            }
        };
        var profile = new UniqueLongestChoice(mutating, new WordParser("a"));
        try (var context = new ParseContext(StringSource.createRootSource("ab"))) {
            context.registerTransactionalState(() -> {
                int saved = state[0];
                return () -> state[0] = saved;
            });
            assertFalse(profile.parse(context).isSucceeded());
            assertEquals(0, state[0]);
            assertTrue(context.getChosen(profile).isEmpty());
            assertTrue(new Choice(profile, new WordParser("ab")).parse(context).isSucceeded());
            assertEquals(0, state[0]);
            assertTrue(context.getChosen(profile).isEmpty());
            assertEquals("ab", context.getCurrent().getTokens().get(0).source.toString());
        }
    }

    @Test public void constructedProfileRejectsExcessiveCandidatesBeforeExecutingAny() {
        var children = new Parser[65];
        java.util.Arrays.fill(children, new WordParser("a"));
        try (var context = new ParseContext(StringSource.createRootSource("a"))) {
            assertFalse(new UniqueLongestChoice(children).parse(context).isSucceeded());
            assertEquals(0, context.getConsumedPosition().value());
            assertTrue(context.getParseFailureDiagnostics().getExpectedTokens().contains("2 to 64 unique longest alternatives"));
        }
    }
}
