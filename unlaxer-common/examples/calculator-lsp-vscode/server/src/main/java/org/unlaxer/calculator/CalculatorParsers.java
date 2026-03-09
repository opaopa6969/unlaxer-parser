package org.unlaxer.calculator;

import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.ascii.LeftParenthesisParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.ascii.RightParenthesisParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.DigitParser;

/**
 * Calculator parser definitions.
 *
 * Grammar:
 * expr     = term (('+' | '-') term)*
 * term     = factor (('*' | '/') factor)*
 * factor   = unary | number | '(' expr ')' | function
 * unary    = ('+' | '-') factor
 * function = ('sin' | 'sqrt' | 'cos' | 'tan') '(' expr ')'
 * number   = digit+ ('.' digit+)?
 */
public class CalculatorParsers {

    // Names for identifying parsers
    public static final Name EXPR = Name.of("expr");
    public static final Name TERM = Name.of("term");
    public static final Name FACTOR = Name.of("factor");
    public static final Name NUMBER = Name.of("number");
    public static final Name FUNCTION = Name.of("function");
    public static final Name FUNCTION_NAME = Name.of("functionName");
    public static final Name OPERATOR = Name.of("operator");

    /**
     * Division parser (/)
     */
    public static class DivisionParser extends MappedSingleCharacterParser {
        public DivisionParser() {
            super('/');
        }
    }

    /**
     * Dot parser (.)
     */
    public static class DotParser extends MappedSingleCharacterParser {
        public DotParser() {
            super('.');
        }
    }

    /**
     * Number parser: digit+ ('.' digit+)?
     */
    public static class NumberParser extends LazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new OneOrMore(DigitParser.class),
                new org.unlaxer.parser.combinator.Optional(
                    new Chain(
                        Parser.get(DotParser.class),
                        new OneOrMore(DigitParser.class)
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
     * Function name parser: 'sin' | 'sqrt' | 'cos' | 'tan'
     */
    public static class FunctionNameParser extends Choice {
        public FunctionNameParser() {
            super(
                Name.of("functionName"),
                new WordParser("sqrt"),
                new WordParser("sin"),
                new WordParser("cos"),
                new WordParser("tan")
            );
        }
    }

    /**
     * Function parser: functionName '(' expr ')'
     */
    public static class FunctionParser extends LazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(FunctionNameParser.class),
                Parser.get(LeftParenthesisParser.class),
                Parser.get(ExprParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    /**
     * Unary parser: ('+' | '-') factor
     */
    public static class UnaryParser extends LazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new Choice(
                    PlusParser.class,
                    MinusParser.class
                ),
                Parser.get(FactorParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    /**
     * Parenthesized expression parser: '(' expr ')'
     */
    public static class ParenExprParser extends LazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(LeftParenthesisParser.class),
                Parser.get(ExprParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    /**
     * Factor parser: function | unary | number | '(' expr ')'
     */
    public static class FactorParser extends LazyChoice {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(FunctionParser.class),
                Parser.get(UnaryParser.class),
                Parser.get(NumberParser.class),
                Parser.get(ParenExprParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    /**
     * Multiplicative operator parser: '*' | '/'
     */
    public static class MulOpParser extends Choice {
        public MulOpParser() {
            super(
                MultipleParser.class,
                DivisionParser.class
            );
        }
    }

    /**
     * Additive operator parser: '+' | '-'
     */
    public static class AddOpParser extends Choice {
        public AddOpParser() {
            super(
                PlusParser.class,
                MinusParser.class
            );
        }
    }

    /**
     * Term parser: factor (('*' | '/') factor)*
     */
    public static class TermParser extends LazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(FactorParser.class),
                new ZeroOrMore(
                    new Chain(
                        Parser.get(MulOpParser.class),
                        Parser.get(FactorParser.class)
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
     * Expression parser: term (('+' | '-') term)*
     */
    public static class ExprParser extends LazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(TermParser.class),
                new ZeroOrMore(
                    new Chain(
                        Parser.get(AddOpParser.class),
                        Parser.get(TermParser.class)
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
     * Get the root parser for calculator expressions.
     */
    public static Parser getRootParser() {
        return Parser.get(ExprParser.class);
    }
}
