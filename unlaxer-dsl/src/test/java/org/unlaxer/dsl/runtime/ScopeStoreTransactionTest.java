package org.unlaxer.dsl.runtime;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.Parsed;
import org.unlaxer.context.ParseContext;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.Not;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.dsl.runtime.ScopeStore.Severity;

public class ScopeStoreTransactionTest {
    private static class DeclaringParser extends Chain implements TransactionListener {
        DeclaringParser() { super(new WordParser("a")); }
        public void setLevel(OutputLevel level) {}
        public void onOpen(ParseContext ctx) {}
        public void onClose(ParseContext ctx) {}
        public void onBegin(ParseContext ctx, Parser parser) {}
        public void onCommit(ParseContext ctx, Parser parser, TokenList tokens) {
            int offset = ctx.getMatchedPosition().value();
            ScopeStore.declare(ctx, "decl" + offset, offset);
            ScopeStore.addReference(ctx, "ref", offset, 1);
            ScopeStore.addDiagnostic(ctx, "warning", offset, 1, Severity.WARNING);
        }
    }

    @Test public void mutationsAdvanceVersionAndRollbackRestoresTheSnapshot() {
        try (var ctx = new ParseContext(StringSource.createRootSource(""))) {
            var parser = new WordParser("x");
            long initial = ctx.getMemoizationStateVersion();
            ctx.begin(parser);
            ScopeStore.enter(ctx);
            long entered = ctx.getMemoizationStateVersion();
            ScopeStore.declare(ctx, "local", 1);
            long declared = ctx.getMemoizationStateVersion();
            ScopeStore.addReference(ctx, "local", 1, 1);
            long referenced = ctx.getMemoizationStateVersion();
            ScopeStore.addDiagnostic(ctx, "diagnostic", 1, 1, Severity.INFO);
            long diagnosed = ctx.getMemoizationStateVersion();
            assertTrue(entered > initial);
            assertTrue(declared > entered);
            assertTrue(referenced > declared);
            assertTrue(diagnosed > referenced);
            ctx.rollback(parser);
            assertEquals(initial, ctx.getMemoizationStateVersion());
        }
    }

    @Test public void failedChoiceAndRepeatAttemptsDoNotPublishCommittedChildState() {
        Parser attempt = new Chain(new DeclaringParser(), new WordParser("!"));
        try (var ctx = new ParseContext(StringSource.createRootSource("a?"))) {
            assertTrue(new Choice(attempt, new WordParser("a?")).parse(ctx).isSucceeded());
            assertTrue(ctx.allConsumed());
            assertTrue(ScopeStore.getAllDeclarations(ctx).isEmpty());
            assertTrue(ScopeStore.getAllReferences(ctx).isEmpty());
            assertTrue(ScopeStore.getDiagnostics(ctx).isEmpty());
        }
        try (var ctx = new ParseContext(StringSource.createRootSource("a!a?"))) {
            assertTrue(new ZeroOrMore(attempt).parse(ctx).isSucceeded());
            assertEquals(2, ctx.getConsumedPosition().value());
            assertEquals(List.of(new ScopeStore.SymbolInfo("decl1", 1)), ScopeStore.getAllDeclarations(ctx));
            assertEquals(1, ScopeStore.getAllReferences(ctx).size());
            assertEquals(1, ScopeStore.getDiagnostics(ctx).size());
        }
    }

    @Test public void positiveAndNegativeLookaheadRestoreStateButKeepTheirCursorContract() {
        try (var ctx = new ParseContext(StringSource.createRootSource("a"))) {
            assertTrue(new MatchOnly(new DeclaringParser()).parse(ctx).isSucceeded());
            assertEquals(0, ctx.getConsumedPosition().value());
            assertEquals(1, ctx.getMatchedPosition().value());
            assertTrue(ScopeStore.getAllDeclarations(ctx).isEmpty());
            assertTrue(ScopeStore.getAllReferences(ctx).isEmpty());
            assertTrue(ScopeStore.getDiagnostics(ctx).isEmpty());
        }
        try (var ctx = new ParseContext(StringSource.createRootSource("a"))) {
            assertTrue(new Not(new DeclaringParser()).parse(ctx).isFailed());
            assertEquals(0, ctx.getConsumedPosition().value());
            assertEquals(0, ctx.getMatchedPosition().value());
            assertTrue(ScopeStore.getAllDeclarations(ctx).isEmpty());
        }
    }

    @Test public void childDeclarationsAreVisibleUntilTheWholeLookaheadFinishes() {
        Parser readsDeclaration = new Chain(new WordParser("b")) {
            @Override public Parsed parse(ParseContext ctx, TokenKind kind, boolean invert) {
                assertTrue(ScopeStore.isDeclared(ctx, "decl1"));
                return super.parse(ctx, kind, invert);
            }
        };
        try (var ctx = new ParseContext(StringSource.createRootSource("ab"))) {
            var lookahead = new MatchOnly(new Chain(new DeclaringParser(), readsDeclaration));
            assertTrue(lookahead.parse(ctx).isSucceeded());
            assertEquals(0, ctx.getConsumedPosition().value());
            assertEquals(2, ctx.getMatchedPosition().value());
            assertFalse(ScopeStore.isDeclared(ctx, "decl1"));
            assertTrue(ScopeStore.getAllDeclarations(ctx).isEmpty());
        }
    }

