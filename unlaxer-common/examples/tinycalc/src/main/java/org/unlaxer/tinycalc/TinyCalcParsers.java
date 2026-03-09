package org.unlaxer.tinycalc;

import java.util.Optional;
import java.util.function.Supplier;

import org.unlaxer.Name;
import org.unlaxer.RecursiveMode;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.ascii.DivisionParser;
import org.unlaxer.parser.ascii.LeftParenthesisParser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.ascii.PointParser;
import org.unlaxer.parser.ascii.RightParenthesisParser;
import org.unlaxer.parser.combinator.Chain;
import org.unlaxer.parser.combinator.Choice;
import org.unlaxer.parser.combinator.LazyChain;
import org.unlaxer.parser.combinator.LazyChoice;
import org.unlaxer.parser.combinator.LazyZeroOrMore;
import org.unlaxer.parser.combinator.OneOrMore;
import org.unlaxer.parser.combinator.WhiteSpaceDelimitedChain;
import org.unlaxer.parser.combinator.WhiteSpaceDelimitedLazyChain;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;
import org.unlaxer.parser.elementary.MultipleParser;
import org.unlaxer.parser.elementary.NumberParser;
import org.unlaxer.parser.elementary.SignParser;
import org.unlaxer.parser.elementary.SingleCharacterParser;
import org.unlaxer.parser.elementary.WordParser;
import org.unlaxer.parser.posix.AlphabetNumericUnderScoreParser;
import org.unlaxer.parser.posix.AlphabetUnderScoreParser;
import org.unlaxer.parser.posix.CommaParser;
import org.unlaxer.parser.posix.DigitParser;
import org.unlaxer.util.cache.SupplierBoundCache;

/**
 * TinyCalc parser definitions.
 *
 * Grammar (BNF):
 *
 * TinyCalc           = VariableDeclarations Expression
 * VariableDeclarations = { VariableDeclaration }
 * VariableDeclaration = ('var' | 'variable') Identifier ['set' Expression] ';'
 *
 * Expression          = Term { ('+' | '-') Term }
 * Term                = Factor { ('*' | '/') Factor }
 * Factor              = FunctionCall | UnaryExpression | Number | Identifier | '(' Expression ')'
 *
 * UnaryExpression     = ('+' | '-') Factor
 *
 * FunctionCall        = SingleArgFunction | TwoArgFunction | NoArgFunction
 * SingleArgFunction   = SingleArgFunctionName '(' Expression ')'
 * TwoArgFunction      = TwoArgFunctionName '(' Expression ',' Expression ')'
 * NoArgFunction       = 'random' '(' ')'
 *
 * SingleArgFunctionName = 'sin' | 'cos' | 'tan' | 'sqrt' | 'abs' | 'log'
 * TwoArgFunctionName    = 'min' | 'max' | 'pow'
 *
 * Identifier          = AlphabetUnderScore { AlphabetNumericUnderScore }
 * Number              = [Sign] (Digits '.' Digits | Digits '.' | Digits | '.' Digits) [Exponent]
 */
public class TinyCalcParsers {

    // ========================================================================
    // Leaf parsers - new single character parsers
    // ========================================================================

    /**
     * Semicolon parser (;)
     */
    public static class SemicolonParser extends SingleCharacterParser {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isMatch(char target) {
            return ';' == target;
        }
    }

    // ========================================================================
    // Identifier parser
    // ========================================================================

    /**
     * Identifier: AlphabetUnderScore { AlphabetNumericUnderScore }
     */
    public static class IdentifierParser extends LazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(AlphabetUnderScoreParser.class),
                new ZeroOrMore(
                    Parser.get(AlphabetNumericUnderScoreParser.class)
                )
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    // ========================================================================
    // Function name parsers
    // ========================================================================

    /**
     * Single argument function names: sin | cos | tan | sqrt | abs | log
     */
    public static class SingleArgFunctionNameParser extends Choice {
        public SingleArgFunctionNameParser() {
            super(
                Name.of("singleArgFunctionName"),
                new WordParser("sqrt"),
                new WordParser("sin"),
                new WordParser("cos"),
                new WordParser("tan"),
                new WordParser("abs"),
                new WordParser("log")
            );
        }
    }

    /**
     * Two argument function names: min | max | pow
     */
    public static class TwoArgFunctionNameParser extends Choice {
        public TwoArgFunctionNameParser() {
            super(
                Name.of("twoArgFunctionName"),
                new WordParser("min"),
                new WordParser("max"),
                new WordParser("pow")
            );
        }
    }

    // ========================================================================
    // Function call parsers (use Lazy for circular reference to ExpressionParser)
    // ========================================================================

