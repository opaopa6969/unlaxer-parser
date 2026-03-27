package org.unlaxer.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.Token.ChildrenKind;
import org.unlaxer.TokenKind;
import org.unlaxer.TokenList;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Integration tests: build grammars with combinators, parse strings,
 * verify token tree structure, transaction state, and cursor positions.
 */
public class FullParsePipelineTest {

    @Test
    public void simpleChainFullParse() {
        // Grammar: "hello" " " "world"
        WordParser hello = new WordParser("hello");
        WordParser space = new WordParser(" ");
        WordParser world = new WordParser("world");
        Chain grammar = new Chain(hello, space, world);

        StringSource source = StringSource.createRootSource("hello world");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = grammar.parse(ctx);
            assertTrue("parse should succeed", parsed.isSucceeded());
            assertTrue("all consumed", ctx.allConsumed());
            assertEquals(11, ctx.getConsumedPosition().value());

            // Token stack should be back to 1 (base)
            assertEquals("transaction committed", 1, ctx.getTokenStack().size());
        }
    }

    @Test
    public void choiceInChain() {
        // Grammar: ("yes" | "no") " " "please"
        WordParser yes = new WordParser("yes");
        WordParser no = new WordParser("no");
        Choice yesOrNo = new Choice(yes, no);
        WordParser space = new WordParser(" ");
        WordParser please = new WordParser("please");
        Chain grammar = new Chain(yesOrNo, space, please);

        // Test with "yes please"
        {
            StringSource source = StringSource.createRootSource("yes please");
            try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
                Parsed parsed = grammar.parse(ctx);
                assertTrue("should parse 'yes please'", parsed.isSucceeded());
                assertTrue("all consumed", ctx.allConsumed());
            }
        }

        // Test with "no please"
        {
            StringSource source = StringSource.createRootSource("no please");
            try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
                Parsed parsed = grammar.parse(ctx);
                assertTrue("should parse 'no please'", parsed.isSucceeded());
                assertTrue("all consumed", ctx.allConsumed());
            }
        }

        // Test failure: "maybe please"
        {
            StringSource source = StringSource.createRootSource("maybe please");
            try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
                Parsed parsed = grammar.parse(ctx);
                assertTrue("should fail for 'maybe please'", parsed.isFailed());
                assertEquals("cursor should be at 0", 0, ctx.getConsumedPosition().value());
            }
        }
    }

    @Test
    public void oneOrMoreDigits() {
        // Grammar: OneOrMore(Digit)
        DigitParser digit = new DigitParser();
        OneOrMore digits = new OneOrMore(digit);

        StringSource source = StringSource.createRootSource("42");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = digits.parse(ctx);
            assertTrue("should parse digits", parsed.isSucceeded());
            assertEquals(2, ctx.getConsumedPosition().value());
            assertTrue("all consumed", ctx.allConsumed());
        }
    }

    @Test
    public void zeroOrMoreMatchesEmpty() {
        // Grammar: ZeroOrMore("+")
        PlusParser plus = new PlusParser();
        ZeroOrMore zeroOrMorePlus = new ZeroOrMore(plus);

        // Empty match
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = zeroOrMorePlus.parse(ctx);
            assertTrue("ZeroOrMore should succeed even with 0 matches", parsed.isSucceeded());
            assertEquals("cursor should not advance", 0, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void zeroOrMoreMatchesMultiple() {
        PlusParser plus = new PlusParser();
        ZeroOrMore zeroOrMorePlus = new ZeroOrMore(plus);

        StringSource source = StringSource.createRootSource("+++abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = zeroOrMorePlus.parse(ctx);
            assertTrue("should succeed", parsed.isSucceeded());
            assertEquals("should consume 3 plus signs", 3, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void digitPlusDigitChain() {
        // Grammar: digit "+" digit
        DigitParser d1 = new DigitParser();
        PlusParser plus = new PlusParser();
        DigitParser d2 = new DigitParser();
        Chain grammar = new Chain(d1, plus, d2);

        StringSource source = StringSource.createRootSource("1+2");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = grammar.parse(ctx);
            assertTrue("1+2 should parse", parsed.isSucceeded());
            assertTrue("all consumed", ctx.allConsumed());

            Token root = parsed.getRootToken();
            // Chain collects children
            TokenList origChildren = root.getOriginalChildren();
            assertTrue("should have children", origChildren.size() >= 3);

            // Verify token text for each part
            assertEquals("1", origChildren.get(0).getSource().sourceAsString());
            assertEquals("+", origChildren.get(1).getSource().sourceAsString());
            assertEquals("2", origChildren.get(2).getSource().sourceAsString());
        }
    }

    @Test
    public void nestedChainAndChoice() {
        // Grammar: Chain(Choice("a", "b"), Choice("1", "2"))
        Choice first = new Choice(new WordParser("a"), new WordParser("b"));
        Choice second = new Choice(new WordParser("1"), new WordParser("2"));
        Chain grammar = new Chain(first, second);

        // "a1" should work
        {
            StringSource source = StringSource.createRootSource("a1");
            try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
                Parsed parsed = grammar.parse(ctx);
                assertTrue("a1 should parse", parsed.isSucceeded());
                assertTrue("all consumed", ctx.allConsumed());
            }
        }
        // "b2" should work
        {
            StringSource source = StringSource.createRootSource("b2");
            try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
                Parsed parsed = grammar.parse(ctx);
                assertTrue("b2 should parse", parsed.isSucceeded());
            }
        }
        // "c1" should fail
        {
            StringSource source = StringSource.createRootSource("c1");
            try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
                Parsed parsed = grammar.parse(ctx);
                assertTrue("c1 should fail", parsed.isFailed());
            }
        }
    }

    @Test
    public void tokenTreeDepthFromParse() {
        // Grammar: Chain(digit, Chain("+", digit))
        DigitParser d1 = new DigitParser();
        PlusParser plus = new PlusParser();
        DigitParser d2 = new DigitParser();
        Chain inner = new Chain(plus, d2);
        Chain grammar = new Chain(d1, inner);

        StringSource source = StringSource.createRootSource("1+2");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = grammar.parse(ctx);
            assertTrue("should succeed", parsed.isSucceeded());

            Token root = parsed.getRootToken();
            // Flatten depth-first to see all tokens
            TokenList flattened = root.flattenDepth(ChildrenKind.original);
            assertTrue("should have at least 4 tokens (root, d1, inner, +, d2)",
                flattened.size() >= 4);
        }
    }

    @Test
    public void transactionStackCorrectAfterMultipleParseCalls() {
        // Parse multiple times in same context
        DigitParser digit = new DigitParser();
        StringSource source = StringSource.createRootSource("123");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed p1 = digit.parse(ctx);
            assertTrue("first digit", p1.isSucceeded());
            assertEquals(1, ctx.getConsumedPosition().value());

            Parsed p2 = digit.parse(ctx);
            assertTrue("second digit", p2.isSucceeded());
            assertEquals(2, ctx.getConsumedPosition().value());

            Parsed p3 = digit.parse(ctx);
            assertTrue("third digit", p3.isSucceeded());
            assertEquals(3, ctx.getConsumedPosition().value());

            assertTrue("all consumed", ctx.allConsumed());
            assertEquals("stack size should be 1 (base)", 1, ctx.getTokenStack().size());
        }
    }

    @Test
    public void cursorPositionMatchesConsumedLength() {
        WordParser parser = new WordParser("hello");
        StringSource source = StringSource.createRootSource("hello world");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("should succeed", parsed.isSucceeded());

            Token consumed = parsed.getConsumed();
            int consumedTextLength = consumed.source.codePointLength().value();
            assertEquals("cursor should match consumed text length",
                consumedTextLength, ctx.getConsumedPosition().value());
        }
    }

    @Test
    public void parseFailureDiagnosticsAvailable() {
        // Parse something that fails and check diagnostics
        WordParser parser = new WordParser("xyz");
        StringSource source = StringSource.createRootSource("abc");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = parser.parse(ctx);
            assertTrue("should fail", parsed.isFailed());

            // Diagnostics should be available
            var diagnostics = ctx.getParseFailureDiagnostics();
            assertTrue("farthestConsumedOffset should be >= 0",
                diagnostics.getFarthestConsumedOffset() >= 0);
        }
    }

    @Test
    public void multipleWordsChain() {
        // Full sentence parse: "the" " " "cat" " " "sat"
        Chain grammar = new Chain(
            new WordParser("the"),
            new WordParser(" "),
            new WordParser("cat"),
            new WordParser(" "),
            new WordParser("sat")
        );

        StringSource source = StringSource.createRootSource("the cat sat");
        try (ParseContext ctx = new ParseContext(source, CreateMetaTokenSpecifier.createMetaOn)) {
            Parsed parsed = grammar.parse(ctx);
            assertTrue("full sentence should parse", parsed.isSucceeded());
            assertTrue("all consumed", ctx.allConsumed());

            Token root = parsed.getRootToken();
            // Should have 5 original children
            assertEquals(5, root.getOriginalChildren().size());
        }
    }
}
