package org.unlaxer.dsl.codegen.rust;

import java.util.List;

/** Target-neutral structural subset; deliberately separate from the metadata-only Parser IR. */
public record GrammarIR(List<Rule> rules, int root, boolean javaWhitespace) {
    public GrammarIR { rules = List.copyOf(rules); }
    /** Skip hides an entire AST projection, never the parser/CST or its state effects. */
    public record Rule(String name, Expression body, Mapping mapping, Operator operator, Catalog catalog, boolean skip, List<String> documentation, List<String> enumLiterals, List<TypeConstraint> typeConstraints) {
        public Rule {
            documentation = List.copyOf(documentation);
            enumLiterals = List.copyOf(enumLiterals);
            typeConstraints = List.copyOf(typeConstraints);
            if (skip && mapping != null) throw new IllegalArgumentException("skipped rule has mapping: " + name);
        }
        public Rule(String name, Expression body, Mapping mapping, Operator operator, Catalog catalog, boolean skip, List<String> documentation, List<String> enumLiterals) {
            this(name, body, mapping, operator, catalog, skip, documentation, enumLiterals, List.of());
        }
        public Rule(String name, Expression body, Mapping mapping, Operator operator, Catalog catalog, boolean skip, List<String> documentation) {
            this(name,body,mapping,operator,catalog,skip,documentation,List.of());
        }
        public Rule(String name, Expression body, Mapping mapping, Operator operator, Catalog catalog, boolean skip) {
            this(name, body, mapping, operator, catalog, skip, List.of());
        }
        public Rule(String name, Expression body, Mapping mapping, Operator operator, Catalog catalog) {
            this(name, body, mapping, operator, catalog, false);
        }
    }
    public record TypeConstraint(Field capture, Field reference) {}
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
    public record Field(String name, Kind kind, Cardinality cardinality, String enumName, NestedShape nested) {
        public Field {
            if (kind == Kind.NESTED && (nested == null || cardinality != Cardinality.ONE || enumName != null)
                    || kind != Kind.NESTED && nested != null) {
                throw new IllegalArgumentException("E-NESTED-SHAPE: invalid nested field schema");
            }
        }
        public Field(String name, Kind kind, Cardinality cardinality, String enumName) { this(name,kind,cardinality,enumName,null); }
        public Field(String name, Kind kind, Cardinality cardinality) { this(name,kind,cardinality,null); }
    }
    public record NestedShape(Kind leaf, String enumName, List<Cardinality> containers, String javaLeafType) {
        public NestedShape(Kind leaf, String enumName, List<Cardinality> containers) { this(leaf,enumName,containers,null); }
        public NestedShape {
            containers = List.copyOf(containers);
            boolean mapped = leaf == Kind.NODE || leaf == Kind.ENUM || leaf == Kind.VALUE;
            if ((leaf != Kind.TEXT && leaf != Kind.INTEGER && !mapped)
                    || (leaf == Kind.ENUM ? enumName == null || enumName.isBlank() : enumName != null)
                    || (mapped ? javaLeafType == null || javaLeafType.isBlank() : javaLeafType != null)
                    || containers.size() > 32
                    || containers.contains(Cardinality.ONE)) {
                throw new IllegalArgumentException("E-NESTED-SHAPE: invalid nested leaf or containers");
            }
        }
    }
    public enum Kind { TEXT, NODE, VALUE, ENUM, INTEGER, NULLABLE_NODE, NESTED }
    public enum Cardinality { ONE, OPTIONAL, MANY }
    public enum ScopeMode { LEXICAL, DYNAMIC }
    public record Declaration(String symbolCapture, String description) {}
    public record Effects(ScopeMode scopeMode, Declaration declares, String backref) {}
    public record RuleEffects(Expression child, Effects effects) implements Expression {}
    /** Compares this rule's own completed named captures; differences are semantic diagnostics. */
    public record CaptureEquality(Expression child, String name) implements Expression {}
    public record NamePredicate(Expression child, String snapshot, String version, String capture, String kind) implements Expression {}
    public record NameRequirement(String id, String version) {}
    public record NameResolutionScope(Expression child, List<NameRequirement> requirements) implements Expression {
        public NameResolutionScope { requirements=List.copyOf(requirements); }
    }
    public enum RecoveryMode { SYNC, BEFORE_SYNC, SKIP }
    /** Rule-level transactional recovery; a recovered tree must not be mapped as a normal AST. */
    public record Recovery(Expression child, RecoveryMode mode, List<String> tokens, String message)
            implements Expression {
        public Recovery {
            tokens = List.copyOf(tokens);
            if (tokens.stream().anyMatch(String::isEmpty)
                || new java.util.HashSet<>(tokens).size() != tokens.size()) {
                throw new IllegalArgumentException("duplicate or empty recovery sync token");
            }
        }
    }
    public sealed interface Expression permits Literal, NumberToken, Reference, Sequence, Choice, LongestChoice, UniqueLongestChoice, PredictiveChoice, Capture,
        CaptureBoundary, OptionalExpr, Repeat, Separated, AnyToken, EofToken, EmptyToken, CaseInsensitiveToken, ErrorExpected, CharRangeToken,
        ExceptToken, UntilToken, LookaheadToken, Delimited, IdentifierToken, GraphemeToken, QuotedToken,
        CodeStartToken, CodeEndToken, LongCodeBlockToken, CustomToken, TextValue, ValueBoundary, TriviaScope, RuleEffects,
        CaptureEquality, NamePredicate, NameResolutionScope, Recovery, LexicalToken, LexicalTriviaScope, LexicalContextScope, GeneralBody, Replay, CatchLabels {}
    public record LexicalContextScope(Expression child, List<org.unlaxer.dsl.runtime.Lexing.Terminal> terminals) implements Expression {
        public LexicalContextScope { terminals = List.copyOf(terminals); }
    }
    public record LexicalToken(String name, org.unlaxer.dsl.runtime.LexicalExpression expression) implements Expression {}
    /** Rule-local trivia policy, transparent to captures and semantic values. */
    public record LexicalTriviaScope(Expression child, org.unlaxer.dsl.runtime.LexicalExpression definition) implements Expression {}
    /** {@code lineComments}: only the Java CPPComment form of a global {@code @comment} (#712). */
    public record TriviaScope(Expression child, boolean javaWhitespace, boolean lineComments) implements Expression {
        public TriviaScope(Expression child, boolean javaWhitespace) { this(child, javaWhitespace, false); }
    }
    /** Retains an otherwise unmapped text branch as a source-positioned semantic value. */
    public record TextValue(Expression child) implements Expression {}
    public record ValueBoundary(Expression child) implements Expression {}
    public record IdentifierToken() implements Expression {}
    public record GraphemeToken() implements Expression {}
    public record QuotedToken(char quote) implements Expression {}
    public record CodeStartToken() implements Expression {}
    public record CodeEndToken() implements Expression {}
    public record LongCodeBlockToken() implements Expression {}
    /** Validated Rust function path for a user supplied parser callback. */
    public record CustomToken(String functionPath) implements Expression {}
    /** Synthetic trivia boundary, outside the capture site; unlike a source-level group. */
    public record Delimited(Expression child) implements Expression {}
    public record AnyToken() implements Expression {}
    public record EofToken() implements Expression {}
    public record EmptyToken() implements Expression {}
    /** A nonempty word matched using the pinned Unicode simple case mapping. */
    public record CaseInsensitiveToken(String word) implements Expression {}
    /** A non-consuming, always-failing expected hint; distinct from recovery. */
    public record ErrorExpected(String message) implements Expression {}
    public record CharRangeToken(char min, char max) implements Expression {}
    public record ExceptToken(String excluded) implements Expression {}
    public record UntilToken(String terminator) implements Expression {}
    public record LookaheadToken(String pattern, boolean positive) implements Expression {}
    public enum BoundaryKind { VALUE, OPTIONAL, LIST, SEPARATOR }
    public record CaptureBoundary(BoundaryKind kind, Expression child) implements Expression {}
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
    public record UniqueLongestChoice(List<Expression> alternatives) implements Expression {
        public UniqueLongestChoice { alternatives = List.copyOf(alternatives); }
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
    public sealed interface GeneralBody extends Expression permits OwnedCapture, CaptureScope, Commit, Label {
        Expression child();
        GeneralBody withChild(Expression child);
    }
    public record OwnedCapture(String name, Expression child) implements GeneralBody {
        @Override public GeneralBody withChild(Expression child) { return new OwnedCapture(name, child); }
    }
    public record CaptureScope(Expression child) implements GeneralBody {
        @Override public GeneralBody withChild(Expression child) { return new CaptureScope(child); }
    }
    /** Cut (#700); a null message uses the runtime default. CST-transparent. */
    public record Commit(Expression child, String message) implements GeneralBody {
        @Override public GeneralBody withChild(Expression child) { return new Commit(child, message); }
    }
    /** Labeled failure (#700). CST-transparent. */
    public record Label(String label, Expression child) implements GeneralBody {
        @Override public GeneralBody withChild(Expression child) { return new Label(label, child); }
    }
    /** Catch labeled failures (#700); analyses treat it as the choice of both operands. */
    public record CatchLabels(Expression child, Expression alternative, List<String> labels) implements Expression {
        public CatchLabels { labels = List.copyOf(labels); }
        public Choice asChoice() { return new Choice(List.of(child, alternative)); }
    }
    public record Replay(String name) implements Expression {}
    public record Capture(String name, Expression expression) implements Expression {}
}
