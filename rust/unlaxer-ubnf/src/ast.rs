//! Owned syntax model. Parsing does not resolve imports or validate backend support.

/// Half-open source ranges. Codepoints are Unicode scalar values, not UTF-16 units.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Span {
    pub byte_start: usize,
    pub byte_end: usize,
    pub codepoint_start: usize,
    pub codepoint_end: usize,
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
    Simple { parser_class: String },
    Until { terminator: String },
    Negation { excluded_chars: String },
    Lookahead { pattern: String },
    NegativeLookahead { pattern: String },
    Any,
    Eof,
    Empty,
    CharRange { min: char, max: char },
    CaseInsensitive { word: String },
    Regex { pattern: String },
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
    pub span: Span,
}

pub type AtomicElement = Spanned<ElementKind>;

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ElementKind {
    Group(RuleBody),
    Optional(RuleBody),
    Repeat(RuleBody),
    OneOrMore(Box<AtomicElement>),
    /// None is the open upper bound; values are restricted to Java's i32 domain.
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
