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
    private static Token flattenedTree(int count) {
        Token[] branches = new Token[count], leaves = new Token[count];
        for (int i = 0; i < count; i++) {
            leaves[i] = token();
            branches[i] = token(token(token(leaves[i])));
        }
        Token root = token(token(branches));
        root.filteredChildren.clear();
        root.filteredChildren.addAll(java.util.List.of(leaves));
        return root;
    }
    @Test public void flattenedTreeValidationHasLinearWorkWithoutIncreasingLimits() throws Exception {
        var counter = MappingGuard.class.getDeclaredField("validationWork");
        counter.setAccessible(true);
        for (int count : new int[] {64, 512, 2048}) {
            MappingGuard guard = MappingGuard.prepare(flattenedTree(count));
            long work = counter.getLong(guard);
            assertTrue("count=" + count + " validationWork=" + work, work <= 8L * count + 16);
            Token repeated = flattenedTree(count);
            repeated.getOriginalChildren().add(repeated.getOriginalChildren().get(0));
            long repeatedWork = counter.getLong(MappingGuard.prepare(repeated));
            assertTrue("same-parent duplicate count=" + count + " work=" + repeatedWork, repeatedWork <= 8L * count + 16);
        }
        assertEquals(8_388_608, MappingGuard.MAX_WORK);
    }
    @Test public void sharedDagShortcutsAndSameSpanForeignNodesKeepTheirBoundary() {
        Token leaf = token(), left = token(leaf), right = token(leaf), root = token(left, right);
        root.filteredChildren.clear(); root.filteredChildren.add(leaf);
        MappingGuard.prepare(root);
        left.filteredChildren.add(right); // another branch has the same source span, not ancestry
        rejected(root, "outside");
        MappingGuard.prepare(flattenedTree(8));
    }

}