    /**
     * Single argument function: funcName '(' Expression ')'
     * Circular: references ExpressionParser
     */
    public static class SingleArgFunctionParser extends WhiteSpaceDelimitedLazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(SingleArgFunctionNameParser.class),
                Parser.get(LeftParenthesisParser.class),
                Parser.get(ExpressionParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    /**
     * Two argument function: funcName '(' Expression ',' Expression ')'
     * Circular: references ExpressionParser
     */
    public static class TwoArgFunctionParser extends WhiteSpaceDelimitedLazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(TwoArgFunctionNameParser.class),
                Parser.get(LeftParenthesisParser.class),
                Parser.get(ExpressionParser.class),
                Parser.get(CommaParser.class),
                Parser.get(ExpressionParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    /**
     * No argument function: 'random' '(' ')'
     */
    public static class NoArgFunctionParser extends WhiteSpaceDelimitedLazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("random"),
                Parser.get(LeftParenthesisParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    /**
     * FunctionCall: SingleArgFunction | TwoArgFunction | NoArgFunction
     */
    public static class FunctionCallParser extends LazyChoice {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(TwoArgFunctionParser.class),
                Parser.get(SingleArgFunctionParser.class),
                Parser.get(NoArgFunctionParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    // ========================================================================
    // Unary expression parser
    // ========================================================================

    /**
     * UnaryExpression: ('+' | '-') Factor
     */
    public static class UnaryExpressionParser extends LazyChain {
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

    // ========================================================================
    // Parenthesized expression
    // ========================================================================

    /**
     * ParenExpression: '(' Expression ')'
     * Circular: references ExpressionParser
     */
    public static class ParenExpressionParser extends WhiteSpaceDelimitedLazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(LeftParenthesisParser.class),
                Parser.get(ExpressionParser.class),
                Parser.get(RightParenthesisParser.class)
            );
        }
    }

    // ========================================================================
    // Factor, Term, Expression
    // ========================================================================

    /**
     * Factor: FunctionCall | UnaryExpression | Number | Identifier | '(' Expression ')'
     */
    public static class FactorParser extends LazyChoice {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(FunctionCallParser.class),
                Parser.get(UnaryExpressionParser.class),
                Parser.get(NumberParser.class),
                Parser.get(IdentifierParser.class),
                Parser.get(ParenExpressionParser.class)
            );
        }

        @Override
        public Optional<RecursiveMode> getNotAstNodeSpecifier() {
            return Optional.empty();
        }
    }

    /**
     * Multiplicative operators: '*' | '/'
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
     * Additive operators: '+' | '-'
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
     * Term: Factor { ('*' | '/') Factor }
     */
    public static class TermParser extends WhiteSpaceDelimitedLazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(FactorParser.class),
                new ZeroOrMore(
                    new WhiteSpaceDelimitedChain(
                        Parser.get(MulOpParser.class),
                        Parser.get(FactorParser.class)
                    )
                )
            );
        }
    }

    /**
     * Expression: Term { ('+' | '-') Term }
     */
    public static class ExpressionParser extends WhiteSpaceDelimitedLazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(TermParser.class),
                new ZeroOrMore(
                    new WhiteSpaceDelimitedChain(
                        Parser.get(AddOpParser.class),
                        Parser.get(TermParser.class)
                    )
                )
            );
        }
    }

    // ========================================================================
    // Variable declaration
    // ========================================================================

    /**
     * Variable keyword: 'var' | 'variable'
     */
    public static class VarKeywordParser extends Choice {
        public VarKeywordParser() {
            super(
                Name.of("varKeyword"),
                new WordParser("variable"),
                new WordParser("var")
            );
        }
    }

    /**
     * Variable initializer: 'set' Expression
     */
    public static class VariableInitializerParser extends WhiteSpaceDelimitedLazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                new WordParser("set"),
                Parser.get(ExpressionParser.class)
            );
        }
    }

    /**
     * VariableDeclaration: ('var'|'variable') Identifier ['set' Expression] ';'
     */
    public static class VariableDeclarationParser extends WhiteSpaceDelimitedLazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(VarKeywordParser.class),
                Parser.get(IdentifierParser.class),
                new org.unlaxer.parser.combinator.Optional(
                    Parser.get(VariableInitializerParser.class)
                ),
                Parser.get(SemicolonParser.class)
            );
        }
    }

    /**
     * VariableDeclarations: { VariableDeclaration }
     */
    public static class VariableDeclarationsParser extends LazyZeroOrMore {
        @Override
        public Supplier<Parser> getLazyParser() {
            return new SupplierBoundCache<>(() -> Parser.get(VariableDeclarationParser.class));
        }

        @Override
        public Optional<Parser> getLazyTerminatorParser() {
            return Optional.empty();
        }
    }

    // ========================================================================
    // Root parser
    // ========================================================================

    /**
     * TinyCalc: VariableDeclarations Expression
     */
    public static class TinyCalcParser extends WhiteSpaceDelimitedLazyChain {
        @Override
        public Parsers getLazyParsers() {
            return new Parsers(
                Parser.get(VariableDeclarationsParser.class),
                Parser.get(ExpressionParser.class)
            );
        }
    }

    /**
     * Get the root parser for TinyCalc.
     */
    public static Parser getRootParser() {
        return Parser.get(TinyCalcParser.class);
    }

    /**
     * Get the expression-only parser (for simple expressions without variable declarations).
     */
    public static Parser getExpressionParser() {
        return Parser.get(ExpressionParser.class);
    }
}
