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
    pub mapping: Option<Mapping>,
    pub operator: Option<Operator>,
    pub catalog: Option<Catalog>,
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Text,
    Node,
    Value,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cardinality {
    One,
    Optional,
    Many,
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Expression {
    RuleEffects {
        child: Box<Expression>,
        effects: RuleEffects,
    },
    Literal(String),
    NumberToken,
    IdentifierToken,
    QuotedToken(char),
    CodeStartToken,
    CodeEndToken,
    AnyToken,
    EofToken,
    EmptyToken,
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
    PredictiveChoice {
        alternatives: Vec<Expression>,
        predictors: Vec<Predictor>,
    },
    Capture {
        name: String,
        expression: Box<Expression>,
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
    TriviaScope {
        child: Box<Expression>,
        java_whitespace: bool,
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
