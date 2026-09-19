package org.unlaxer;

import static org.junit.Assert.*;

import java.util.List;
import java.util.function.Predicate;
import org.junit.Test;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.listener.TransactionListener;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.MatchOnly;
import org.unlaxer.parser.combinator.Not;
import org.unlaxer.parser.combinator.Optional;
import org.unlaxer.parser.elementary.EmptyParser;
import org.unlaxer.parser.elementary.EndOfSourceParser;
import org.unlaxer.parser.elementary.WordParser;

public class ZeroWidthSourceAnchorTest {
    private static final Name PAYLOAD = Name.of("payload");
    private static final Name RELATED = Name.of("related");

    private static LazyChain part(Parser child) {
        return new LazyChain() {
            private static final long serialVersionUID = 1L;
            @Override public Parsers getLazyParsers() { return new Parsers(child); }
        };
    }

    private static Token find(Token token, Parser parser) {
        if (token.parser == parser) return token;
        for (Token child : token.getOriginalChildren()) {
            Token found = find(child, parser);
            if (found != null) return found;
        }
        return null;
    }

    private static void assertAnchor(Token token, Source root, int position) {
        assertNotNull(token);
        assertTrue(token.source.isEmpty());
        assertSame(root, token.source.root());
        assertEquals(position, token.source.offsetFromRoot().value());
        assertEquals(0, token.source.codePointLength().value());
    }

    @Test public void emptyAndMissingOptionalAnchorAtUnicodeCursor() {
        for (Parser child : List.of(new EmptyParser(), new Optional(new WordParser("x")))) {
            Parser part = part(child);
            Parser rootParser = new Chain(new WordParser("a😀"), part, new WordParser("b"));
            Source source = StringSource.createRootSource("a😀b");
            try (var context = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
                var result = rootParser.parse(context);
                assertTrue(result.isSucceeded());
                assertTrue(context.allConsumed());
                assertAnchor(find(result.getRootToken(), part), source, 2);
                assertAnchor(find(result.getRootToken(), child), source, 2);
            }
        }
    }

    @Test public void eofAndLookaheadKeepNonConsumingPosition() {
        for (Parser child : List.of(new EndOfSourceParser(), new MatchOnly(new WordParser("b")),
                new Not(new WordParser("x")))) {
            boolean eof = child instanceof EndOfSourceParser;
            String input = eof ? "a😀" : "a😀b";
            Source source = StringSource.createRootSource(input);
            Parser part = part(child);
            Parser root = new Chain(new WordParser("a😀"), part);
            try (var context = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
                var parsed = root.parse(context);
                assertTrue(parsed.isSucceeded());
                assertEquals(2, context.getPosition(TokenKind.consumed).value());
                assertAnchor(find(parsed.getRootToken(), part), source, 2);
            }
        }
    }

    @Test public void failedOuterTransactionRestoresCursorAndAnchoredChildrenDoNotLeak() {
        Source source = StringSource.createRootSource("a😀b");
        Parser root = new Chain(new WordParser("a😀"), part(new EmptyParser()), new WordParser("x"));
        try (var context = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            assertFalse(root.parse(context).isSucceeded());
            assertEquals(0, context.getPosition(TokenKind.consumed).value());
            assertEquals(0, context.getPosition(TokenKind.matchOnly).value());
            assertTrue(context.getCurrent().getTokens().isEmpty());
            assertTrue(new WordParser("a😀b").parse(context).isSucceeded());
        }
    }

    @Test public void anchoredEmptyChildSurvivesTokenListCollection() {
        Source source = StringSource.createRootSource("a😀b");
        Parser parser = new EmptyParser();
        Source anchor = source.peek(new CodePointIndex(2), new CodePointLength(0));
        Token child = new Token(TokenKind.consumed, anchor, parser);
        Token parent = new Token(TokenKind.consumed, TokenList.of(child), parser);
        assertAnchor(parent, source, 2);
        assertSame(child, parent.getOriginalChildren().get(0));
        assertSame(parent, child.parent.orElseThrow());
        assertTrue(TokenList.of(child).toSource(Source.SourceKind.detached).sourceKind().isDetached());
    }

