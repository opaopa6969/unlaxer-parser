package org.unlaxer.dsl.codegen.rust;

import java.util.List;

/** Target-neutral structural subset; deliberately separate from the metadata-only Parser IR. */
public record GrammarIR(List<Rule> rules, int root, boolean javaWhitespace) {
    public GrammarIR { rules = List.copyOf(rules); }
    public record Rule(String name, Expression body, Mapping mapping) {}
    public record Mapping(String name, List<Field> fields) {
        public Mapping { fields = List.copyOf(fields); }
    }
    public record Field(String name, Kind kind, Cardinality cardinality) {}
    public enum Kind { TEXT, NODE }
    public enum Cardinality { ONE, OPTIONAL, MANY }
    public sealed interface Expression permits Literal, NumberToken, Reference, Sequence, Choice, Capture,
        OptionalExpr, Repeat, Separated, AnyToken, EofToken, EmptyToken, CharRangeToken,
        ExceptToken, UntilToken, LookaheadToken, Delimited, IdentifierToken, QuotedToken {}
    public record IdentifierToken() implements Expression {}
    public record QuotedToken(char quote) implements Expression {}
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
    public record Capture(String name, Expression expression) implements Expression {}
}
