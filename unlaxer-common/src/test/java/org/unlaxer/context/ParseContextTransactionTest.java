package org.unlaxer.context;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.CodePointIndex;
import org.unlaxer.CodePointLength;
import org.unlaxer.Committed;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for ParseContext transaction model: begin, commit, rollback,
 * nested transactions, and cursor position tracking.
 */
public class ParseContextTransactionTest {

    @Test
    public void beginCommitAdvancesCursor() {
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source)) {
            assertEquals(0, ctx.getConsumedPosition().value());

            WordParser parser = new WordParser("abc");
            ctx.begin(parser);
            ctx.consume(new CodePointLength(3));
            ctx.commit(parser, TokenKind.consumed);

            assertEquals(3, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void beginRollbackResetsCursor() {
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source)) {
            assertEquals(0, ctx.getConsumedPosition().value());

            WordParser parser = new WordParser("abc");
            ctx.begin(parser);
            ctx.consume(new CodePointLength(3));
            assertEquals(3, ctx.getConsumedPosition().value());

            ctx.rollback(parser);
            assertEquals(0, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void nestedTransactionCommitInnerRollbackOuter() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            WordParser outerParser = new WordParser("abc");
            WordParser innerParser = new WordParser("def");

            // begin outer
            ctx.begin(outerParser);
            ctx.consume(new CodePointLength(3));
            assertEquals(3, ctx.getConsumedPosition().value());

            // begin inner
            ctx.begin(innerParser);
            ctx.consume(new CodePointLength(3));
            assertEquals(6, ctx.getConsumedPosition().value());

            // commit inner
            ctx.commit(innerParser, TokenKind.consumed);
            assertEquals(6, ctx.getConsumedPosition().value());

            // rollback outer -- everything resets
            ctx.rollback(outerParser);
            assertEquals(0, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void nestedTransactionCommitBoth() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            WordParser outerParser = new WordParser("abc");
            WordParser innerParser = new WordParser("def");

            ctx.begin(outerParser);
            ctx.consume(new CodePointLength(3));

            ctx.begin(innerParser);
            ctx.consume(new CodePointLength(3));

            ctx.commit(innerParser, TokenKind.consumed);
            ctx.commit(outerParser, TokenKind.consumed);

            assertEquals(6, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void multipleSequentialTransactions() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            WordParser p1 = new WordParser("abc");
            WordParser p2 = new WordParser("def");

            // first transaction: consume "abc"
            ctx.begin(p1);
            ctx.consume(new CodePointLength(3));
            ctx.commit(p1, TokenKind.consumed);
            assertEquals(3, ctx.getConsumedPosition().value());

            // second transaction: consume "def"
            ctx.begin(p2);
            ctx.consume(new CodePointLength(3));
            ctx.commit(p2, TokenKind.consumed);
            assertEquals(6, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void rollbackFirstThenCommitSecond() {
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source)) {
            WordParser p1 = new WordParser("abc");
            WordParser p2 = new WordParser("abc");

            // first attempt: rollback
            ctx.begin(p1);
            ctx.consume(new CodePointLength(3));
            ctx.rollback(p1);
            assertEquals(0, ctx.getConsumedPosition().value());

            // second attempt: commit
            ctx.begin(p2);
            ctx.consume(new CodePointLength(3));
            ctx.commit(p2, TokenKind.consumed);
            assertEquals(3, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void tokenStackSizeIsOneAfterClose() {
        StringSource source = StringSource.createRootSource("hello");
        try (ParseContext ctx = new ParseContext(source)) {
            WordParser p = new WordParser("hello");
            ctx.begin(p);
            ctx.consume(new CodePointLength(5));
            ctx.commit(p, TokenKind.consumed);

            // After commit, tokenStack should be back to size 1 (base element)
            assertEquals(1, ctx.getTokenStack().size());
        }
    }

    @Test
    public void parsedResultFromChainParser() {
        // Chain of two WordParsers: "ab" then "cd"
        WordParser ab = new WordParser("ab");
        WordParser cd = new WordParser("cd");
        Chain chain = new Chain(ab, cd);

        StringSource source = StringSource.createRootSource("abcd");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = chain.parse(ctx);
            assertTrue("chain parse should succeed", parsed.isSucceeded());
            assertEquals(4, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void parsedResultFromFailedChain() {
        WordParser ab = new WordParser("ab");
        WordParser cd = new WordParser("cd");
        Chain chain = new Chain(ab, cd);

        StringSource source = StringSource.createRootSource("abXX");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = chain.parse(ctx);
            assertTrue("chain parse should fail", parsed.isFailed());
            // Cursor should be reset to 0 because chain rolled back
            assertEquals(0, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void commitProducesTokens() {
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source)) {
            WordParser parser = new WordParser("abc");

            ctx.begin(parser);
            Token token = new Token(TokenKind.consumed, source.peek(new CodePointIndex(0), new CodePointLength(3)), parser);
            ctx.getCurrent().addToken(token, TokenKind.consumed);
            ctx.consume(new CodePointLength(3));

            Committed committed = ctx.commit(parser, TokenKind.consumed);
            assertFalse("committed should not be empty", committed.isEmpty());
        }
    }

    @Test
    public void allConsumedReturnsTrueWhenFullyParsed() {
        WordParser parser = new WordParser("hello");
        StringSource source = StringSource.createRootSource("hello");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("parse should succeed", parsed.isSucceeded());
            assertTrue("all should be consumed", ctx.allConsumed());
        }
    }

    @Test
    public void allConsumedReturnsFalseWhenPartiallyParsed() {
        WordParser parser = new WordParser("hel");
        StringSource source = StringSource.createRootSource("hello");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("parse should succeed", parsed.isSucceeded());
            assertFalse("should not be all consumed", ctx.allConsumed());
        }
    }
}