    @Test public void rollbackListenerCanInitializeStateAndThrowWithoutLeavingMutations() {
        Parser parser = new DeclaringParser() {
            @Override public void onRollback(ParseContext ctx, Parser parser, TokenList tokens) {
                ScopeStore.enter(ctx);
                ScopeStore.declare(ctx, "discard", 7);
                throw new IllegalStateException("listener failure");
            }
        };
        try (var ctx = new ParseContext(StringSource.createRootSource(""))) {
            ctx.begin(parser);
            assertThrows(IllegalStateException.class, () -> ctx.rollback(parser));
            assertEquals(0, ScopeStore.currentScopeDepth(ctx));
            assertTrue(ScopeStore.getAllDeclarations(ctx).isEmpty());
        }
    }

    private static class CommittingNot extends Not implements TransactionListener {
        CommittingNot() { super(new WordParser("z")); }
        public void setLevel(OutputLevel level) {}
        public void onOpen(ParseContext ctx) {}
        public void onClose(ParseContext ctx) {}
        public void onBegin(ParseContext ctx, Parser parser) {}
        public void onCommit(ParseContext ctx, Parser parser, TokenList tokens) {
            ScopeStore.declare(ctx, "discard", 4);
        }
    }

    @Test public void successfulNegativeLookaheadRestoresItsOwnLazyCommitListenerState() {
        try (var ctx = new ParseContext(StringSource.createRootSource("a"))) {
            assertTrue(new CommittingNot().parse(ctx).isSucceeded());
            assertEquals(0, ctx.getConsumedPosition().value());
            assertTrue(ScopeStore.getAllDeclarations(ctx).isEmpty());
        }
    }

    @Test public void lazyInitializationAndNestedCommitAreRevertedByOuterRollback() {
        try (var ctx = new ParseContext(StringSource.createRootSource(""))) {
            var parser = new WordParser("x");
            ctx.begin(parser);
            ctx.begin(parser);
            ScopeStore.enter(ctx);
            ScopeStore.declare(ctx, "discard", 3);
            ScopeStore.addReference(ctx, "discard", 5, 7);
            ScopeStore.addDiagnostic(ctx, "discard", 5, 7, Severity.WARNING);
            ctx.commit(parser, TokenKind.consumed);
            ctx.rollback(parser);
            assertEquals(0, ScopeStore.currentScopeDepth(ctx));
            assertFalse(ScopeStore.isDeclared(ctx, "discard"));
            assertTrue(ScopeStore.getAllDeclarations(ctx).isEmpty());
            assertTrue(ScopeStore.getAllReferences(ctx).isEmpty());
            assertTrue(ScopeStore.getDiagnostics(ctx).isEmpty());
        }
    }

    @Test public void poppedScopesOverwritesAndClearedDiagnosticsReturnOnRollback() {
        try (var ctx = new ParseContext(StringSource.createRootSource(""))) {
            ScopeStore.declare(ctx, "global", 1);
            ScopeStore.enter(ctx);
            ScopeStore.declare(ctx, "local", 2);
            ScopeStore.addDiagnostic(ctx, "keep", 2, 1, Severity.INFO);
            var declarations = ScopeStore.getAllDeclarations(ctx);
            var references = ScopeStore.getAllReferences(ctx);
            var diagnostics = ScopeStore.getDiagnostics(ctx);
            var currentSnapshot = ScopeStore.declaredInCurrentScope(ctx);
            var parser = new WordParser("x");
            ctx.begin(parser);
            ScopeStore.declare(ctx, "local", 99);
            assertEquals(List.of(new ScopeStore.SymbolInfo("local", 2)), currentSnapshot);
            ScopeStore.leave(ctx);
            ScopeStore.declare(ctx, "global", 99);
            ScopeStore.addReference(ctx, "global", 99, 1);
            ScopeStore.clearDiagnostics(ctx);
            ctx.rollback(parser);
            assertEquals(1, ScopeStore.currentScopeDepth(ctx));
            assertEquals(2, ScopeStore.resolve(ctx, "local").orElseThrow().sourceOffset());
            assertEquals(1, ScopeStore.resolve(ctx, "global").orElseThrow().sourceOffset());
            assertEquals(List.of(new ScopeStore.SymbolInfo("global", 1), new ScopeStore.SymbolInfo("local", 2)), declarations);
            assertTrue(references.isEmpty());
            assertEquals("keep", diagnostics.get(0).message());
            assertThrows(UnsupportedOperationException.class, () -> declarations.clear());
        }
    }

    @Test public void contextsRemainIndependentAndCommittedStatePersists() {
        try (var first = new ParseContext(StringSource.createRootSource(""));
             var second = new ParseContext(StringSource.createRootSource(""))) {
            var parser = new WordParser("x");
            first.begin(parser);
            ScopeStore.declare(first, "kept", 8);
            second.begin(parser);
            ScopeStore.declare(second, "discard", 9);
            first.commit(parser, TokenKind.consumed);
            second.rollback(parser);
            assertTrue(ScopeStore.isDeclared(first, "kept"));
            assertFalse(ScopeStore.isDeclared(second, "discard"));
            assertFalse(ScopeStore.isDeclared(second, "kept"));
        }
    }
}
