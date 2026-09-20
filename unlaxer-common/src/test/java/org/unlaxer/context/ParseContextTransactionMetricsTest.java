package org.unlaxer.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.WordParser;

public class ParseContextTransactionMetricsTest {
    private final Parser parser = new WordParser("x");

    @Test public void emptyCommitAndRollbackHaveNoStateSnapshotPayload() {
        try (ParseContext context = context()) {
            context.enableTransactionMetrics();
            context.begin(parser);
            context.commit(parser, TokenKind.consumed);
            context.begin(parser);
            context.rollback(parser);

            assertEquals(new TransactionMetrics(2, 1, 1, 0, 2, 0),
                context.snapshotTransactionMetrics());
        }
    }

    @Test public void eagerLateRegistrationSnapshotsEveryOpenFrame() {
        try (ParseContext context = context()) {
            context.enableTransactionMetrics();
            context.begin(parser);
            context.begin(parser);
            context.registerTransactionalState(() -> () -> {});
            context.commit(parser, TokenKind.consumed);
            context.rollback(parser);

            assertEquals(new TransactionMetrics(2, 1, 1, 2, 0, 2),
                context.snapshotTransactionMetrics());
        }
    }

    @Test public void mutationAwareStateSnapshotsOncePerOpenFrameOnlyWhenMutated() {
        try (ParseContext context = context()) {
            LazyState state = new LazyState();
            context.registerTransactionalState(state);
            context.enableTransactionMetrics();

            context.begin(parser);
            context.begin(parser);
            state.set(context, 1);
            state.set(context, 2);
            context.commit(parser, TokenKind.consumed);
            context.rollback(parser);

            assertEquals(0, state.value);
            assertEquals(2, state.checkpoints);
            assertEquals(new TransactionMetrics(2, 1, 1, 2, 0, 2),
                context.snapshotTransactionMetrics());
        }
    }

    @Test public void unusedMutationAwareStateLeavesCheckpointPayloadEmpty() {
        try (ParseContext context = context()) {
            LazyState state = new LazyState();
            context.registerTransactionalState(state);
            context.enableTransactionMetrics();
            context.begin(parser);
            context.commit(parser, TokenKind.consumed);

            assertEquals(0, state.checkpoints);
            assertEquals(new TransactionMetrics(1, 1, 0, 0, 1, 0),
                context.snapshotTransactionMetrics());
        }
    }

    @Test public void mutationBarrierRejectsUnregisteredAndEagerOwners() {
        try (ParseContext context = context()) {
            LazyState unregistered = new LazyState();
            assertThrows(IllegalStateException.class,
                () -> context.beforeTransactionalStateMutation(unregistered));
            TransactionalState eager = () -> () -> {};
            context.registerTransactionalState(eager);
            assertThrows(IllegalArgumentException.class,
                () -> context.beforeTransactionalStateMutation(eager));
        }
    }

    private ParseContext context() {
        return new ParseContext(StringSource.createRootSource(""));
    }

    private static final class LazyState implements MutationAwareTransactionalState {
        int value;
        int checkpoints;

        void set(ParseContext context, int next) {
            context.beforeTransactionalStateMutation(this);
            value = next;
        }

        @Override public Runnable checkpoint() {
            checkpoints++;
            int saved = value;
            return () -> value = saved;
        }
    }
}
