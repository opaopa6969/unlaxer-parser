package org.unlaxer.context;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.unlaxer.Name;
import org.unlaxer.StringSource;
import org.unlaxer.TokenList;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.elementary.WordParser;

public class TransactionalStateTest {
    private static class State implements TransactionalState {
        int value;
        int checkpoints;
        public Runnable checkpoint() {
            checkpoints++;
            int saved = value;
            return () -> value = saved;
        }
    }

    @Test public void duplicateRegistrationDoesNotDuplicateSnapshotsOrCopyUnownedObjects() {
        try (ParseContext ctx = new ParseContext(StringSource.createRootSource(""))) {
            State state = new State();
            state.value = 7;
            ctx.registerTransactionalState(state);
            ctx.registerTransactionalState(state);
            List<String> unowned = new ArrayList<>();
            ctx.getGlobalScopeTreeMap().put(Name.of("unowned"), unowned);
            Parser parser = new WordParser("x");
            ctx.begin(parser);
            ctx.registerTransactionalState(state);
            assertEquals(1, state.checkpoints);
            state.value = 9;
            unowned.add("not transactional");
            ctx.rollback(parser);
            assertEquals(7, state.value);
            assertEquals(List.of("not transactional"), unowned);
        }
    }

    private static final class BeginsWithMutation extends Chain implements TransactionListener {
        final State state;
        BeginsWithMutation(State state) { super(new WordParser("a")); this.state = state; }
        public void setLevel(OutputLevel level) {}
        public void onOpen(ParseContext ctx) {}
        public void onClose(ParseContext ctx) {}
        public void onBegin(ParseContext ctx, Parser parser) { state.value++; }
        public void onRollback(ParseContext ctx, Parser parser, TokenList tokens) {
            assertEquals(8, state.value); // rollback listeners run before state restoration
        }
    }

    @Test public void snapshotPrecedesBeginAndRestoreFollowsRollbackNotification() {
        try (ParseContext ctx = new ParseContext(StringSource.createRootSource(""))) {
            State state = new State();
            state.value = 7;
            ctx.registerTransactionalState(state);
            Parser parser = new BeginsWithMutation(state);
            ctx.begin(parser);
            assertEquals(8, state.value);
            ctx.rollback(parser);
            assertEquals(7, state.value);
        }
    }
}
