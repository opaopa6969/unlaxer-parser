package org.unlaxer;

import static org.junit.Assert.*;

import java.util.Optional;

import org.junit.Test;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.ascii.DivisionParser;
import org.unlaxer.parser.ascii.LeftParenthesisParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.ascii.RightParenthesisParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.NonOrdered;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.RecursiveMode;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Tests for examples from README.md
 */
public class ReadmeExamplesTest extends ParserTestBase {

    // ========================================
    // Basic Example - Arithmetic Expression
    // ========================================

    @Test
    public void testBasicExample() {
        // Define grammar: [0-9]+([-+*/][0-9]+)*
        Parser parser = new Chain(
            new OneOrMore(DigitParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(
                        PlusParser.class,
                        MinusParser.class,
                        MultipleParser.class,
                        DivisionParser.class
                    ),
                    new OneOrMore(DigitParser.class)
                )
            )
        );

        // Parse input
        ParseContext context = new ParseContext(
            StringSource.createRootSource("1+2+3")
        );
        Parsed result = parser.parse(context);

        // Check result
        assertTrue(result.isSucceeded());
        assertEquals("1+2+3", result.getConsumed().source.toString());

        context.close();
    }

    // ========================================
    // Chain - Sequential Matching
    // ========================================

    @Test
    public void testChainSequentialMatching() {
        // Matches: "if", whitespace, identifier pattern
        Parser ifStatement = new Chain(
            new WordParser("if"),
            new OneOrMore(new MappedSingleCharacterParser(' ')),
            new OneOrMore(new MappedSingleCharacterParser("abcdefghijklmnopqrstuvwxyz"))
        );

        testAllMatch(ifStatement, "if x");
        testAllMatch(ifStatement, "if condition");
        testUnMatch(ifStatement, "if");
        testUnMatch(ifStatement, "ifx");
    }

    // ========================================
    // Choice - Alternative Matching
    // ========================================

    @Test
    public void testChoiceAlternativeMatching() {
        // Matches: number OR word
        Parser literal = new Choice(
            new OneOrMore(DigitParser.class),
            new OneOrMore(new MappedSingleCharacterParser("abcdefghijklmnopqrstuvwxyz"))
        );

        testPartialMatch(literal, "123abc", "123");
        testPartialMatch(literal, "abc123", "abc");
        testUnMatch(literal, "+");
    }

    // ========================================
    // ZeroOrMore - Repetition (0+)
    // ========================================

    @Test
    public void testZeroOrMoreRepetition() {
        // Matches: "", "a", "aa", "aaa", ...
        Parser manyAs = new ZeroOrMore(new MappedSingleCharacterParser('a'));

        testAllMatch(manyAs, "");
        testAllMatch(manyAs, "a");
        testAllMatch(manyAs, "aa");
        testAllMatch(manyAs, "aaa");
        testPartialMatch(manyAs, "aaab", "aaa");
    }

    // ========================================
    // OneOrMore - Repetition (1+)
    // ========================================

    @Test
    public void testOneOrMoreRepetition() {
        // Matches: "1", "12", "123", ...
        Parser digits = new OneOrMore(DigitParser.class);

        testAllMatch(digits, "1");
        testAllMatch(digits, "12");
        testAllMatch(digits, "123");
        testUnMatch(digits, "");
        testPartialMatch(digits, "123abc", "123");
    }

    // ========================================
    // Optional - Zero or One
    // ========================================

    @Test
    public void testOptionalZeroOrOne() {
        // Matches: "42" or "-42"
        Parser signedNumber = new Chain(
            new org.unlaxer.parser.combinator.Optional(MinusParser.class),
            new OneOrMore(DigitParser.class)
        );

        testAllMatch(signedNumber, "42");
        testAllMatch(signedNumber, "-42");
        testPartialMatch(signedNumber, "-123abc", "-123");
    }

    // ========================================
    // NonOrdered - Interleaved Matching
    // ========================================

    @Test
    public void testNonOrderedInterleavedMatching() {
        // Matches: "abc", "acb", "bac", "bca", "cab", "cba"
        Parser anyOrder = new NonOrdered(
            new MappedSingleCharacterParser('a'),
            new MappedSingleCharacterParser('b'),
            new MappedSingleCharacterParser('c')
        );

        testAllMatch(anyOrder, "abc");
        testAllMatch(anyOrder, "acb");
        testAllMatch(anyOrder, "bac");
        testAllMatch(anyOrder, "bca");
        testAllMatch(anyOrder, "cab");
        testAllMatch(anyOrder, "cba");
        testUnMatch(anyOrder, "ab");
        testUnMatch(anyOrder, "aab");
    }

    // ========================================
    // Source Hierarchy
    // ========================================

    @Test
    public void testSourceHierarchy() {
        // Root source - the original input
        Source root = StringSource.createRootSource("Hello World");

        // SubSource - a view into the parent (maintains position tracking)
        Source sub = root.subSource(
            new CodePointIndex(0),
            new CodePointIndex(5)
        );
        assertEquals("Hello", sub.sourceAsString());
        assertEquals(new CodePointOffset(0), sub.offsetFromRoot());
        assertTrue(sub.parent().isPresent());

        // Nested subSource - offsets are composed
        Source nested = sub.subSource(
            new CodePointIndex(1),
            new CodePointIndex(4)
        );
        assertEquals("ell", nested.sourceAsString());
        assertEquals(new CodePointOffset(1), nested.offsetFromParent());
        assertEquals(new CodePointOffset(1), nested.offsetFromRoot());
        assertTrue(nested.parent().isPresent());
    }

    @Test
    public void testSubSourceVsDetached() {
        Source root = StringSource.createRootSource("ABCDEFGH");

        // SubSource - keeps parent reference and offset
        Source sub = root.subSource(new CodePointIndex(2), new CodePointIndex(6));
        assertEquals("CDEF", sub.sourceAsString());
        assertTrue(sub.parent().isPresent());
        assertEquals(new CodePointOffset(2), sub.offsetFromRoot());

        // Detached - becomes independent root
        Source detached = sub.reRoot();
        assertEquals("CDEF", detached.sourceAsString());
        assertTrue(detached.isRoot());
        assertEquals(new CodePointOffset(0), detached.offsetFromRoot());
    }

    @Test
    public void testSourceHierarchyExample() {
        // Root: "The quick brown fox jumps"
        Source root = StringSource.createRootSource("The quick brown fox jumps");

        // Level 1: "quick brown fox"
        Source level1 = root.subSource(new CodePointIndex(4), new CodePointIndex(19));

        // Level 2: "brown"
        Source level2 = level1.subSource(new CodePointIndex(6), new CodePointIndex(11));

        // Accessing positions
        assertEquals("brown", level2.sourceAsString());
        assertEquals("quick brown fox", level2.parent().get().sourceAsString());
        assertEquals(new CodePointOffset(6), level2.offsetFromParent());
        assertEquals(new CodePointOffset(10), level2.offsetFromRoot());
    }

    // ========================================
    // CodePointIndex - Unicode-Aware Position
    // ========================================

    @Test
    public void testCodePointIndexWithEmoji() {
        String text = "A\uD83D\uDE00B";  // A😀B
        Source source = StringSource.createRootSource(text);

        // SubSource with emoji
        Source emoji = source.subSource(
            new CodePointIndex(1),
            new CodePointIndex(2)
        );
        assertEquals("\uD83D\uDE00", emoji.sourceAsString());  // 😀
    }

    @Test
    public void testCodePointIndexOperations() {
        CodePointIndex index = new CodePointIndex(10);

        // Arithmetic
        assertEquals(new CodePointIndex(11), index.newWithIncrements());
        assertEquals(new CodePointIndex(9), index.newWithDecrements());
        assertEquals(new CodePointIndex(15), index.newWithAdd(5));
        assertEquals(new CodePointIndex(7), index.newWithMinus(3));

        // Comparison
        assertTrue(index.eq(new CodePointIndex(10)));
        assertTrue(index.lt(new CodePointIndex(15)));
        assertTrue(index.ge(new CodePointIndex(5)));

        // Value access
        assertEquals(10, index.value());
    }

    // ========================================
    // Parse Context Options
    // ========================================

    @Test
    public void testParseContextWithMetaOn() {
        Parser parser = new OneOrMore(DigitParser.class);
        Source source = StringSource.createRootSource("123");

        // Enable meta token creation
        ParseContext context = new ParseContext(
            source,
            CreateMetaTokenSpecifier.createMetaOn
        );

        Parsed result = parser.parse(context);
        assertTrue(result.isSucceeded());
        context.close();
    }

    @Test
    public void testParseContextWithMetaOff() {
        Parser parser = new OneOrMore(DigitParser.class);
        Source source = StringSource.createRootSource("123");

        // Disable meta token creation
        ParseContext context = new ParseContext(
            source,
            CreateMetaTokenSpecifier.createMetaOff
        );

        Parsed result = parser.parse(context);
        assertTrue(result.isSucceeded());
        context.close();
    }

    // ========================================
    // Parse Status
    // ========================================

    @Test
    public void testParseStatus() {
        Parser parser = new OneOrMore(DigitParser.class);

        // Success case
        ParseContext successContext = new ParseContext(
            StringSource.createRootSource("123")
        );
        Parsed successResult = parser.parse(successContext);
        assertEquals(Parsed.Status.succeeded, successResult.status);
        successContext.close();

        // Failure case
        ParseContext failContext = new ParseContext(
            StringSource.createRootSource("abc")
        );
        Parsed failResult = parser.parse(failContext);
        assertEquals(Parsed.Status.failed, failResult.status);
        failContext.close();
    }

    // ========================================
    // Token Tree
    // ========================================

    @Test
    public void testTokenTree() {
        Parser parser = new Chain(
            new OneOrMore(DigitParser.class),
            Parser.get(PlusParser.class),
            new OneOrMore(DigitParser.class)
        );

        ParseContext context = new ParseContext(
            StringSource.createRootSource("12+34"),
            CreateMetaTokenSpecifier.createMetaOn
        );
        Parsed result = parser.parse(context);

        assertTrue(result.isSucceeded());
        Token root = result.getRootToken();

        // Token properties
        assertEquals("12+34", root.source.sourceAsString());

        context.close();
    }

    // ========================================
    // Pretty Printing
    // ========================================

    @Test
    public void testPrettyPrinting() {
        Parser parser = new Chain(
            new OneOrMore(DigitParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(
                        PlusParser.class,
                        MinusParser.class
                    ),
                    new OneOrMore(DigitParser.class)
                )
            )
        );

        ParseContext context = new ParseContext(
            StringSource.createRootSource("1+2+3"),
            CreateMetaTokenSpecifier.createMetaOn
        );
        Parsed result = parser.parse(context);

        assertTrue(result.isSucceeded());

        // Print token tree (just verify it doesn't throw)
        String printed = TokenPrinter.get(result.getRootToken());
        assertNotNull(printed);
        assertTrue(printed.contains("1+2+3"));

        context.close();
    }

    // ========================================
    // Recursive Grammar with Lazy Evaluation
    // ========================================

    /**
     * Factor parser for recursive expression grammar:
     * factor = number | '(' expr ')'
     */
    public static class TestFactorParser extends LazyChoice {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new OneOrMore(DigitParser.class),
                new Chain(
                    Parser.get(LeftParenthesisParser.class),
                    Parser.get(TestExprParser.class),
                    Parser.get(RightParenthesisParser.class)
                )
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    /**
     * Term parser for recursive expression grammar:
     * term = factor (('*' | '/') factor)*
     */
    public static class TestTermParser extends LazyChoice {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new Chain(
                    Parser.get(TestFactorParser.class),
                    new ZeroOrMore(
                        new Chain(
                            new Choice(
                                Parser.get(MultipleParser.class),
                                Parser.get(DivisionParser.class)
                            ),
                            Parser.get(TestFactorParser.class)
                        )
                    )
                )
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    /**
     * Expression parser for recursive expression grammar:
     * expr = term (('+' | '-') term)*
     */
    public static class TestExprParser extends LazyChoice {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new Chain(
                    Parser.get(TestTermParser.class),
                    new ZeroOrMore(
                        new Chain(
                            new Choice(
                                Parser.get(PlusParser.class),
                                Parser.get(MinusParser.class)
                            ),
                            Parser.get(TestTermParser.class)
                        )
                    )
                )
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    @Test
    public void testRecursiveGrammar() {
        Parser expr = Parser.get(TestExprParser.class);

        // Simple expressions
        testAllMatch(expr, "1");
        testAllMatch(expr, "1+2");
        testAllMatch(expr, "1+2+3");
        testAllMatch(expr, "1*2");
        testAllMatch(expr, "1+2*3");

        // Expressions with parentheses
        testAllMatch(expr, "(1)");
        testAllMatch(expr, "(1+2)");
        testAllMatch(expr, "1+(2*3)");
        testAllMatch(expr, "(1+2)*3");
        testAllMatch(expr, "((1+2))");
        testAllMatch(expr, "1+2*(3-4)");
    }

    // ========================================
    // Named Parsers
    // ========================================

    @Test
    public void testNamedParsers() {
        // Create a named parser by passing Name to constructor
        Parser number = new OneOrMore(Name.of("Number"), DigitParser.class);

        // Verify the name
        assertNotNull(number.getName());

        testAllMatch(number, "123");
    }

    // ========================================
    // reRoot with transformation
    // ========================================

    @Test
    public void testReRootWithTransformation() {
        Source root = StringSource.createRootSource("ABCDE");
        Source sub = root.subSource(new CodePointIndex(1), new CodePointIndex(4)); // "BCD"

        Source newRoot = sub.reRoot(s -> s.replace("BC", "X")); // "XD"

        assertTrue(newRoot.isRoot());
        assertEquals("XD", newRoot.sourceAsString());
        assertEquals(new CodePointOffset(0), newRoot.offsetFromRoot());
    }

    // ========================================
    // Source operations
    // ========================================

    @Test
    public void testSourceOperations() {
        Source source = StringSource.createRootSource("Hello World");

        // Create views
        Source sub = source.subSource(
            new CodePointIndex(6),
            new CodePointIndex(11)
        );
        assertEquals("World", sub.sourceAsString());

        // Transform (creates new detached source)
        Source upper = source.toUpperCaseAsStringInterface();
        assertEquals("HELLO WORLD", upper.sourceAsString());
    }

    // ========================================
    // Complete Arithmetic Expression Parser
    // ========================================

    @Test
    public void testCompleteArithmeticParser() {
        Parser expr = Parser.get(TestExprParser.class);

        String input = "1+2*(3-4)";
        ParseContext context = new ParseContext(
            StringSource.createRootSource(input)
        );

        Parsed result = expr.parse(context);

        assertTrue(result.isSucceeded());
        assertEquals(input, result.getConsumed().source.toString());

        context.close();
    }
}
