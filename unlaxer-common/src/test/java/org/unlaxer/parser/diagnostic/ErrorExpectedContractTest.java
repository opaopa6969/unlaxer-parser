package org.unlaxer.parser.diagnostic;

import static org.junit.Assert.*;

import java.util.Optional;
import java.util.Set;
import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.TransactionalState;
import org.unlaxer.parser.ErrorMessageParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.elementary.WordParser;

/** Java oracle for the ERROR element's Rust generation contract (#329). */
public class ErrorExpectedContractTest {
    @Test public void blankHintsKeepFailurePositionAndJavaNativeParserNameFallback() {
        for (String message : new String[] {
            "", " \t\n", "\u001c", "\u2003", "\u3000", "\u0085", "\u00a0",
            "\u2007", "\u202f", "\ufeff", "  required 😀  ", "''"
        }) {
            var error = ErrorMessageParser.expected(message);
            assertEquals(message.isBlank() ? Optional.empty() : Optional.of(message),
                error.expectedDisplayText());
            try (var context = new ParseContext(StringSource.createRootSource("😀x"))) {
                assertTrue(new Chain(new WordParser("😀"), error).parse(context).isFailed());
                assertEquals(0, context.getConsumedPosition().value());
                assertEquals(0, context.getMatchedPosition().value());
                var diagnostic = context.getParseFailureDiagnostics();
                assertEquals(1, diagnostic.getFarthestOffset());
                // No explicit hint, but the Java diagnostics layer adds its native parser name.
                assertEquals(message.isBlank() ? Set.of("ErrorMessageParser") : Set.of(message),
                    diagnostic.getExpectedTokens());
            }
        }
    }

    @Test public void errorIsAFailingAlternativeNotACutOrRecoveryNode() {
        try (var context = new ParseContext(StringSource.createRootSource("ok"))) {
            var parser = new Choice(ErrorMessageParser.expected("first failed"), new WordParser("ok"));
            assertTrue(parser.parse(context).isSucceeded());
            assertEquals(2, context.getConsumedPosition().value());
        }
    }

    private static final class State implements TransactionalState {
        int value = 7;
        int mutationCalls;
        @Override public Runnable checkpoint() {
            int saved = value;
            return () -> value = saved;
        }
    }

    @Test public void failedBranchRestoresStateAfterARealMutation() {
        State state = new State();
        WordParser mutation = new WordParser("😀") {
            private static final long serialVersionUID = 1L;
            @Override public Parsed parse(ParseContext context, TokenKind kind, boolean invert) {
                state.value = 99;
                state.mutationCalls++;
                return super.parse(context, kind, invert);
            }
        };
        try (var context = new ParseContext(StringSource.createRootSource("😀x"))) {
            context.registerTransactionalState(state);
            assertTrue(new Chain(mutation, ErrorMessageParser.expected("required")).parse(context).isFailed());
            assertEquals(1, state.mutationCalls);
            assertEquals(7, state.value);
            assertEquals(0, context.getConsumedPosition().value());
            assertEquals(0, context.getMatchedPosition().value());
            assertEquals(1, context.getParseFailureDiagnostics().getFarthestOffset());
        }
    }
}