    @Test public void collectionPreservesMetadataAndOriginalFilteredChildren() {
        Source source = StringSource.createRootSource("a😀b");
        Object payload = new Object();
        Token related = new Token(TokenKind.virtualTokenConsumed, StringSource.createDetachedSource("virtual"), new EmptyParser());
        class MetadataChain extends Chain {
            private static final long serialVersionUID = 1L;
            MetadataChain() { super(new EmptyParser()); }
            @Override public Token collect(List<Token> tokens, TokenKind kind, Predicate<Token> filter) {
                Token result = new Token(kind, StringSource.createDetachedSource(""), this, TokenList.of(tokens));
                result.putExtraObject(PAYLOAD, payload);
                result.putRelatedToken(RELATED, related);
                result.filteredChildren.clear(); // a deliberate custom CST projection
                return result;
            }
        }
        Parser part = new MetadataChain();
        try (var context = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Token[] notified = new Token[1];
            context.addTransactionListener(Name.of("anchor-observer"), new TransactionListener() {
                @Override public void setLevel(OutputLevel level) {}
                @Override public void onOpen(ParseContext context) {}
                @Override public void onBegin(ParseContext context, Parser parser) {}
                @Override public void onClose(ParseContext context) {}
                @Override public void onCommit(ParseContext context, Parser parser, TokenList tokens) {
                    if (parser == part) notified[0] = tokens.get(0);
                }
            });
            assertTrue(new WordParser("a😀").parse(context).isSucceeded());
            Token token = part.parse(context).getRootToken();
            assertSame(token, notified[0]);
            assertAnchor(token, source, 2);
            assertSame(payload, token.getExtraObject(PAYLOAD).orElseThrow());
            assertSame(related, token.getRelatedToken(RELATED).orElseThrow());
            assertFalse(token.getOriginalChildren().isEmpty());
            assertTrue(token.filteredChildren.isEmpty());
            assertSame(token, token.getOriginalChildren().get(0).parent.orElseThrow());
        }
    }

    @Test public void customCollectorVirtualAndTypedTokensSurviveCommitUnchanged() {
        Source source = StringSource.createRootSource("ab");
        Parser empty = new EmptyParser();
        for (Token preserved : List.of(
                new Token(TokenKind.virtualTokenConsumed, StringSource.createDetachedSource(""), empty),
                new TypedToken<>(TokenKind.consumed, StringSource.createDetachedSource(""), empty),
                new Token(TokenKind.consumed, source.peek(new CodePointIndex(0), new CodePointLength(0)), empty))) {
            Parser collector = new Chain(new EmptyParser()) {
                private static final long serialVersionUID = 1L;
                @Override public Token collect(List<Token> tokens, TokenKind kind, Predicate<Token> filter) {
                    return preserved;
                }
            };
            try (var context = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
                assertTrue(new WordParser("a").parse(context).isSucceeded());
                assertSame(preserved, collector.parse(context).getRootToken());
                assertEquals(1, context.getPosition(TokenKind.consumed).value());
                assertEquals(0, preserved.source.offsetFromRoot().value());
            }
        }
    }

    @Test public void explicitVirtualAndCustomSourcesAreNotRewritten() {
        Source source = StringSource.createRootSource("abc");
        Source anchor = source.peek(new CodePointIndex(2), new CodePointLength(0));
        Parser parser = new EmptyParser();
        Token virtual = new Token(TokenKind.virtualTokenConsumed, StringSource.createDetachedSource(""), parser);
        assertSame(virtual, virtual.anchorCollectedEmptySource(anchor));
        Token explicit = new Token(TokenKind.consumed, source.peek(new CodePointIndex(1), new CodePointLength(0)), parser);
        assertSame(explicit, explicit.anchorCollectedEmptySource(anchor));
        Token typed = new TypedToken<>(TokenKind.consumed, StringSource.createDetachedSource(""), parser);
        assertSame(typed, typed.anchorCollectedEmptySource(anchor));
        Token nonempty = new Token(TokenKind.consumed, StringSource.createDetachedSource("x"), parser);
        assertSame(nonempty, nonempty.anchorCollectedEmptySource(anchor));
        Token token = new Token(TokenKind.consumed, StringSource.createDetachedSource(""), parser);
        assertThrows(IllegalArgumentException.class, () -> token.anchorCollectedEmptySource(null));
        assertThrows(IllegalArgumentException.class, () -> token.anchorCollectedEmptySource(source));
        assertThrows(IllegalArgumentException.class, () -> token.anchorCollectedEmptySource(StringSource.createDetachedSource("")));
    }
}
