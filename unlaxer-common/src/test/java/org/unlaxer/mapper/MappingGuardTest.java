package org.unlaxer.mapper;

import static org.junit.Assert.*;
import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.elementary.EmptyParser;

public class MappingGuardTest {
    private static Token token(Token... children) {
        return new Token(TokenKind.consumed, StringSource.createRootSource("😀"),
            new EmptyParser(), TokenList.of(children));
    }
    private static void rejected(Token token, String detail) {
        var failure = assertThrows(IllegalArgumentException.class, () -> MappingGuard.prepare(token));
        assertTrue(failure.toString(), failure.getMessage().contains(detail));
    }
    @Test public void rejectsNullAndCyclesBeforeWalkingRecursively() {
        rejected(null, "null root");
        Token root = token(); root.getOriginalChildren().add(root);
        rejected(root, "cycle");
        root = token(); root.getOriginalChildren().add(null);
        rejected(root, "null child");
        root = token(); root.parser = null;
        rejected(root, "metadata");
    }
    @Test public void permitsFlatteningButRejectsForeignFilteredEdges() {
        Token leaf = token(), middle = token(leaf), root = token(middle);
        root.filteredChildren.clear(); root.filteredChildren.add(leaf);
        MappingGuard.prepare(root).visit(leaf);
        root.filteredChildren.add(token());
        rejected(root, "outside");
    }
    @Test public void checksFilteredGraphsOfHiddenOriginalNodes() {
        Token leaf = token(), middle = token(leaf), root = token(middle);
        root.filteredChildren.clear(); middle.filteredChildren.add(root);
        rejected(root, "outside");
    }
    @Test public void depthBoundaryAndFailureDoNotLeakBetweenCalls() {
        Token root = token();
        for (int i = 1; i < MappingGuard.MAX_DEPTH; i++) root = token(root);
        MappingGuard.prepare(root);
        rejected(token(root), "depth");
        MappingGuard.prepare(token());
    }
    @Test public void sharedDagUsesActualWorkAndBoundedText() {
        Token leaf = token(), root = token(leaf, leaf);
        var guard = MappingGuard.prepare(root);
        guard.visit(root); guard.visit(leaf);
        guard.chargeWork(MappingGuard.MAX_WORK - 8);
        assertThrows(IllegalArgumentException.class, () -> guard.chargeWork(10));
        var fresh = MappingGuard.prepare(root);
        fresh.chargeTextBytes(MappingGuard.MAX_TEXT_BYTES);
        assertThrows(IllegalArgumentException.class, () -> fresh.chargeTextBytes(1));
        MappingGuard.prepare(root).text(leaf);
    }
    @Test public void nonContainingLookaheadAndDetachedSourcesRemainLegal() {
        Token lookahead = new Token(TokenKind.matchOnly, StringSource.createRootSource("😀xy"), new EmptyParser());
        Token root = new Token(TokenKind.consumed, StringSource.createRootSource(""), new EmptyParser(), TokenList.of(lookahead));
        MappingGuard.prepare(root);
        Token generated = new Token(TokenKind.virtualTokenConsumed, StringSource.createDetachedSource("generated"), new EmptyParser());
        MappingGuard.prepare(token(generated));
    }
    @Test public void exactNodeAndEdgeLimitsAcceptThenRejectOneMore() {
        assertEquals(1_048_576, MappingGuard.MAX_NODES);
        assertEquals(8_388_608, MappingGuard.MAX_EDGES);
        var source = StringSource.createRootSource("😀");
        var parser = new EmptyParser();
        Token root = new Token(TokenKind.consumed, source, parser);
        for (int i = 1; i < MappingGuard.MAX_NODES; i++) {
            root.getOriginalChildren().add(new Token(TokenKind.consumed, source, parser));
        }
        MappingGuard.prepare(root);
        root.getOriginalChildren().add(new Token(TokenKind.consumed, source, parser));
        rejected(root, "nodes");
        root.getOriginalChildren().clear();
        Token leaf = new Token(TokenKind.consumed, source, parser);
        for (int i = 0; i < MappingGuard.MAX_EDGES; i++) root.getOriginalChildren().add(leaf);
        MappingGuard.prepare(root);
        root.getOriginalChildren().add(leaf);
        rejected(root, "edges");
        MappingGuard.prepare(token());
    }
    @Test public void tokenIdentityDoesNotCollapseEqualValuesAndUtf8BudgetIsExact() {
        class EqualToken extends Token {
            EqualToken() { super(TokenKind.consumed, StringSource.createRootSource("😀"), new EmptyParser()); }
            @Override public boolean equals(Object value) { return value instanceof EqualToken; }
            @Override public int hashCode() { return 1; }
        }
        Token first = new EqualToken(), second = new EqualToken();
        assertEquals(first, second); assertNotSame(first, second);
        Token root = token(first, second);
        var guard = MappingGuard.prepare(root);
        guard.visit(first); guard.visit(second);
        assertThrows(IllegalArgumentException.class, () -> guard.visit(new EqualToken()));
        root.filteredChildren.add(new EqualToken());
        rejected(root, "outside");
        assertEquals(268_435_456L, MappingGuard.MAX_TEXT_BYTES);
        var text = MappingGuard.prepare(first);
        text.chargeTextBytes(MappingGuard.MAX_TEXT_BYTES - 4);
        text.text(first);
        assertThrows(IllegalArgumentException.class, () -> text.text(first));
        MappingGuard.prepare(first).text(first);
    }
    @Test public void recoveryDetectionIncludesHiddenSharedOriginalNodes() {
        Token leaf = token(), root = token(leaf, leaf);
        root.filteredChildren.clear();
        assertFalse(MappingGuard.prepare(root).hasRecoveredSyntax());
        Token marker = new Token(TokenKind.consumed, StringSource.createRootSource(""),
            new org.unlaxer.parser.combinator.RecoveryDiagnostic.Marker("recover"));
        leaf.getOriginalChildren().add(marker);
        assertTrue(MappingGuard.prepare(root).hasRecoveredSyntax());
        leaf.getOriginalChildren().clear();
        assertFalse(MappingGuard.prepare(root).hasRecoveredSyntax());
    }
}
