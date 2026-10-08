package org.unlaxer.parser.combinator;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.TransactionalState;
import org.unlaxer.parser.elementary.WordParser;

/** Recovery spans and messages are committed CST state, not speculative side effects. */
public class RecoveryDiagnosticTest {
    private static final String MESSAGE = "syntax error: skipped to sync point";

    @Test public void syncIsCodePointSafeAndTakesLongestTokenAtTheNearestPosition() {
        var parser = new SyncPointRecoveryParser(new WordParser("ok"), ";", ";;");
        try (var context = new ParseContext(StringSource.createRootSource("😀x;;tail"))) {
            assertTrue(parser.parse(context).isSucceeded());
            assertEquals(4, context.getConsumedPosition().value());
            assertEquals(List.of(new RecoveryDiagnostic(0, 4, MESSAGE)),
                RecoveryDiagnostic.from(context));
            assertEquals(RecoveryDiagnostic.from(context), RecoveryDiagnostic.from(
                context.getCurrent().getTokens().get(0)));
        }
    }

    @Test public void beforeSyncAndSkipKeepDelimiterAndAlwaysAdvance() {
        var child = new WordParser("ok");
        try (var context = new ParseContext(StringSource.createRootSource("😀x;tail"))) {
            assertTrue(new SyncPointRecoveryParser(child, SyncPointRecoveryParser.Mode.BEFORE_SYNC, ";")
                .parse(context).isSucceeded());
            assertEquals(2, context.getConsumedPosition().value());
            assertEquals(List.of(new RecoveryDiagnostic(0, 2, MESSAGE)), RecoveryDiagnostic.from(context));
        }
        try (var context = new ParseContext(StringSource.createRootSource(";tail"))) {
            assertTrue(new SyncPointRecoveryParser(child, SyncPointRecoveryParser.Mode.BEFORE_SYNC, ";")
                .parse(context).isFailed());
            assertTrue(RecoveryDiagnostic.from(context).isEmpty());
        }
        try (var context = new ParseContext(StringSource.createRootSource("😀;tail"))) {
            assertTrue(new SyncPointRecoveryParser(child, SyncPointRecoveryParser.Mode.SKIP, ";")
                .parse(context).isSucceeded());
            assertEquals(1, context.getConsumedPosition().value());
        }
        try (var context = new ParseContext(StringSource.createRootSource(";tail"))) {
            assertTrue(new SyncPointRecoveryParser(child, SyncPointRecoveryParser.Mode.SKIP, ";")
                .parse(context).isSucceeded());
            assertEquals(1, context.getConsumedPosition().value());
        }
        try (var context = new ParseContext(StringSource.createRootSource("😀x"))) {
            assertTrue(new SyncPointRecoveryParser(child, SyncPointRecoveryParser.Mode.SKIP, ";")
                .parse(context).isSucceeded());
            assertEquals(2, context.getConsumedPosition().value());
        }
        try (var context = new ParseContext(StringSource.createRootSource("😀x"))) {
            assertTrue(new SyncPointRecoveryParser(child, SyncPointRecoveryParser.Mode.SKIP)
                .parse(context).isSucceeded());
            assertEquals(1, context.getConsumedPosition().value());
        }
    }

    @Test public void invalidTokensAndMissingSyncDoNotSucceed() {
        assertThrows(IllegalArgumentException.class,
            () -> new SyncPointRecoveryParser(new WordParser("ok"), ""));
        assertThrows(IllegalArgumentException.class,
            () -> new SyncPointRecoveryParser(new WordParser("ok"), ";", ";"));
        try (var context = new ParseContext(StringSource.createRootSource("😀x"))) {
            assertTrue(new SyncPointRecoveryParser(new WordParser("ok"), ";")
                .parse(context).isFailed());
            assertTrue(RecoveryDiagnostic.from(context).isEmpty());
        }
    }

    @Test public void lookaheadRecoverySucceedsWithoutPublishingMarker() {
        var recovery = new SyncPointRecoveryParser(new WordParser("ok"), ";");
        try (var context = new ParseContext(StringSource.createRootSource("bad;"))) {
            assertTrue(new MatchOnly(recovery).parse(context).isSucceeded());
            assertEquals(0, context.getConsumedPosition().value());
            assertEquals(4, context.getMatchedPosition().value());
            assertTrue(RecoveryDiagnostic.from(context).isEmpty());
        }
    }


    private static final class State implements TransactionalState {
        int value = 7;
        @Override public Runnable checkpoint() {
            int before = value;
            return () -> value = before;
        }
    }

    @Test public void childAndOuterFailureRollbackStateAndRecoveryMarker() {
        State state = new State();
        var child = new WordParser("ok") {
            private static final long serialVersionUID = 1L;
            @Override public org.unlaxer.Parsed parse(ParseContext context,
                    org.unlaxer.TokenKind kind, boolean invert) {
                state.value = 99;
                return super.parse(context, kind, invert);
            }
        };
        var recovery = new SyncPointRecoveryParser(child, ";");
        try (var context = new ParseContext(StringSource.createRootSource("bad;!"))) {
            context.registerTransactionalState(state);
            assertTrue(new Chain(recovery, new WordParser("?" )).parse(context).isFailed());
            assertEquals(7, state.value);
            assertEquals(0, context.getConsumedPosition().value());
            assertTrue(RecoveryDiagnostic.from(context).isEmpty());
        }
    }
}
