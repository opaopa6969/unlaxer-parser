//! Owned, normalized input to the Rust emitter, independent of the UBNF frontend.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GrammarIr {
    pub rules: Vec<Rule>,
    pub root: usize,
    pub java_whitespace: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Rule {
    pub name: String,
    pub body: Expression,
    /// Parsed normally, but excluded with all descendants from AST projection.
    pub skip: bool,
    pub mapping: Option<Mapping>,
    pub operator: Option<Operator>,
    pub catalog: Option<Catalog>,
    /// Ordered @doc paragraphs. Tooling metadata; never changes recognition.
    pub documentation: Vec<String>,
    /// Ordered literal alternatives for a grammar @enum; empty for ordinary rules.
    pub enum_literals: Vec<String>,
    /// Mapping-time constraints, resolved after every named value is bound.
    pub type_constraints: Vec<TypeConstraint>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TypeConstraint {
    pub capture: Field,
    pub reference: Field,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Catalog {
    pub context: String,
    /// Stable lexical target names; frontends normalize this list by name.
    pub captures: Vec<String>,
}

/// Descriptive only: the rule graph determines parsing precedence.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Operator {
    pub associativity: Associativity,
    pub precedence: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Associativity {
    Left,
    Right,
    None,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Mapping {
    pub name: String,
    pub fields: Vec<Field>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Field {
    pub name: String,
    pub kind: Kind,
    pub cardinality: Cardinality,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Kind {
    /// Java generated numeric capture contract: signed 32-bit integer.
    Integer,
    /// Explicit nested capture; containers are ordered outermost first.
    Nested {
        leaf: Box<Kind>,
        containers: Vec<Cardinality>,
    },
    Enum(String),
    Text,
    Node,
    /// Nullable only on an associative synthetic literal leaf; not an Optional container.
    NullableNode,
    Value,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cardinality {
    One,
    Optional,
    Many,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BoundaryKind {
    Value,
    Optional,
    List,
    Separator,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScopeMode {
    Lexical,
    Dynamic,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Declaration {
    pub symbol_capture: String,
    pub description: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RuleEffects {
    pub scope_mode: Option<ScopeMode>,
    pub declares: Option<Declaration>,
    pub backref: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecoveryMode {
    Sync,
    BeforeSync,
    Skip,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Expression {
    OwnedCapture {
        name: String,
        child: Box<Expression>,
    },
    Replay(String),
    CaptureScope(Box<Expression>),
    /// Cut (#700); `None` uses the runtime default message. CST-transparent.
    Commit {
        child: Box<Expression>,
        message: Option<String>,
    },
    /// Labeled failure (#700). CST-transparent.
    Label {
        label: String,
        child: Box<Expression>,
    },
    /// Catch labeled failures (#700); analyses treat it as the choice of both operands.
    CatchLabels {
        child: Box<Expression>,
        alternative: Box<Expression>,
        labels: Vec<String>,
    },
    NamePredicate {
        child: Box<Expression>,
        snapshot: String,
        version: String,
        capture: String,
        kind: String,
    },
    NameResolutionScope {
        child: Box<Expression>,
        requirements: Vec<(String, String)>,
    },
    LexicalContextScope {
        child: Box<Expression>,
        terminals: Vec<(String, bool, crate::lexical::LexicalExpression)>,
    },
    LexicalToken {
        name: String,
        expression: crate::lexical::LexicalExpression,
    },
    RuleEffects {
        child: Box<Expression>,
        effects: RuleEffects,
    },
    /// Compare this rule body's completed named captures after a successful parse.
    CaptureEquality {
        child: Box<Expression>,
        name: String,
    },
    Recovery {
        child: Box<Expression>,
        mode: RecoveryMode,
        tokens: Vec<String>,
        message: String,
    },
    /// A word. `Literal("")` is Java's lexical `LiteralParser("")`, used only in
    /// token-stream or lexical-context grammars: zero-width success.
    Literal(String),
    /// UBNF `''` as Java `WordParser("")`: never matches; expects the empty word.
    EmptyWordToken,
    /// Always fails without consuming input; the message is an expected hint.
    ErrorExpected(String),
    NumberToken,
    IdentifierToken,
    GraphemeToken,
    QuotedToken(char),
    CodeStartToken,
    CodeEndToken,
    LongCodeBlockToken,
    /// A trusted, validated Rust function path supplied by a token adapter registry.
    CustomToken(String),
    AnyToken,
    EofToken,
    EmptyToken,
    /// A nonempty word matched using the pinned Unicode simple case mapping.
    CaseInsensitiveToken(String),
    CharRangeToken {
        min: char,
        max: char,
    },
    ExceptToken(String),
    UntilToken(String),
    LookaheadToken {
        pattern: String,
        positive: bool,
    },
    Reference(usize),
    Sequence(Vec<Expression>),
    Choice(Vec<Expression>),
    LongestChoice(Vec<Expression>),
    UniqueLongestChoice(Vec<Expression>),
    PredictiveChoice {
        alternatives: Vec<Expression>,
        predictors: Vec<Predictor>,
    },
    Capture {
        name: String,
        expression: Box<Expression>,
    },
    CaptureBoundary {
        kind: BoundaryKind,
        child: Box<Expression>,
    },
    OptionalExpr(Box<Expression>),
    Repeat {
        child: Box<Expression>,
        min: usize,
        max: Option<usize>,
    },
    Separated {
        child: Box<Expression>,
        separator: Box<Expression>,
    },
    /// Synthetic trivia boundary outside a capture; not a source-level group.
    Delimited(Box<Expression>),
    /// Preserve a text alternative as a synthetic CST value with its own span.
    TextValue(Box<Expression>),
    /// Scalar/optional capture boundary: retain its full span if all values are text.
    ValueBoundary(Box<Expression>),
    /// Rule-local trivia policy, transparent to captures and semantic values.
    LexicalTriviaScope {
        child: Box<Expression>,
        definition: crate::lexical::LexicalExpression,
    },
    TriviaScope {
        child: Box<Expression>,
        java_whitespace: bool,
        /// Only the Java `CPPComment` form of a global `@comment` (#712).
        line_comments: bool,
    },
}

/// Conservative FIRST predicate. `Any` deliberately disables pruning for an
/// alternative; `OneOf` contains only predicates that are all proven possible.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Predictor {
    Any,
    Literal(String),
    Number,
    Identifier,
    Quoted(char),
    OneOf(Vec<Predictor>),
}
