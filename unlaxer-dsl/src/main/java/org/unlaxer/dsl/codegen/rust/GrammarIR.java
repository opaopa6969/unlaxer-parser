package org.unlaxer.dsl.codegen.rust;

import java.util.List;

/** Target-neutral structural subset; deliberately separate from the metadata-only Parser IR. */
public record GrammarIR(List<Rule> rules, int root, boolean javaWhitespace) {
    public GrammarIR { rules = List.copyOf(rules); }
    public record Rule(String name, Expression body, Mapping mapping) {}
    public record Mapping(String name, List<Field> fields) {
        public Mapping { fields = List.copyOf(fields); }
    }
    public record Field(String name, Kind kind) {}
    public enum Kind { TEXT, NODE }
    public sealed interface Expression permits Literal, NumberToken, Reference, Sequence, Choice, Capture {}
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
