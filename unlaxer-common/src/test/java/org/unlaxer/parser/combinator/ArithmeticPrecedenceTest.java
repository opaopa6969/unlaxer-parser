package org.unlaxer.parser.combinator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.Test;
import org.unlaxer.Parsed;
import org.unlaxer.ParserTestBase;
import org.unlaxer.RecursiveMode;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.ascii.DivisionParser;
import org.unlaxer.parser.ascii.LeftParenthesisParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.ascii.RightParenthesisParser;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Integration test: build a calculator parser using combinators and verify
 * correct parsing structure for precedence and associativity.
 *
 * Grammar:
 *   expr   = term (('+' | '-') term)*
 *   term   = factor (('*' | '/') factor)*
 *   factor = number | '(' expr ')'
 *   number = [0-9]+
 */
public class ArithmeticPrecedenceTest extends ParserTestBase {

    // --- Parser definitions as inner classes for recursive reference ---

    public static class NumberParser extends OneOrMore {
        public NumberParser() {
            super(DigitParser.class);
        }
    }

    public static class FactorParser extends LazyChoice {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(NumberParser.class),
                new Chain(
                    Parser.get(LeftParenthesisParser.class),
                    Parser.get(ExprParser.class),
                    Parser.get(RightParenthesisParser.class)
                )
            );
        }
        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    public static class TermParser extends LazyChoice {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new Chain(
                    Parser.get(FactorParser.class),
                    new ZeroOrMore(
                        new Chain(
                            new Choice(
                                Parser.get(MultipleParser.class),
                                Parser.get(DivisionParser.class)
                            ),
                            Parser.get(FactorParser.class)
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

    public static class ExprParser extends LazyChoice {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new Chain(
                    Parser.get(TermParser.class),
                    new ZeroOrMore(
                        new Chain(
                            new Choice(
                                Parser.get(PlusParser.class),
                                Parser.get(MinusParser.class)
                            ),
                            Parser.get(TermParser.class)
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

    // --- Evaluate AST ---

    /**
     * Recursively evaluate the token tree produced by our grammar.
     * Walks the parse tree and computes the integer value.
     */
    private int evaluate(Token token) {
        Parser p = token.parser;
        String src = token.source.sourceAsString();

        // Leaf: pure digit string
        if (src.matches("\\d+") && token.getOriginalChildren().isEmpty()) {
            return Integer.parseInt(src);
        }

        // Number parser: digits
        if (p instanceof NumberParser) {
            return Integer.parseInt(src);
        }

        // Digit parser
        if (p instanceof DigitParser) {
            return Integer.parseInt(src);
        }

        // For any combinator, try to walk children
        List<Token> children = token.getOriginalChildren();

        // Single child: delegate
        if (children.size() == 1) {
            return evaluate(children.get(0));
        }

        // Chain-like: first child is value, remaining children may include ops
        if (p instanceof Chain || p instanceof ExprParser || p instanceof TermParser) {
            return evaluateChainLike(children);
        }

        // FactorParser with children: either number (1 child) or parens (chain child)
        if (p instanceof FactorParser) {
            return evaluateChainLike(children);
        }

        // Choice: delegate to first child
        if (p instanceof Choice || p instanceof LazyChoice) {
            if (!children.isEmpty()) {
                return evaluate(children.get(0));
            }
        }

        // ZeroOrMore with children: process each
        if (p instanceof ZeroOrMore) {
            // Should be handled by caller
            if (!children.isEmpty()) {
                return evaluate(children.get(0));
            }
        }

        // Fallback: try to find evaluable child (skip punctuation like parens)
        for (Token child : children) {
            String cs = child.source.sourceAsString();
            if (!"(".equals(cs) && !")".equals(cs) && !cs.isEmpty()) {
                try {
                    return evaluate(child);
                } catch (NumberFormatException e) {
                    // skip
                }
            }
        }

        return Integer.parseInt(src);
    }

    /**
     * Evaluate a chain-like token (Chain, Expr, Term) that has
     * [value, ZeroOrMore(op, value)*] structure.
     */
    private int evaluateChainLike(List<Token> children) {
        if (children.isEmpty()) {
            throw new IllegalArgumentException("Empty chain");
        }

        // Skip punctuation tokens ("(", ")")
        // Find the first evaluable value and the ZeroOrMore repeats
        int result = 0;
        boolean foundFirst = false;
        Token repeatsToken = null;

        for (Token child : children) {
            String cs = child.source.sourceAsString();
            Parser cp = child.parser;

            // Skip parentheses
            if ("(".equals(cs) || ")".equals(cs)) {
                continue;
            }

            if (!foundFirst) {
                result = evaluate(child);
                foundFirst = true;
            } else if (cp instanceof ZeroOrMore) {
                repeatsToken = child;
            }
        }

        if (repeatsToken != null) {
            List<Token> repeats = repeatsToken.getOriginalChildren();
            for (Token repeat : repeats) {
                List<Token> opAndValue = repeat.getOriginalChildren();
                if (opAndValue.size() >= 2) {
                    Token opToken = opAndValue.get(0);
                    Token valToken = opAndValue.get(1);

                    // The op might be wrapped in a Choice
                    String op = extractOp(opToken);
                    int right = evaluate(valToken);

                    switch (op) {
                        case "+": result += right; break;
                        case "-": result -= right; break;
                        case "*": result *= right; break;
                        case "/": result /= right; break;
                    }
                }
            }
        }

        return result;
    }

    /**
     * Extract operator string, unwrapping Choice wrappers if needed.
     */
    private String extractOp(Token token) {
        String src = token.source.sourceAsString();
        if ("+".equals(src) || "-".equals(src) || "*".equals(src) || "/".equals(src)) {
            return src;
        }
        // Walk into children to find operator
        for (Token child : token.getOriginalChildren()) {
            String result = extractOp(child);
            if (result != null) {
                return result;
            }
        }
        return src;
    }

    // --- Helper to parse and evaluate ---

    private int parseAndEval(String input) {
        Parser expr = Parser.get(ExprParser.class);
        ParseContext context = new ParseContext(
            StringSource.createRootSource(input),
            CreateMetaTokenSpecifier.createMetaOn
        );
        Parsed result = expr.parse(context);
        assertTrue("Parse should succeed for: " + input, result.isSucceeded());
        assertEquals("Should consume entire input: " + input,
            input, result.getConsumed().source.toString());
        Token root = result.getRootToken();
        int value = evaluate(root);
        context.close();
        return value;
    }

    // --- Tests ---

    @Test
    public void testSimpleAddition() {
        testAllMatch(Parser.get(ExprParser.class), "1+2");
    }

    @Test
    public void testSimpleMultiplication() {
        testAllMatch(Parser.get(ExprParser.class), "2*3");
    }

    @Test
    public void testPrecedence_MultiplicationBeforeAddition() {
        // "1+2*3" should parse such that * binds tighter than +
        // Structural test: the parse tree should group 2*3 together
        testAllMatch(Parser.get(ExprParser.class), "1+2*3");
        assertEquals(7, parseAndEval("1+2*3"));
    }

    @Test
    public void testLeftAssociativity_Subtraction() {
        // "3-2-1" = (3-2)-1 = 0, NOT 3-(2-1) = 2
        testAllMatch(Parser.get(ExprParser.class), "3-2-1");
        assertEquals(0, parseAndEval("3-2-1"));
    }

    @Test
    public void testParenthesesOverridePrecedence() {
        // "(1+2)*3" = 9
        testAllMatch(Parser.get(ExprParser.class), "(1+2)*3");
        assertEquals(9, parseAndEval("(1+2)*3"));
    }

    @Test
    public void testMultipleSameLevelOps() {
        // "1+2+3+4" = 10
        testAllMatch(Parser.get(ExprParser.class), "1+2+3+4");
        assertEquals(10, parseAndEval("1+2+3+4"));
    }

    @Test
    public void testComplexExpression() {
        // "2*(3+4)-1" = 2*7-1 = 14-1 = 13
        testAllMatch(Parser.get(ExprParser.class), "2*(3+4)-1");
        assertEquals(13, parseAndEval("2*(3+4)-1"));
    }

    @Test
    public void testNestedParentheses() {
        // "((1+2))" = 3
        testAllMatch(Parser.get(ExprParser.class), "((1+2))");
        assertEquals(3, parseAndEval("((1+2))"));
    }

    @Test
    public void testSingleNumber() {
        testAllMatch(Parser.get(ExprParser.class), "42");
        assertEquals(42, parseAndEval("42"));
    }
}
