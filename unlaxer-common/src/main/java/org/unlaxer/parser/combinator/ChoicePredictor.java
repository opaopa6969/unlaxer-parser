package org.unlaxer.parser.combinator;

import java.io.Serializable;

import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;

/** A conservative, side-effect-free FIRST predicate for one choice alternative. */
public sealed interface ChoicePredictor extends Serializable
        permits ChoicePredictor.Any, ChoicePredictor.Literal, ChoicePredictor.Number,
                ChoicePredictor.Identifier, ChoicePredictor.Quoted, ChoicePredictor.AnyOf {

    boolean mayMatch(ParseContext context, TokenKind tokenKind);

    static ChoicePredictor any() { return Any.INSTANCE; }

    static ChoicePredictor literal(String value) { return new Literal(value); }

    static ChoicePredictor number() { return Number.INSTANCE; }

    static ChoicePredictor identifier() { return Identifier.INSTANCE; }

    static ChoicePredictor quoted(char quote) { return new Quoted(quote); }

    static ChoicePredictor anyOf(ChoicePredictor... predictors) {
        return new AnyOf(java.util.List.of(predictors));
    }

    /** Unknown FIRST set. It must never remove an alternative. */
    enum Any implements ChoicePredictor {
        INSTANCE;

        @Override
        public boolean mayMatch(ParseContext context, TokenKind tokenKind) { return true; }
    }

    private static int firstCodePoint(ParseContext context, TokenKind tokenKind) {
        var position = context.getPosition(tokenKind);
        var source = context.getSource();
        return position.value() < source.codePointLength().value()
            ? source.codePointAt(position).value() : -1;
    }

    private static boolean mayStartWithUnconsumedTrivia(int codePoint) {
        return codePoint >= 0 && (Character.isWhitespace(codePoint)
            || Character.isSpaceChar(codePoint) || codePoint == '/');
    }

    /** A necessary case-sensitive literal prefix. */
    record Literal(String value) implements ChoicePredictor {
        public Literal {
            if (value == null) throw new NullPointerException("value");
        }

        @Override
        public boolean mayMatch(ParseContext context, TokenKind tokenKind) {
            // Trivia may be consumed by an enclosing/generated interleave parser before the
            // alternative itself observes input. Without previewing that policy here, retain
            // every literal candidate at whitespace or a possible comment opener.
            var position = context.getPosition(tokenKind);
            var source = context.getSource();
            int codePoint = firstCodePoint(context, tokenKind);
            if (mayStartWithUnconsumedTrivia(codePoint)) return true;
            return source.startsWith(value, position);
        }
    }

    /** Necessary first-character class of the built-in {@code NumberParser}. */
    enum Number implements ChoicePredictor {
        INSTANCE;

        @Override
        public boolean mayMatch(ParseContext context, TokenKind tokenKind) {
            int codePoint = firstCodePoint(context, tokenKind);
            return mayStartWithUnconsumedTrivia(codePoint)
                || codePoint == '+' || codePoint == '-' || codePoint == '.'
                || codePoint >= '0' && codePoint <= '9';
        }
    }

    /** Necessary first-character class of the built-in C-style {@code IdentifierParser}. */
    enum Identifier implements ChoicePredictor {
        INSTANCE;

        @Override
        public boolean mayMatch(ParseContext context, TokenKind tokenKind) {
            int codePoint = firstCodePoint(context, tokenKind);
            return mayStartWithUnconsumedTrivia(codePoint) || codePoint == '_'
                || codePoint >= 'A' && codePoint <= 'Z'
                || codePoint >= 'a' && codePoint <= 'z';
        }
    }

    /** Necessary opening delimiter of a known quoted-string parser. */
    record Quoted(char quote) implements ChoicePredictor {
        @Override
        public boolean mayMatch(ParseContext context, TokenKind tokenKind) {
            int codePoint = firstCodePoint(context, tokenKind);
            return mayStartWithUnconsumedTrivia(codePoint) || codePoint == quote;
        }
    }

    /** Union of finite known FIRST predicates. */
    record AnyOf(java.util.List<ChoicePredictor> predictors) implements ChoicePredictor {
        public AnyOf { predictors = java.util.List.copyOf(predictors); }

        @Override
        public boolean mayMatch(ParseContext context, TokenKind tokenKind) {
            for (ChoicePredictor predictor : predictors) {
                if (predictor.mayMatch(context, tokenKind)) return true;
            }
            return false;
        }
    }
}
