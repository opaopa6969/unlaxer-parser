package org.unlaxer.dsl.codegen.rust;

import java.util.List;

/** Target-neutral structural subset; deliberately separate from the metadata-only Parser IR. */
public record GrammarIR(List<Rule> rules, int root, boolean javaWhitespace) {
    public GrammarIR { rules = List.copyOf(rules); }
    public record Rule(String name, Expression body, Mapping mapping, Operator operator, Catalog catalog) {}
    public record Catalog(String context, List<String> captures) {
        public Catalog { captures = List.copyOf(captures); }
    }
    /** Precedence is metadata: the rule graph, not these numbers, determines parsing order. */
    public record Operator(Associativity associativity, int precedence) {}
    public enum Associativity { LEFT, RIGHT, NONE }
    public List<Mapping> mappings() {
        return rules.stream().map(Rule::mapping).filter(java.util.Objects::nonNull).distinct().toList();
    }
    public record Mapping(String name, List<Field> fields) {
        public Mapping { fields = List.copyOf(fields); }
    }
    public record Field(String name, Kind kind, Cardinality cardinality) {}
    public enum Kind { TEXT, NODE, VALUE }
    public enum Cardinality { ONE, OPTIONAL, MANY }
    public enum ScopeMode { LEXICAL, DYNAMIC }
    public record Declaration(String symbolCapture, String description) {}
    public record Effects(ScopeMode scopeMode, Declaration declares, String backref) {}
    public record RuleEffects(Expression child, Effects effects) implements Expression {}
    public sealed interface Expression permits Literal, NumberToken, Reference, Sequence, Choice, LongestChoice, PredictiveChoice, Capture,
        OptionalExpr, Repeat, Separated, AnyToken, EofToken, EmptyToken, CharRangeToken,
        ExceptToken, UntilToken, LookaheadToken, Delimited, IdentifierToken, QuotedToken,
        CodeStartToken, CodeEndToken, TextValue, ValueBoundary, TriviaScope, RuleEffects {}
    /** Rule-local trivia policy, transparent to captures and semantic values. */
    public record TriviaScope(Expression child, boolean javaWhitespace) implements Expression {}
    /** Retains an otherwise unmapped text branch as a source-positioned semantic value. */
    public record TextValue(Expression child) implements Expression {}
    public record ValueBoundary(Expression child) implements Expression {}
    public record IdentifierToken() implements Expression {}
    public record QuotedToken(char quote) implements Expression {}
    public record CodeStartToken() implements Expression {}
    public record CodeEndToken() implements Expression {}
    /** Synthetic trivia boundary, outside the capture site; unlike a source-level group. */
    public record Delimited(Expression child) implements Expression {}
    public record AnyToken() implements Expression {}
    public record EofToken() implements Expression {}
    public record EmptyToken() implements Expression {}
    public record CharRangeToken(char min, char max) implements Expression {}
    public record ExceptToken(String excluded) implements Expression {}
    public record UntilToken(String terminator) implements Expression {}
    public record LookaheadToken(String pattern, boolean positive) implements Expression {}
    public record OptionalExpr(Expression child) implements Expression {}
    /** A null maximum denotes unbounded repetition. */
    public record Repeat(Expression child, int min, Integer max) implements Expression {}
    public record Separated(Expression child, Expression separator) implements Expression {}
    public record Literal(String text) implements Expression {}
    public record NumberToken() implements Expression {}
    public record Reference(int rule) implements Expression {}
    public record Sequence(List<Expression> elements) implements Expression {
        public Sequence { elements = List.copyOf(elements); }
    }
    public record Choice(List<Expression> alternatives) implements Expression {
        public Choice { alternatives = List.copyOf(alternatives); }
    }
    public record LongestChoice(List<Expression> alternatives) implements Expression {
        public LongestChoice { alternatives = List.copyOf(alternatives); }
    }
    public sealed interface Predictor permits AnyPredictor, LiteralPredictor, NumberPredictor,
        IdentifierPredictor, QuotedPredictor, AnyOfPredictor {}
    public record AnyPredictor() implements Predictor {}
    public record LiteralPredictor(String text) implements Predictor {}
    public record NumberPredictor() implements Predictor {}
    public record IdentifierPredictor() implements Predictor {}
    public record QuotedPredictor(char quote) implements Predictor {}
    public record AnyOfPredictor(List<Predictor> alternatives) implements Predictor {
        public AnyOfPredictor { alternatives = List.copyOf(alternatives); }
    }
    public record PredictiveChoice(List<Expression> alternatives, List<Predictor> predictors) implements Expression {
        public PredictiveChoice {
            alternatives = List.copyOf(alternatives);
            predictors = List.copyOf(predictors);
            if (alternatives.size() != predictors.size()) {
                throw new IllegalArgumentException("predictive alternatives/predictors mismatch");
            }
        }
    }
    public record Capture(String name, Expression expression) implements Expression {}
}
