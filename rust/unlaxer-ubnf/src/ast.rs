//! Owned syntax model. Parsing does not resolve imports or validate backend support.

/// Half-open source ranges. Codepoints are Unicode scalar values, not UTF-16 units.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Span {
    pub byte_start: usize,
    pub byte_end: usize,
    pub codepoint_start: usize,
    pub codepoint_end: usize,
}

/// The operands of `CATCH(body, alternative {, label})`, stored once inside the choice:
/// the first `split` alternatives of [`ElementKind::Catch::choice`] are the body, the
/// rest the alternative. No operand is duplicated, so nesting depth stays linear.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CatchOperands {
    pub split: usize,
    pub body_span: Span,
    pub alternative_span: Span,
    pub labels: Vec<String>,
}

impl CatchOperands {
    /// The body operand as an owned rule body (a copy of its share of `choice`).
    pub fn body(&self, choice: &RuleBody) -> RuleBody {
        RuleBody {
            alternatives: choice.alternatives[..self.split].to_vec(),
            span: self.body_span,
        }
    }
    /// The alternative operand as an owned rule body.
    pub fn alternative(&self, choice: &RuleBody) -> RuleBody {
        RuleBody {
            alternatives: choice.alternatives[self.split..].to_vec(),
            span: self.alternative_span,
        }
    }
}

/// `CATCH(body, alternative {, labels})`, moving both operands into the stored choice.
pub fn catch_element(body: RuleBody, alternative: RuleBody, labels: Vec<String>) -> ElementKind {
    let operands = CatchOperands {
        split: body.alternatives.len(),
        body_span: body.span,
        alternative_span: alternative.span,
        labels,
    };
    let span = body.span.through(alternative.span);
    let mut alternatives = body.alternatives;
    alternatives.extend(alternative.alternatives);
    ElementKind::Catch {
        choice: RuleBody { alternatives, span },
        operands: Box::new(operands),
    }
}

impl Span {
    pub(crate) fn through(self, end: Self) -> Self {
        Self {
            byte_end: end.byte_end,
            codepoint_end: end.codepoint_end,
            ..self
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Spanned<T> {
    pub kind: T,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct UbnfFile {
    pub grammars: Vec<GrammarDecl>,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GrammarDecl {
    pub name: String,
    pub imports: Vec<ImportDecl>,
    pub settings: Vec<GlobalSetting>,
    pub tokens: Vec<TokenDecl>,
    pub rules: Vec<RuleDecl>,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ImportDecl {
    pub alias: String,
    pub path: String,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct GlobalSetting {
    pub key: String,
    pub value: SettingValue,
    pub value_span: Span,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SettingValue {
    String(String),
    Block(Vec<KeyValuePair>),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct KeyValuePair {
    pub key: String,
    pub value: String,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TokenDecl {
    pub name: String,
    pub kind: TokenKind,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TokenKind {
    Declarative {
        expression: crate::lexical::LexicalExpression,
    },
    Simple {
        parser_class: String,
    },
    /// Target-neutral token adapter reference. Validation and binding happen after parsing.
    Adapter {
        id: String,
        version: String,
    },
    Until {
        terminator: String,
    },
    Negation {
        excluded_chars: String,
    },
    Lookahead {
        pattern: String,
    },
    NegativeLookahead {
        pattern: String,
    },
    Any,
    Eof,
    Empty,
    CharRange {
        min: char,
        max: char,
    },
    CaseInsensitive {
        word: String,
    },
    Regex {
        pattern: String,
    },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RuleDecl {
    pub annotations: Vec<Annotation>,
    pub name: String,
    pub body: RuleBody,
    pub span: Span,
}

pub type Annotation = Spanned<AnnotationKind>;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum AnnotationKind {
    Root,
    Mapping {
        class_name: String,
        params: Vec<String>,
    },
    Eval {
        kind: String,
        strategy: String,
        params: Vec<KeyValuePair>,
    },
    Whitespace {
        style: Option<String>,
    },
    Interleave {
        profile: String,
    },
    LexicalContext {
        tokens: Vec<String>,
        literals: Vec<String>,
    },
    Backref {
        name: String,
    },
    ScopeTree {
        mode: String,
    },
    Declares {
        symbol_capture: String,
        description: Option<String>,
    },
    Catalog {
        context: String,
    },
    LeftAssoc,
    RightAssoc,
    LongestChoice,
    UniqueLongestChoice,
    Predicate {
        name: String,
    },
    NamePredicate {
        snapshot: String,
        version: String,
        name: String,
        kind: String,
    },
    PredictiveChoice,
    Precedence {
        level: i32,
    },
    Doc {
        text: String,
    },
    Recovery {
        mode: RecoveryMode,
        sync_tokens: Vec<String>,
    },
    Skip,
    Simple {
        name: String,
    },
    CommonField {
        fields: Vec<String>,
    },
    Enum,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RecoveryMode {
    Sync,
    Auto,
    Skip,
}

/// Nonempty ordered choice of nonempty sequences (even for one alternative).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RuleBody {
    pub alternatives: Vec<Sequence>,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Sequence {
    pub elements: Vec<AnnotatedElement>,
    pub span: Span,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AnnotatedElement {
    pub element: AtomicElement,
    pub capture: Option<String>,
    pub typeof_constraint: Option<String>,
    /// The complete capture suffix, including '@'; absent on synthetic wrappers.
    pub capture_span: Option<Span>,
    /// The complete '@typeof(...)' prefix.
    pub typeof_span: Option<Span>,
    pub span: Span,
}

pub type AtomicElement = Spanned<ElementKind>;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ElementKind {
    OwnedCapture {
        name: String,
        body: RuleBody,
    },
    Replay {
        name: String,
    },
    CaptureScope(RuleBody),
    /// `COMMIT(body [, 'message'])`: cut (#700). CST-transparent like a group.
    Commit {
        body: RuleBody,
        message: Option<String>,
    },
    /// `LABEL(label, body)`: labeled failure (#700). CST-transparent like a group.
    Label {
        label: String,
        body: RuleBody,
    },
    /// `CATCH(body, alternative {, label})`: catch labeled failures (#700).
    /// An empty label list is syntax; validation rejects it with this element's span.
    Catch {
        /// Exactly one operand produces the CST, so shape analyses see the choice of both
        /// operands' alternatives (Java `CatchElement.body()`). Build it with [`catch_element`].
        choice: RuleBody,
        /// Boxed so that the recursive element type does not grow (deep nesting limit).
        operands: Box<CatchOperands>,
    },
    Group(RuleBody),
    Optional(RuleBody),
    Repeat(RuleBody),
    OneOrMore(Box<AtomicElement>),
    /// None is the open upper bound; values above i32::MAX are rejected by validation.
    BoundedRepeat {
        element: Box<AtomicElement>,
        min: u32,
        max: Option<u32>,
    },
    Separated {
        element: Box<AtomicElement>,
        separator: Box<AtomicElement>,
    },
    Terminal(String),
    RuleRef {
        namespace: Option<String>,
        name: String,
    },
    Error(String),
}
