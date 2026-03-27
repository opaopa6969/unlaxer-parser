package org.unlaxer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for Parsed result object: success/failure status,
 * consumed content, root token, and partial parse scenarios.
 */
public class ParsedResultTest {

    @Test
    public void successfulParseStatus() {
        WordParser parser = new WordParser("abc");
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("should succeed", parsed.isSucceeded());
            assertFalse("should not be failed", parsed.isFailed());
            assertFalse("should not be stopped", parsed.isStopped());
        }
    }

    @Test
    public void failedParseStatus() {
        WordParser parser = new WordParser("xyz");
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertFalse("should not succeed", parsed.isSucceeded());
            assertTrue("should be failed", parsed.isFailed());
        }
    }

    @Test
    public void parsedStatusConstants() {
        assertTrue("FAILED should be failed", Parsed.FAILED.isFailed());
        assertTrue("SUCCEEDED should be succeeded", Parsed.SUCCEEDED.isSucceeded());
        assertTrue("STOPPED should be stopped", Parsed.STOPPED.isStopped());
        assertTrue("STOPPED isSucceeded should be true", Parsed.STOPPED.isSucceeded());
    }

    @Test
    public void parsedNegate() {
        Parsed failed = new Parsed(Parsed.Status.failed);
        Parsed negated = failed.negate();
        assertTrue("negated failed should be succeeded", negated.isSucceeded());

        Parsed succeeded = new Parsed(Parsed.Status.succeeded);
        Parsed negatedSucceeded = succeeded.negate();
        assertTrue("negated succeeded should be failed", negatedSucceeded.isFailed());
    }

    @Test
    public void getConsumedFromSuccessfulParse() {
        WordParser parser = new WordParser("hello");
        StringSource source = StringSource.createRootSource("hello world");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("should succeed", parsed.isSucceeded());

            Token consumed = parsed.getConsumed();
            assertEquals("hello", consumed.source.sourceAsString());
        }
    }

    @Test
    public void getRootTokenFromParse() {
        WordParser parser = new WordParser("abc");
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("should succeed", parsed.isSucceeded());

            Token rootToken = parsed.getRootToken();
            assertEquals("abc", rootToken.getSource().sourceAsString());
        }
    }

    @Test
    public void partialParseConsumedLessThanSourceLength() {
        // Parse only "abc" from "abcdef"
        WordParser parser = new WordParser("abc");
        StringSource source = StringSource.createRootSource("abcdef");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("should succeed", parsed.isSucceeded());
            assertEquals(3, ctx.getConsumedPosition().value());
            assertFalse("should not be fully consumed", ctx.allConsumed());
        }
    }

    @Test
    public void choiceParseReturnsFirstMatch() {
        WordParser abc = new WordParser("abc");
        WordParser def = new WordParser("def");
        Choice choice = new Choice(abc, def);

        StringSource source = StringSource.createRootSource("def");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = choice.parse(ctx);
            assertTrue("choice should succeed with second option", parsed.isSucceeded());
            assertEquals(3, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void choiceParseAllFail() {
        WordParser abc = new WordParser("abc");
        WordParser def = new WordParser("def");
        Choice choice = new Choice(abc, def);

        StringSource source = StringSource.createRootSource("xyz");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = choice.parse(ctx);
            assertTrue("choice should fail", parsed.isFailed());
            assertEquals("cursor should not advance", 0, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void chainParsePartialFailure() {
        // Chain expects "ab" then "cd" but input is "abXY"
        WordParser ab = new WordParser("ab");
        WordParser cd = new WordParser("cd");
        Chain chain = new Chain(ab, cd);

        StringSource source = StringSource.createRootSource("abXY");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = chain.parse(ctx);
            assertTrue("chain should fail on second element", parsed.isFailed());
            assertEquals("cursor should reset due to rollback", 0, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void oneOrMoreParseMultipleDigits() {
        DigitParser digit = new DigitParser();
        OneOrMore digits = new OneOrMore(digit);

        StringSource source = StringSource.createRootSource("12345abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = digits.parse(ctx);
            assertTrue("should succeed", parsed.isSucceeded());
            assertEquals("should consume 5 digits", 5, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void oneOrMoreParseZeroOccurrencesFails() {
        DigitParser digit = new DigitParser();
        OneOrMore digits = new OneOrMore(digit);

        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = digits.parse(ctx);
            assertTrue("should fail with zero occurrences", parsed.isFailed());
        }
    }

    @Test
    public void parsedMessage() {
        Parsed parsed = new Parsed(Parsed.Status.failed);
        parsed.setMessage("unexpected token");
        assertEquals("failed:unexpected token", parsed.getMessage());
    }

    @Test
    public void committedIsCollected() {
        WordParser parser = new WordParser("abc");
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("should succeed", parsed.isSucceeded());
            // WordParser is a terminal parser that produces a collected token
            assertTrue("committed should be collected", parsed.isCollected());
        }
    }

    @Test
    public void committedIsEmpty() {
        Committed empty = new Committed();
        assertTrue("empty committed should be empty", empty.isEmpty());
        assertFalse("empty committed should not be collected", empty.isCollected());
    }
}
