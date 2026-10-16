package org.unlaxer.context;

import static org.junit.Assert.*;

import org.junit.Test;
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.elementary.WordParser;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

public class SuccessMemoBudgetTest {
    static final class Safe extends LazyChain implements SafeSuccessMemoizable {
        final Parsers children;

        Safe(Parser parser) {
            children = new Parsers(parser);
        }

        public Parsers getLazyParsers() {
            return children;
        }
    }

    static class CountingWord extends WordParser {
        int calls;

        CountingWord() {
            super("x");
        }

        public Token getToken(ParseContext c, TokenKind k, boolean invert) {
            calls++;
            return super.getToken(c, k, invert);
        }
    }

    static ParseContext context() {
        return ParseContext.withOptions(
                StringSource.createRootSource("x"),
                ParseOptions.withMemoization(Memoization.SAFE_FAILURES));
    }

    static Token attempt(ParseContext c, Parser parser) {
        c.beginPreservingMatched(parser);
        assertTrue(parser.parse(c).isSucceeded());
        assertEquals(1, c.position());
        assertEquals(1, c.matchedPosition());
        Token root = c.getCurrent().getTokens().get(0);
        c.rollback(parser);
        assertEquals(0, c.position());
        assertEquals(0, c.matchedPosition());
        assertTrue(c.getCurrent().getTokens().isEmpty());
        return root;
    }

    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }

    static Token leaf() {
        return new Token(
                TokenKind.consumed, StringSource.createRootSource("x"), new WordParser("x"));
    }

    @Test
    public void finiteEntrySingleAndTotalBudgetsReturnSafeMissesBeforeCopy() {
        var word = new CountingWord();
        var parser = new Safe(word);
        long bytes;
        try (var c = context()) {
            attempt(c, parser);
            bytes = c.getPackratMemoTable().successRetainedBytes();
            assertTrue(bytes > 0);
        }
        for (long[] budget :
                new long[][] {
                    {0, 1048576, 1048576},
                    {4, 0, 1048576},
                    {4, 1048576, 0},
                    {4, bytes - 1, 1048576},
                    {4, 1048576, bytes - 1}
                }) {
            try (var c = context()) {
                var table = c.getPackratMemoTable();
                table.setSuccessBudget((int) budget[0], budget[1], budget[2]);
                int before = word.calls;
                attempt(c, parser);
                attempt(c, parser);
                assertEquals(2, word.calls - before);
                assertEquals(0, table.successHits());
                assertEquals(0, table.successEntryCount());
                assertEquals(0, table.successRetainedBytes());
                assertEquals(2, table.successBudgetMisses());
            }
        }
        try (var c = context()) {
            var table = c.getPackratMemoTable();
            table.setSuccessBudget(4, bytes, bytes);
            int before = word.calls;
            attempt(c, parser);
            attempt(c, parser);
            assertEquals(1, word.calls - before);
            assertEquals(1, table.successHits());
            assertEquals(bytes, table.successRetainedBytes());
        }
    }

    @Test
    public void replacementEvictionAndPublicEntryReuseReleaseCountsAndLinks() throws Exception {
        var a = new Safe(new CountingWord());
        var b = new Safe(new CountingWord());
        try (var c = context()) {
            var table = c.getPackratMemoTable();
            table.setWindow(64);
            var key =
                    new PackratMemoTable.PositionKey(
                            0, 0, TokenKind.consumed, false, c.getMemoizationStateVersion(), false);
            attempt(c, a);
            var old = table.get(a, key);
            long bytes = table.successRetainedBytes();
            attempt(c, b);
            var replacement = table.get(b, key);
            assertEquals(2, table.successEntryCount());
            assertEquals(2 * bytes, table.successRetainedBytes());
            table.put(a, key, replacement);
            assertEquals(2, table.entryCount());
            assertEquals(2, table.successEntryCount());
            assertEquals(2 * bytes, table.successRetainedBytes());
            for (String link :
                    new String[] {
                        "indexedKey", "indexedParser", "nextAtPosition", "previousAtPosition"
                    }) assertNull(field(old, link));
            assertNotSame(replacement, table.get(a, key));
            table.put(a, key, table.get(a, key));
            assertEquals(2, table.entryCount());
            table.observeCursor(1024);
            assertEquals(0, table.entryCount());
            assertEquals(0, table.successEntryCount());
            assertEquals(0, table.successRetainedBytes());
            assertEquals(2, table.evictedCount());
            // The original, detached immutable result can be inserted at a new position.
            table.put(
                    a,
                    new PackratMemoTable.PositionKey(1024, 1024, TokenKind.consumed, false, 0),
                    old);
            assertEquals(1, table.successEntryCount());
            assertEquals(bytes, table.successRetainedBytes());
            Object index = field(table, "entryChainByPosition");
            table.put(
                    a,
                    new PackratMemoTable.PositionKey(
                            Integer.MAX_VALUE, Integer.MAX_VALUE, TokenKind.consumed, false, 0),
                    old);
            assertSame(index, field(table, "entryChainByPosition"));
            assertEquals(1, table.successEntryCount());
            assertEquals(1, table.successBudgetMisses());
        }
    }

    @Test
    public void opaqueAndMalformedPublicTreesMissWithoutCallingDeepCopyOrSource() {
        Token t = leaf();
        t.getOriginalChildren().add(t);
        t.filteredChildren.add(t);
        assertNull(Token.prepareMemoSnapshot(new TokenList(t), 1048576));
        Token shared = leaf();
        assertNull(Token.prepareMemoSnapshot(new TokenList(shared, shared), 1048576));
        Token n = leaf();
        n.getOriginalChildren().add(null);
        assertNull(Token.prepareMemoSnapshot(new TokenList(n), 1048576));
        Token unknown =
                new Token(
                        TokenKind.consumed,
                        StringSource.createRootSource("x"),
                        new WordParser("x")) {
                    public Token deepCopy() {
                        throw new AssertionError("custom deepCopy must not be called");
                    }
                };
        assertNull(Token.prepareMemoSnapshot(new TokenList(unknown), 1048576));
        TokenList opaqueList =
                new TokenList() {
                    public int size() {
                        throw new AssertionError("custom size must not be called");
                    }
                };
        assertNull(Token.prepareMemoSnapshot(opaqueList, 1048576));
        Source source =
                new RangedString(0, "x") {
                    public Source root() {
                        throw new AssertionError("custom root must not be called");
                    }
                };
        Token customSource = new Token(TokenKind.consumed, source, new WordParser("x"));
        assertNull(Token.prepareMemoSnapshot(new TokenList(customSource), 1048576));
        int[] rootCalls = {0};
        Source knownRoot = StringSource.createRootSource("x");
        Source opaqueParent =
                new RangedString(0, "x") {
                    public Source root() {
                        rootCalls[0]++;
                        return knownRoot;
                    }
                };
        Source child = new StringSource(opaqueParent, "x", new CodePointOffset(0));
        Token hiddenCustomParent = new Token(TokenKind.consumed, child, new WordParser("x"));
        rootCalls[0] = 0;
        assertNull(Token.prepareMemoSnapshot(new TokenList(hiddenCustomParent), 1048576));
        assertEquals(0, rootCalls[0]);
        Source ancestry = StringSource.createRootSource("x");
        for (int i = 0; i < 10000; i++)
            ancestry = new StringSource(ancestry, "x", new CodePointOffset(0));
        Token longAncestry = new Token(TokenKind.consumed, ancestry, new WordParser("x"));
        assertNull(Token.prepareMemoSnapshot(new TokenList(longAncestry), 1024));

        Token invalidFiltered = leaf();
        invalidFiltered.filteredChildren.add(leaf());
        assertNull(Token.prepareMemoSnapshot(new TokenList(invalidFiltered), 1048576));
        assertNull(Token.prepareMemoSnapshot(new TokenList(leaf()), Long.MAX_VALUE));
        assertNull(Token.prepareMemoSnapshot(new TokenList(leaf()), -1));
    }

    @Test
    public void cyclicAndSubclassTokensCannotAbortAnOtherwiseSuccessfulParse() {
        for (boolean cycle : new boolean[] {true, false}) {
            var word =
                    new CountingWord() {
                        public Token getToken(ParseContext c, TokenKind k, boolean invert) {
                            Token t = super.getToken(c, k, invert);
                            if (cycle) {
                                t.getOriginalChildren().add(t);
                                t.filteredChildren.add(t);
                                return t;
                            }
                            return new Token(k, t.source, this) {
                                public Token deepCopy() {
                                    throw new AssertionError("not invoked");
                                }
                            };
                        }
                    };
            try (var c = context()) {
                var parser = new Safe(word);
                attempt(c, parser);
                attempt(c, parser);
                assertEquals(0, c.getPackratMemoTable().successHits());
                assertEquals(0, c.getPackratMemoTable().successEntryCount());
            }
        }
    }

    @Test
    public void shallowMetadataMapsAreIsolatedAndRelatedValuesKeepTheirExistingContract() {
        Token related = leaf();
        Name extra = Name.of("value"), relation = Name.of("related");
        var word =
                new CountingWord() {
                    public Token getToken(ParseContext c, TokenKind k, boolean invert) {
                        Token t = super.getToken(c, k, invert);
                        t.putExtraObject(extra, "kept");
                        t.putRelatedToken(relation, related);
                        return t;
                    }
                };
        try (var c = context()) {
            var parser = new Safe(word);
            Token first = attempt(c, parser);
            Token original = first.getOriginalChildren().get(0);
            original.putExtraObject(extra, "changed");
            original.removeRelatedToken(relation);
            first.getOriginalChildren().clear();
            first.filteredChildren.clear();
            Token replayed = attempt(c, parser);
            Token value = replayed.getOriginalChildren().get(0);
            assertEquals("kept", value.getExtraObject(extra).orElseThrow());
            assertSame(related, value.getRelatedToken(relation).orElseThrow());
            assertSame(replayed, value.parent.orElseThrow());
            assertEquals(1, c.getPackratMemoTable().successHits());
        }
    }

    @Test
    public void threeThousandDeepTreesCopyAndReplayOnOneMiBStack() throws Exception {
        var error = new AtomicReference<Throwable>();
        Thread thread =
                new Thread(
                        null,
                        () -> {
                            try {
                                var word =
                                        new CountingWord() {
                                            public Token getToken(
                                                    ParseContext c, TokenKind k, boolean invert) {
                                                Token t = super.getToken(c, k, invert);
                                                for (int i = 0; i < 3000; i++)
                                                    t =
                                                            new Token(
                                                                    k,
                                                                    t.source,
                                                                    this,
                                                                    new TokenList(t));
                                                return t;
                                            }
                                        };
                                try (var c = context()) {
                                    var parser = new Safe(word);
                                    attempt(c, parser);
                                    Token copy = attempt(c, parser);
                                    int depth = 0;
                                    while (!copy.getOriginalChildren().isEmpty()) {
                                        Token child = copy.getOriginalChildren().get(0);
                                        assertSame(copy, child.parent.orElseThrow());
                                        copy = child;
                                        depth++;
                                        assertTrue(depth < 3010);
                                    }
                                    assertTrue(depth >= 3000);
                                    assertEquals(1, word.calls);
                                    assertEquals(1, c.getPackratMemoTable().successHits());
                                }
                            } catch (Throwable failure) {
                                error.set(failure);
                            }
                        },
                        "deep-memo",
                        1024 * 1024);
        thread.setDaemon(true);
        thread.start();
        thread.join(30000);
        assertFalse("memo copy must terminate", thread.isAlive());
        if (error.get() != null) throw new AssertionError(error.get());
    }

    @Test
    public void deferredCopyUsesTheCapturedProjectionParserAndMetadataValues() {
        Token left = leaf(), right = leaf();
        Parser parser = new WordParser("root");
        Token original =
                new Token(
                        TokenKind.consumed,
                        StringSource.createRootSource("x"),
                        parser,
                        new TokenList(left, right));
        original.filteredChildren.clear();
        original.filteredChildren.add(right);
        Name name = Name.of("value");
        original.putExtraObject(name, "kept");
        var plan = Token.prepareMemoSnapshot(new TokenList(original), 1048576);
        assertNotNull(plan);
        original.filteredChildren.clear();
        original.getOriginalChildren().clear();
        original.parser = null;
        original.putExtraObject(name, "changed after planning");
        Token frozen = plan.freeze().get(0);
        assertSame(parser, frozen.parser);
        assertEquals(2, frozen.getOriginalChildren().size());
        assertEquals(1, frozen.filteredChildren.size());
        assertSame(frozen.getOriginalChildren().get(1), frozen.filteredChildren.get(0));
        assertEquals("kept", frozen.getExtraObject(name).orElseThrow());
        Token replayed = plan.copy().get(0);
        assertNotSame(frozen, replayed);
        assertSame(replayed.getOriginalChildren().get(1), replayed.filteredChildren.get(0));
        assertSame(replayed, replayed.getOriginalChildren().get(1).parent.orElseThrow());
    }

    @Test
    public void visibleSourceTextIsChargedEvenWhenItsImmutableSourceIsShared() {
        Source large = StringSource.createRootSource("q".repeat(300000));
        Token largeToken = new Token(TokenKind.consumed, large, new WordParser("x"));
        assertNull(Token.prepareMemoSnapshot(new TokenList(largeToken), 2L * 1024 * 1024));
        var word =
                new CountingWord() {
                    public Token getToken(ParseContext c, TokenKind k, boolean invert) {
                        Token value = super.getToken(c, k, invert);
                        return new Token(k, value.source, this, new TokenList(largeToken));
                    }
                };
        try (var c = context()) {
            var parser = new Safe(word);
            attempt(c, parser);
            attempt(c, parser);
            assertEquals(2, word.calls);
            assertEquals(0, c.getPackratMemoTable().successEntryCount());
            assertEquals(0, c.getPackratMemoTable().successHits());
        }
    }

    @Test
    public void invalidAndExtremeSuccessSettingsDoNotAllocateOrDisableFailureBudgets() {
        try (var c = context()) {
            var table = c.getPackratMemoTable();
            assertThrows(IllegalArgumentException.class, () -> table.setSuccessBudget(-1, 0, 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> table.setSuccessBudget(0, Long.MAX_VALUE, 0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> table.setSuccessBudget(0, 0, Long.MAX_VALUE));
            table.setSuccessBudget(0, 0, 0);
            assertEquals(0, table.failureBudgetMisses());
            assertEquals(0, table.successEntryCount());
        }
    }
}
