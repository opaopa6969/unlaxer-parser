//! Experimental UBNF structural subset. No JVM, unsafe code, or external dependencies.
use std::any::Any;
use std::collections::{BTreeMap, HashMap};
use std::hash::{BuildHasher, Hasher};
use std::rc::Rc;
use std::sync::Arc;

pub mod call_inference;
pub mod capture_tree;
pub mod editor;
pub mod editor_cst;
pub mod editor_queries;
pub mod embedded;
mod first;
pub mod grapheme;
pub mod incremental;
pub mod language_profile;
pub mod language_queries;
pub mod lexical;
pub mod lexing;
mod long_code_fence;
pub mod mapping;
#[cfg(test)]
mod memo_retention_tests;
pub mod names;
pub mod pipeline;
pub mod provider_process;
pub mod provider_protocol;
mod scope;
pub mod semantic;
pub mod semantic_project;
pub mod semantic_queries;
pub mod semantic_query_cache;
pub mod semantic_rename;
pub mod semantic_rules;
pub mod shared_calls;
pub mod source;
pub mod source_edits;
pub mod token;
pub mod token_trace;
pub mod type_system;
mod unicode_case_data;
mod unicode_grapheme_data;
mod unicode_xid;
#[doc(hidden)]
pub use first::{set_candidate_exclusion_for_current_thread, CandidateExclusion};
pub use scope::{
    Declaration, ReferenceInfo, RuleEffects, ScopeMode, ScopeStore, Severity, SymbolDiagnostic,
    SymbolInfo,
};

/// Half-open Unicode scalar (code-point) offsets, not UTF-8 bytes or UTF-16 units.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Span {
    pub start: usize,
    pub end: usize,
}

/// Reserved CST rule ID for a text-projection boundary, never an index into grammar rules.
/// The node retains its original children; mappers should project its span as text.
pub const TEXT_VALUE_RULE: usize = usize::MAX;

/// Reserved CST rule ID for a scalar mixed-value capture boundary.
/// A mapper may collapse a nonempty all-text projection to this complete source span.
pub const VALUE_BOUNDARY_RULE: usize = usize::MAX - 1;

/// Reserved CST rule ID for a successfully recovered invalid source region.
/// Recovery nodes are not grammar rules or typed AST values.
pub const RECOVERY_ERROR_RULE: usize = usize::MAX - 2;

/// Reserved rule ID for an explicit Classic terminal, never a grammar-rule index.
pub const TOKEN_LEAF_RULE: usize = usize::MAX - 3;
/// Reserved rule ID for a Classic collecting boundary.
pub const TOKEN_META_RULE: usize = usize::MAX - 4;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecoveryMode {
    /// Resume after the nearest sync token, consuming that token.
    Sync,
    /// Resume immediately before the nearest known following token.
    BeforeSync,
    /// Resume before a following token, or consume one scalar if none is known.
    Skip,
}

/// A committed recovery event, retained with the CST rather than a fatal parse error.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecoveryDiagnostic {
    /// Half-open Unicode-scalar span of the consumed invalid region.
    pub span: Span,
    pub message: &'static str,
    /// CST node carrying `RECOVERY_ERROR_RULE` and this event's span.
    pub node: usize,
}

#[derive(Debug, Clone)]
pub enum Expr {
    /// An atomic token compiled from a declarative UBNF lexical expression.
    Lexical(&'static str, lexical::LexicalExpression),
    Literal(&'static str),
    /// Unicode 17.0.0 simple lower(upper(cp)) equality, preserving original text.
    /// No normalization, locale tailoring, or multi-scalar folding. Empty words fail.
    CaseInsensitive(&'static str),
    Number,
    /// Java clang IdentifierParser: ASCII letter/underscore, then ASCII alphanumeric/underscore.
    Identifier,
    /// One Unicode 17 extended grapheme cluster, only at an original-source boundary.
    Grapheme,
    /// tinyexpression CodeStartParser: an atomic line-oriented opening code fence.
    CodeStart,
    /// tinyexpression CodeEndParser: an atomic line-oriented closing code fence.
    CodeEnd,
    /// tinyexpression LongCodeBlockParser: N >= 4 backticks, exact-width closing line.
    LongCodeBlock,
    /// Java single/double-quoted text: backslash followed by any scalar, no escape decoding.
    Quoted(char),
    Rule(usize),
    Sequence(Vec<Expr>),
    /// Match each child occurrence once, greedily choosing the first unused match.
    /// Failed candidates roll back; selected children are never permuted on later failure.
    /// Empty children succeed, nullable children run once, and no trivia is added here.
    NonOrdered(Vec<Expr>),
    Choice(Vec<Expr>),
    /// Try every alternative from the same transactional state and commit the
    /// one that consumes the most input. Equal-length matches keep declaration order.
    LongestChoice(Vec<Expr>),
    /// Choose a nonempty unique maximum; reject ties and counts outside 2..=64.
    UniqueLongestChoice(Vec<Expr>),
    /// Ordered choice that skips alternatives whose conservative FIRST
    /// predictor proves they cannot match. `Any` preserves ordinary choice.
    PredictiveChoice {
        alternatives: Vec<Expr>,
        predictors: Vec<Predictor>,
    },
    Capture(&'static str, Box<Expr>),
    /// Read outer bindings, discard local captures on exit without discarding child results.
    CaptureScope(Box<Expr>),
    /// General owned binding, separate from AST field captures. CST-transparent.
    OwnedCapture(&'static str, Box<Expr>),
    /// Replay a general owned binding at the current input cursor.
    Replay(&'static str),
    /// Classic MatchOnly: publish matched tokens/cursor, restore owned semantic state.
    MatchOnly(Box<Expr>),
    /// Toggle leaf inversion; unlike PEG not_ahead, this can consume input.
    InvertMatch(Box<Expr>),
    /// Preserve token kind and stop inherited leaf inversion.
    StopInvert(Box<Expr>),
    /// Force the exact Consumed kind, preserving inherited leaf inversion.
    StopConsume(Box<Expr>),
    /// Wrap successful child nodes in a text-projection boundary without changing parsing.
    TextValue(Box<Expr>),
    /// Preserve a scalar mixed-value capture span without deciding text versus node.
    ValueBoundary(Box<Expr>),
    /// Explicit nested capture CST boundary; existing flat Capture remains unchanged.
    CaptureBoundary(capture_tree::Kind, Box<Expr>),
    /// AST field boundary: validate input provenance before Classic collection.
    InputCaptureBoundary(capture_tree::Kind, Box<Expr>),
    /// Apply rule-local symbol effects to this child's captures after a successful parse.
    RuleEffects {
        child: Box<Expr>,
        effects: RuleEffects,
    },
    /// Compare completed captures from this child, without changing syntax acceptance.
    /// Syntax predicate over an explicit immutable name snapshot.
    NamePredicate {
        child: Box<Expr>,
        snapshot: &'static str,
        version: &'static str,
        capture: &'static str,
        kind: &'static str,
    },
    /// Entry boundary: unresolved names cannot become discarded-alternative success.
    NameResolutionScope {
        child: Box<Expr>,
        requirements: Vec<names::Requirement>,
    },
    CaptureEquality {
        child: Box<Expr>,
        name: &'static str,
    },
    /// Apply a local trivia policy to sequence boundaries, restoring the caller afterwards.
    LexicalTriviaScope {
        child: Box<Expr>,
        definition: lexical::LexicalExpression,
    },
    LexicalContextScope {
        child: Box<Expr>,
        terminals: Arc<[lexing::Terminal]>,
    },
    TriviaScope {
        child: Box<Expr>,
        whitespace: bool,
    },
    Optional(Box<Expr>),
    /// Java Occurs retains a failed direct consuming atom's match-cursor reset.
    JavaOptional(Box<Expr>),
    Repeat {
        child: Box<Expr>,
        min: usize,
        max: Option<usize>,
    },
    /// Explicit historical Java Zero/LazyZero: probe once and accept no matches.
    /// Unlike a bounded zero repetition this can inspect and reset the match cursor.
    ZeroMatch(Box<Expr>),
    /// Java Occurs counts a successful zero-consumption child once, then stops.
    JavaRepeat {
        child: Box<Expr>,
        min: usize,
        max: Option<usize>,
    },
    Lookahead {
        child: Box<Expr>,
        positive: bool,
    },
    Any,
    Eof,
    Empty,
    CharRange(char, char),
    /// One scalar recognized by a custom terminal, with inherited Classic arguments.
    /// The callback may read external state, so FIRST and memoization are conservative.
    Character {
        label: &'static str,
        predicate: fn(char) -> bool,
    },
    Except(&'static str),
    Until(&'static str),
    /// UBNF/Java predicates use a separate, advancing match-only cursor.
    JavaLookahead {
        pattern: &'static str,
        positive: bool,
    },
    /// Java EMPTY optionally matches one scalar without consuming it.
    JavaEmpty,
    /// Java UNTIL succeeds at EOF even if its non-consuming terminator is absent.
    JavaUntil(&'static str),
    Error(&'static str),
    /// Recover after a failed child parse, keeping a CST marker for skipped source.
    Recovery {
        child: Box<Expr>,
        mode: RecoveryMode,
        tokens: Vec<&'static str>,
        message: &'static str,
    },
    /// Undeclared custom parser: [`Diagnostics::Auto`] conservatively uses `Detailed`.
    Custom(fn(&mut ParseContext<'_>) -> ParseResult),
    /// Custom parser with a caller-supplied contract for automatic diagnostics.
    /// Deferred diagnostics require `reads_diagnostics: false` and `replayable: true`.
    /// The contract includes nested parsers and external effects: changing diagnostic
    /// recording must not affect acceptance, CST, captures, scopes or user state, and
    /// a fresh-context retry must reproduce the result without harmful repeated effects.
    /// These flags do not declare memoization safety; custom parsers remain memo-unsafe.
    CustomWith {
        parser: fn(&mut ParseContext<'_>) -> ParseResult,
        reads_diagnostics: bool,
        replayable: bool,
    },
    Backreference(&'static str),
}

#[derive(Debug, Clone)]
pub enum Predictor {
    Any,
    Literal(&'static str),
    Number,
    Identifier,
    Quoted(char),
    OneOf(Vec<Predictor>),
}

impl Predictor {
    fn matches(&self, raw: &str, after_trivia: &str) -> bool {
        match self {
            Self::Any => true,
            Self::Literal(value) => raw.starts_with(value) || after_trivia.starts_with(value),
            Self::Number => number_may_start(raw) || number_may_start(after_trivia),
            Self::Identifier => identifier_may_start(raw) || identifier_may_start(after_trivia),
            Self::Quoted(quote) => raw.starts_with(*quote) || after_trivia.starts_with(*quote),
            Self::OneOf(values) => values.iter().any(|value| value.matches(raw, after_trivia)),
        }
    }
}

fn number_may_start(value: &str) -> bool {
    let bytes = value.as_bytes();
    let offset = usize::from(
        bytes
            .first()
            .is_some_and(|byte| matches!(byte, b'+' | b'-')),
    );
    bytes.get(offset).is_some_and(u8::is_ascii_digit)
        || (bytes.get(offset) == Some(&b'.')
            && bytes.get(offset + 1).is_some_and(u8::is_ascii_digit))
}

fn identifier_may_start(value: &str) -> bool {
    value
        .as_bytes()
        .first()
        .is_some_and(|byte| byte.is_ascii_alphabetic() || *byte == b'_')
}

/// Reusable combinators. These build the same rule expressions used by generated parsers.
impl Expr {
    /// A pinned Unicode 17 simple case-insensitive word. Empty words fail to parse.
    pub fn case_insensitive(text: &'static str) -> Self {
        Self::CaseInsensitive(text)
    }

    pub fn literal(text: &'static str) -> Self {
        Self::Literal(text)
    }
    pub fn character(label: &'static str, predicate: fn(char) -> bool) -> Self {
        Self::Character { label, predicate }
    }
    pub fn sequence(elements: impl IntoIterator<Item = Self>) -> Self {
        Self::Sequence(elements.into_iter().collect())
    }
    pub fn choice(alternatives: impl IntoIterator<Item = Self>) -> Self {
        Self::Choice(alternatives.into_iter().collect())
    }
    /// Java NonOrdered semantics; this is unrelated to UBNF trivia `@interleave`.
    pub fn non_ordered(children: impl IntoIterator<Item = Self>) -> Self {
        Self::NonOrdered(children.into_iter().collect())
    }
    pub fn longest_choice(alternatives: impl IntoIterator<Item = Self>) -> Self {
        Self::LongestChoice(alternatives.into_iter().collect())
    }
    pub fn unique_longest_choice(alternatives: impl IntoIterator<Item = Self>) -> Self {
        Self::UniqueLongestChoice(alternatives.into_iter().collect())
    }
    pub fn predictive_choice(alternatives: impl IntoIterator<Item = (Predictor, Self)>) -> Self {
        let (predictors, alternatives) = alternatives.into_iter().unzip();
        Self::PredictiveChoice {
            alternatives,
            predictors,
        }
    }
    /// Match tinyexpression's opening code fence without applying grammar trivia between parts.
    pub fn code_start() -> Self {
        Self::CodeStart
    }
    /// Match tinyexpression's closing code fence without applying grammar trivia between parts.
    pub fn code_end() -> Self {
        Self::CodeEnd
    }

    pub fn long_code_block() -> Self {
        Self::LongCodeBlock
    }
    pub fn then(self, next: Self) -> Self {
        Self::sequence([self, next])
    }
    pub fn or(self, alternative: Self) -> Self {
        Self::choice([self, alternative])
    }
    pub fn optional(self) -> Self {
        Self::Optional(Box::new(self))
    }
    pub fn optional_java(self) -> Self {
        Self::JavaOptional(Box::new(self))
    }
    pub fn repeat(self, min: usize, max: Option<usize>) -> Self {
        Self::Repeat {
            child: Box::new(self),
            min,
            max,
        }
    }
    pub fn zero_or_more(self) -> Self {
        self.repeat(0, None)
    }
    pub fn zero_match(self) -> Self {
        Self::ZeroMatch(Box::new(self))
    }
    pub fn repeat_java(self, min: usize, max: Option<usize>) -> Self {
        Self::JavaRepeat {
            child: Box::new(self),
            min,
            max,
        }
    }
    pub fn one_or_more(self) -> Self {
        self.repeat(1, None)
    }
    pub fn capture(self, name: &'static str) -> Self {
        Self::Capture(name, Box::new(self))
    }
    /// Capture this child's actual source in the separate general owned binding store.
    pub fn capture_owned(self, name: &'static str) -> Self {
        Self::OwnedCapture(name, Box::new(self))
    }
    /// Read through outer captures and discard definitions made by this child.
    pub fn capture_scope(self) -> Self {
        Self::CaptureScope(Box::new(self))
    }

    /// Preserve this expression's consumed source as one text value, including trivia.
    /// Inner nodes/captures remain available; no quote stripping or text decoding occurs.
    pub fn text_value(self) -> Self {
        Self::TextValue(Box::new(self))
    }
    /// Preserve a mixed scalar/optional capture boundary; parsing remains transparent.
    pub fn value_boundary(self) -> Self {
        Self::ValueBoundary(Box::new(self))
    }
    pub fn lexical_context_scope(self, terminals: Vec<lexing::Terminal>) -> Self {
        Self::LexicalContextScope {
            child: Box::new(self),
            terminals: terminals.into(),
        }
    }

    pub fn lexical_trivia_scope(self, definition: lexical::LexicalExpression) -> Self {
        Self::LexicalTriviaScope {
            child: Box::new(self),
            definition,
        }
    }
    pub fn trivia_scope(self, whitespace: bool) -> Self {
        Self::TriviaScope {
            child: Box::new(self),
            whitespace,
        }
    }
    pub fn rule_effects(self, effects: RuleEffects) -> Self {
        Self::RuleEffects {
            child: Box::new(self),
            effects,
        }
    }
    /// Compare this child's own named captures after parsing, retaining semantic errors.
    pub fn compare_captures(name: &'static str, child: Self) -> Self {
        Self::CaptureEquality {
            child: Box::new(child),
            name,
        }
    }
    /// Wrap an expression with an error-recovery strategy. Empty tokens fail closed at parse time.
    pub fn recover(
        self,
        mode: RecoveryMode,
        tokens: impl IntoIterator<Item = &'static str>,
        message: &'static str,
    ) -> Self {
        Self::Recovery {
            child: Box::new(self),
            mode,
            tokens: tokens.into_iter().collect(),
            message,
        }
    }
    pub fn ahead(self) -> Self {
        Self::Lookahead {
            child: Box::new(self),
            positive: true,
        }
    }
    pub fn match_only(self) -> Self {
        Self::MatchOnly(Box::new(self))
    }
    pub fn invert_match(self) -> Self {
        Self::InvertMatch(Box::new(self))
    }
    pub fn stop_invert(self) -> Self {
        Self::StopInvert(Box::new(self))
    }
    pub fn stop_consume(self) -> Self {
        Self::StopConsume(Box::new(self))
    }
    pub fn not_ahead(self) -> Self {
        Self::Lookahead {
            child: Box::new(self),
            positive: false,
        }
    }
    pub fn separated_by(self, separator: Self) -> Self {
        Self::sequence([self.clone(), separator.then(self).zero_or_more()])
    }
    /// Classic separated list: require the first item, then count a successful
    /// no-progress separator/item tail once and stop, matching Java Occurs.
    /// The existing [`Self::separated_by`] retains its pure PEG repeat contract.
    pub fn separated_by_java(self, separator: Self) -> Self {
        Self::sequence([self.clone(), separator.then(self).repeat_java(0, None)])
    }
}

#[derive(Debug, Clone)]
pub struct Rule {
    pub name: &'static str,
    pub expression: Expr,
}

/// Immutable grammar graph shared across parses.
///
/// Generated parsers keep one of these in a `OnceLock`. Cloning this value only
/// increments the `Arc` reference count; parser cursors, captures, CST nodes,
/// diagnostics, scopes and user state remain owned by each [`ParseContext`].
pub type SharedGrammar = Arc<[Rule]>;

/// Parse-local memoization policy. The default preserves the historical behavior.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Memoization {
    #[default]
    Off,
    /// Cache failures for context-independent rules and successes for their statically
    /// pure subset. The historical variant name is retained for compatibility.
    /// Explicit Classic modes retain their kind/inversion in the key. Opt-in
    /// token tracing does not replay successes.
    SafeFailures,
}

/// Syntax-failure recording policy, independent of semantic diagnostics and memoization.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum Diagnostics {
    /// Resolve once when preparing a full-input parse: use `DetailedOnFailure` if
    /// every custom parser declares diagnostic independence and replayability,
    /// otherwise `Detailed`. Direct [`ParseContext`] construction uses `Detailed`.
    #[default]
    Auto,
    /// Record speculative failures throughout parsing, including successful parses.
    Detailed,
    /// Skip syntax-failure recording on the first pass. Full-input parsing functions
    /// retry failures with `Detailed` in a fresh context and return that diagnostic.
    /// Direct [`ParseContext`] operations do not retry and retain empty diagnostics.
    ///
    /// Only use with parsers whose acceptance does not depend on diagnostics and
    /// which can safely run again: custom callbacks and external effects may run twice.
    DetailedOnFailure,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ParseOptions {
    pub memoization: Memoization,
    pub diagnostics: Diagnostics,
    /// Explicit debugging metadata; ordinary parsing retains no terminal trace.
    pub token_trace: Option<token_trace::Limits>,
}

impl ParseOptions {
    pub const fn with_memoization(memoization: Memoization) -> Self {
        Self {
            memoization,
            diagnostics: Diagnostics::Auto,
            token_trace: None,
        }
    }

    /// Opts into bounded committed terminal metadata without changing the CST.
    pub const fn with_token_trace(mut self, limits: token_trace::Limits) -> Self {
        self.token_trace = Some(limits);
        self
    }

    /// Selects syntax diagnostics without changing the memoization policy.
    pub const fn with_diagnostics(mut self, diagnostics: Diagnostics) -> Self {
        self.diagnostics = diagnostics;
        self
    }
}

/// Converts an owned rule graph into the representation accepted by the shared
/// parsing APIs. This allocation is intended to happen once per generated grammar.
pub fn share_grammar(rules: Vec<Rule>) -> SharedGrammar {
    Arc::from(rules)
}

#[derive(Debug, Clone)]
pub struct Capture {
    pub name: &'static str,
    pub span: Span,
    pub nodes: Vec<usize>,
}

#[derive(Debug, Clone)]
pub struct Node {
    /// Grammar-local rule index or a reserved CST boundary/terminal constant:
    /// [`TEXT_VALUE_RULE`], [`VALUE_BOUNDARY_RULE`], [`RECOVERY_ERROR_RULE`],
    /// [`TOKEN_LEAF_RULE`] or [`TOKEN_META_RULE`].
    pub rule: usize,
    pub span: Span,
    pub children: Vec<usize>,
    pub captures: Vec<Capture>,
}

/// Owns the input and a flat CST arena; retained trees have no global mutable state.
#[derive(Debug, Clone)]
pub struct Tree {
    pub source: String,
    pub nodes: Vec<Node>,
    pub root: usize,
    byte_offsets: Vec<usize>,
    scopes: ScopeStore,
    recoveries: Vec<RecoveryDiagnostic>,
    tokens: token::Store,
}

impl Tree {
    /// Validate public CST fields against the owned source before traversing a caller-supplied tree.
    /// Parser-produced arenas are postorder; rejecting forward references also rejects cycles.
    pub fn validate_structure(&self) -> Result<(), &'static str> {
        if self.root >= self.nodes.len()
            || self.byte_offsets.iter().copied().ne(self
                .source
                .char_indices()
                .map(|(offset, _)| offset)
                .chain(std::iter::once(self.source.len())))
        {
            return Err("invalid CST source or root");
        }
        let length = self.byte_offsets.len() - 1;
        let valid_span = |span: Span| span.start <= span.end && span.end <= length;
        for (id, node) in self.nodes.iter().enumerate() {
            if !valid_span(node.span)
                || node.children.iter().any(|&child| child >= id)
                || node.captures.iter().any(|capture| {
                    !valid_span(capture.span) || capture.nodes.iter().any(|&child| child >= id)
                })
            {
                return Err("invalid CST span, edge or cycle");
            }
        }
        Ok(())
    }
    /// Recovery events reachable from this tree's selected root only.
    pub fn recoveries(&self) -> &[RecoveryDiagnostic] {
        &self.recoveries
    }
    /// Owned semantic metadata as of tree creation, independent of subsequent context changes.
    pub fn scopes(&self) -> &ScopeStore {
        &self.scopes
    }
    pub fn text(&self, span: Span) -> &str {
        &self.source[self.byte_offsets[span.start]..self.byte_offsets[span.end]]
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseError {
    pub offset: usize,
    pub expected: Vec<String>,
}

impl std::fmt::Display for ParseError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "at code point {}: expected {}",
            self.offset,
            self.expected.join(", ")
        )
    }
}
impl std::error::Error for ParseError {}

/// Full-input validation with a stable category and backend-native farthest-failure hints.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ParseDiagnostic {
    pub kind: &'static str,
    pub offset: usize,
    pub expected: Vec<String>,
    pub farthest: ParseError,
}

impl ParseDiagnostic {
    pub fn canonical_json(&self) -> String {
        let expected = self
            .expected
            .iter()
            .map(|s| json_string(s))
            .collect::<Vec<_>>()
            .join(",");
        let farthest = self
            .farthest
            .expected
            .iter()
            .map(|s| json_string(s))
            .collect::<Vec<_>>()
            .join(",");
        format!("{{\"kind\":{},\"offset\":{},\"expected\":[{}],\"farthestOffset\":{},\"farthestExpected\":[{}]}}",
            json_string(self.kind), self.offset, expected, self.farthest.offset, farthest)
    }
}

impl std::fmt::Display for ParseDiagnostic {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{} at code point {}: expected {}",
            self.kind,
            self.offset,
            self.expected.join(", ")
        )
    }
}
impl std::error::Error for ParseDiagnostic {}

/// Matches Java String.strip / Character.isWhitespace (not Rust's broader trim set).
pub fn strip_capture(text: &str) -> &str {
    text.trim_matches(|c| {
        matches!(c,
            '\u{0009}'..='\u{000d}' | '\u{001c}'..='\u{0020}' | '\u{1680}' |
            '\u{2000}'..='\u{2006}' | '\u{2008}'..='\u{200a}' | '\u{2028}' | '\u{2029}' |
            '\u{205f}' | '\u{3000}'
        )
    })
}

/// Java generated mapper's String.strip followed by single-quote removal.
/// Double quotes and backslash escapes stay literal; CST/capture text stays untouched.
pub fn java_capture_text(text: &str) -> &str {
    let text = strip_capture(text);
    if text.len() >= 2 && text.starts_with('\'') && text.ends_with('\'') {
        &text[1..text.len() - 1]
    } else {
        text
    }
}

#[derive(Default)]
struct Fragment {
    nodes: Vec<usize>,
    captures: Vec<Capture>,
    source: Option<Box<FragmentSource>>,
}

#[derive(Clone)]
struct FragmentSource {
    span: Span,
    kind: token::TokenKind,
    classic: bool,
    generated: bool,
    text: Option<Arc<str>>,
}

/// Shared entry point for handwritten and generated parsers. Use context.parse(parser)
/// when calling a custom implementation so failed calls roll back transactional state.
pub trait Parser {
    fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult;
    /// Classic entry. Existing custom parsers retain transactional failure cleanup;
    /// mode-aware implementations may override this to reproduce leaf cursor effects.
    fn parse_classic(&self, context: &mut ParseContext<'_>) -> ParseResult {
        context.transaction(|context| self.parse(context))
    }
}

pub type ParseResult = Result<ParseMatch, ParseError>;

#[derive(Debug, Clone)]
pub struct ParseMatch {
    pub span: Span,
    pub nodes: Vec<usize>,
    pub captures: Vec<Capture>,
}

impl ParseMatch {
    pub fn empty(span: Span) -> Self {
        Self {
            span,
            nodes: vec![],
            captures: vec![],
        }
    }
    pub fn root_node(&self) -> Option<usize> {
        if self.nodes.len() == 1 {
            Some(self.nodes[0])
        } else {
            None
        }
    }
}

impl Parser for Expr {
    fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult {
        context.parse_expression(self)
    }
    fn parse_classic(&self, context: &mut ParseContext<'_>) -> ParseResult {
        context.parse_expression(self)
    }
}

trait StateValue {
    fn copy_value(&self) -> Box<dyn StateValue>;
    fn as_any(&self) -> &dyn Any;
    fn as_any_mut(&mut self) -> &mut dyn Any;
}
impl<T: Any + Clone> StateValue for T {
    fn copy_value(&self) -> Box<dyn StateValue> {
        Box::new(self.clone())
    }
    fn as_any(&self) -> &dyn Any {
        self
    }
    fn as_any_mut(&mut self) -> &mut dyn Any {
        self
    }
}

#[derive(Default)]
struct StateMap(HashMap<String, Box<dyn StateValue>>);

impl Clone for StateMap {
    fn clone(&self) -> Self {
        Self(
            self.0
                .iter()
                .map(|(key, value)| (key.clone(), value.as_ref().copy_value()))
                .collect(),
        )
    }
}

#[derive(Debug, Clone, Default)]
struct CaptureStore {
    values: HashMap<&'static str, Vec<Span>>,
    journal: Vec<&'static str>,
    checkpoint_depth: usize,
}

impl CaptureStore {
    fn is_empty(&self) -> bool {
        self.values.is_empty()
    }

    fn get(&self, name: &str) -> Option<&Vec<Span>> {
        self.values.get(name)
    }

    fn push(&mut self, name: &'static str, span: Span) {
        if self.checkpoint_depth > 0 {
            self.journal.push(name);
        }
        self.values.entry(name).or_default().push(span);
    }

    fn checkpoint(&mut self) -> usize {
        let mark = self.journal.len();
        self.checkpoint_depth += 1;
        mark
    }

    fn commit_checkpoint(&mut self) {
        debug_assert!(self.checkpoint_depth > 0);
        self.checkpoint_depth -= 1;
        if self.checkpoint_depth == 0 {
            self.journal.clear();
        }
    }

    fn rollback_checkpoint(&mut self, mark: usize) {
        debug_assert!(self.checkpoint_depth > 0);
        debug_assert!(mark <= self.journal.len());
        while self.journal.len() > mark {
            let name = self.journal.pop().expect("journal length checked");
            let remove = {
                let spans = self
                    .values
                    .get_mut(name)
                    .expect("journaled capture must exist");
                spans.pop().expect("journaled capture must have a span");
                spans.is_empty()
            };
            if remove {
                self.values.remove(name);
            }
        }
        self.checkpoint_depth -= 1;
        if self.checkpoint_depth == 0 {
            self.journal.clear();
        }
    }
}

#[derive(Debug, Clone, Default)]
struct OwnedCaptureStore {
    values: HashMap<&'static str, Vec<token::TokenSource>>,
    journal: Vec<&'static str>,
    checkpoint_depth: usize,
}

impl OwnedCaptureStore {
    fn is_empty(&self) -> bool {
        self.values.is_empty()
    }

    fn get(&self, name: &str) -> Option<&Vec<token::TokenSource>> {
        self.values.get(name)
    }

    fn push(&mut self, name: &'static str, source: token::TokenSource) {
        if self.checkpoint_depth > 0 {
            self.journal.push(name);
        }
        self.values.entry(name).or_default().push(source);
    }

    fn checkpoint(&mut self) -> usize {
        let mark = self.journal.len();
        self.checkpoint_depth += 1;
        mark
    }

    fn commit_checkpoint(&mut self) {
        debug_assert!(self.checkpoint_depth > 0);
        self.checkpoint_depth -= 1;
        if self.checkpoint_depth == 0 {
            self.journal.clear();
        }
    }

    fn rollback_checkpoint(&mut self, mark: usize) {
        debug_assert!(self.checkpoint_depth > 0);
        debug_assert!(mark <= self.journal.len());
        while self.journal.len() > mark {
            let name = self.journal.pop().expect("journal length checked");
            let remove = {
                let spans = self
                    .values
                    .get_mut(name)
                    .expect("journaled capture must exist");
                spans.pop().expect("journaled capture must have a source");
                spans.is_empty()
            };
            if remove {
                self.values.remove(name);
            }
        }
        self.checkpoint_depth -= 1;
        if self.checkpoint_depth == 0 {
            self.journal.clear();
        }
    }
}

/// Opt-in counters for ParseContext checkpoint behavior. Payload counters exclude
/// cursor and CST-length scalars, which are always copied inline.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct CheckpointMetrics {
    pub opened: u64,
    pub committed: u64,
    pub rolled_back: u64,
    pub nonempty_payload_snapshots: u64,
    pub empty_payload_checkpoints: u64,
    /// Deep copies caused by COW payload mutation.
    pub copy_on_write_deep_copies: u64,
    /// Scope undo records created since metrics were enabled, including rolled-back records.
    pub scope_journal_entries: u64,
}

struct Checkpoint {
    match_cursor_policy: MatchCursorPolicy,
    position: usize,
    matched_position: usize,
    nodes: usize,
    metrics_counted: bool,
    captures_journal_mark: usize,
    owned_captures_journal_mark: usize,
    state: Option<Rc<StateMap>>,
    scopes_journal_mark: scope::Checkpoint,
    recoveries: usize,
    tokens: Rc<token::Store>,
    token_trace: token_trace::Mark,
}

struct ChoiceWinner {
    position: usize,
    matched_position: usize,
    node_start: usize,
    nodes: Vec<Node>,
    captures: CaptureStore,
    owned_captures: OwnedCaptureStore,
    state: Rc<StateMap>,
    scopes: ScopeStore,
    recoveries: Vec<RecoveryDiagnostic>,
    fragment: Fragment,
    tokens: Rc<token::Store>,
    token_trace: Option<token_trace::Trace>,
}

/// IDs belong to one context and survive rollback and temporary grammar sessions.
/// Keep the first name inline so a single distinct failure needs no table allocation.
#[derive(Default)]
struct ExpectedNames {
    first: Option<Rc<str>>,
    remaining: Vec<Rc<str>>,
    ids: HashMap<Rc<str>, u32>,
}

impl ExpectedNames {
    fn intern(&mut self, name: &str) -> u32 {
        let Some(first) = &self.first else {
            self.first = Some(Rc::from(name));
            return 0;
        };
        if first.as_ref() == name {
            return 0;
        }
        if let Some(&id) = self.ids.get(name) {
            return id;
        }
        let id = u32::try_from(self.remaining.len() + 1).expect("too many expected names");
        let name: Rc<str> = Rc::from(name);
        self.remaining.push(Rc::clone(&name));
        self.ids.insert(name, id);
        id
    }

    fn name(&self, id: u32) -> &str {
        if id == 0 {
            self.first.as_deref().expect("expected name was interned")
        } else {
            &self.remaining[id as usize - 1]
        }
    }

    fn strings(&self, expected: &[u32]) -> Vec<String> {
        let mut expected: Vec<_> = expected
            .iter()
            .map(|&id| self.name(id).to_owned())
            .collect();
        expected.sort_unstable();
        expected.dedup();
        expected
    }
}

#[derive(Debug, Clone, Default)]
enum ExpectedIds {
    #[default]
    Empty,
    Single(u32),
    Multiple(Rc<Vec<u32>>),
}

#[derive(Debug, Clone, Default)]
struct FailureDiagnostic {
    // Only memo entries use this field; diagnostic merging does not change it.
    memo_expression_depth: usize,
    memo_expected_text_bytes: usize,
    farthest: Option<usize>,
    expected: ExpectedIds,
    classic_max_reached: Option<usize>,
    // Historical name: any syntax failure (Classic or native) overrides a success watermark.
    classic_failure_seen: bool,
    classic_failure_end: Option<(usize, MatchCursorPolicy)>,
}

impl FailureDiagnostic {
    fn record_position(&mut self, position: usize) {
        if self.farthest.is_none_or(|farthest| position > farthest) {
            self.farthest = Some(position);
            self.expected = ExpectedIds::Empty;
        }
    }

    fn record(&mut self, position: usize, expected: u32) {
        if self.farthest.is_none_or(|farthest| position > farthest) {
            self.farthest = Some(position);
            self.expected = ExpectedIds::Single(expected);
            return;
        }
        if self.farthest == Some(position) && !self.expected_values().contains(&expected) {
            match &mut self.expected {
                ExpectedIds::Empty => self.expected = ExpectedIds::Single(expected),
                ExpectedIds::Single(first) => {
                    self.expected = ExpectedIds::Multiple(Rc::new(vec![*first, expected]));
                }
                ExpectedIds::Multiple(values) => Rc::make_mut(values).push(expected),
            }
        }
    }

    fn expected_values(&self) -> &[u32] {
        match &self.expected {
            ExpectedIds::Empty => &[],
            ExpectedIds::Single(expected) => std::slice::from_ref(expected),
            ExpectedIds::Multiple(values) => values,
        }
    }

    fn merge(&mut self, other: &Self) {
        self.classic_failure_seen |= other.classic_failure_seen;
        if let Some(reached) = other.classic_max_reached {
            self.classic_max_reached = Some(self.classic_max_reached.unwrap_or(0).max(reached));
        }
        let Some(position) = other.farthest else {
            return;
        };
        if self.farthest.is_none_or(|farthest| position > farthest) {
            self.farthest = Some(position);
            self.expected.clone_from(&other.expected);
            return;
        }
        if self.farthest == Some(position) {
            if matches!(self.expected, ExpectedIds::Empty) {
                self.expected.clone_from(&other.expected);
                return;
            }
            if let (ExpectedIds::Multiple(expected), ExpectedIds::Multiple(other)) =
                (&self.expected, &other.expected)
            {
                if Rc::ptr_eq(expected, other) {
                    return;
                }
            }
            for &expected in other.expected_values() {
                self.record(position, expected);
            }
        }
    }
}

fn append_missing_expected(target: &mut Vec<u32>, source: &[u32]) {
    for expected in source {
        if !target.contains(expected) {
            target.push(*expected);
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct FailureMemoKey {
    rule: usize,
    position: usize,
    matched_position: usize,
    depth_and_modes: usize,
    state_version: usize,
}

impl FailureMemoKey {
    fn new(
        rule: usize,
        position: usize,
        matched_position: usize,
        whitespace: bool,
        depth: usize,
    ) -> Self {
        Self {
            rule,
            position,
            matched_position,
            depth_and_modes: depth * 8 + 4 + usize::from(whitespace),
            state_version: 0,
        }
    }
    fn with_state_version(mut self, version: usize) -> Self {
        self.state_version = version;
        self
    }
    fn with_token_mode(mut self, mode: Option<token::ParseMode>) -> Self {
        if let Some(mode) = mode {
            let kind = match mode.kind {
                token::TokenKind::Consumed => 1,
                token::TokenKind::MatchOnly => 2,
                token::TokenKind::VirtualConsumed => 3,
                token::TokenKind::VirtualMatchOnly => 4,
            };
            // Rule depth occupies bits3..10; preserve every normal-mode key bit.
            self.depth_and_modes |= (kind << 11) | (usize::from(mode.invert) << 14);
        }
        self
    }
    fn with_match_policy(mut self, policy: MatchCursorPolicy) -> Self {
        self.depth_and_modes = (self.depth_and_modes & !6)
            | (usize::from(policy.reset) << 2)
            | (usize::from(policy.non_ordered) << 1);
        self
    }
}

#[derive(Debug, Clone, Copy, Default)]
struct FailureMemoBuildHasher;

impl BuildHasher for FailureMemoBuildHasher {
    type Hasher = FailureMemoHasher;

    fn build_hasher(&self) -> Self::Hasher {
        FailureMemoHasher::default()
    }
}

#[derive(Debug, Default)]
struct FailureMemoHasher(u64);

impl FailureMemoHasher {
    fn mix(&mut self, value: u64) {
        const HASH_MULTIPLIER: u64 = 0x517c_c1b7_2722_0a95;
        self.0 = (self.0.rotate_left(5) ^ value).wrapping_mul(HASH_MULTIPLIER);
    }
}

impl Hasher for FailureMemoHasher {
    fn finish(&self) -> u64 {
        self.0
    }

    fn write(&mut self, bytes: &[u8]) {
        for chunk in bytes.chunks(std::mem::size_of::<u64>()) {
            let mut word = [0; std::mem::size_of::<u64>()];
            word[..chunk.len()].copy_from_slice(chunk);
            self.mix(u64::from_ne_bytes(word));
        }
    }

    fn write_usize(&mut self, value: usize) {
        self.mix(value as u64);
    }
}

// Only this compact call record lives across recursive execution. Probe/replay
// and snapshot construction use separate non-recursive frames.
struct MemoCall {
    key: FailureMemoKey,
    position: usize,
    count: usize,
    capture_mark: usize,
    detailed: bool,
    success: bool,
}

type MemoMap<V> = HashMap<FailureMemoKey, V, FailureMemoBuildHasher>;
type FailureMemoBuckets = MemoBuckets<FailureDiagnostic>;
type SuccessMemoBuckets = MemoBuckets<Rc<SuccessMemo>>;

const MAX_SUCCESS_MEMO_BYTES: usize = 2 * 1024 * 1024;
const MAX_RETAINED_SUCCESS_MEMO_BYTES: usize = 16 * 1024 * 1024;

trait MemoValue {
    fn retained_bytes(&self) -> usize;
    fn failure_budget() -> bool {
        false
    }
}
impl MemoValue for FailureDiagnostic {
    fn retained_bytes(&self) -> usize {
        // Structural charge, not an RSS estimate. Include key/map overhead and
        // conservatively charge shared expected data once per retained entry.
        let ids = match &self.expected {
            ExpectedIds::Multiple(values) => values.capacity().saturating_mul(4).saturating_add(32),
            _ => 0,
        };
        256_usize
            .saturating_add(ids)
            .saturating_add(self.memo_expected_text_bytes)
    }
    fn failure_budget() -> bool {
        true
    }
}

/// No arena IDs or caller-owned metadata escape into the memo. Node edges are
/// relative to this snapshot; spans remain absolute original-source CP offsets.
struct SuccessMemo {
    nodes: Vec<Node>,
    tokens: token::MemoTokens,
    root: usize,
    captures: Vec<(&'static str, Span)>,
    consumed: usize,
    matched: usize,
    policy: MatchCursorPolicy,
    expression_depth: usize,
    diagnostic: FailureDiagnostic,
    bytes: usize,
}
impl MemoValue for Rc<SuccessMemo> {
    fn retained_bytes(&self) -> usize {
        self.bytes
    }
}

const FAILURE_MEMO_BUCKET_SIZE: usize = 256;
const DEFAULT_FAILURE_MEMO_ENTRIES: usize = 65_536;
const DEFAULT_FAILURE_MEMO_BYTES: usize = 16 * 1024 * 1024;
const DEFAULT_FAILURE_MEMO_ENTRY_BYTES: usize = 1024 * 1024;

#[derive(Clone, Copy)]
struct FailureMemoBudget {
    entries: usize,
    bytes: usize,
    entry_bytes: usize,
}
fn parse_failure_budget(value: Option<&str>, default: usize, ceiling: usize) -> usize {
    value
        .filter(|s| !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit()))
        .and_then(|s| s.parse::<usize>().ok())
        .filter(|&n| n <= ceiling)
        .unwrap_or(default)
}
fn configured_failure_budget(name: &str, default: usize, ceiling: usize) -> usize {
    parse_failure_budget(std::env::var(name).ok().as_deref(), default, ceiling)
}
fn failure_memo_budget() -> FailureMemoBudget {
    static BUDGET: std::sync::OnceLock<FailureMemoBudget> = std::sync::OnceLock::new();
    *BUDGET.get_or_init(|| FailureMemoBudget {
        entries: configured_failure_budget(
            "UNLAXER_FAILURE_MEMO_MAX_ENTRIES",
            DEFAULT_FAILURE_MEMO_ENTRIES,
            1_048_576,
        ),
        bytes: configured_failure_budget(
            "UNLAXER_FAILURE_MEMO_MAX_BYTES",
            DEFAULT_FAILURE_MEMO_BYTES,
            1024 * 1024 * 1024,
        ),
        entry_bytes: configured_failure_budget(
            "UNLAXER_FAILURE_MEMO_MAX_ENTRY_BYTES",
            DEFAULT_FAILURE_MEMO_ENTRY_BYTES,
            64 * 1024 * 1024,
        ),
    })
}

fn success_memo_budget() -> FailureMemoBudget {
    static BUDGET: std::sync::OnceLock<FailureMemoBudget> = std::sync::OnceLock::new();
    *BUDGET.get_or_init(|| FailureMemoBudget {
        entries: configured_failure_budget("UNLAXER_SUCCESS_MEMO_MAX_ENTRIES", 65_536, 1_048_576),
        bytes: configured_failure_budget(
            "UNLAXER_SUCCESS_MEMO_MAX_BYTES",
            MAX_RETAINED_SUCCESS_MEMO_BYTES,
            1024 * 1024 * 1024,
        ),
        entry_bytes: configured_failure_budget(
            "UNLAXER_SUCCESS_MEMO_MAX_ENTRY_BYTES",
            MAX_SUCCESS_MEMO_BYTES,
            64 * 1024 * 1024,
        ),
    })
}

/// Keep nearby outcomes together for lookup and destruction. The first bucket is
/// inline so inputs shorter than one bucket need no additional allocation.
struct MemoBuckets<V> {
    first: MemoMap<V>,
    remaining: Vec<MemoMap<V>>,
    retained_bytes: usize,
    max_bytes: usize,
    entries: usize,
    max_entries: usize,
    max_entry_bytes: usize,
    budget_misses: usize,
    window: Option<usize>,
    high_water: usize,
    evicted_buckets: usize,
    eviction_underruns: usize,
    #[cfg(test)]
    evicted_entries: usize,
}

impl<V: MemoValue> Default for MemoBuckets<V> {
    fn default() -> Self {
        // Like Java's system properties, read process-level tuning only once.
        static WINDOW: std::sync::OnceLock<Option<usize>> = std::sync::OnceLock::new();
        Self::with_window(*WINDOW.get_or_init(|| {
            if std::env::var("UNLAXER_MEMO_EVICT_BELOW_FRONTIER").as_deref() == Ok("false") {
                None
            } else {
                Some(
                    std::env::var("UNLAXER_MEMO_WINDOW")
                        .ok()
                        .and_then(|value| value.parse::<usize>().ok())
                        .filter(|&value| value > 0)
                        .unwrap_or(1024),
                )
            }
        }))
    }
}

impl<V: MemoValue> MemoBuckets<V> {
    fn with_window(window: Option<usize>) -> Self {
        let budget = if V::failure_budget() {
            failure_memo_budget()
        } else {
            success_memo_budget()
        };
        Self {
            first: MemoMap::default(),
            remaining: vec![],
            retained_bytes: 0,
            max_bytes: budget.bytes,
            entries: 0,
            max_entries: budget.entries,
            max_entry_bytes: budget.entry_bytes,
            budget_misses: 0,
            window,
            high_water: 0,
            evicted_buckets: 0,
            eviction_underruns: 0,
            #[cfg(test)]
            evicted_entries: 0,
        }
    }

    fn observe_cursor(&mut self, position: usize) {
        self.high_water = self.high_water.max(position);
        let Some(window) = self.window else { return };
        // Only whole buckets strictly behind the retained scalar-position window.
        let target = self.high_water.saturating_sub(window) / FAILURE_MEMO_BUCKET_SIZE;
        if target <= self.evicted_buckets {
            return;
        }
        for bucket in self.evicted_buckets..target.min(self.remaining.len() + 1) {
            let map = if bucket == 0 {
                &mut self.first
            } else {
                &mut self.remaining[bucket - 1]
            };
            #[cfg(test)]
            {
                self.evicted_entries += map.len();
            }
            self.retained_bytes -= map.values().map(MemoValue::retained_bytes).sum::<usize>();
            self.entries -= map.len();
            // clear() would keep the allocation alive. Drop the hash table itself.
            *map = MemoMap::default();
        }
        self.evicted_buckets = target;
    }

    fn probe(&mut self, position: usize, key: &FailureMemoKey) -> Option<&V> {
        self.observe_cursor(position);
        let bucket = position / FAILURE_MEMO_BUCKET_SIZE;
        if bucket < self.evicted_buckets {
            self.eviction_underruns += 1;
            let lookback = self.high_water.saturating_sub(position);
            let window = self.window.unwrap_or(0).max(lookback).saturating_mul(2);
            self.window = Some(window);
            // Already evicted entries cannot be recovered, but the re-parse must be
            // allowed to memoize here again. Growing only the window is insufficient.
            self.evicted_buckets =
                self.high_water.saturating_sub(window) / FAILURE_MEMO_BUCKET_SIZE;
        }
        self.get(bucket, key)
    }

    fn get(&self, bucket: usize, key: &FailureMemoKey) -> Option<&V> {
        if bucket == 0 {
            self.first.get(key)
        } else {
            self.remaining.get(bucket - 1)?.get(key)
        }
    }

    /// Success snapshot construction consults this before cloning any payload.
    fn snapshot_room(&self, bucket: usize, key: &FailureMemoKey) -> usize {
        let old = self.get(bucket, key);
        if bucket < self.evicted_buckets
            || old.is_none() && self.entries >= self.max_entries
            || bucket > self.remaining.len()
                && bucket > self.max_bytes / std::mem::size_of::<MemoMap<V>>()
        {
            return 0;
        }
        let retained = self.retained_bytes - old.map_or(0, MemoValue::retained_bytes);
        self.max_entry_bytes
            .min(self.max_bytes.saturating_sub(retained))
    }

    fn insert(&mut self, bucket: usize, key: FailureMemoKey, value: V) {
        // An enclosing rule can finish after traversal has passed its start.
        // A later probe below this frontier widens the window and re-evaluates it.
        if bucket < self.evicted_buckets {
            return;
        }
        let bytes = value.retained_bytes();
        let old = self.get(bucket, &key);
        let replacing = old.is_some();
        let old_bytes = old.map_or(0, MemoValue::retained_bytes);
        let retained = self.retained_bytes - old_bytes;
        // A distant first insertion must not allocate an unbounded empty bucket
        // index. The inline first bucket requires no such allocation.
        let index_too_large = bucket > self.remaining.len()
            && bucket > self.max_bytes / std::mem::size_of::<MemoMap<V>>();
        if bytes > self.max_entry_bytes
            || bytes > self.max_bytes.saturating_sub(retained)
            || !replacing && self.entries >= self.max_entries
            || index_too_large
        {
            self.budget_misses = self.budget_misses.saturating_add(1);
            return; // A full cache is a safe miss, never a parse/resource failure.
        }
        let map = if bucket == 0 {
            &mut self.first
        } else {
            if self.remaining.len() < bucket {
                // Empty maps allocate no entry storage, including skipped buckets.
                self.remaining.reserve_exact(bucket - self.remaining.len());
                self.remaining.resize_with(bucket, MemoMap::default);
            }
            &mut self.remaining[bucket - 1]
        };
        map.insert(key, value);
        if !replacing {
            self.entries += 1;
        }
        self.retained_bytes = retained + bytes;
    }
}

// Java Chain switches its current transaction to an advancing matched cursor.
// NonOrdered inherits that policy; its Java children otherwise reset on begin.
// `non_ordered` confines the compatibility policy to the new combinator.
#[derive(Clone, Copy, Debug)]
struct MatchCursorPolicy {
    reset: bool,
    non_ordered: bool,
}
impl Default for MatchCursorPolicy {
    fn default() -> Self {
        Self {
            reset: true,
            non_ordered: false,
        }
    }
}

/// Per-parse state, shared by generated rules and custom parsers. Transactions restore
/// cursors, CST nodes, captures and cloneable user values, but retain failure diagnostics.
/// Clone must isolate mutable values: external effects and shared interior state are not rolled back.
/// Rollback applies to Result::Err, not to panic unwinding. Discard the context after a panic.
/// Capture names are context-wide, and backreferences use the most recent successful capture.
/// Lexical symbols and semantic diagnostics use a separate, owned transactional ScopeStore.
///
/// With [`Diagnostics::DetailedOnFailure`], all direct context operations (including
/// `parse`, `parse_grammar`, `parse_shared_grammar`, `transaction` and `with_scope`)
/// run once, without collecting syntax diagnostics or automatically retrying.
/// [`Self::failure`] and [`Self::error`] then return offset zero and an empty expected
/// list; errors supplied by custom parsers are still returned unchanged.
/// Use the free full-input parsing functions for automatic diagnostic retries.
/// Checkpoint counters and memoized-rule hit counts describe only this context's
/// first pass, not any separate diagnostic retry; semantic diagnostics are unaffected.
pub struct ParseContext<'a> {
    token_mode: Option<token::ParseMode>,
    classic_max_reached: Option<usize>,
    // Historical name: any syntax failure (Classic or native) overrides a success watermark.
    classic_failure_seen: bool,
    match_cursor_policy: MatchCursorPolicy,
    bindings: BTreeMap<String, Vec<String>>,
    input: &'a str,
    grapheme_boundaries: Option<grapheme::Boundaries>,
    lexing: Option<lexing::Session<'a>>,
    rules: Arc<[Rule]>,
    whitespace: bool,
    lexical_trivia: Option<lexical::LexicalExpression>,
    lexical_context: Option<Arc<[lexing::Terminal]>>,
    position: usize,
    matched_position: usize,
    nodes: Vec<Node>,
    farthest: usize,
    expected: Vec<u32>,
    expected_names: ExpectedNames,
    byte_offsets: Vec<usize>,
    // Empty for ASCII, where byte and scalar offsets are identical. Otherwise
    // non-boundary bytes contain usize::MAX to retain code_point's rejection.
    code_point_offsets: Vec<usize>,
    captures: CaptureStore,
    owned_captures: OwnedCaptureStore,
    state: Rc<StateMap>,
    scopes: ScopeStore,
    recoveries: Vec<RecoveryDiagnostic>,
    call_depth: usize,
    expression_depth: usize,
    resource_failure: Option<ParseError>,
    options: ParseOptions,
    grammar_session: u64,
    next_grammar_session: u64,
    memo_safe_rules: Vec<bool>,
    success_memo_safe_rules: Vec<bool>,
    failure_memo: FailureMemoBuckets,
    success_memo: SuccessMemoBuckets,
    diagnostic_frames: Vec<FailureDiagnostic>,
    memoized_failure_hits: usize,
    memoized_success_hits: usize,
    checkpoint_metrics: Option<CheckpointMetrics>,
    checkpoint_metrics_scope_journal_base: u64,
    // FIRST sets of `rules` (#300) while candidate exclusion is active, i.e. only
    // with DetailedOnFailure, where a failed candidate records no diagnostics.
    first_sets: Option<Arc<[first::FirstSet]>>,
    exclusion_audit: bool,
    tokens: Rc<token::Store>,
    next_token_generation: u64,
    unique_choice_failure_seen: bool,
    names_state: names::State,
    token_trace: Option<token_trace::Trace>,
    trace_custom_contracts: Vec<bool>,
}

/// Ordered choice with rollback and full-input acceptance. Rule nesting is bounded
/// at 256 and active expression frames (including wrappers) at 1024.
pub fn parse(
    rules: &[Rule],
    root: usize,
    whitespace: bool,
    input: &str,
) -> Result<Tree, ParseError> {
    parse_detailed(rules, root, whitespace, input).map_err(|diagnostic| diagnostic.farthest)
}

/// Full-input parsing; [`Diagnostics::Auto`] resolves from the grammar at preparation.
/// [`Diagnostics::DetailedOnFailure`] retries failures with
/// detailed diagnostics in a fresh context using the same input and memoization policy.
pub fn parse_with_options(
    rules: &[Rule],
    root: usize,
    whitespace: bool,
    input: &str,
    options: ParseOptions,
) -> Result<Tree, ParseError> {
    parse_detailed_with_options(rules, root, whitespace, input, options)
        .map_err(|diagnostic| diagnostic.farthest)
}

/// Full-input parsing over an immutable grammar graph shared across calls.
/// Unlike [`parse`], this does not clone every rule and expression for each parse.
pub fn parse_shared(
    grammar: &SharedGrammar,
    root: usize,
    whitespace: bool,
    input: &str,
) -> Result<Tree, ParseError> {
    parse_detailed_shared(grammar, root, whitespace, input)
        .map_err(|diagnostic| diagnostic.farthest)
}

/// Shared-grammar version of [`parse_with_options`], including diagnostic retries.
pub fn parse_shared_with_options(
    grammar: &SharedGrammar,
    root: usize,
    whitespace: bool,
    input: &str,
    options: ParseOptions,
) -> Result<Tree, ParseError> {
    parse_detailed_shared_with_options(grammar, root, whitespace, input, options)
        .map_err(|diagnostic| diagnostic.farthest)
}

/// Adds a primary trailing-input diagnostic without discarding speculative farthest failures.
/// Syntax hints and rule-limit failures remain backend-native, not a language-independent oracle.
pub fn parse_detailed(
    rules: &[Rule],
    root: usize,
    whitespace: bool,
    input: &str,
) -> Result<Tree, ParseDiagnostic> {
    parse_detailed_with_options(rules, root, whitespace, input, ParseOptions::default())
}

/// Full-input parsing with a categorized diagnostic. [`Diagnostics::Auto`] resolves
/// from the grammar once before parsing. Resource exhaustion has kind
/// `resource_limit` and cannot be recovered as a syntax mismatch. With
/// [`Diagnostics::DetailedOnFailure`], failure (including trailing input) triggers
/// a fresh detailed parse and returns its complete [`ParseDiagnostic`].
pub fn parse_detailed_with_options(
    rules: &[Rule],
    root: usize,
    whitespace: bool,
    input: &str,
    options: ParseOptions,
) -> Result<Tree, ParseDiagnostic> {
    parse_detailed_owned(
        Arc::from(rules),
        root,
        whitespace,
        input,
        options,
        BTreeMap::new(),
    )
}

/// Detailed full-input parsing over an immutable grammar graph shared across calls.
/// All mutable parsing data is initialized afresh for this invocation.
pub fn parse_detailed_shared(
    grammar: &SharedGrammar,
    root: usize,
    whitespace: bool,
    input: &str,
) -> Result<Tree, ParseDiagnostic> {
    parse_detailed_shared_with_options(grammar, root, whitespace, input, ParseOptions::default())
}

/// Shared-grammar version of [`parse_detailed_with_options`], including diagnostic retries.
pub fn parse_detailed_shared_with_options(
    grammar: &SharedGrammar,
    root: usize,
    whitespace: bool,
    input: &str,
    options: ParseOptions,
) -> Result<Tree, ParseDiagnostic> {
    parse_detailed_owned(
        Arc::clone(grammar),
        root,
        whitespace,
        input,
        options,
        BTreeMap::new(),
    )
}

/// Full-input parsing with caller-owned immutable name snapshots; no external name provider.
pub fn parse_detailed_shared_with_name_snapshots(
    grammar: &SharedGrammar,
    root: usize,
    whitespace: bool,
    input: &str,
    options: ParseOptions,
    snapshots: &[names::Snapshot],
) -> Result<Tree, ParseDiagnostic> {
    let bindings = names::bindings_of(snapshots).map_err(|_| ParseDiagnostic {
        kind: "name_snapshot_invalid",
        offset: 0,
        expected: vec!["valid immutable name snapshots".into()],
        farthest: ParseError {
            offset: 0,
            expected: vec!["valid immutable name snapshots".into()],
        },
    })?;
    parse_detailed_owned(
        Arc::clone(grammar),
        root,
        whitespace,
        input,
        options,
        bindings,
    )
}

fn parse_detailed_owned(
    rules: SharedGrammar,
    root: usize,
    whitespace: bool,
    input: &str,
    options: ParseOptions,
    bindings: BTreeMap<String, Vec<String>>,
) -> Result<Tree, ParseDiagnostic> {
    let options = if options.diagnostics == Diagnostics::Auto {
        options.with_diagnostics(if grammar_allows_deferred_diagnostics(&rules) {
            Diagnostics::DetailedOnFailure
        } else {
            Diagnostics::Detailed
        })
    } else {
        options
    };
    let mut parser = ParseContext::with_bindings(input, bindings, options);
    parser.rules = rules;
    parser.whitespace = whitespace;
    if parser.first_sets.is_some() {
        parser.first_sets = Some(first::table(&parser.rules));
    }
    if options.memoization == Memoization::SafeFailures {
        parser.memo_safe_rules = memo_safe_rules(&parser.rules);
    }
    let mut trailing_offset = None;
    if let Some(root) = parser.rule(root, 0) {
        if parser.position == input.len() {
            let recoveries = parser.selected_recoveries(root);
            let tokens = parser.retained_tokens();
            return Ok(Tree {
                source: input.to_owned(),
                nodes: parser.nodes,
                root,
                byte_offsets: parser.byte_offsets,
                scopes: parser.scopes,
                recoveries,
                tokens,
            });
        }
        trailing_offset = Some(parser.code_point(parser.position));
        parser.fail("end of input");
    }
    if let Some(error) = parser.resource_failure.take() {
        return Err(ParseDiagnostic {
            kind: "resource_limit",
            offset: error.offset,
            expected: error.expected.clone(),
            farthest: error,
        });
    }
    if let Some(failure) = parser.name_failure() {
        return Err(ParseDiagnostic {
            kind: failure.kind,
            offset: failure.span.start,
            expected: vec![failure.expected.clone()],
            farthest: failure.error(),
        });
    }
    if options.diagnostics == Diagnostics::DetailedOnFailure {
        let bindings = parser.bindings.clone();
        let rules = Arc::clone(&parser.rules);
        // Release the first pass, including its empty-diagnostic memo entries.
        drop(parser);
        return parse_detailed_owned(
            rules,
            root,
            whitespace,
            input,
            options.with_diagnostics(Diagnostics::Detailed),
            bindings,
        );
    }
    let farthest = ParseError {
        offset: parser.code_point(parser.farthest),
        expected: parser.expected_names.strings(&parser.expected),
    };
    Err(ParseDiagnostic {
        kind: if trailing_offset.is_some() {
            "trailing_input"
        } else if parser.unique_choice_failure_seen
            && farthest
                .expected
                .iter()
                .any(|hint| hint == "unique longest alternative")
        {
            "ambiguity"
        } else if parser.unique_choice_failure_seen
            && farthest
                .expected
                .iter()
                .any(|hint| hint == "nonempty unique longest alternative")
        {
            "empty_choice"
        } else if parser.unique_choice_failure_seen
            && farthest
                .expected
                .iter()
                .any(|hint| hint == "2 to 64 unique longest alternatives")
        {
            "choice_limit"
        } else {
            "syntax"
        },
        offset: trailing_offset.unwrap_or(farthest.offset),
        expected: if trailing_offset.is_some() {
            vec!["end of input".to_owned()]
        } else {
            farthest.expected.clone()
        },
        farthest,
    })
}

impl<'a> ParseContext<'a> {
    pub fn new(input: &'a str) -> Self {
        Self::with_options(input, ParseOptions::default())
    }

    /// Creates a low-level context. [`Diagnostics::Auto`] resolves to `Detailed`,
    /// preserving diagnostics for callers of [`Self::failure`] and [`Self::error`].
    /// [`Diagnostics::DetailedOnFailure`] disables
    /// syntax diagnostics here and does not automatically retry failed operations.
    pub fn with_options(input: &'a str, options: ParseOptions) -> Self {
        Self::with_bindings(input, BTreeMap::new(), options)
    }

    /// Takes ownership of a parse-local snapshot. Values retain order and duplicates.
    /// No binding can be replaced during this context's lifetime.
    pub fn with_bindings(
        input: &'a str,
        bindings: BTreeMap<String, Vec<String>>,
        options: ParseOptions,
    ) -> Self {
        let options = if options.diagnostics == Diagnostics::Auto {
            options.with_diagnostics(Diagnostics::Detailed)
        } else {
            options
        };
        let byte_offsets: Vec<_> = input
            .char_indices()
            .map(|(i, _)| i)
            .chain(std::iter::once(input.len()))
            .collect();
        let code_point_offsets = if input.is_ascii() {
            vec![]
        } else {
            let mut offsets = vec![usize::MAX; input.len() + 1];
            for (code_point, &byte) in byte_offsets.iter().enumerate() {
                offsets[byte] = code_point;
            }
            offsets
        };
        Self {
            bindings,
            input,
            lexing: None,
            rules: Arc::from([]),
            token_mode: None,
            classic_max_reached: None,
            classic_failure_seen: false,
            match_cursor_policy: MatchCursorPolicy::default(),
            whitespace: false,
            lexical_trivia: None,
            lexical_context: None,
            position: 0,
            matched_position: 0,
            nodes: vec![],
            farthest: 0,
            expected: vec![],
            expected_names: ExpectedNames::default(),
            byte_offsets,
            code_point_offsets,
            captures: CaptureStore::default(),
            owned_captures: OwnedCaptureStore::default(),
            state: Rc::new(StateMap::default()),
            scopes: ScopeStore::default(),
            recoveries: Vec::new(),
            call_depth: 0,
            grapheme_boundaries: None,
            expression_depth: 0,
            resource_failure: None,
            options,
            grammar_session: 0,
            next_grammar_session: 1,
            memo_safe_rules: vec![],
            success_memo_safe_rules: vec![],
            failure_memo: FailureMemoBuckets::default(),
            success_memo: SuccessMemoBuckets::default(),
            diagnostic_frames: vec![],
            memoized_failure_hits: 0,
            memoized_success_hits: 0,
            checkpoint_metrics: None,
            checkpoint_metrics_scope_journal_base: 0,
            first_sets: None,
            exclusion_audit: false,
            tokens: Rc::new(token::Store::new()),
            next_token_generation: 1,
            unique_choice_failure_seen: false,
            names_state: names::State::default(),
            token_trace: options.token_trace.map(token_trace::Trace::new),
            trace_custom_contracts: Vec::new(),
        }
        .with_candidate_exclusion()
    }

    fn with_candidate_exclusion(mut self) -> Self {
        let mode = first::mode();
        if self.options.diagnostics == Diagnostics::DetailedOnFailure
            && mode != CandidateExclusion::Off
        {
            // Rule references into the empty initial grammar fail, as in the empty table.
            self.first_sets = Some(Arc::from([]));
            self.exclusion_audit = mode == CandidateExclusion::Audit;
        }
        self
    }

    /// Returns the resolved options; `diagnostics` is never [`Diagnostics::Auto`].
    pub fn options(&self) -> ParseOptions {
        self.options
    }

    /// Enables parse-local checkpoint counters, initially reset to zero.
    pub fn enable_checkpoint_metrics(&mut self) {
        self.checkpoint_metrics = Some(CheckpointMetrics::default());
        self.checkpoint_metrics_scope_journal_base = self.scopes.journal_entries_created();
    }

    /// Returns a stable snapshot without disabling collection. Before enabling,
    /// every counter is zero.
    pub fn snapshot_checkpoint_metrics(&self) -> CheckpointMetrics {
        let mut metrics = self.checkpoint_metrics.unwrap_or_default();
        if self.checkpoint_metrics.is_some() {
            metrics.scope_journal_entries = self
                .scopes
                .journal_entries_created()
                .saturating_sub(self.checkpoint_metrics_scope_journal_base);
        }
        metrics
    }

    /// Hits in this context's pass only; excludes any separate detailed retry.
    pub fn memoized_failure_hits(&self) -> usize {
        self.memoized_failure_hits
    }

    /// Retained failures in the active grammar session; successes have their own budget.
    pub fn failure_memo_entries(&self) -> usize {
        self.failure_memo.entries
    }
    /// Conservative cache-owned failure payload charge, not process RSS.
    pub fn failure_memo_retained_bytes(&self) -> usize {
        self.failure_memo.retained_bytes
    }
    /// Insertions refused by a finite failure budget in the active grammar session.
    pub fn failure_memo_budget_misses(&self) -> usize {
        self.failure_memo.budget_misses
    }

    /// Success snapshot payload charge in the active grammar session, not process RSS.
    pub fn success_memo_retained_bytes(&self) -> usize {
        self.success_memo.retained_bytes
    }
    pub fn success_memo_entries(&self) -> usize {
        self.success_memo.entries
    }
    pub fn success_memo_budget_misses(&self) -> usize {
        self.success_memo.budget_misses
    }

    /// Actual successful rule replays in this context's pass. Tracing, unsafe
    /// rules and snapshots exceeding the memo budgets are misses.
    pub fn memoized_success_hits(&self) -> usize {
        self.memoized_success_hits
    }

    /// Read-only external data, independent of captures and transactional state.
    /// An absent key returns an empty slice.
    pub fn binding_values(&self, name: &str) -> &[String] {
        self.bindings.get(name).map(Vec::as_slice).unwrap_or(&[])
    }

    pub fn source(&self) -> &'a str {
        self.input
    }
    pub fn remaining(&self) -> &'a str {
        &self.input[self.position..]
    }
    pub fn position(&self) -> usize {
        self.code_point(self.position)
    }
    /// Unicode code-point position of the UBNF match-only cursor. Consuming a
    /// character resets this cursor to the consumed position; PEG `ahead` restores it.
    pub fn matched_position(&self) -> usize {
        self.code_point(self.matched_position)
    }
    pub fn node(&self, id: usize) -> Option<&Node> {
        self.nodes.get(id)
    }
    pub fn tree(&self, root: usize) -> Option<Tree> {
        self.nodes.get(root)?;
        Some(Tree {
            source: self.input.to_owned(),
            nodes: self.nodes.clone(),
            root,
            byte_offsets: self.byte_offsets.clone(),
            scopes: self.scopes.clone(),
            recoveries: self.selected_recoveries(root),
            tokens: self.retained_tokens(),
        })
    }

    /// Committed recovery events in this context; rollback removes failed branches.
    pub fn recoveries(&self) -> &[RecoveryDiagnostic] {
        &self.recoveries
    }

    fn selected_recoveries(&self, root: usize) -> Vec<RecoveryDiagnostic> {
        if self.recoveries.is_empty() {
            return Vec::new();
        }
        let mut reachable = vec![false; self.nodes.len()];
        let mut pending = vec![root];
        while let Some(id) = pending.pop() {
            if id >= self.nodes.len() || reachable[id] {
                continue;
            }
            reachable[id] = true;
            pending.extend(&self.nodes[id].children);
        }
        self.recoveries
            .iter()
            .filter(|event| reachable.get(event.node).copied().unwrap_or(false))
            .cloned()
            .collect()
    }
    pub fn text(&self, span: Span) -> Option<&'a str> {
        if span.start > span.end {
            return None;
        }
        self.input
            .get(*self.byte_offsets.get(span.start)?..*self.byte_offsets.get(span.end)?)
    }
    pub fn advance(&mut self, code_points: usize) -> bool {
        let Some(end) = self
            .position()
            .checked_add(code_points)
            .and_then(|end| self.byte_offsets.get(end))
        else {
            return false;
        };
        self.position = *end;
        self.matched_position = self.position;
        true
    }
    pub fn captured(&self, name: &str) -> Option<&'a str> {
        self.text(*self.captures.get(name)?.last()?)
    }
    /// Latest general owned binding; independent of AST field captures.
    pub fn captured_source(&self, name: &str) -> Option<&token::TokenSource> {
        self.owned_captures.get(name)?.last()
    }
    /// The actual captured text, including projected/generated values.
    pub fn captured_text(&self, name: &str) -> Option<&str> {
        match self.captured_source(name)? {
            token::TokenSource::Input(span) => self.text(*span),
            token::TokenSource::Projected { text, .. } => Some(text),
            token::TokenSource::Generated { text, .. } => Some(text),
        }
    }
    /// General binding history, including owned projected/generated sources.
    pub fn capture_sources(&self, name: &str) -> &[token::TokenSource] {
        self.owned_captures
            .get(name)
            .map(Vec::as_slice)
            .unwrap_or(&[])
    }
    /// Legacy input-backed AST capture history.
    pub fn capture_spans(&self, name: &str) -> &[Span] {
        self.captures.get(name).map(Vec::as_slice).unwrap_or(&[])
    }
    /// Read outer bindings and discard local definitions on exit. Controlled errors
    /// roll back child effects; a panic still requires discarding this context.
    pub fn with_capture_scope<T>(
        &mut self,
        operation: impl FnOnce(&mut Self) -> Result<T, ParseError>,
    ) -> Result<T, ParseError> {
        self.transaction(|context| {
            let mark = context.owned_captures.checkpoint();
            let result = operation(context);
            context.owned_captures.rollback_checkpoint(mark);
            result
        })
    }
    pub fn set_state<T: Any + Clone>(&mut self, name: impl Into<String>, value: T) {
        self.state_mut_map().0.insert(name.into(), Box::new(value));
    }
    pub fn state<T: Any>(&self, name: &str) -> Option<&T> {
        self.state.0.get(name)?.as_ref().as_any().downcast_ref()
    }
    pub fn state_mut<T: Any>(&mut self, name: &str) -> Option<&mut T> {
        self.state
            .0
            .get(name)?
            .as_ref()
            .as_any()
            .downcast_ref::<T>()?;
        self.state_mut_map()
            .0
            .get_mut(name)?
            .as_mut()
            .as_any_mut()
            .downcast_mut()
    }
    pub fn remove_state(&mut self, name: &str) {
        if self.state.0.contains_key(name) {
            self.state_mut_map().0.remove(name);
        }
    }

    pub fn scopes(&self) -> &ScopeStore {
        &self.scopes
    }

    /// Current parse-visible state epoch. Rollback restores it; later mutations
    /// never reuse a prior branch's epoch. None means the allocator is exhausted
    /// and all memo lookups/inserts are permanently disabled for this context.
    pub fn memoization_state_version(&self) -> Option<usize> {
        self.scopes.memo_version()
    }

    /// Notify memoization before retrying a rule whose result can observe a
    /// caller-owned state mutation. This does not make opaque custom parsers safe
    /// or permit replay of stateful successes. Scope mutations do this internally.
    pub fn mark_memoization_state_changed(&mut self) {
        self.scopes.mark_memo_changed();
    }

    /// Changes participate in all parser checkpoints, independently of named user state.
    pub fn scopes_mut(&mut self) -> &mut ScopeStore {
        self.scopes_mut_store()
    }

    /// Runs in a lexical child scope. On success, names leave scope but source events remain.
    /// On error, cursors and all transactional values return to their state before entry.
    /// Manual enter/leave calls inside the operation must be balanced.
    /// As with transaction(), a panicking operation requires discarding the context.
    pub fn with_scope<T>(
        &mut self,
        operation: impl FnOnce(&mut Self) -> Result<T, ParseError>,
    ) -> Result<T, ParseError> {
        self.transaction(|context| {
            context.scopes_mut().enter();
            let result = operation(context);
            context.scopes_mut().leave();
            result
        })
    }
    /// Records an expected name and returns [`Self::failure`]. With
    /// [`Diagnostics::DetailedOnFailure`], records nothing and returns an empty diagnostic.
    pub fn error(&mut self, expected: &str) -> ParseError {
        self.fail(expected);
        self.failure()
    }
    /// Current syntax diagnostic; offset zero and no expected names with
    /// [`Diagnostics::DetailedOnFailure`]. Does not trigger a detailed retry.
    pub fn name_failure(&self) -> Option<&names::Failure> {
        self.names_state.failure()
    }

    pub fn failure(&self) -> ParseError {
        if let Some(error) = &self.resource_failure {
            return error.clone();
        }
        if let Some(failure) = self.name_failure() {
            return failure.error();
        }
        ParseError {
            offset: self.code_point(if !self.classic_failure_seen {
                self.classic_max_reached.unwrap_or(self.farthest)
            } else {
                self.farthest
            }),
            expected: self.expected_names.strings(&self.expected),
        }
    }

    fn resource_error(&mut self, expected: &str) -> ParseError {
        let error = ParseError {
            offset: self.position(),
            expected: vec![expected.to_owned()],
        };
        self.resource_failure.get_or_insert(error).clone()
    }

    pub fn parse<P: Parser + ?Sized>(&mut self, parser: &P) -> ParseResult {
        if let Some(error) = &self.resource_failure {
            return Err(error.clone());
        }
        if self.call_depth >= 256 {
            return Err(self.resource_error("parser calls below 256"));
        }
        self.call_depth += 1;
        let result = self.transaction(|context| parser.parse(context));
        self.call_depth -= 1;
        result
    }

    pub fn transaction<T>(
        &mut self,
        operation: impl FnOnce(&mut Self) -> Result<T, ParseError>,
    ) -> Result<T, ParseError> {
        if let Some(error) = &self.resource_failure {
            return Err(error.clone());
        }
        let checkpoint = self.checkpoint();
        let result = operation(self);
        let result = match &self.resource_failure {
            Some(error) => Err(error.clone()),
            None => result,
        };
        if let Err(error) = &result {
            if let Some(&byte) = self
                .byte_offsets
                .get(error.offset)
                .filter(|_| self.options.diagnostics == Diagnostics::Detailed)
            {
                if byte > self.farthest {
                    self.farthest = byte;
                    self.expected.clear();
                }
                if byte == self.farthest {
                    for expected in &error.expected {
                        let expected = self.expected_names.intern(expected);
                        if !self.expected.contains(&expected) {
                            self.expected.push(expected);
                        }
                    }
                }
            }
            self.restore(checkpoint);
        } else {
            self.commit_checkpoint(checkpoint);
        }
        result
    }

    /// Temporarily installs grammar rules/trivia policy; user state and input are shared.
    /// This accepts a prefix. Use an EOF parser or parse_detailed for full-input acceptance.
    /// Node rule IDs are local to this grammar; map each returned root with its own mapper.
    pub fn parse_grammar(
        &mut self,
        rules: Vec<Rule>,
        root: usize,
        whitespace: bool,
    ) -> ParseResult {
        let grammar = share_grammar(rules);
        self.parse_shared_grammar(&grammar, root, whitespace)
    }

    /// Temporarily installs an immutable shared grammar while retaining this
    /// context's parse-local cursor, captures, nodes, scopes and user state.
    /// The previous grammar and trivia policy are restored on both success and
    /// ordinary parse failure. Reentrant parser/grammar calls share the 256-call
    /// budget; child grammars do not reset active expression nesting.
    pub fn parse_shared_grammar(
        &mut self,
        grammar: &SharedGrammar,
        root: usize,
        whitespace: bool,
    ) -> ParseResult {
        if let Some(error) = &self.resource_failure {
            return Err(error.clone());
        }
        if self.call_depth >= 256 {
            return Err(self.resource_error("parser calls below 256"));
        }
        self.call_depth += 1;
        let previous_definition = self.lexical_trivia.take();
        let previous_goal = self.lexical_context.take();
        let outer_entry = self.rules.is_empty();
        let previous_lexing = if outer_entry {
            None
        } else {
            self.lexing.take()
        };
        let previous_rules = std::mem::replace(&mut self.rules, Arc::clone(grammar));
        let previous_whitespace = std::mem::replace(&mut self.whitespace, whitespace);
        let safe_rules = if self.options.memoization == Memoization::SafeFailures {
            memo_safe_rules(grammar)
        } else {
            vec![]
        };
        let previous_safe_rules = std::mem::replace(&mut self.memo_safe_rules, safe_rules);
        let previous_success_safe_rules = std::mem::take(&mut self.success_memo_safe_rules);
        let first_sets = self.first_sets.as_ref().map(|_| first::table(grammar));
        let previous_first_sets = std::mem::replace(&mut self.first_sets, first_sets);
        let previous_failure_memo = std::mem::take(&mut self.failure_memo);
        let previous_success_memo = std::mem::take(&mut self.success_memo);
        let previous_session = self.grammar_session;
        let active_session = self.next_grammar_session;
        self.next_grammar_session = self.next_grammar_session.wrapping_add(1);
        self.grammar_session = active_session;
        let result = self.parse_expression(&Expr::Rule(root));
        self.failure_memo = previous_failure_memo;
        self.success_memo = previous_success_memo;
        self.rules = previous_rules;
        self.lexical_context = previous_goal;
        if !outer_entry {
            self.lexing = previous_lexing;
        }
        self.whitespace = previous_whitespace;
        self.lexical_trivia = previous_definition;
        self.memo_safe_rules = previous_safe_rules;
        self.success_memo_safe_rules = previous_success_safe_rules;
        self.first_sets = previous_first_sets;
        self.grammar_session = previous_session;
        self.call_depth -= 1;
        result
    }

    /// Temporarily changes sequence trivia handling, on both success and failure.
    /// Atomic parsers still consume only their own token syntax.
    pub fn with_trivia<T>(
        &mut self,
        whitespace: bool,
        operation: impl FnOnce(&mut Self) -> T,
    ) -> T {
        let previous_definition = self.lexical_trivia.take();
        let previous = std::mem::replace(&mut self.whitespace, whitespace);
        let result = operation(self);
        self.whitespace = previous;
        self.lexical_trivia = previous_definition;
        result
    }

    fn parse_expression(&mut self, expression: &Expr) -> ParseResult {
        let previous = self.token_mode;
        if previous.is_none() && requires_classic_mode(expression, &self.rules) {
            self.token_mode = Some(token::ParseMode::default());
        }
        let start = self.position();
        let result = match self.expression(expression, self.call_depth) {
            Some(fragment) => Ok(ParseMatch {
                span: fragment
                    .source
                    .as_deref()
                    .filter(|source| source.classic)
                    .map_or(
                        Span {
                            start,
                            end: self.position(),
                        },
                        |source| source.span,
                    ),
                nodes: fragment.nodes,
                captures: fragment.captures,
            }),
            None => Err(self.failure()),
        };
        self.token_mode = previous;
        result
    }

    fn checkpoint(&mut self) -> Checkpoint {
        if self.options.memoization == Memoization::SafeFailures {
            self.failure_memo
                .observe_cursor(self.code_point(self.position));
            self.success_memo
                .observe_cursor(self.code_point(self.position));
        }
        let captures_nonempty = !self.captures.is_empty() || !self.owned_captures.is_empty();
        let captures_journal_mark = self.captures.checkpoint();
        let owned_captures_journal_mark = self.owned_captures.checkpoint();
        let state = (!self.state.0.is_empty()).then(|| Rc::clone(&self.state));
        let scopes_nonempty = !self.scopes.is_empty();
        let scopes_journal_mark = self.scopes.checkpoint();
        let metrics_counted = self.checkpoint_metrics.is_some();
        if let Some(metrics) = &mut self.checkpoint_metrics {
            metrics.opened += 1;
            if captures_nonempty || state.is_some() || scopes_nonempty {
                metrics.nonempty_payload_snapshots += 1;
            } else {
                metrics.empty_payload_checkpoints += 1;
            }
        }
        Checkpoint {
            match_cursor_policy: self.match_cursor_policy,
            position: self.position,
            matched_position: self.matched_position,
            nodes: self.nodes.len(),
            metrics_counted,
            captures_journal_mark,
            owned_captures_journal_mark,
            scopes_journal_mark,
            recoveries: self.recoveries.len(),
            tokens: Rc::clone(&self.tokens),
            token_trace: token_trace::Mark::of(self.token_trace.as_ref()),
            state,
        }
    }

    fn commit_checkpoint(&mut self, checkpoint: Checkpoint) {
        self.captures.commit_checkpoint();
        self.owned_captures.commit_checkpoint();
        self.scopes.commit_checkpoint();
        if checkpoint.metrics_counted {
            if let Some(metrics) = &mut self.checkpoint_metrics {
                metrics.committed += 1;
            }
        }
    }

    fn restore(&mut self, checkpoint: Checkpoint) {
        if checkpoint.metrics_counted {
            if let Some(metrics) = &mut self.checkpoint_metrics {
                metrics.rolled_back += 1;
            }
        }
        self.match_cursor_policy = checkpoint.match_cursor_policy;
        self.position = checkpoint.position;
        self.matched_position = checkpoint.matched_position;
        self.nodes.truncate(checkpoint.nodes);
        self.tokens = checkpoint.tokens;
        checkpoint.token_trace.restore(self.token_trace.as_mut());
        self.recoveries.truncate(checkpoint.recoveries);
        self.captures
            .rollback_checkpoint(checkpoint.captures_journal_mark);
        self.owned_captures
            .rollback_checkpoint(checkpoint.owned_captures_journal_mark);
        if let Some(state) = checkpoint.state {
            self.state = state;
        } else if !self.state.0.is_empty() {
            if let Some(state) = Rc::get_mut(&mut self.state) {
                state.0.clear();
            } else {
                self.state = Rc::new(StateMap::default());
            }
        }
        self.scopes
            .rollback_checkpoint(checkpoint.scopes_journal_mark);
    }

    // Optional mode-only boundaries stay boxed across recursive parsing. Moving
    // Checkpoint values after recursion must not enlarge every native frame.
    #[inline(never)]
    fn finish_classic_candidate(&mut self, checkpoint: Option<Box<Checkpoint>>, commit: bool) {
        if let Some(checkpoint) = checkpoint {
            if commit {
                self.commit_checkpoint(*checkpoint);
            } else {
                self.restore(*checkpoint);
            }
        }
    }

    fn record_cow_copy(&mut self) {
        if let Some(metrics) = &mut self.checkpoint_metrics {
            metrics.copy_on_write_deep_copies += 1;
        }
    }

    fn state_mut_map(&mut self) -> &mut StateMap {
        if Rc::strong_count(&self.state) > 1 {
            self.record_cow_copy();
        }
        Rc::make_mut(&mut self.state)
    }

    fn scopes_mut_store(&mut self) -> &mut ScopeStore {
        &mut self.scopes
    }
    fn code_point(&self, byte: usize) -> usize {
        if self.code_point_offsets.is_empty() {
            assert!(
                byte <= self.input.len(),
                "parser maintains UTF-8 boundaries"
            );
            return byte;
        }
        self.code_point_offsets
            .get(byte)
            .copied()
            .filter(|&offset| offset != usize::MAX)
            .expect("parser maintains UTF-8 boundaries")
    }

    fn span(&self, start: usize) -> Span {
        Span {
            start: self.code_point(start),
            end: self.code_point(self.position),
        }
    }

    fn fail(&mut self, expected: &str) {
        self.fail_at(self.position, expected);
    }

    fn fail_without_expected(&mut self) {
        // The successful Classic watermark is only a fallback when neither entry
        // has failed. Native PEG failures must also select the real failure frontier.
        if self.options.diagnostics == Diagnostics::Detailed {
            self.classic_failure_seen = true;
            if let Some(frame) = self.diagnostic_frames.last_mut() {
                frame.classic_failure_seen = true;
            }
        }
        if self.options.diagnostics == Diagnostics::DetailedOnFailure {
            return;
        }
        let position = self.position;
        if let Some(frame) = self.diagnostic_frames.last_mut() {
            frame.record_position(position);
        }
        if position > self.farthest {
            self.farthest = position;
            self.expected.clear();
        }
    }

    fn fail_at(&mut self, position: usize, expected: &str) {
        // The successful Classic watermark is only a fallback when neither entry
        // has failed. Native PEG failures must also select the real failure frontier.
        if self.options.diagnostics == Diagnostics::Detailed {
            self.classic_failure_seen = true;
            if let Some(frame) = self.diagnostic_frames.last_mut() {
                frame.classic_failure_seen = true;
            }
        }
        if self.options.diagnostics == Diagnostics::DetailedOnFailure {
            return;
        }
        // Failures behind both farthest positions cannot contribute a name.
        if position < self.farthest
            && self
                .diagnostic_frames
                .last()
                .is_none_or(|frame| frame.farthest.is_some_and(|farthest| position < farthest))
        {
            return;
        }
        let expected = self.expected_names.intern(expected);
        if let Some(diagnostic) = self.diagnostic_frames.last_mut() {
            diagnostic.record(position, expected);
        }
        if position > self.farthest {
            self.farthest = position;
            self.expected.clear();
        }
        if position == self.farthest && !self.expected.contains(&expected) {
            self.expected.push(expected);
        }
    }

    fn replay_failure(&mut self, diagnostic: &FailureDiagnostic) {
        if self.options.diagnostics == Diagnostics::DetailedOnFailure {
            return;
        }
        self.classic_failure_seen |= diagnostic.classic_failure_seen;
        if let Some(reached) = diagnostic.classic_max_reached {
            self.classic_max_reached = Some(self.classic_max_reached.unwrap_or(0).max(reached));
        }
        if let Some(frame) = self.diagnostic_frames.last_mut() {
            frame.classic_failure_seen |= diagnostic.classic_failure_seen;
            if let Some(reached) = diagnostic.classic_max_reached {
                frame.classic_max_reached =
                    Some(frame.classic_max_reached.unwrap_or(0).max(reached));
            }
        }
        let Some(position) = diagnostic.farthest else {
            return;
        };
        if let Some(frame) = self.diagnostic_frames.last_mut() {
            frame.merge(diagnostic);
        }
        match position.cmp(&self.farthest) {
            std::cmp::Ordering::Greater => {
                self.farthest = position;
                self.expected.clear();
                self.expected
                    .extend_from_slice(diagnostic.expected_values());
            }
            std::cmp::Ordering::Equal => {
                append_missing_expected(&mut self.expected, diagnostic.expected_values());
            }
            std::cmp::Ordering::Less => {}
        }
    }

    fn rule(&mut self, id: usize, depth: usize) -> Option<usize> {
        if self.resource_failure.is_some() {
            return None;
        }
        if depth >= 256 {
            self.resource_error("rule nesting below 256");
            return None;
        }
        if id >= self.rules.len() {
            self.fail("valid rule reference");
            return None;
        }
        if self.options.memoization == Memoization::SafeFailures
            && self.names_state.frames.is_empty()
            && self.lexical_trivia.is_none()
            && self.lexical_context.is_none()
            && self.scopes.memo_version().is_some()
            && self.memo_safe_rules.get(id).copied().unwrap_or(false)
        {
            self.memoized_rule(id, depth)
        } else {
            self.execute_rule(id, depth)
        }
    }

    // A no-cache recursive rule must not retain the large memo snapshot/key
    // frame. In particular name-resolution rules still reach their depth guard
    // on the default 2 MiB thread stack after the state-version key was added.
    #[inline(never)]
    fn execute_rule(&mut self, id: usize, depth: usize) -> Option<usize> {
        let rules = Arc::clone(&self.rules);
        let start = self.position;
        let count = self.nodes.len();
        match self.expression(&rules[id].expression, depth + 1) {
            Some(fragment) => Some(self.build_rule_node(id, start, fragment)),
            None => {
                self.position = start;
                self.nodes.truncate(count);
                None
            }
        }
    }

    #[inline(never)]
    fn memoized_rule(&mut self, id: usize, depth: usize) -> Option<usize> {
        let key = FailureMemoKey::new(
            id,
            self.position,
            self.matched_position,
            self.whitespace,
            depth,
        )
        .with_match_policy(self.match_cursor_policy)
        .with_token_mode(self.token_mode)
        .with_state_version(self.scopes.memo_version().expect("memo version checked"));
        let position = self.code_point(self.position);
        if let Some(result) = self.probe_rule_memo(position, &key) {
            return result;
        }
        let success = self.token_trace.is_none();
        let capture_mark = if success {
            self.captures.checkpoint()
        } else {
            self.captures.journal.len()
        };
        let detailed = self.options.diagnostics == Diagnostics::Detailed;
        if detailed {
            self.diagnostic_frames.push(FailureDiagnostic::default());
        }
        let call = MemoCall {
            key,
            position,
            count: self.nodes.len(),
            capture_mark,
            detailed,
            success,
        };
        let result = self.execute_rule(id, depth);
        self.finish_rule_memo(call, result);
        result
    }

    #[inline(never)]
    fn probe_rule_memo(&mut self, position: usize, key: &FailureMemoKey) -> Option<Option<usize>> {
        if let Some(diagnostic) = self
            .failure_memo
            .probe(position, key)
            .filter(|entry| self.expression_depth <= entry.memo_expression_depth)
            .cloned()
        {
            self.memoized_failure_hits += 1;
            self.replay_failure(&diagnostic);
            if let Some((matched, policy)) = diagnostic.classic_failure_end {
                self.matched_position = matched;
                self.match_cursor_policy = policy;
            }
            return Some(None);
        }
        if self.token_trace.is_none() {
            if let Some(entry) = self.success_memo.probe(position, key).cloned() {
                // A shallower retry has at least the saved remaining budget.
                if self.expression_depth <= entry.expression_depth {
                    self.memoized_success_hits += 1;
                    return Some(Some(self.replay_success(&entry)));
                }
            }
        }
        None
    }

    #[inline(never)]
    fn finish_rule_memo(&mut self, call: MemoCall, result: Option<usize>) {
        let mut diagnostic = if call.detailed {
            let diagnostic = self
                .diagnostic_frames
                .pop()
                .expect("memoized rule installed a diagnostic frame");
            if let Some(parent) = self.diagnostic_frames.last_mut() {
                parent.merge(&diagnostic);
            }
            diagnostic
        } else {
            FailureDiagnostic::default()
        };
        if result.is_none() && self.token_mode.is_some() {
            diagnostic.classic_failure_end =
                Some((self.matched_position, self.match_cursor_policy));
        }
        // Resource exhaustion and epoch exhaustion are never cacheable failures.
        if self.resource_failure.is_none() && self.scopes.memo_version().is_some() {
            if let Some(root) = result {
                if call.success {
                    self.memoize_success(
                        call.position,
                        call.key,
                        call.count,
                        root,
                        call.capture_mark,
                        diagnostic,
                    );
                }
            } else {
                diagnostic.memo_expression_depth = self.expression_depth;
                diagnostic.memo_expected_text_bytes =
                    diagnostic
                        .expected_values()
                        .iter()
                        .fold(0_usize, |bytes, &id| {
                            bytes
                                .saturating_add(self.expected_names.name(id).len())
                                .saturating_add(32)
                        });
                self.failure_memo.insert(
                    call.position / FAILURE_MEMO_BUCKET_SIZE,
                    call.key,
                    diagnostic,
                );
            }
        }
        if call.success {
            self.captures.commit_checkpoint();
        }
    }

    /// Snapshot only the newly appended pure arena. No public NodeId, mutable
    /// TokenInfo, scope, owned binding, or user value can be retained here.
    #[inline(never)]
    fn memoize_success(
        &mut self,
        position: usize,
        key: FailureMemoKey,
        start: usize,
        root: usize,
        capture_mark: usize,
        mut diagnostic: FailureDiagnostic,
    ) {
        // Failure-only parses keep their historical zero-extra-allocation path.
        // Compute the stronger proof lazily on the first completed safe rule.
        if self.success_memo_safe_rules.len() != self.rules.len() {
            self.success_memo_safe_rules = memo_success_safe_rules(&self.rules);
        }
        if !self
            .success_memo_safe_rules
            .get(key.rule)
            .copied()
            .unwrap_or(false)
        {
            return;
        }
        let limit = self
            .success_memo
            .snapshot_room(position / FAILURE_MEMO_BUCKET_SIZE, &key);
        if diagnostic.retained_bytes() > limit {
            self.success_memo.budget_misses = self.success_memo.budget_misses.saturating_add(1);
            return;
        }
        let mut text_bytes = 0_usize;
        for &id in diagnostic.expected_values() {
            text_bytes = text_bytes
                .saturating_add(self.expected_names.name(id).len())
                .saturating_add(32);
            if text_bytes > limit {
                break;
            }
        }
        diagnostic.memo_expected_text_bytes = text_bytes;
        let nodes = &self.nodes[start..];
        let journal = &self.captures.journal[capture_mark..];
        let mut bytes = std::mem::size_of::<SuccessMemo>()
            .saturating_add(std::mem::size_of_val(nodes))
            .saturating_add(std::mem::size_of_val(journal))
            .saturating_add(journal.len().saturating_mul(std::mem::size_of::<Span>()))
            .saturating_add(diagnostic.retained_bytes());
        if bytes > limit {
            self.success_memo.budget_misses = self.success_memo.budget_misses.saturating_add(1);
            return;
        }
        for node in nodes {
            bytes = bytes
                .saturating_add(std::mem::size_of_val(node.children.as_slice()))
                .saturating_add(std::mem::size_of_val(node.captures.as_slice()));
            for capture in &node.captures {
                bytes = bytes.saturating_add(std::mem::size_of_val(capture.nodes.as_slice()));
            }
            if bytes > limit {
                self.success_memo.budget_misses = self.success_memo.budget_misses.saturating_add(1);
                return;
            }
            // Fail closed if a future pure expression introduces foreign edges.
            if node
                .children
                .iter()
                .chain(node.captures.iter().flat_map(|c| &c.nodes))
                .any(|&id| id < start || id >= self.nodes.len())
            {
                self.success_memo.budget_misses = self.success_memo.budget_misses.saturating_add(1);
                return;
            }
        }
        let Some(token_bytes) = token::MemoTokens::measure_bounded(
            &self.tokens,
            start,
            self.nodes.len(),
            limit.saturating_sub(bytes),
        ) else {
            self.success_memo.budget_misses = self.success_memo.budget_misses.saturating_add(1);
            return;
        };
        bytes = bytes.saturating_add(token_bytes);
        if bytes > limit || self.tokens.calls.range(start..).next().is_some() {
            self.success_memo.budget_misses = self.success_memo.budget_misses.saturating_add(1);
            return;
        }
        let mut nodes = nodes.to_vec();
        for node in &mut nodes {
            for child in node
                .children
                .iter_mut()
                .chain(node.captures.iter_mut().flat_map(|c| &mut c.nodes))
            {
                *child -= start;
            }
        }
        // Journal order matters for repeated names. Values themselves are immutable
        // input spans; record only this successful branch's appended occurrences.
        let mut counts = HashMap::new();
        let mut captures = Vec::with_capacity(journal.len());
        for &name in journal.iter().rev() {
            let count = counts.entry(name).or_insert(0);
            let spans = &self.captures.values[name];
            *count += 1;
            captures.push((name, spans[spans.len() - *count]));
        }
        captures.reverse();
        self.success_memo.insert(
            position / FAILURE_MEMO_BUCKET_SIZE,
            key,
            Rc::new(SuccessMemo {
                nodes,
                tokens: token::MemoTokens::capture(&self.tokens, start, self.nodes.len()),
                root: root - start,
                captures,
                consumed: self.position,
                matched: self.matched_position,
                policy: self.match_cursor_policy,
                expression_depth: self.expression_depth,
                diagnostic,
                bytes,
            }),
        );
    }

    #[inline(never)]
    fn replay_success(&mut self, entry: &SuccessMemo) -> usize {
        let start = self.nodes.len();
        for node in &entry.nodes {
            let mut node = node.clone();
            for child in node
                .children
                .iter_mut()
                .chain(node.captures.iter_mut().flat_map(|c| &mut c.nodes))
            {
                *child += start;
            }
            self.nodes.push(node);
        }
        entry.tokens.replay(self, start);
        for &(name, span) in &entry.captures {
            self.captures.push(name, span);
        }
        self.position = entry.consumed;
        self.matched_position = entry.matched;
        self.match_cursor_policy = entry.policy;
        self.replay_failure(&entry.diagnostic);
        start + entry.root
    }

    #[inline(never)]
    fn build_rule_node(&mut self, id: usize, start: usize, fragment: Fragment) -> usize {
        let fragment = self.collect_classic_fragment(fragment, self.token_mode().kind);
        let classic = fragment
            .source
            .as_deref()
            .filter(|source| source.classic)
            .cloned();
        let node_id = self.nodes.len();
        self.nodes.push(Node {
            rule: id,
            span: classic
                .as_ref()
                .map_or_else(|| self.span(start), |source| source.span),
            children: fragment.nodes,
            captures: fragment.captures,
        });
        if let Some(source) = classic {
            self.mark_classic_node(node_id, &source);
        }
        node_id
    }

    fn expression(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        // Wrappers do not increase rule depth. Bound their active frames separately,
        // including frames retained across a custom callback or child grammar entry.
        if self.resource_failure.is_some() {
            return None;
        }
        if self.expression_depth >= 1024 {
            self.resource_error("expression nesting below 1024");
            return None;
        }
        self.expression_depth += 1;
        let checkpoint = self.checkpoint();
        let parent_policy = self.match_cursor_policy;
        // Chain sets the policy before beginning its own transaction, so a successful
        // Sequence changes the policy for following siblings in the same owner.
        // These built-in lexical parsers are also Chain implementations in Java.
        if matches!(
            expression,
            Expr::Sequence(_)
                | Expr::Number
                | Expr::Identifier
                | Expr::Quoted(_)
                | Expr::CodeStart
                | Expr::CodeEnd
                | Expr::CaptureBoundary(_, _)
                | Expr::InputCaptureBoundary(_, _)
        ) {
            self.match_cursor_policy.reset = false;
        }
        let java_transaction = matches!(
            expression,
            Expr::NonOrdered(_)
                | Expr::OwnedCapture(_, _)
                | Expr::CaptureScope(_)
                | Expr::MatchOnly(_)
                | Expr::InvertMatch(_)
                | Expr::JavaEmpty
                | Expr::JavaLookahead { .. }
                | Expr::JavaUntil(_)
                | Expr::JavaOptional(_)
                | Expr::JavaRepeat { .. }
                | Expr::ZeroMatch(_)
                | Expr::Choice(_)
                | Expr::LongestChoice(_)
                | Expr::UniqueLongestChoice(_)
                | Expr::PredictiveChoice { .. }
                | Expr::Recovery { .. }
        );
        let no_repeat_attempt = matches!(expression,
            Expr::Repeat { min, max: Some(max), .. } | Expr::JavaRepeat { min, max: Some(max), .. }
                if *max == 0 || *max < *min);
        if java_transaction {
            if !no_repeat_attempt
                && (self.token_mode.is_some()
                    || self.match_cursor_policy.non_ordered
                    || matches!(
                        expression,
                        Expr::NonOrdered(_) | Expr::MatchOnly(_) | Expr::InvertMatch(_)
                    ))
                && self.match_cursor_policy.reset
                && !matches!(
                    expression,
                    Expr::LongestChoice(_) | Expr::UniqueLongestChoice(_)
                )
            {
                self.matched_position = self.position;
            }
            if matches!(expression, Expr::NonOrdered(_)) {
                self.match_cursor_policy.non_ordered = true;
            }
        }
        // Recursive combinators bypass the large general dispatch frame.
        let result = match expression {
            Expr::MatchOnly(_)
            | Expr::InvertMatch(_)
            | Expr::StopInvert(_)
            | Expr::StopConsume(_) => self.classic_wrapper(expression, depth),
            Expr::Rule(rule) => self.rule(*rule, depth).map(|id| Fragment {
                nodes: vec![id],
                captures: Vec::new(),
                source: self.tokens.values.get(&id).map(|value| {
                    Box::new(FragmentSource {
                        span: self.nodes[id].span,
                        kind: value.kind,
                        classic: true,
                        generated: matches!(value.source, token::TokenSource::Generated { .. }),
                        text: value.source.owned_shared(),
                    })
                }),
            }),
            Expr::LexicalContextScope { child, terminals } => {
                self.expression_with_lexical_context(child, terminals, depth)
            }
            Expr::LexicalTriviaScope { child, definition } => {
                self.expression_with_lexical_trivia(child, definition, depth)
            }
            Expr::TriviaScope { child, whitespace } => {
                self.with_trivia(*whitespace, |context| context.expression(child, depth))
            }
            Expr::Sequence(elements) => self.sequence(elements, depth),
            Expr::NonOrdered(children) => self.non_ordered(children, depth),
            Expr::Choice(alternatives) => self.ordered_choice(alternatives.iter(), depth),
            Expr::LongestChoice(alternatives) => self.longest_choice(alternatives, depth, false),
            Expr::UniqueLongestChoice(alternatives) => {
                self.longest_choice(alternatives, depth, true)
            }
            Expr::PredictiveChoice {
                alternatives,
                predictors,
            } => self.predictive_choice(alternatives, predictors, depth),
            Expr::NameResolutionScope {
                child,
                requirements,
            } => self.name_scope(child, requirements, depth),
            Expr::NamePredicate {
                child,
                snapshot,
                version,
                capture,
                kind,
            } => self.name_predicate(child, snapshot, version, capture, kind, depth),
            Expr::Custom(_) | Expr::CustomWith { .. } => self.expression_custom(expression, depth),
            Expr::Recovery { .. } => self.expression_recovery(expression, depth),
            Expr::Optional(_) | Expr::JavaOptional(_) => {
                self.expression_optional(expression, depth)
            }
            Expr::Repeat { .. } | Expr::JavaRepeat { .. } | Expr::ZeroMatch(_) => {
                self.expression_repeat(expression, depth)
            }
            Expr::Lookahead { .. } => self.expression_lookahead(expression, depth),
            Expr::Capture(_, _) | Expr::OwnedCapture(_, _) => {
                self.expression_capture(expression, depth)
            }
            Expr::CaptureScope(child) => self.expression_capture_scope(child, depth),
            Expr::RuleEffects { .. } => self.expression_rule_effects(expression, depth),
            Expr::CaptureEquality { .. } => self.expression_capture_equality(expression, depth),
            Expr::TextValue(_)
            | Expr::ValueBoundary(_)
            | Expr::CaptureBoundary(_, _)
            | Expr::InputCaptureBoundary(_, _) => self.expression_value_boundary(expression, depth),
            Expr::Character { .. } => self.classic_atom(expression, depth),
            _ if self.token_mode.is_some() => self.classic_atom(expression, depth),
            _ => self.expression_inner(expression, depth),
        };
        self.finish_expression(
            expression,
            result,
            checkpoint,
            parent_policy,
            java_transaction,
        )
    }

    // Keep source/trace post-processing out of the recursive dispatch frame.
    #[inline(never)]
    fn finish_expression(
        &mut self,
        expression: &Expr,
        result: Option<Fragment>,
        checkpoint: Checkpoint,
        parent_policy: MatchCursorPolicy,
        java_transaction: bool,
    ) -> Option<Fragment> {
        let result = if self.resource_failure.is_some() {
            None
        } else {
            result.map(|mut fragment| {
                if self.token_mode.is_some() {
                    if let Some(label) = classic_collector_label(expression) {
                        fragment = self.classic_meta_node(fragment, self.token_mode().kind, label);
                    }
                    if fragment.source.is_none() {
                        let kind = self.token_mode().kind;
                        let anchor = if kind.is_real() {
                            self.code_point(self.mode_cursor())
                        } else {
                            0
                        };
                        fragment.source = Some(Box::new(FragmentSource {
                            span: Span {
                                start: anchor,
                                end: anchor,
                            },
                            kind,
                            classic: true,
                            generated: false,
                            text: None,
                        }));
                    }
                }
                fragment
            })
        };
        if result.is_none() {
            let leaf_reset = self.token_mode.is_some_and(|mode| mode.kind.is_consumed())
                && matches!(
                    expression,
                    Expr::Literal(_)
                        | Expr::CaseInsensitive(_)
                        | Expr::Replay(_)
                        | Expr::Any
                        | Expr::CharRange(_, _)
                        | Expr::Except(_)
                        | Expr::Character { .. }
                        | Expr::Grapheme
                );
            let transparent = self.token_mode.is_some()
                && matches!(expression, Expr::StopConsume(_) | Expr::StopInvert(_));
            let failed_matched = self.matched_position;
            self.restore(checkpoint);
            if self.resource_failure.is_none() {
                if leaf_reset {
                    self.matched_position = self.position;
                } else if transparent {
                    self.matched_position = failed_matched;
                }
                // Java Chain sets this on its owner before beginning the local
                // transaction, so syntax failure retains it until an enclosing
                // transaction rolls back. Resource failures keep full rollback.
                if self.token_mode.is_some()
                    && matches!(
                        expression,
                        Expr::Sequence(_)
                            | Expr::Number
                            | Expr::Identifier
                            | Expr::Quoted(_)
                            | Expr::CodeStart
                            | Expr::CodeEnd
                            | Expr::CaptureBoundary(_, _)
                            | Expr::InputCaptureBoundary(_, _)
                    )
                {
                    self.match_cursor_policy.reset = false;
                }
                if self.token_mode.is_some()
                    && (classic_collector_label(expression).is_some()
                        || matches!(
                            expression,
                            Expr::CaptureBoundary(_, _)
                                | Expr::InputCaptureBoundary(_, _)
                                | Expr::Rule(_)
                                | Expr::MatchOnly(_)
                                | Expr::InvertMatch(_)
                                | Expr::JavaLookahead { .. }
                                | Expr::JavaUntil(_)
                                | Expr::JavaEmpty
                                | Expr::Eof
                                | Expr::Lookahead { .. }
                        ))
                {
                    self.fail_at(
                        self.position.max(self.matched_position),
                        classic_collector_label(expression).unwrap_or("parser"),
                    );
                }
            }
        } else {
            if self.token_mode.is_some()
                && (classic_collector_label(expression).is_some()
                    || matches!(
                        expression,
                        Expr::CaptureBoundary(_, _)
                            | Expr::InputCaptureBoundary(_, _)
                            | Expr::MatchOnly(_)
                            | Expr::InvertMatch(_)
                            | Expr::Rule(_)
                            | Expr::JavaUntil(_)
                    ))
            {
                if let Some(fragment) = &result {
                    self.collect_classic_trace(checkpoint.token_trace.leaves, &fragment.nodes);
                }
            }
            if self.token_mode.is_none() {
                self.trace_expression(expression, checkpoint.position);
            }
            self.commit_checkpoint(checkpoint);
        }
        // A Java transaction discards its local cursor policy on both outcomes.
        if java_transaction {
            self.match_cursor_policy = parent_policy;
        }
        self.expression_depth -= 1;
        result
    }

    #[inline(never)]
    fn expression_with_lexical_context(
        &mut self,
        child: &Expr,
        terminals: &Arc<[lexing::Terminal]>,
        depth: usize,
    ) -> Option<Fragment> {
        let previous = self.lexical_context.replace(Arc::clone(terminals));
        let result = self.expression(child, depth);
        self.lexical_context = previous;
        result
    }

    // Keep the saved lexical expression out of the recursive dispatch frame.
    // Otherwise its stack storage is reserved even for plain Rule references,
    // which can exhaust a test thread's stack before the depth-256 diagnostic.
    #[inline(never)]
    fn expression_with_lexical_trivia(
        &mut self,
        child: &Expr,
        definition: &lexical::LexicalExpression,
        depth: usize,
    ) -> Option<Fragment> {
        let previous = self.lexical_trivia.replace(definition.clone());
        let previous_whitespace = std::mem::replace(&mut self.whitespace, false);
        let result = self.expression(child, depth);
        self.whitespace = previous_whitespace;
        self.lexical_trivia = previous;
        result
    }

    #[inline(never)]
    fn sequence(&mut self, elements: &[Expr], depth: usize) -> Option<Fragment> {
        let mut result = Fragment::default();
        let owner_start = self.position;
        self.sequence_trivia(&mut result, owner_start, depth)?;
        for element in elements {
            if self.excludes(element, depth) {
                return None;
            }
            let child_start = self.position;
            let fragment = self.expression(element, depth)?;
            self.append_classic_fragment(
                &mut result,
                fragment,
                self.span(child_start),
                self.code_point(owner_start),
            );
            self.sequence_trivia(&mut result, owner_start, depth)?;
        }
        Some(result)
    }

    #[inline(never)]
    fn non_ordered(&mut self, children: &[Expr], depth: usize) -> Option<Fragment> {
        let mut selected = vec![false; children.len()];
        let mut result = Fragment::default();
        let owner_start = self.position();
        for _ in 0..children.len() {
            let mut found = false;
            for (index, child) in children.iter().enumerate() {
                if selected[index] {
                    continue;
                }
                let child_start = self.position;
                let checkpoint = self
                    .token_mode
                    .is_some()
                    .then(|| Box::new(self.checkpoint()));
                if let Some(fragment) = self.expression(child, depth) {
                    self.finish_classic_candidate(checkpoint, true);
                    selected[index] = true;
                    self.append_classic_fragment(
                        &mut result,
                        fragment,
                        self.span(child_start),
                        owner_start,
                    );
                    found = true;
                    break;
                }
                self.finish_classic_candidate(checkpoint, false);
                if self.resource_failure.is_some() {
                    return None;
                }
            }
            if !found {
                return None;
            }
        }
        Some(result)
    }

    fn expression_inner(&mut self, expression: &Expr, _depth: usize) -> Option<Fragment> {
        match expression {
            Expr::MatchOnly(_)
            | Expr::InvertMatch(_)
            | Expr::StopInvert(_)
            | Expr::StopConsume(_)
            | Expr::Character { .. } => unreachable!("Classic expression dispatched by expression"),
            Expr::Custom(_) | Expr::CustomWith { .. } => {
                unreachable!("recursive expression dispatched by expression")
            }
            Expr::Lexical(label, expression) => {
                let end = match &mut self.lexing {
                    Some(session) => session.match_at(
                        label,
                        false,
                        Some(expression),
                        self.position,
                        self.lexical_context.as_ref(),
                    ),
                    None => match &self.lexical_context {
                        Some(goal) => {
                            lexing::match_goal(self.input, self.position, label, false, goal)
                        }
                        None => expression.match_at(self.input, self.position),
                    },
                };
                if let Some(end) = end {
                    if end != self.position {
                        self.matched_position = end;
                    }
                    self.position = end;
                    Some(Fragment::default())
                } else {
                    self.fail(label);
                    None
                }
            }
            Expr::Backreference(name) | Expr::Replay(name) => {
                let text = if matches!(expression, Expr::Replay(_)) {
                    self.captured_text(name)
                } else {
                    self.captured(name)
                };
                if let Some(text) = text.filter(|text| self.remaining().starts_with(text)) {
                    self.position += text.len();
                    self.matched_position = self.position;
                    Some(Fragment::default())
                } else {
                    if matches!(expression, Expr::Replay(_)) {
                        self.fail(&format!("SAME_AS({name})"));
                    } else {
                        self.fail(name);
                    }
                    None
                }
            }
            Expr::Empty => Some(Fragment::default()),
            Expr::JavaEmpty => {
                if let Some(next) = self.input[self.matched_position..].chars().next() {
                    self.matched_position += next.len_utf8();
                }
                Some(Fragment::default())
            }
            Expr::JavaLookahead { pattern, positive } => {
                // Java WordParser rejects a zero-length word; this differs from Literal("").
                let matched =
                    !pattern.is_empty() && self.input[self.matched_position..].starts_with(pattern);
                if matched == *positive {
                    if *positive {
                        self.matched_position += pattern.len();
                    }
                    Some(Fragment::default())
                } else {
                    self.fail_at(
                        self.matched_position,
                        if *positive {
                            pattern
                        } else {
                            "negative lookahead"
                        },
                    );
                    None
                }
            }
            Expr::JavaUntil(terminator) => {
                loop {
                    if !terminator.is_empty()
                        && self.input[self.matched_position..].starts_with(terminator)
                    {
                        self.matched_position += terminator.len();
                        break;
                    }
                    let Some(next) = self.input[self.position..].chars().next() else {
                        break;
                    };
                    self.position += next.len_utf8();
                    self.matched_position = self.position;
                }
                Some(Fragment::default())
            }
            Expr::Eof => {
                if self.position == self.input.len() {
                    Some(Fragment::default())
                } else {
                    self.fail("end of input");
                    None
                }
            }
            Expr::Error(message) => {
                if strip_capture(message).is_empty() {
                    self.fail_without_expected();
                } else {
                    self.fail(message);
                }
                None
            }
            Expr::Recovery { .. } => unreachable!("recursive expression dispatched by expression"),
            Expr::Grapheme => {
                let boundaries = self
                    .grapheme_boundaries
                    .get_or_insert_with(|| grapheme::Boundaries::new(self.input));
                if let Some(end) = boundaries.next_utf8(self.position) {
                    self.position = end;
                    self.matched_position = end;
                    Some(Fragment::default())
                } else {
                    self.fail("grapheme cluster");
                    None
                }
            }

            Expr::Any | Expr::CharRange(_, _) | Expr::Except(_) => {
                let next = self.input[self.position..].chars().next();
                let accepted = next.is_some_and(|c| match expression {
                    Expr::CharRange(min, max) => *min <= c && c <= *max,
                    Expr::Except(excluded) => !excluded.contains(c),
                    _ => true,
                });
                if accepted {
                    self.position += next.expect("accepted character").len_utf8();
                    self.matched_position = self.position;
                    Some(Fragment::default())
                } else {
                    self.fail("character");
                    None
                }
            }
            Expr::Until(terminator) => {
                if let Some(length) = self.input[self.position..].find(terminator) {
                    self.position += length;
                    self.matched_position = self.position;
                    Some(Fragment::default())
                } else {
                    self.fail(terminator);
                    None
                }
            }
            Expr::Optional(_) | Expr::JavaOptional(_) => {
                unreachable!("recursive expression dispatched by expression")
            }
            Expr::Repeat { .. } | Expr::JavaRepeat { .. } | Expr::ZeroMatch(_) => {
                unreachable!("recursive expression dispatched by expression")
            }
            Expr::Lookahead { .. } => unreachable!("recursive expression dispatched by expression"),
            Expr::CaseInsensitive(word) => {
                let mut input = self.input[self.position..].chars();
                let mut bytes = 0;
                let matched = !word.is_empty()
                    && word.chars().all(|expected| {
                        input.next().is_some_and(|actual| {
                            bytes += actual.len_utf8();
                            unicode_case_data::key(expected) == unicode_case_data::key(actual)
                        })
                    });
                if matched {
                    self.position += bytes;
                    self.matched_position = self.position;
                    Some(Fragment::default())
                } else {
                    self.fail(word);
                    None
                }
            }
            Expr::Literal(literal) => {
                let end = match &mut self.lexing {
                    Some(session) => session.match_at(
                        literal,
                        true,
                        None,
                        self.position,
                        self.lexical_context.as_ref(),
                    ),
                    None => match &self.lexical_context {
                        Some(goal) => {
                            lexing::match_goal(self.input, self.position, literal, true, goal)
                        }
                        None => self.input[self.position..]
                            .starts_with(literal)
                            .then_some(self.position + literal.len()),
                    },
                };
                if let Some(end) = end {
                    self.position = end;
                    self.matched_position = self.position;
                    Some(Fragment::default())
                } else {
                    self.fail(literal);
                    None
                }
            }
            Expr::Number => {
                let accepted = self.number();
                if accepted {
                    self.matched_position = self.position;
                }
                accepted.then(Fragment::default)
            }
            Expr::Identifier => {
                let bytes = self.input.as_bytes();
                if !bytes
                    .get(self.position)
                    .is_some_and(|c| c.is_ascii_alphabetic() || *c == b'_')
                {
                    self.fail("identifier");
                    return None;
                }
                let first = self.position;
                self.position += 1;
                self.trace_atom("AlphabetUnderScoreParser", first);
                let rest = self.position;
                while bytes
                    .get(self.position)
                    .is_some_and(|c| c.is_ascii_alphanumeric() || *c == b'_')
                {
                    let start = self.position;
                    self.position += 1;
                    self.trace_atom("AlphabetNumericUnderScoreParser", start);
                }
                if rest == self.position {
                    self.trace_atom("ZeroOrMore", rest);
                }
                self.matched_position = self.position;
                Some(Fragment::default())
            }
            Expr::CodeStart => {
                if self.code_start() {
                    Some(Fragment::default())
                } else {
                    None
                }
            }
            Expr::CodeEnd => {
                if self.code_end() {
                    Some(Fragment::default())
                } else {
                    None
                }
            }
            Expr::LongCodeBlock => {
                if let Some(end) = long_code_fence::end(self.input, self.position) {
                    self.position = end;
                    self.matched_position = end;
                    Some(Fragment::default())
                } else {
                    self.fail("long code block");
                    None
                }
            }
            Expr::Quoted(quote) => {
                if !matches!(quote, '\'' | '"') {
                    self.fail("single or double quote delimiter");
                    return None;
                }
                if !self.remaining().starts_with(*quote) {
                    self.fail("opening quote");
                    return None;
                }
                let label = if *quote == '\'' {
                    "SingleQuoteParser"
                } else {
                    "DoubleQuoteParser"
                };
                let start = self.position;
                self.position += quote.len_utf8();
                self.trace_atom(label, start);
                let body = self.position;
                loop {
                    let Some(next) = self.remaining().chars().next() else {
                        self.fail("closing quote");
                        return None;
                    };
                    let start = self.position;
                    if next == *quote && start == body {
                        self.trace_atom("ZeroOrMore", body);
                    }
                    self.position += next.len_utf8();
                    self.trace_atom(
                        if next == '\\' {
                            "BackSlashParser"
                        } else {
                            label
                        },
                        start,
                    );
                    if next == *quote {
                        self.matched_position = self.position;
                        return Some(Fragment::default());
                    }
                    if next == '\\' {
                        let Some(escaped) = self.remaining().chars().next() else {
                            self.fail("closing quote");
                            return None;
                        };
                        let start = self.position;
                        self.position += escaped.len_utf8();
                        self.trace_atom("WildCardStringParser", start);
                    }
                }
            }
            Expr::Rule(_) => unreachable!("rule references are dispatched by expression"),
            Expr::Sequence(_)
            | Expr::NonOrdered(_)
            | Expr::Choice(_)
            | Expr::LongestChoice(_)
            | Expr::UniqueLongestChoice(_)
            | Expr::NameResolutionScope { .. }
            | Expr::NamePredicate { .. }
            | Expr::PredictiveChoice { .. } => {
                unreachable!("combinators are dispatched by expression")
            }

            Expr::Capture(_, _) | Expr::OwnedCapture(_, _) | Expr::CaptureScope(_) => {
                unreachable!("recursive expression dispatched by expression")
            }
            Expr::RuleEffects { .. } => {
                unreachable!("recursive expression dispatched by expression")
            }
            Expr::CaptureEquality { .. } => {
                unreachable!("recursive expression dispatched by expression")
            }
            Expr::LexicalContextScope { .. }
            | Expr::LexicalTriviaScope { .. }
            | Expr::TriviaScope { .. } => {
                unreachable!("trivia scopes are dispatched by expression")
            }
            Expr::TextValue(_)
            | Expr::ValueBoundary(_)
            | Expr::CaptureBoundary(_, _)
            | Expr::InputCaptureBoundary(_, _) => {
                unreachable!("recursive expression dispatched by expression")
            }
        }
    }

    #[inline(never)]
    fn expression_custom(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::Custom(parser) | Expr::CustomWith { parser, .. } => {
                if self.call_depth.max(depth) >= 256 {
                    self.resource_error("parser calls below 256");
                    return None;
                }
                let tracing = self.token_trace.is_some();
                if tracing {
                    self.trace_custom_contracts.push(false);
                }
                // A callback can re-enter a grammar through any public parser entry.
                // Carry the active rule depth across that API boundary.
                let previous_depth = self.call_depth;
                self.call_depth = self.call_depth.max(depth) + 1;
                let result = self.transaction(|context| parser(context));
                self.call_depth = previous_depth;
                if tracing && !self.trace_custom_contracts.pop().unwrap() && result.is_ok() {
                    self.unsupported_trace(
                        "custom parser without explicit terminal trace contract",
                    );
                }
                result.ok().map(|matched| {
                    let info = matched
                        .nodes
                        .first()
                        .and_then(|index| self.tokens.values.get(index));
                    let classic = self.token_mode.is_some()
                        || matched.nodes.iter().any(|index| {
                            self.tokens
                                .values
                                .get(index)
                                .is_some_and(|info| info.classic)
                        });
                    let source = classic.then(|| {
                        Box::new(FragmentSource {
                            span: matched.span,
                            kind: info.map_or(self.token_mode().kind, |info| info.kind),
                            classic: true,
                            generated: info.is_some_and(|info| {
                                matches!(info.source, token::TokenSource::Generated { .. })
                            }),
                            text: info.and_then(|info| info.source.owned_shared()),
                        })
                    });
                    Fragment {
                        nodes: matched.nodes,
                        captures: matched.captures,
                        source,
                    }
                })
            }
            _ => unreachable!("recursive expression dispatched by variant"),
        }
    }

    #[inline(never)]
    fn expression_recovery(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::Recovery {
                child,
                mode,
                tokens,
                message,
            } => {
                if tokens.iter().any(|token| token.is_empty()) {
                    self.fail("nonempty recovery token");
                    return None;
                }
                if tokens
                    .iter()
                    .enumerate()
                    .any(|(index, token)| tokens[..index].contains(token))
                {
                    self.fail("unique recovery token");
                    return None;
                }
                let start = self.position;
                let checkpoint = self
                    .token_mode
                    .is_some()
                    .then(|| Box::new(self.checkpoint()));
                if let Some(fragment) = self.expression(child, depth) {
                    self.finish_classic_candidate(checkpoint, true);
                    return Some(fragment);
                }
                self.finish_classic_candidate(checkpoint, false);
                if self.token_mode.is_some() {
                    let token_kind = self.token_mode().kind;
                    let active = self.mode_cursor();
                    self.position = active;
                    let end = self.recovery_end(*mode, tokens);
                    self.position = start;
                    let end = end?;
                    if token_kind.is_match_only() {
                        self.matched_position = end;
                        return Some(Fragment::default());
                    }
                    let span = Span {
                        start: self.code_point(active),
                        end: self.code_point(end),
                    };
                    let target = self.position() + span.end - span.start;
                    self.position = *self.byte_offsets.get(target)?;
                    self.matched_position = self.position;
                    let id = self.add_token_node(
                        RECOVERY_ERROR_RULE,
                        token_kind,
                        token::TokenSource::Input(span),
                    )?;
                    self.recoveries.push(RecoveryDiagnostic {
                        span,
                        message,
                        node: id.index(),
                    });
                    self.mark_classic_node(
                        id.index(),
                        &FragmentSource {
                            span,
                            kind: token_kind,
                            classic: true,
                            generated: false,
                            text: None,
                        },
                    );
                    self.unsupported_trace("recovery");
                    return Some(Fragment {
                        nodes: vec![id.index()],
                        captures: Vec::new(),
                        source: Some(Box::new(FragmentSource {
                            span,
                            kind: token_kind,
                            classic: true,
                            generated: false,
                            text: None,
                        })),
                    });
                }
                let end = self.recovery_end(*mode, tokens)?;
                self.position = end;
                self.matched_position = end;
                let span = Span {
                    start: self.code_point(start),
                    end: self.code_point(end),
                };
                let node = self.nodes.len();
                self.nodes.push(Node {
                    rule: RECOVERY_ERROR_RULE,
                    span,
                    children: Vec::new(),
                    captures: Vec::new(),
                });
                self.recoveries.push(RecoveryDiagnostic {
                    span,
                    message,
                    node,
                });
                Some(Fragment {
                    nodes: vec![node],
                    captures: Vec::new(),
                    source: None,
                })
            }
            _ => unreachable!("recursive expression dispatched by variant"),
        }
    }

    #[inline(never)]
    fn expression_optional(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::Optional(child) | Expr::JavaOptional(child) => {
                let start = self.position;
                let count = self.nodes.len();
                let attempt = if self.excludes(child, depth) {
                    None
                } else {
                    self.expression(child, depth)
                };
                match attempt {
                    Some(fragment) => Some(fragment),
                    None => {
                        self.position = start;
                        self.nodes.truncate(count);
                        if matches!(expression, Expr::JavaOptional(_)) {
                            self.java_failed_atom(child);
                            if self.token_mode.is_some() {
                                return Some(Fragment::default());
                            }
                            self.trace_empty("Optional");
                        }
                        Some(Fragment::default())
                    }
                }
            }
            _ => unreachable!("recursive expression dispatched by variant"),
        }
    }

    #[inline(never)]
    fn expression_repeat(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        let (child, min, max, java, probe) = match expression {
            Expr::Repeat { child, min, max } | Expr::JavaRepeat { child, min, max } => (
                child.as_ref(),
                *min,
                *max,
                matches!(expression, Expr::JavaRepeat { .. }),
                false,
            ),
            Expr::ZeroMatch(child) => (child.as_ref(), 0, Some(0), true, true),
            _ => unreachable!("repetition dispatched by variant"),
        };
        if max.is_some_and(|max| max < min) {
            self.fail_at(
                self.position.max(self.matched_position),
                "valid repetition bounds",
            );
            return None;
        }
        let mut result = Fragment::default();
        let owner_start = self.position();
        let mut iterations = 0;
        while (probe && iterations == 0) || max.is_none_or(|max| iterations < max) {
            let start = self.position;
            let progress_start = if self.token_mode.is_some() {
                self.mode_cursor()
            } else {
                start
            };
            let count = self.nodes.len();
            // A skipped body takes the failure branch, including Java's
            // failed-atom cursor reset, exactly as an evaluated failure would.
            let attempt = if self.excludes(child, depth) {
                None
            } else {
                self.expression(child, depth)
            };
            match attempt {
                Some(fragment) => {
                    let progress_end = if self.token_mode.is_some() {
                        self.mode_cursor()
                    } else {
                        self.position
                    };
                    if progress_end == progress_start && max.is_none() && !java {
                        self.nodes.truncate(count);
                        self.fail("progress in unbounded repetition");
                        return None;
                    }
                    iterations += 1;
                    self.append_classic_fragment(
                        &mut result,
                        fragment,
                        self.span(start),
                        owner_start,
                    );
                    if java
                        && (if self.token_mode.is_some() {
                            self.mode_cursor()
                        } else {
                            self.position
                        }) == progress_start
                    {
                        break;
                    }
                }
                None => {
                    self.position = start;
                    self.nodes.truncate(count);
                    if java {
                        self.java_failed_atom(child);
                    }
                    break;
                }
            }
        }
        if java && iterations == 0 && min == 0 {
            if self.token_mode.is_some() {
                return Some(Fragment::default());
            }
            self.trace_empty("ZeroOrMore");
        }
        let accepted = iterations >= min && max.is_none_or(|max| iterations <= max);
        if !accepted && self.token_mode.is_some() {
            self.fail_at(self.byte_offsets[owner_start], "repetition bounds");
        }
        accepted.then_some(result)
    }

    #[inline(never)]
    fn expression_lookahead(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::Lookahead { child, positive } => {
                let checkpoint = self.checkpoint();
                let farthest = self.farthest;
                let classic_diagnostics = (self.classic_max_reached, self.classic_failure_seen);
                let expected = self.expected.clone();
                let diagnostic_frames = self.diagnostic_frames.clone();
                let previous = self.token_mode;
                if previous.is_some() {
                    self.token_mode = Some(token::ParseMode::default());
                }
                let matched = self.expression(child, depth).is_some();
                self.token_mode = previous;
                self.restore(checkpoint);
                // Successful assertions discard speculative diagnostics. A failed
                // negative assertion retains failures explored inside its matching child.
                if matched == *positive {
                    self.farthest = farthest;
                    (self.classic_max_reached, self.classic_failure_seen) = classic_diagnostics;
                    self.expected = expected;
                    self.diagnostic_frames = diagnostic_frames;
                }
                if matched == *positive {
                    Some(Fragment::default())
                } else {
                    if !*positive {
                        self.fail("negative lookahead");
                    }
                    None
                }
            }
            _ => unreachable!("recursive expression dispatched by variant"),
        }
    }

    #[inline(never)]
    fn expression_capture(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        let (name, child, owned) = match expression {
            Expr::Capture(name, child) => (*name, child.as_ref(), false),
            Expr::OwnedCapture(name, child) => (*name, child.as_ref(), true),
            _ => unreachable!("capture dispatched by variant"),
        };
        let start = self.position;
        let anchor = self.code_point(self.mode_cursor());
        let fragment = self.expression(child, depth)?;
        self.finish_capture(fragment, name, start, anchor, owned)
    }

    // Source construction and binding publication must not enlarge the recursive
    // capture frame: 1024 nested wrappers still fail safely on a 2 MiB stack.
    #[inline(never)]
    fn finish_capture(
        &mut self,
        mut fragment: Fragment,
        name: &'static str,
        start: usize,
        anchor: usize,
        owned: bool,
    ) -> Option<Fragment> {
        let span = fragment
            .source
            .as_deref()
            .filter(|source| source.classic)
            .map_or_else(|| self.span(start), |source| source.span);
        if !owned {
            // Keep the existing AST field contract and its input-only metadata.
            if fragment
                .source
                .as_deref()
                .is_some_and(|source| source.text.is_some() || source.generated)
            {
                self.fail("AST field capture requires original input source; use capture_owned for general replay");
                return None;
            }
            self.captures.push(name, span);
            fragment.captures.push(Capture {
                name,
                span,
                nodes: fragment.nodes.clone(),
            });
            return Some(fragment);
        }
        let source = match fragment.source.as_deref().filter(|source| source.classic) {
            Some(source) if source.generated => token::TokenSource::Generated {
                anchor: source.span.start,
                text: source.text.as_deref().unwrap_or("").to_owned(),
            },
            Some(source) if source.text.is_some() => token::TokenSource::Projected {
                span,
                text: Arc::clone(source.text.as_ref().unwrap()),
            },
            Some(source) if source.kind.is_virtual() && span.start == span.end => {
                token::TokenSource::Generated {
                    anchor,
                    text: String::new(),
                }
            }
            _ => token::TokenSource::Input(span),
        };
        self.owned_captures.push(name, source);
        self.mark_memoization_state_changed();
        Some(fragment)
    }

    #[inline(never)]
    fn expression_capture_scope(&mut self, child: &Expr, depth: usize) -> Option<Fragment> {
        let mark = self.owned_captures.checkpoint();
        let result = self.expression(child, depth);
        self.owned_captures.rollback_checkpoint(mark);
        result
    }

    #[inline(never)]
    fn expression_rule_effects(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::RuleEffects { child, effects } => {
                if effects.scope_mode.is_some() {
                    self.scopes_mut().enter();
                }
                let fragment = self.expression(child, depth)?;
                if effects.scope_mode.is_some() {
                    self.scopes_mut().leave();
                }
                self.apply_rule_effects(&fragment, effects);
                Some(fragment)
            }
            _ => unreachable!("recursive expression dispatched by variant"),
        }
    }

    #[inline(never)]
    fn apply_rule_effects(&mut self, fragment: &Fragment, effects: &RuleEffects) {
        if let Some(declaration) = &effects.declares {
            for capture in fragment
                .captures
                .iter()
                .filter(|c| c.name == declaration.symbol_capture)
            {
                if let Some((name, offset)) = self.symbol_capture(capture.span) {
                    self.scopes_mut().declare(name, offset);
                }
            }
        }
        if let Some(target) = effects.backref {
            for capture in fragment.captures.iter().filter(|c| c.name == target) {
                if let Some((name, offset)) = self.symbol_capture(capture.span) {
                    let length = name.chars().count();
                    self.scopes_mut().add_reference(name, offset, length);
                    if !self.scopes.is_declared(name) {
                        self.scopes_mut().add_diagnostic(
                            &format!("未定義のシンボル: '{name}'"),
                            offset,
                            length,
                            Severity::Warning,
                        );
                    }
                }
            }
        }
    }

    #[inline(never)]
    fn expression_capture_equality(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::CaptureEquality { child, name } => {
                let fragment = self.expression(child, depth)?;
                self.apply_capture_equality(&fragment, name);
                Some(fragment)
            }
            _ => unreachable!("recursive expression dispatched by variant"),
        }
    }

    #[inline(never)]
    fn apply_capture_equality(&mut self, fragment: &Fragment, name: &str) {
        let mut first: Option<String> = None;
        for capture in fragment
            .captures
            .iter()
            .filter(|capture| capture.name == name)
        {
            let raw = self
                .text(capture.span)
                .expect("capture span belongs to source");
            let leading = raw.chars().take_while(|ch| *ch <= '\u{20}').count();
            let value = raw.trim_matches(|ch| ch <= '\u{20}').to_owned();
            let offset = capture.span.start + leading;
            let length = value.chars().count();
            if let Some(expected) = &first {
                if expected != &value {
                    self.scopes_mut().add_diagnostic(
                        &format!(
                            "back-reference mismatch: expected '{expected}' but got '{value}'"
                        ),
                        offset,
                        length,
                        Severity::Error,
                    );
                }
            } else {
                first = Some(value);
            }
        }
    }

    #[inline(never)]
    fn expression_value_boundary(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::TextValue(child)
            | Expr::ValueBoundary(child)
            | Expr::CaptureBoundary(_, child)
            | Expr::InputCaptureBoundary(_, child) => {
                let start = self.position;
                let fragment = self.expression(child, depth)?;
                if matches!(expression, Expr::InputCaptureBoundary(_, _))
                    && fragment
                        .source
                        .as_deref()
                        .is_some_and(|source| source.text.is_some() || source.generated)
                {
                    self.fail("AST field capture requires original input source; use capture_owned for general replay");
                    return None;
                }
                if let Expr::CaptureBoundary(kind, _) | Expr::InputCaptureBoundary(kind, _) =
                    expression
                {
                    if self.token_mode.is_some() {
                        let fragment = self.classic_meta_node(
                            fragment,
                            self.token_mode().kind,
                            "CaptureBoundaryParser",
                        );
                        self.nodes[fragment.nodes[0]].rule = kind.rule();
                        return Some(fragment);
                    }
                }
                let node_id = self.nodes.len();
                self.nodes.push(Node {
                    rule: if let Expr::CaptureBoundary(kind, _)
                    | Expr::InputCaptureBoundary(kind, _) = expression
                    {
                        kind.rule()
                    } else if matches!(expression, Expr::TextValue(_)) {
                        TEXT_VALUE_RULE
                    } else {
                        VALUE_BOUNDARY_RULE
                    },
                    span: fragment
                        .source
                        .as_deref()
                        .map_or_else(|| self.span(start), |source| source.span),
                    children: fragment.nodes,
                    captures: vec![],
                });
                if let Some(source) = fragment.source.as_deref() {
                    self.mark_classic_node(node_id, source);
                }
                Some(Fragment {
                    nodes: vec![node_id],
                    captures: fragment.captures,
                    source: fragment.source,
                })
            }
            _ => unreachable!("recursive expression dispatched by variant"),
        }
    }

    fn symbol_capture(&self, span: Span) -> Option<(&'a str, usize)> {
        let raw = self.text(span)?;
        let leading = raw.chars().take_while(|c| *c <= '\u{20}').count();
        let name = raw.trim_matches(|c| c <= '\u{20}');
        (!name.is_empty()).then_some((name, span.start + leading))
    }

    fn code_start(&mut self) -> bool {
        if !self.at_start_of_line() {
            self.fail("start of line");
            return false;
        }
        if !self.consume_code_fence() || !self.consume_ascii_identifier("scheme") {
            return false;
        }
        if !self.remaining().starts_with(':') {
            self.fail(":");
            return false;
        }
        self.position += 1;
        if !self.consume_ascii_identifier("Java class name") {
            return false;
        }
        while self.remaining().starts_with('.') {
            self.position += 1;
            if !self.consume_ascii_identifier("Java class name segment") {
                return false;
            }
        }
        self.consume_end_of_line("opening code fence")
    }

    fn code_end(&mut self) -> bool {
        if !self.at_start_of_line() {
            self.fail("start of line");
            return false;
        }
        self.consume_code_fence() && self.consume_end_of_line("closing code fence")
    }

    fn at_start_of_line(&self) -> bool {
        self.position == 0
            || self
                .input
                .as_bytes()
                .get(self.position - 1)
                .is_some_and(|byte| matches!(byte, b'\r' | b'\n'))
    }

    fn consume_code_fence(&mut self) -> bool {
        if self.remaining().starts_with("```") {
            self.position += 3;
            true
        } else {
            self.fail("```");
            false
        }
    }

    fn consume_ascii_identifier(&mut self, expected: &str) -> bool {
        let bytes = self.input.as_bytes();
        if !bytes
            .get(self.position)
            .is_some_and(|byte| byte.is_ascii_alphabetic() || *byte == b'_')
        {
            self.fail(expected);
            return false;
        }
        self.position += 1;
        while bytes
            .get(self.position)
            .is_some_and(|byte| byte.is_ascii_alphanumeric() || *byte == b'_')
        {
            self.position += 1;
        }
        true
    }

    fn consume_end_of_line(&mut self, expected: &str) -> bool {
        if self.remaining().starts_with("\r\n") {
            self.position += 2;
        } else if self.remaining().starts_with(['\r', '\n']) {
            self.position += 1;
        } else if !self.remaining().is_empty() {
            self.fail(expected);
            return false;
        }
        self.matched_position = self.position;
        true
    }

    fn longest_choice(
        &mut self,
        alternatives: &[Expr],
        depth: usize,
        unique: bool,
    ) -> Option<Fragment> {
        if self.token_mode.is_some() {
            return self.classic_longest_choice(alternatives, depth, unique);
        }
        if unique && !(2..=64).contains(&alternatives.len()) {
            self.unique_choice_failure_seen = true;
            self.fail("2 to 64 unique longest alternatives");
            return None;
        }
        let start = self.position;
        let node_start = self.nodes.len();
        let mut winner: Option<ChoiceWinner> = None;
        let mut ties = 0;
        for alternative in alternatives {
            if self.excludes(alternative, depth) {
                continue;
            }
            let checkpoint = self.checkpoint();
            if let Some(fragment) = self.expression(alternative, depth) {
                let consumed = self.position - start;
                let replaces = winner
                    .as_ref()
                    .is_none_or(|current| consumed > current.position - start);
                if winner
                    .as_ref()
                    .is_some_and(|current| current.position == self.position)
                {
                    ties += 1;
                }
                if replaces {
                    ties = 1;
                    winner = Some(ChoiceWinner {
                        position: self.position,
                        matched_position: self.matched_position,
                        node_start,
                        nodes: self.nodes[node_start..].to_vec(),
                        captures: {
                            let mut captures = self.captures.clone();
                            captures.commit_checkpoint();
                            captures
                        },
                        owned_captures: {
                            let mut captures = self.owned_captures.clone();
                            captures.commit_checkpoint();
                            captures
                        },
                        state: Rc::clone(&self.state),
                        scopes: {
                            let mut scopes = self.scopes.clone();
                            scopes.commit_checkpoint();
                            scopes
                        },
                        recoveries: self.recoveries.clone(),
                        tokens: Rc::clone(&self.tokens),
                        token_trace: self.token_trace.clone(),
                        fragment,
                    });
                }
            }
            self.restore(checkpoint);
        }
        if unique {
            if let Some(winner) = &winner {
                if winner.position == start || ties > 1 {
                    self.unique_choice_failure_seen = true;
                    self.fail_at(
                        winner.position,
                        if winner.position == start {
                            "nonempty unique longest alternative"
                        } else {
                            "unique longest alternative"
                        },
                    );
                    return None;
                }
            }
        }
        winner.map(|mut winner| {
            debug_assert_eq!(self.nodes.len(), winner.node_start);
            self.position = winner.position;
            self.matched_position = winner.matched_position;
            self.nodes.extend(winner.nodes);
            self.captures = winner.captures;
            self.owned_captures = winner.owned_captures;
            self.state = winner.state;
            self.tokens = winner.tokens;
            self.token_trace = winner.token_trace;
            winner
                .scopes
                .retain_journal_entry_count(self.scopes.journal_entries_created());
            winner.scopes.retain_memo_allocator(&self.scopes);
            self.scopes = winner.scopes;
            self.recoveries = winner.recoveries;
            winner.fragment
        })
    }

    fn ordered_choice<'b>(
        &mut self,
        alternatives: impl IntoIterator<Item = &'b Expr>,
        depth: usize,
    ) -> Option<Fragment> {
        let start = self.position;
        let count = self.nodes.len();
        for alternative in alternatives {
            if self.excludes(alternative, depth) {
                continue;
            }
            let checkpoint = self
                .token_mode
                .is_some()
                .then(|| Box::new(self.checkpoint()));
            if self.token_mode.is_some() && self.match_cursor_policy.reset {
                self.matched_position = self.position;
            }
            if let Some(fragment) = self.expression(alternative, depth) {
                self.finish_classic_candidate(checkpoint, true);
                return Some(fragment);
            }
            self.finish_classic_candidate(checkpoint, false);
            self.position = start;
            self.nodes.truncate(count);
        }
        None
    }

    fn predictive_choice(
        &mut self,
        alternatives: &[Expr],
        predictors: &[Predictor],
        depth: usize,
    ) -> Option<Fragment> {
        // With structural FIRST sets the generated predictors, and the retry that guards
        // them against a conservative predictor, are unnecessary (#300).
        if self.token_mode.is_some()
            || alternatives.len() != predictors.len()
            || self.first_sets.is_some()
        {
            return self.ordered_choice(alternatives.iter(), depth);
        }
        let raw = self.remaining();
        let after_trivia = self.prediction_after_trivia(raw);
        let selected = predictors
            .iter()
            .filter(|predictor| predictor.matches(raw, after_trivia))
            .count();
        if selected == 0 || selected == alternatives.len() {
            return self.ordered_choice(alternatives.iter(), depth);
        }

        // A successful predicted path commits exactly like Choice. On failure,
        // retry the ordinary choice so diagnostics and custom ParseContext state
        // remain observationally equivalent even if a predictor is conservative.
        let checkpoint = self.checkpoint();
        if let Some(fragment) = self.ordered_choice(
            alternatives
                .iter()
                .zip(predictors)
                .filter_map(|(alternative, predictor)| {
                    predictor.matches(raw, after_trivia).then_some(alternative)
                }),
            depth,
        ) {
            self.commit_checkpoint(checkpoint);
            return Some(fragment);
        }
        self.restore(checkpoint);
        self.ordered_choice(alternatives.iter(), depth)
    }

    /// Whether `candidate` is proven to fail here from its FIRST set, so it is not evaluated.
    /// Only active with deferred diagnostics: a failed candidate records nothing there, and
    /// the transaction around it restores every other observable (cursor, matched cursor,
    /// CST, captures, scopes and user state). Failure memo entries and hit counters may differ.
    #[inline]
    fn excludes(&mut self, candidate: &Expr, depth: usize) -> bool {
        let Some(table) = &self.first_sets else {
            return false;
        };
        if self.token_mode.is_some() || self.lexical_trivia.is_some() {
            return false;
        }
        if first::of(candidate, table).may_start(&self.input[self.position..], self.whitespace) {
            return false;
        }
        if self.exclusion_audit {
            // The audit evaluation replaces the skipped one (a failed `expression` restores
            // its checkpoint), so nested audits stay linear instead of doubling per level.
            let checkpoint = self.checkpoint();
            let matched = self.expression(candidate, depth).is_some();
            self.restore(checkpoint);
            assert!(
                !matched,
                "FIRST-set exclusion would skip a candidate that matches at byte {}",
                self.position
            );
        }
        true
    }

    fn prediction_after_trivia<'b>(&self, raw: &'b str) -> &'b str {
        if let Some(definition) = &self.lexical_trivia {
            let mut position = 0;
            while let Some(end) = definition.match_at(raw, position) {
                if end <= position {
                    break;
                }
                position = end;
            }
            return &raw[position..];
        }
        if !self.whitespace {
            return raw;
        }
        let mut position = 0;
        loop {
            let start = position;
            while raw
                .as_bytes()
                .get(position)
                .is_some_and(|byte| matches!(byte, b' ' | b'\t' | b'\n' | b'\r' | 11 | 12))
            {
                position += 1;
            }
            let rest = &raw[position..];
            if rest.starts_with("//") {
                position += rest.find(['\r', '\n']).unwrap_or(rest.len());
            } else if let Some(comment) = rest.strip_prefix("/*") {
                if let Some(end) = comment.find("*/") {
                    position += end + 4;
                }
            }
            if position == start {
                return &raw[position..];
            }
        }
    }

    fn recovery_end(&self, mode: RecoveryMode, tokens: &[&str]) -> Option<usize> {
        let remaining = &self.input[self.position..];
        if remaining.is_empty() {
            return None;
        }
        let nearest = tokens
            .iter()
            .filter_map(|token| remaining.find(token).map(|at| (at, token.len())))
            .min_by(|left, right| left.0.cmp(&right.0).then(right.1.cmp(&left.1)));
        let length = match (mode, nearest) {
            (RecoveryMode::Sync, Some((at, token_len))) => at + token_len,
            (RecoveryMode::BeforeSync, Some((at, _))) if at > 0 => at,
            (RecoveryMode::BeforeSync, _) | (RecoveryMode::Sync, None) => return None,
            (RecoveryMode::Skip, Some((at, _))) if at > 0 => at,
            (RecoveryMode::Skip, Some(_)) => remaining.chars().next()?.len_utf8(),
            (RecoveryMode::Skip, None) if tokens.is_empty() => remaining.chars().next()?.len_utf8(),
            (RecoveryMode::Skip, None) => remaining.len(),
        };
        Some(self.position + length)
    }

    fn skip(&mut self) {
        if let Some(definition) = &self.lexical_trivia {
            if let Some(session) = &mut self.lexing {
                let end = session.skip(
                    self.position,
                    self.lexical_context.is_some(),
                    Some(definition),
                );
                if end != self.position {
                    self.position = end;
                    self.matched_position = end;
                }
                return;
            }
            while let Some(end) = definition.match_at(self.input, self.position) {
                if end <= self.position {
                    break;
                }
                self.position = end;
                self.matched_position = end;
            }
            return;
        }
        if !self.whitespace {
            return;
        }
        if let Some(session) = &mut self.lexing {
            let end = session.skip(self.position, self.lexical_context.is_some(), None);
            if end != self.position {
                self.position = end;
                self.matched_position = end;
            }
            return;
        }
        loop {
            let start = self.position;
            while self
                .input
                .as_bytes()
                .get(self.position)
                .is_some_and(|c| matches!(c, b' ' | b'\t' | b'\n' | b'\r' | 11 | 12))
            {
                self.position += 1;
            }
            let rest = &self.input[self.position..];
            if rest.starts_with("//") {
                self.position += rest.find(['\r', '\n']).unwrap_or(rest.len());
            } else if let Some(comment) = rest.strip_prefix("/*") {
                if let Some(end) = comment.find("*/") {
                    self.position += end + 4;
                }
                // An unterminated comment is not trivia and will be rejected by full consumption.
            }
            if self.position == start {
                break;
            }
            self.matched_position = self.position;
        }
    }

    // Java AbstractTokenParser calls consume(0) on a failed consuming atom. Occurs
    // does not wrap that attempt in a child transaction, whereas chain/rule/capture
    // children roll themselves back. Keep this effect confined to UBNF occurrences.
    fn java_failed_atom(&mut self, child: &Expr) {
        match child {
            Expr::StopConsume(inner) => {
                let previous = self.token_mode;
                self.token_mode = Some(token::ParseMode {
                    kind: token::TokenKind::Consumed,
                    ..self.token_mode()
                });
                self.java_failed_atom(inner);
                self.token_mode = previous;
                return;
            }
            Expr::StopInvert(inner) => {
                self.java_failed_atom(inner);
                return;
            }
            _ => {}
        }
        if self.token_mode.is_some_and(|mode| !mode.kind.is_consumed()) {
            return;
        }
        if let Expr::TextValue(child)
        | Expr::CaptureBoundary(_, child)
        | Expr::InputCaptureBoundary(_, child)
        | Expr::ValueBoundary(child)
        | Expr::TriviaScope { child, .. }
        | Expr::LexicalTriviaScope { child, .. }
        | Expr::LexicalContextScope { child, .. } = child
        {
            self.java_failed_atom(child);
            return;
        }
        if matches!(
            child,
            Expr::Literal(_)
                | Expr::CaseInsensitive(_)
                | Expr::Replay(_)
                | Expr::Any
                | Expr::CharRange(_, _)
                | Expr::Except(_)
                | Expr::Character { .. }
        ) {
            self.matched_position = self.position;
        }
    }

    // Matches Java NumberParser: optional sign, decimal, optional complete exponent.
    fn number(&mut self) -> bool {
        let start = self.position;
        self.sign();
        let before = self.digits();
        let mut after = false;
        if self.input.as_bytes().get(self.position) == Some(&b'.') {
            let point = self.position;
            self.position += 1;
            self.trace_atom("PointParser", point);
            after = self.digits();
        }
        if !before && !after {
            self.fail("number");
            self.position = start;
            return false;
        }
        let exponent = self.position;
        let trace = token_trace::Mark::of(self.token_trace.as_ref());
        if self
            .input
            .as_bytes()
            .get(self.position)
            .is_some_and(|c| matches!(c, b'e' | b'E'))
        {
            self.position += 1;
            self.trace_atom("EParser", exponent);
            self.sign();
            if !self.digits() {
                self.position = exponent;
                trace.restore(self.token_trace.as_mut());
                self.trace_atom("Optional", exponent);
            }
        } else {
            self.trace_atom("Optional", exponent);
        }
        true
    }

    fn sign(&mut self) {
        let start = self.position;
        if self
            .input
            .as_bytes()
            .get(self.position)
            .is_some_and(|c| matches!(c, b'+' | b'-'))
        {
            self.position += 1;
            self.trace_atom("SignParser", start);
        } else {
            self.trace_atom("Optional", start);
        }
    }

    fn digits(&mut self) -> bool {
        let start = self.position;
        while self
            .input
            .as_bytes()
            .get(self.position)
            .is_some_and(u8::is_ascii_digit)
        {
            let digit = self.position;
            self.position += 1;
            self.trace_atom("DigitParser", digit);
        }
        self.position > start
    }
}

/// Whether every custom parser in the grammar permits deferred diagnostics.
///
/// Visits all rule expressions, including unreachable rules and nested combinators,
/// without following rule references (recursive grammars therefore terminate).
/// Full-input entry points call this once during preparation when resolving `Auto`,
/// never during parsing or a detailed retry. No result is cached in [`SharedGrammar`].
pub fn grammar_allows_deferred_diagnostics(rules: &[Rule]) -> bool {
    rules
        .iter()
        .all(|rule| expression_allows_deferred_diagnostics(&rule.expression))
}

fn expression_allows_deferred_diagnostics(expression: &Expr) -> bool {
    match expression {
        Expr::Custom(_) | Expr::Character { .. } => false,
        Expr::CustomWith {
            reads_diagnostics,
            replayable,
            ..
        } => !reads_diagnostics && *replayable,
        Expr::Sequence(children)
        | Expr::NonOrdered(children)
        | Expr::Choice(children)
        | Expr::LongestChoice(children)
        | Expr::UniqueLongestChoice(children) => {
            children.iter().all(expression_allows_deferred_diagnostics)
        }
        Expr::PredictiveChoice { alternatives, .. } => alternatives
            .iter()
            .all(expression_allows_deferred_diagnostics),
        Expr::MatchOnly(child)
        | Expr::InvertMatch(child)
        | Expr::StopInvert(child)
        | Expr::StopConsume(child)
        | Expr::OwnedCapture(_, child)
        | Expr::CaptureScope(child)
        | Expr::Capture(_, child)
        | Expr::TextValue(child)
        | Expr::CaptureBoundary(_, child)
        | Expr::InputCaptureBoundary(_, child)
        | Expr::ValueBoundary(child)
        | Expr::Optional(child)
        | Expr::JavaOptional(child)
        | Expr::ZeroMatch(child)
        | Expr::Repeat { child, .. }
        | Expr::JavaRepeat { child, .. }
        | Expr::Lookahead { child, .. }
        | Expr::RuleEffects { child, .. }
        | Expr::CaptureEquality { child, .. }
        | Expr::NamePredicate { child, .. }
        | Expr::NameResolutionScope { child, .. }
        | Expr::Recovery { child, .. }
        | Expr::TriviaScope { child, .. }
        | Expr::LexicalTriviaScope { child, .. }
        | Expr::LexicalContextScope { child, .. } => expression_allows_deferred_diagnostics(child),
        Expr::Lexical(_, _)
        | Expr::Literal(_)
        | Expr::CaseInsensitive(_)
        | Expr::Number
        | Expr::Identifier
        | Expr::Grapheme
        | Expr::CodeStart
        | Expr::CodeEnd
        | Expr::LongCodeBlock
        | Expr::Quoted(_)
        | Expr::Rule(_)
        | Expr::Any
        | Expr::Eof
        | Expr::Empty
        | Expr::CharRange(_, _)
        | Expr::Except(_)
        | Expr::Until(_)
        | Expr::JavaLookahead { .. }
        | Expr::JavaEmpty
        | Expr::JavaUntil(_)
        | Expr::Error(_)
        | Expr::Replay(_)
        | Expr::Backreference(_) => true,
    }
}

fn memo_safe_rules(rules: &[Rule]) -> Vec<bool> {
    memo_safe_rules_for(rules, false)
}

fn memo_success_safe_rules(rules: &[Rule]) -> Vec<bool> {
    memo_safe_rules_for(rules, true)
}

fn memo_safe_rules_for(rules: &[Rule], success: bool) -> Vec<bool> {
    let mut references = vec![Vec::new(); rules.len()];
    let mut safe = rules
        .iter()
        .enumerate()
        .map(|(id, rule)| expression_is_memo_safe(&rule.expression, &mut references[id], success))
        .collect::<Vec<_>>();
    let mut referenced_by = vec![Vec::new(); rules.len()];
    for (parent, children) in references.iter().enumerate() {
        for child in children
            .iter()
            .copied()
            .filter(|child| *child < rules.len())
        {
            referenced_by[child].push(parent);
        }
    }
    let mut unsafe_worklist = safe
        .iter()
        .enumerate()
        .filter_map(|(id, safe)| (!safe).then_some(id))
        .collect::<Vec<_>>();
    while let Some(unsafe_rule) = unsafe_worklist.pop() {
        for parent in referenced_by[unsafe_rule].iter().copied() {
            if safe[parent] {
                safe[parent] = false;
                unsafe_worklist.push(parent);
            }
        }
    }
    safe
}

fn expression_is_memo_safe(expression: &Expr, references: &mut Vec<usize>, success: bool) -> bool {
    if success && matches!(expression, Expr::RuleEffects { .. }) {
        return false; // Successful symbol/listener effects are not replayable.
    }
    match expression {
        Expr::Character { .. } => false,
        Expr::Custom(_)
        | Expr::CustomWith { .. }
        | Expr::Replay(_)
        | Expr::Backreference(_)
        | Expr::OwnedCapture(_, _)
        | Expr::CaptureScope(_)
        | Expr::CaptureEquality { .. }
        | Expr::NamePredicate { .. }
        | Expr::NameResolutionScope { .. }
        | Expr::Recovery { .. } => false,
        Expr::Rule(id) => {
            references.push(*id);
            true
        }
        Expr::Sequence(children)
        | Expr::NonOrdered(children)
        | Expr::Choice(children)
        | Expr::LongestChoice(children)
        | Expr::UniqueLongestChoice(children) => children
            .iter()
            .all(|child| expression_is_memo_safe(child, references, success)),
        Expr::PredictiveChoice { alternatives, .. } => alternatives
            .iter()
            .all(|child| expression_is_memo_safe(child, references, success)),
        Expr::MatchOnly(child)
        | Expr::InvertMatch(child)
        | Expr::StopInvert(child)
        | Expr::StopConsume(child)
        | Expr::Capture(_, child)
        | Expr::TextValue(child)
        | Expr::CaptureBoundary(_, child)
        | Expr::InputCaptureBoundary(_, child)
        | Expr::ValueBoundary(child)
        | Expr::Optional(child)
        | Expr::JavaOptional(child)
        | Expr::ZeroMatch(child)
        | Expr::Repeat { child, .. }
        | Expr::JavaRepeat { child, .. }
        | Expr::Lookahead { child, .. }
        | Expr::RuleEffects { child, .. }
        | Expr::TriviaScope { child, .. }
        | Expr::LexicalTriviaScope { child, .. }
        | Expr::LexicalContextScope { child, .. } => {
            expression_is_memo_safe(child, references, success)
        }
        Expr::Lexical(_, _)
        | Expr::Literal(_)
        | Expr::CaseInsensitive(_)
        | Expr::Number
        | Expr::Identifier
        | Expr::Grapheme
        | Expr::CodeStart
        | Expr::CodeEnd
        | Expr::LongCodeBlock
        | Expr::Quoted(_)
        | Expr::Any
        | Expr::Eof
        | Expr::Empty
        | Expr::CharRange(_, _)
        | Expr::Except(_)
        | Expr::Until(_)
        | Expr::JavaLookahead { .. }
        | Expr::JavaEmpty
        | Expr::JavaUntil(_)
        | Expr::Error(_) => true,
    }
}

/// Shared JSON string escaping for deterministic cross-language AST fixtures.
pub fn json_string(value: &str) -> String {
    let mut result = String::from("\"");
    for character in value.chars() {
        match character {
            '"' => result.push_str("\\\""),
            '\\' => result.push_str("\\\\"),
            '\n' => result.push_str("\\n"),
            '\r' => result.push_str("\\r"),
            '\t' => result.push_str("\\t"),
            c if c < ' ' => result.push_str(&format!("\\u{:04x}", c as u32)),
            c => result.push(c),
        }
    }
    result.push('"');
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    static PREDICTIVE_SKIPPED_CALLS: std::sync::atomic::AtomicUsize =
        std::sync::atomic::AtomicUsize::new(0);

    fn predictive_skipped(context: &mut ParseContext<'_>) -> ParseResult {
        PREDICTIVE_SKIPPED_CALLS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        context.error("skipped");
        Err(context.failure())
    }

    #[test]
    fn capture_stripping_preserves_nonbreaking_spaces_like_java() {
        assert_eq!(strip_capture("\u{001c}\u{3000}value \t"), "value");
        for c in ['\u{0085}', '\u{00a0}', '\u{2007}', '\u{202f}'] {
            let text = format!("{c}value{c}");
            assert_eq!(strip_capture(&text), text);
        }
    }

    #[test]
    fn detailed_errors_separate_trailing_input_from_farthest_failure() {
        let rules = vec![Rule {
            name: "root",
            expression: Expr::Choice(vec![
                Expr::Sequence(vec![
                    Expr::Literal("😀"),
                    Expr::Literal("!"),
                    Expr::Literal("?"),
                ]),
                Expr::Literal("😀"),
            ]),
        }];
        let diagnostic = parse_detailed(&rules, 0, false, "😀!x").unwrap_err();
        assert_eq!(diagnostic.kind, "trailing_input");
        assert_eq!(diagnostic.offset, 1);
        assert_eq!(diagnostic.expected, vec!["end of input"]);
        assert_eq!(diagnostic.farthest.offset, 2);
        assert_eq!(diagnostic.farthest.expected, vec!["?"]);
        assert_eq!(
            parse(&rules, 0, false, "😀!x").unwrap_err(),
            diagnostic.farthest
        );
        assert_eq!(
            diagnostic.canonical_json(),
            r#"{"kind":"trailing_input","offset":1,"expected":["end of input"],"farthestOffset":2,"farthestExpected":["?"]}"#
        );
        let syntax = parse_detailed(&rules, 0, false, "x").unwrap_err();
        assert_eq!(syntax.kind, "syntax");
        assert_eq!(syntax.offset, 0);
        assert_eq!(syntax.expected, vec!["😀"]);
        assert!(parse_detailed(&rules, 0, false, "😀!?").is_ok());
    }

    #[test]
    fn custom_parser_dynamic_expected_remains_owned() {
        fn dynamic_failure(context: &mut ParseContext<'_>) -> ParseResult {
            let expected = format!("dynamic {}", context.remaining());
            Err(context.error(&expected))
        }

        let mut context = ParseContext::new("value");
        let error = context.parse(&Expr::Custom(dynamic_failure)).unwrap_err();
        assert_eq!(error.offset, 0);
        assert_eq!(error.expected, vec!["dynamic value"]);
    }

    #[test]
    fn expected_names_intern_dynamic_and_static_text_once() {
        let mut names = ExpectedNames::default();
        let first = names.intern(&String::from("z"));
        assert_eq!(names.intern("z"), first);
        let second = names.intern("a");
        assert_eq!(names.intern(&String::from("a")), second);
        assert_ne!(first, second);
        for index in 0..128 {
            let name = format!("dynamic {index}");
            let id = names.intern(&name);
            assert_eq!(names.intern(&name.clone()), id);
            assert_eq!(names.name(id), name);
        }
        assert_eq!(names.intern("z"), first);
        assert_eq!(names.intern("a"), second);
        assert_eq!(names.remaining.len(), 129);
    }

    #[test]
    fn dynamic_expected_names_preserve_failure_order_after_rollback() {
        let mut context = ParseContext::new("");
        context.fail("z");
        context.fail(&String::from("a"));
        context.fail("a");
        let result: Result<(), ParseError> = context.transaction(|_| {
            Err(ParseError {
                offset: 0,
                expected: vec!["m".to_owned(), "z".to_owned(), "m".to_owned()],
            })
        });
        assert!(result.is_err());
        context.fail("m");
        assert_eq!(context.expected, vec![0, 1, 2]);
        assert_eq!(context.expected_names.remaining.len(), 2);
        assert_eq!(context.position(), 0);
        assert_eq!(context.failure().expected, vec!["a", "m", "z"]);
        assert_eq!(
            context.failure().to_string(),
            "at code point 0: expected a, m, z"
        );
    }

    #[test]
    fn rollback_unicode_and_diagnostic_offsets() {
        let rules = vec![
            Rule {
                name: "root",
                expression: Expr::Choice(vec![
                    Expr::Sequence(vec![Expr::Rule(1), Expr::Literal("!")]),
                    Expr::Sequence(vec![Expr::Rule(1), Expr::Literal("?")]),
                ]),
            },
            Rule {
                name: "word",
                expression: Expr::Literal("😀"),
            },
        ];
        let tree = parse(&rules, 0, false, "😀?").unwrap();
        assert_eq!(tree.nodes.len(), 2); // failed branch left no CST nodes
        assert_eq!(tree.nodes[tree.root].span, Span { start: 0, end: 2 });
        assert_eq!(tree.text(tree.nodes[0].span), "😀");
        let error = parse(&rules, 0, false, "😀x").unwrap_err();
        assert_eq!(error.offset, 1);
        assert_eq!(error.expected, vec!["!", "?"]);
    }

    #[test]
    fn unicode_spans_match_binary_search_at_every_boundary() {
        for input in ["", "ascii", "éあ😀e\u{301}\r\n\0z", "😀"] {
            let mut context = ParseContext::new(input);
            let boundaries = context.byte_offsets.clone();
            for &end in &boundaries {
                context.position = end;
                for &start in boundaries.iter().take_while(|&&start| start <= end) {
                    assert_eq!(
                        context.span(start),
                        Span {
                            start: boundaries.binary_search(&start).unwrap(),
                            end: boundaries.binary_search(&end).unwrap(),
                        },
                        "{input:?}: {start}..{end}"
                    );
                }
            }
        }
    }

    #[test]
    fn code_point_rejects_non_boundaries_and_out_of_range_offsets() {
        for input in ["", "ascii", "éあ😀e\u{301}"] {
            let context = ParseContext::new(input);
            for byte in (0..=input.len() + 1).chain(std::iter::once(usize::MAX)) {
                if context.byte_offsets.binary_search(&byte).is_err() {
                    assert!(
                        std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                            context.code_point(byte)
                        }))
                        .is_err(),
                        "{input:?}: {byte}"
                    );
                }
            }
        }
    }

    #[test]
    fn predictive_choice_prunes_only_proven_impossible_alternatives() {
        PREDICTIVE_SKIPPED_CALLS.store(0, std::sync::atomic::Ordering::Relaxed);
        let rules = vec![Rule {
            name: "root",
            expression: Expr::PredictiveChoice {
                alternatives: vec![Expr::Custom(predictive_skipped), Expr::Literal("alpha")],
                predictors: vec![Predictor::Literal("beta"), Predictor::Literal("alpha")],
            },
        }];
        assert!(parse(&rules, 0, false, "alpha").is_ok());
        assert_eq!(
            PREDICTIVE_SKIPPED_CALLS.load(std::sync::atomic::Ordering::Relaxed),
            0
        );
    }

    #[test]
    fn predictive_choice_preserves_order_and_retries_full_choice_after_miss() {
        let ordered = vec![Rule {
            name: "root",
            expression: Expr::PredictiveChoice {
                alternatives: vec![Expr::Literal("a"), Expr::Literal("ab")],
                predictors: vec![Predictor::Literal("a"), Predictor::Literal("a")],
            },
        }];
        assert_eq!(parse(&ordered, 0, false, "ab").unwrap_err().offset, 1);

        // Even malformed/manual predictor metadata cannot change acceptance:
        // the selected branch fails and ordinary Choice is retried.
        let fallback = vec![Rule {
            name: "root",
            expression: Expr::PredictiveChoice {
                alternatives: vec![
                    Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("x")]),
                    Expr::Literal("ab"),
                ],
                predictors: vec![Predictor::Literal("a"), Predictor::Literal("z")],
            },
        }];
        assert!(parse(&fallback, 0, false, "ab").is_ok());
    }

    #[test]
    fn predictive_choice_considers_java_trivia_without_consuming_it() {
        let rules = vec![Rule {
            name: "root",
            expression: Expr::PredictiveChoice {
                alternatives: vec![
                    Expr::Sequence(vec![Expr::Literal("alpha")]),
                    Expr::Sequence(vec![Expr::Literal("beta")]),
                ],
                predictors: vec![Predictor::Literal("alpha"), Predictor::Literal("beta")],
            },
        }];
        assert!(parse(&rules, 0, true, " /* lead */ beta").is_ok());
    }

    #[test]
    fn lexical_predictors_match_only_necessary_ascii_starts() {
        let unchanged = "";
        for source in ["0", "+1", "-2", ".5", "-.5", "12."] {
            assert!(Predictor::Number.matches(source, unchanged), "{source}");
        }
        for source in ["", "+", "-.", ".", "x", "١"] {
            assert!(!Predictor::Number.matches(source, unchanged), "{source}");
        }
        for source in ["name", "_name", "Z9"] {
            assert!(Predictor::Identifier.matches(source, unchanged), "{source}");
        }
        for source in ["", "9name", "é"] {
            assert!(
                !Predictor::Identifier.matches(source, unchanged),
                "{source}"
            );
        }
        assert!(Predictor::Quoted('"').matches("\"value\"", unchanged));
        assert!(!Predictor::Quoted('"').matches("'value'", unchanged));
    }

    #[test]
    fn number_full_consumption_and_comments() {
        let rules = vec![Rule {
            name: "root",
            expression: Expr::Sequence(vec![Expr::Number]),
        }];
        for input in ["0", "+1", "-.5", "12.", "1E-2", " /*😀*/ 2 //ok"] {
            assert!(parse(&rules, 0, true, input).is_ok(), "{input}");
        }
        for input in ["", ".", "+", "1e", "1x", "2/*unclosed", "١"] {
            assert!(parse(&rules, 0, true, input).is_err(), "{input}");
        }
    }

    #[test]
    fn recursion_limit_is_a_diagnostic() {
        let rules = vec![Rule {
            name: "cycle",
            expression: Expr::Rule(0),
        }];
        assert!(parse(&rules, 0, false, "").unwrap_err().expected[0].contains("256"));
        let rules = vec![Rule {
            name: "named_cycle",
            expression: Expr::Sequence(vec![Expr::Rule(0)]).lexical_trivia_scope(
                lexical::LexicalExpression {
                    op: lexical::Op::LITERAL,
                    text: " ",
                    min: 0,
                    max: 0,
                    children: vec![],
                },
            ),
        }];
        for memoization in [Memoization::Off, Memoization::SafeFailures] {
            let diagnostic = parse_detailed_with_options(
                &rules,
                0,
                false,
                "",
                ParseOptions::with_memoization(memoization),
            )
            .unwrap_err();
            assert_eq!(diagnostic.farthest.offset, 0);
            assert_eq!(diagnostic.farthest.expected, vec!["rule nesting below 256"]);
        }
    }

    #[test]
    fn resource_failure_is_not_memoized_and_aborts_the_context() {
        let grammar = share_grammar(vec![Rule {
            name: "root",
            expression: Expr::Literal("ok"),
        }]);
        let mut context = ParseContext::with_options(
            "ok",
            ParseOptions::with_memoization(Memoization::SafeFailures),
        );
        context.rules = Arc::clone(&grammar);
        context.memo_safe_rules = memo_safe_rules(&grammar);
        context.expression_depth = 1024;
        assert!(context.rule(0, 1).is_none());
        assert!(context
            .failure()
            .expected
            .iter()
            .any(|value| value.contains("1024")));
        context.expression_depth = 0;
        assert!(context.rule(0, 1).is_none());
        assert_eq!(context.position(), 0);
        assert!(context.failure_memo.first.is_empty());
        assert_eq!(context.memoized_failure_hits(), 0);
    }

    #[test]
    fn rule_depth_and_invalid_id_diagnostics_keep_their_priority() {
        let grammar = share_grammar(vec![Rule {
            name: "root",
            expression: Expr::Literal("unused"),
        }]);
        for memoization in [Memoization::Off, Memoization::SafeFailures] {
            for (id, depth, expected) in [
                (usize::MAX, 255, "valid rule reference"),
                (usize::MAX, 256, "rule nesting below 256"),
                (0, 256, "rule nesting below 256"),
            ] {
                let mut context =
                    ParseContext::with_options("", ParseOptions::with_memoization(memoization));
                context.rules = Arc::clone(&grammar);
                context.memo_safe_rules = memo_safe_rules(&grammar);
                assert!(context.rule(id, depth).is_none());
                assert_eq!(context.failure().expected, vec![expected]);
                assert_eq!(context.memoized_failure_hits(), 0);
                assert!(context.diagnostic_frames.is_empty());
            }
        }
    }

    #[test]
    fn deferred_diagnostics_preserve_memo_keys_hits_and_checkpoint_metrics() {
        // This pins the deferred pass itself; FIRST-set exclusion (#300) legitimately
        // removes memo probes and checkpoints and is covered by its own tests.
        set_candidate_exclusion_for_current_thread(Some(CandidateExclusion::Off));
        let grammar = share_grammar(vec![
            Rule {
                name: "root",
                expression: Expr::choice([Expr::Rule(1), Expr::Rule(1), Expr::literal("😀ok")]),
            },
            Rule {
                name: "fails",
                expression: Expr::literal("😀")
                    .then(Expr::choice([Expr::literal("no"), Expr::literal("never")])),
            },
            Rule {
                name: "unsafe",
                expression: Expr::Backreference("name"),
            },
        ]);
        for input in ["😀ok", "😀bad"] {
            let options = ParseOptions::with_memoization(Memoization::SafeFailures);
            let mut detailed = ParseContext::with_options(input, options);
            let mut deferred = ParseContext::with_options(
                input,
                options.with_diagnostics(Diagnostics::DetailedOnFailure),
            );
            for context in [&mut detailed, &mut deferred] {
                context.rules = Arc::clone(&grammar);
                context.memo_safe_rules = memo_safe_rules(&grammar);
                context.enable_checkpoint_metrics();
                assert_eq!(context.rule(0, 0).is_some(), input == "😀ok");
                assert_eq!(context.memoized_failure_hits(), 1);
            }
            assert_eq!(detailed.memo_safe_rules, deferred.memo_safe_rules);
            assert_eq!(deferred.memo_safe_rules, [true, true, false]);
            assert_eq!(
                detailed.failure_memo.first.len(),
                deferred.failure_memo.first.len()
            );
            for key in detailed.failure_memo.first.keys() {
                let diagnostic = deferred.failure_memo.first.get(key).unwrap();
                assert_eq!(diagnostic.farthest, None);
                assert!(matches!(diagnostic.expected, ExpectedIds::Empty));
            }
            assert_eq!(
                detailed.snapshot_checkpoint_metrics(),
                deferred.snapshot_checkpoint_metrics()
            );
            assert_eq!(deferred.farthest, 0);
            assert!(deferred.expected.is_empty());
            assert!(deferred.expected_names.first.is_none());
            assert!(deferred.diagnostic_frames.is_empty());
            assert_eq!(deferred.diagnostic_frames.capacity(), 0);
            // Replaying even a populated diagnostic is inert in the deferred pass.
            deferred.replay_failure(&FailureDiagnostic {
                farthest: Some(input.len()),
                expected: ExpectedIds::Single(123),
                ..FailureDiagnostic::default()
            });
            assert_eq!(deferred.farthest, 0);
            assert!(deferred.expected.is_empty());
        }
    }

    #[test]
    fn safe_failure_memoization_is_opt_in_and_replays_local_diagnostics() {
        let grammar = share_grammar(vec![
            Rule {
                name: "root",
                expression: Expr::Sequence(vec![
                    Expr::Lookahead {
                        child: Box::new(Expr::Rule(1)),
                        positive: false,
                    },
                    Expr::Rule(1),
                ]),
            },
            Rule {
                name: "r",
                expression: Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("x")]),
            },
        ]);

        let mut off = ParseContext::new("az");
        assert!(off.parse_shared_grammar(&grammar, 0, false).is_err());
        assert_eq!(off.memoized_failure_hits(), 0);

        let mut on = ParseContext::with_options(
            "az",
            ParseOptions::with_memoization(Memoization::SafeFailures),
        );
        let error = on.parse_shared_grammar(&grammar, 0, false).unwrap_err();
        assert_eq!(on.memoized_failure_hits(), 1);
        assert_eq!(error.offset, 1);
        assert_eq!(error.expected, vec!["x"]);
    }

    #[test]
    fn failure_memo_hits_and_misses_across_scalar_bucket_boundaries() {
        let grammar = share_grammar(vec![Rule {
            name: "fails_after_one_scalar",
            expression: Expr::Any.then(Expr::choice([
                Expr::Literal("z"),
                Expr::Literal("!"),
                Expr::Literal("z"),
            ])),
        }]);
        for scalar in ["a", "😀"] {
            let input = scalar.repeat(260);
            let mut context = ParseContext::with_options(
                &input,
                ParseOptions::with_memoization(Memoization::SafeFailures),
            );
            context.rules = Arc::clone(&grammar);
            context.memo_safe_rules = memo_safe_rules(&grammar);
            context.enable_checkpoint_metrics();
            let mut off = ParseContext::new(&input);
            off.rules = Arc::clone(&grammar);
            off.enable_checkpoint_metrics();

            for (attempt, position) in [255, 256, 257, 257, 255, 256].into_iter().enumerate() {
                let byte = context.byte_offsets[position];
                context.position = byte;
                context.matched_position = byte;
                context.farthest = 0;
                context.expected.clear();
                context.diagnostic_frames.push(FailureDiagnostic::default());
                let metrics_before = context.snapshot_checkpoint_metrics();
                assert!(context.rule(0, 0).is_none());
                assert_eq!(context.position, byte);
                assert_eq!(context.matched_position, byte);
                assert!(context.nodes.is_empty());
                assert_eq!(context.memoized_failure_hits(), attempt.saturating_sub(2));
                let diagnostic = context.diagnostic_frames.pop().unwrap();
                assert_eq!(
                    diagnostic.farthest,
                    Some(context.byte_offsets[position + 1])
                );
                assert_eq!(
                    expected_values(&context.expected_names, &diagnostic),
                    vec!["z", "!"]
                );
                assert_eq!(
                    context.failure(),
                    ParseError {
                        offset: position + 1,
                        expected: vec!["!".to_owned(), "z".to_owned()],
                    }
                );
                if attempt < 3 {
                    off.position = byte;
                    off.matched_position = byte;
                    assert!(off.rule(0, 0).is_none());
                    assert_eq!(context.failure(), off.failure());
                    assert_eq!(
                        context.snapshot_checkpoint_metrics(),
                        off.snapshot_checkpoint_metrics()
                    );
                } else {
                    assert_eq!(context.snapshot_checkpoint_metrics(), metrics_before);
                }
            }
            assert_eq!(context.failure_memo.first.len(), 1);
            assert_eq!(context.failure_memo.remaining.len(), 1);
            assert_eq!(context.failure_memo.remaining[0].len(), 2);
        }
    }

    #[test]
    fn failure_memo_allocates_entry_storage_only_for_populated_buckets() {
        let mut memo = FailureMemoBuckets::default();
        let key = FailureMemoKey::new(0, 0, 0, false, 0);
        assert!(memo.get(8, &key).is_none());
        assert_eq!(memo.first.capacity(), 0);
        assert_eq!(memo.remaining.capacity(), 0);

        let mut names = ExpectedNames::default();
        memo.insert(0, key, failure_diagnostic(&mut names, 0, &["first"]));
        assert_eq!(memo.remaining.capacity(), 0);
        let distant_key = FailureMemoKey::new(0, 8 * FAILURE_MEMO_BUCKET_SIZE, 0, false, 0);
        memo.insert(
            8,
            distant_key,
            failure_diagnostic(&mut names, distant_key.position, &["last"]),
        );
        assert!(memo.remaining[..7].iter().all(|map| map.capacity() == 0));
        assert_eq!(
            expected_values(&names, memo.get(0, &key).unwrap()),
            vec!["first"]
        );
        assert_eq!(
            expected_values(&names, memo.get(8, &distant_key).unwrap()),
            vec!["last"]
        );
        assert!(memo.get(7, &distant_key).is_none());
    }

    #[test]
    fn failure_diagnostic_promotes_single_id_and_keeps_first_seen_order() {
        let mut diagnostic = FailureDiagnostic::default();
        assert!(matches!(diagnostic.expected, ExpectedIds::Empty));
        diagnostic.record(2, 7);
        diagnostic.record(2, 7);
        diagnostic.record(1, 9);
        assert!(matches!(diagnostic.expected, ExpectedIds::Single(7)));
        assert_eq!(diagnostic.expected_values(), &[7]);

        diagnostic.record(2, 3);
        diagnostic.record(2, 7);
        diagnostic.record(2, 5);
        assert!(matches!(diagnostic.expected, ExpectedIds::Multiple(_)));
        assert_eq!(diagnostic.expected_values(), &[7, 3, 5]);

        let shared = diagnostic.clone();
        diagnostic.record(3, 9);
        assert_eq!(diagnostic.farthest, Some(3));
        assert!(matches!(diagnostic.expected, ExpectedIds::Single(9)));
        assert_eq!(shared.expected_values(), &[7, 3, 5]);

        let mut other = FailureDiagnostic::default();
        other.record(3, 1);
        diagnostic.merge(&other);
        diagnostic.merge(&other);
        assert_eq!(diagnostic.expected_values(), &[9, 1]);
    }

    #[test]
    fn replay_failure_shares_multiple_ids_until_the_frame_changes() {
        let mut context = ParseContext::new("abc");
        let diagnostic = failure_diagnostic(&mut context.expected_names, 2, &["z", "a", "z"]);
        context.diagnostic_frames.push(FailureDiagnostic::default());
        context.replay_failure(&diagnostic);
        context.replay_failure(&diagnostic);
        let frame = context.diagnostic_frames.last().unwrap();
        let (ExpectedIds::Multiple(frame_ids), ExpectedIds::Multiple(memo_ids)) =
            (&frame.expected, &diagnostic.expected)
        else {
            panic!("multiple expected IDs must retain shared storage");
        };
        assert!(Rc::ptr_eq(frame_ids, memo_ids));
        assert_eq!(frame.expected_values(), &[0, 1]);
        assert_eq!(context.failure().expected, vec!["a", "z"]);

        context.fail_at(2, "m");
        assert_eq!(diagnostic.expected_values(), &[0, 1]);
        assert_eq!(context.failure().expected, vec!["a", "m", "z"]);
        assert_eq!(
            context.diagnostic_frames.last().unwrap().expected_values(),
            &[0, 1, 2]
        );
    }

    fn failure_diagnostic(
        names: &mut ExpectedNames,
        position: usize,
        expected: &[&str],
    ) -> FailureDiagnostic {
        let mut diagnostic = FailureDiagnostic::default();
        for expected in expected {
            diagnostic.record(position, names.intern(expected));
        }
        diagnostic
    }

    fn expected_values<'a>(
        names: &'a ExpectedNames,
        diagnostic: &FailureDiagnostic,
    ) -> Vec<&'a str> {
        diagnostic
            .expected_values()
            .iter()
            .map(|&id| names.name(id))
            .collect()
    }

    fn replay_memoized_failure(
        global: (usize, &[&str]),
        frame: Option<(usize, &[&str])>,
        memoized: (usize, &[&str]),
    ) -> ParseContext<'static> {
        let rules = share_grammar(vec![Rule {
            name: "memoized",
            expression: Expr::Literal("unused"),
        }]);
        let mut context = ParseContext::with_options(
            "",
            ParseOptions::with_memoization(Memoization::SafeFailures),
        );
        context.rules = rules;
        context.memo_safe_rules = vec![true];
        let global = failure_diagnostic(&mut context.expected_names, global.0, global.1);
        let frame = frame.map_or_else(FailureDiagnostic::default, |(position, expected)| {
            failure_diagnostic(&mut context.expected_names, position, expected)
        });
        let memoized = failure_diagnostic(&mut context.expected_names, memoized.0, memoized.1);
        context.farthest = global.farthest.unwrap_or(0);
        context.expected = global.expected_values().to_vec();
        context.diagnostic_frames.push(frame);
        context
            .failure_memo
            .insert(0, FailureMemoKey::new(0, 0, 0, false, 0), memoized);

        assert!(context.rule(0, 0).is_none());
        assert_eq!(context.memoized_failure_hits(), 1);
        context
    }

    #[test]
    fn memo_hit_bulk_replay_preserves_expected_order() {
        let context = replay_memoized_failure((0, &[]), None, (0, &["z", "a", "m"]));

        assert_eq!(
            context
                .expected
                .iter()
                .map(|&id| context.expected_names.name(id))
                .collect::<Vec<_>>(),
            vec!["z", "a", "m"]
        );
        assert_eq!(
            expected_values(
                &context.expected_names,
                context.diagnostic_frames.last().unwrap()
            ),
            vec!["z", "a", "m"]
        );
    }

    #[test]
    fn memo_hit_bulk_replay_shares_interned_ids_and_deduplicates_names() {
        let dynamic = String::from("a");
        let mut context = replay_memoized_failure(
            (0, &["shared", &dynamic]),
            None,
            (0, &["z", "a", &dynamic, "shared", "z"]),
        );
        let memoized = context
            .failure_memo
            .get(0, &FailureMemoKey::new(0, 0, 0, false, 0))
            .unwrap()
            .clone();
        let frame = context.diagnostic_frames.last().unwrap();
        let (ExpectedIds::Multiple(frame_ids), ExpectedIds::Multiple(memo_ids)) =
            (&frame.expected, &memoized.expected)
        else {
            panic!("multiple expected IDs must retain shared storage");
        };
        assert!(Rc::ptr_eq(frame_ids, memo_ids));
        assert_eq!(context.expected, vec![0, 1, 2]);
        assert_eq!(frame.expected_values(), &[2, 1, 0]);
        assert_eq!(context.failure().expected, vec!["a", "shared", "z"]);

        // Extending the live frame must leave the shared memo entry unchanged.
        context.fail("m");
        assert_eq!(memoized.expected_values(), &[2, 1, 0]);
        assert_eq!(context.expected, vec![0, 1, 2, 3]);
        assert_eq!(
            context.diagnostic_frames.last().unwrap().expected_values(),
            &[2, 1, 0, 3]
        );
        assert_eq!(context.failure().expected, vec!["a", "m", "shared", "z"]);
    }

    #[test]
    fn memo_hit_bulk_replay_replaces_diagnostic_when_farthest_advances() {
        let context = replay_memoized_failure(
            (1, &["old-global"]),
            Some((1, &["old-frame"])),
            (2, &["new-b", "new-a"]),
        );

        assert_eq!(context.farthest, 2);
        assert_eq!(
            context
                .expected
                .iter()
                .map(|&id| context.expected_names.name(id))
                .collect::<Vec<_>>(),
            vec!["new-b", "new-a"]
        );
        let frame = context.diagnostic_frames.last().unwrap();
        assert_eq!(frame.farthest, Some(2));
        assert_eq!(
            expected_values(&context.expected_names, frame),
            vec!["new-b", "new-a"]
        );
    }

    #[test]
    fn memo_hit_bulk_replay_merges_at_same_farthest() {
        let context = replay_memoized_failure(
            (2, &["global", "shared"]),
            Some((2, &["frame", "shared"])),
            (2, &["shared", "memo-b", "memo-a"]),
        );

        assert_eq!(context.farthest, 2);
        assert_eq!(
            context
                .expected
                .iter()
                .map(|&id| context.expected_names.name(id))
                .collect::<Vec<_>>(),
            vec!["global", "shared", "memo-b", "memo-a"]
        );
        assert_eq!(
            expected_values(
                &context.expected_names,
                context.diagnostic_frames.last().unwrap()
            ),
            vec!["frame", "shared", "memo-b", "memo-a"]
        );
    }

    #[test]
    fn memo_hit_bulk_replay_ignores_diagnostic_behind_farthest() {
        let context =
            replay_memoized_failure((3, &["global"]), Some((3, &["frame"])), (2, &["memo"]));

        assert_eq!(context.farthest, 3);
        assert_eq!(
            context
                .expected
                .iter()
                .map(|&id| context.expected_names.name(id))
                .collect::<Vec<_>>(),
            vec!["global"]
        );
        assert_eq!(
            expected_values(
                &context.expected_names,
                context.diagnostic_frames.last().unwrap()
            ),
            vec!["frame"]
        );
    }

    #[test]
    fn lexical_trivia_cannot_replay_failures_from_another_definition() {
        fn trivia(text: &'static str) -> lexical::LexicalExpression {
            lexical::LexicalExpression {
                op: lexical::Op::LITERAL,
                text,
                min: 0,
                max: 0,
                children: vec![],
            }
        }
        for memoization in [Memoization::Off, Memoization::SafeFailures] {
            let rules = vec![
                Rule {
                    name: "root",
                    expression: Expr::Choice(vec![
                        Expr::Rule(1).lexical_trivia_scope(trivia("#")),
                        Expr::Rule(1).lexical_trivia_scope(trivia(" ")),
                    ]),
                },
                Rule {
                    name: "pair",
                    expression: Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("b")]),
                },
            ];
            let tree = parse_detailed_with_options(
                &rules,
                0,
                false,
                "a b",
                ParseOptions::with_memoization(memoization),
            )
            .unwrap();
            assert_eq!(tree.nodes[tree.root].span, Span { start: 0, end: 3 });
        }
    }

    #[test]
    fn safe_failure_memo_key_distinguishes_all_parser_state_dimensions() {
        let grammar = share_grammar(vec![Rule {
            name: "r",
            expression: Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("x")]),
        }]);
        let mut context = ParseContext::with_options(
            " ay",
            ParseOptions::with_memoization(Memoization::SafeFailures),
        );
        context.rules = Arc::clone(&grammar);
        context.memo_safe_rules = memo_safe_rules(&grammar);
        context.grammar_session = 17;

        let cases = [
            ((0, 0, 0, false), (Some(0), vec!["a".to_owned()])),
            ((0, 1, 0, false), (Some(0), vec!["a".to_owned()])),
            ((0, 0, 1, false), (Some(0), vec!["a".to_owned()])),
            ((0, 0, 0, true), (Some(2), vec!["x".to_owned()])),
            ((1, 0, 0, false), (Some(2), vec!["x".to_owned()])),
        ];

        for &(state, ref expected) in &cases {
            let (position, matched_position, depth, whitespace) = state;
            context.position = position;
            context.matched_position = matched_position;
            context.whitespace = whitespace;
            context.diagnostic_frames.push(FailureDiagnostic::default());
            assert!(context.rule(0, depth).is_none());
            let diagnostic = context
                .diagnostic_frames
                .pop()
                .expect("test installed a diagnostic frame");
            assert_eq!(
                (
                    diagnostic.farthest,
                    context.expected_names.strings(diagnostic.expected_values())
                ),
                *expected
            );
        }
        assert_eq!(context.memoized_failure_hits(), 0);

        for &(state, ref expected) in &cases {
            let (position, matched_position, depth, whitespace) = state;
            context.position = position;
            context.matched_position = matched_position;
            context.whitespace = whitespace;
            context.diagnostic_frames.push(FailureDiagnostic::default());
            assert!(context.rule(0, depth).is_none());
            let diagnostic = context
                .diagnostic_frames
                .pop()
                .expect("test installed a diagnostic frame");
            assert_eq!(
                (
                    diagnostic.farthest,
                    context.expected_names.strings(diagnostic.expected_values())
                ),
                *expected
            );
        }
        assert_eq!(context.memoized_failure_hits(), cases.len());
    }

    #[test]
    fn memo_replay_across_nested_frames_keeps_expected_sorted_and_unique() {
        let grammar = share_grammar(vec![
            Rule {
                name: "root",
                expression: Expr::Sequence(vec![
                    Expr::Lookahead {
                        child: Box::new(Expr::Rule(1)),
                        positive: false,
                    },
                    Expr::Rule(1),
                ]),
            },
            Rule {
                name: "outer",
                expression: Expr::Choice(vec![
                    Expr::Rule(2),
                    Expr::Literal("z"),
                    Expr::Literal("a"),
                    Expr::Literal("a"),
                ]),
            },
            Rule {
                name: "inner",
                expression: Expr::Choice(vec![Expr::Literal("m"), Expr::Literal("a")]),
            },
        ]);
        let mut context = ParseContext::with_options(
            "q",
            ParseOptions::with_memoization(Memoization::SafeFailures),
        );

        let error = context
            .parse_shared_grammar(&grammar, 0, false)
            .unwrap_err();

        assert_eq!(context.memoized_failure_hits(), 1);
        assert_eq!(error.offset, 0);
        assert_eq!(error.expected, vec!["a", "m", "z"]);
    }

    #[test]
    fn nested_memo_frames_merge_success_and_failure_diagnostics_into_outer_memo() {
        let grammar = share_grammar(vec![
            Rule {
                name: "outer",
                expression: Expr::Sequence(vec![Expr::Rule(1), Expr::Rule(3)]),
            },
            Rule {
                name: "successful_middle",
                expression: Expr::Choice(vec![Expr::Rule(2), Expr::Literal("")]),
            },
            Rule {
                name: "failed_inner",
                expression: Expr::Choice(vec![Expr::Literal("inner-b"), Expr::Literal("inner-a")]),
            },
            Rule {
                name: "failed_middle",
                expression: Expr::Rule(4),
            },
            Rule {
                name: "failed_deepest",
                expression: Expr::Choice(vec![Expr::Literal("tail-b"), Expr::Literal("tail-a")]),
            },
        ]);
        let mut context = ParseContext::with_options(
            "q",
            ParseOptions::with_memoization(Memoization::SafeFailures),
        );
        context.rules = Arc::clone(&grammar);
        context.memo_safe_rules = memo_safe_rules(&grammar);
        context.grammar_session = 17;

        assert!(context.rule(0, 0).is_none());

        let outer = context
            .failure_memo
            .get(0, &FailureMemoKey::new(0, 0, 0, false, 0))
            .expect("outer failure was memoized");
        assert_eq!(outer.farthest, Some(0));
        assert_eq!(
            outer
                .expected_values()
                .iter()
                .map(|&id| context.expected_names.name(id))
                .collect::<Vec<_>>(),
            vec!["inner-b", "inner-a", "tail-b", "tail-a"]
        );
        assert_eq!(
            context.failure(),
            ParseError {
                offset: 0,
                expected: vec![
                    "inner-a".to_owned(),
                    "inner-b".to_owned(),
                    "tail-a".to_owned(),
                    "tail-b".to_owned(),
                ],
            }
        );
    }

    #[test]
    fn memoized_rule_does_not_capture_a_sibling_lookahead_diagnostic() {
        let grammar = share_grammar(vec![
            Rule {
                name: "root",
                expression: Expr::Sequence(vec![
                    Expr::Lookahead {
                        child: Box::new(Expr::Choice(vec![
                            Expr::Sequence(vec![Expr::Literal("az"), Expr::Error("far")]),
                            Expr::Rule(1),
                        ])),
                        positive: false,
                    },
                    Expr::Rule(1),
                ]),
            },
            Rule {
                name: "r",
                expression: Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("x")]),
            },
        ]);

        let error = parse_detailed_shared_with_options(
            &grammar,
            0,
            false,
            "az",
            ParseOptions::with_memoization(Memoization::SafeFailures),
        )
        .unwrap_err();
        assert_eq!(error.farthest.offset, 1);
        assert_eq!(error.farthest.expected, vec!["x"]);
    }

    #[test]
    fn shared_grammar_sessions_evict_failures_and_restore_the_caller_session() {
        let grammar = share_grammar(vec![Rule {
            name: "root",
            expression: Expr::Choice(vec![Expr::Literal("x"), Expr::Literal("x")]),
        }]);
        let mut context = ParseContext::with_options(
            "y",
            ParseOptions::with_memoization(Memoization::SafeFailures),
        );
        context.grammar_session = 41;

        for _ in 0..3 {
            assert!(context.parse_shared_grammar(&grammar, 0, false).is_err());
            assert!(context.failure_memo.first.is_empty());
            assert!(context.failure_memo.remaining.is_empty());
            assert_eq!(context.grammar_session, 41);
        }
    }

    #[test]
    fn custom_and_backreference_ancestors_are_not_memoized() {
        static CUSTOM_CALLS: std::sync::atomic::AtomicUsize =
            std::sync::atomic::AtomicUsize::new(0);
        fn custom(context: &mut ParseContext<'_>) -> ParseResult {
            CUSTOM_CALLS.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
            context.error("custom");
            Err(context.failure())
        }
        let rules = vec![
            Rule {
                name: "custom_parent",
                expression: Expr::Choice(vec![Expr::Rule(1), Expr::Rule(1)]),
            },
            Rule {
                name: "custom",
                expression: Expr::Custom(custom),
            },
            Rule {
                name: "backreference_parent",
                expression: Expr::Rule(3),
            },
            Rule {
                name: "backreference",
                expression: Expr::Backreference("name"),
            },
            Rule {
                name: "safe",
                expression: Expr::Literal("safe"),
            },
        ];
        assert_eq!(
            memo_safe_rules(&rules),
            vec![false, false, false, false, true]
        );
        let mut context = ParseContext::with_options(
            "",
            ParseOptions::with_memoization(Memoization::SafeFailures),
        );
        let grammar = share_grammar(rules);
        assert!(context.parse_shared_grammar(&grammar, 0, false).is_err());
        assert_eq!(CUSTOM_CALLS.load(std::sync::atomic::Ordering::Relaxed), 2);
        assert_eq!(context.memoized_failure_hits(), 0);
    }
}

#[cfg(test)]
mod parse_bindings_tests {
    use super::*;
    #[test]
    fn bindings_are_owned_read_only_and_context_local() {
        let mut bindings = BTreeMap::from([(
            "words".to_owned(),
            vec!["𠮷".to_owned(), String::new(), "𠮷".to_owned()],
        )]);
        let a = ParseContext::with_bindings("𠮷", bindings.clone(), ParseOptions::default());
        bindings.insert("words".to_owned(), vec!["changed".to_owned()]);
        let b = ParseContext::with_bindings("𠮷", bindings, ParseOptions::default());
        assert_eq!(a.binding_values("words"), ["𠮷", "", "𠮷"]);
        assert_eq!(b.binding_values("words"), ["changed"]);
        assert!(a.binding_values("missing").is_empty());
        assert!(ParseContext::new("𠮷").binding_values("words").is_empty());
    }
}

fn classic_collector_label(expression: &Expr) -> Option<&'static str> {
    match expression {
        Expr::Sequence(_) => Some("Chain"),
        Expr::NonOrdered(_) => Some("NonOrdered"),
        Expr::Recovery { .. } => Some("SyncPointRecoveryParser"),
        Expr::Choice(_) | Expr::PredictiveChoice { .. } => Some("Choice"),
        Expr::LongestChoice(_) => Some("LongestChoice"),
        Expr::UniqueLongestChoice(_) => Some("UniqueLongestChoice"),
        Expr::Optional(_) | Expr::JavaOptional(_) => Some("Optional"),
        Expr::Repeat {
            min: 0, max: None, ..
        }
        | Expr::JavaRepeat {
            min: 0, max: None, ..
        } => Some("ZeroOrMore"),
        Expr::Repeat {
            min: 1, max: None, ..
        }
        | Expr::JavaRepeat {
            min: 1, max: None, ..
        } => Some("OneOrMore"),
        Expr::Repeat { .. } | Expr::JavaRepeat { .. } | Expr::ZeroMatch(_) => Some("Occurs"),
        Expr::CodeStart => Some("CodeStartParser"),
        Expr::CodeEnd => Some("CodeEndParser"),
        Expr::Number => Some("NumberParser"),
        Expr::Identifier => Some("IdentifierParser"),
        Expr::Quoted('\'') => Some("SingleQuotedParser"),
        Expr::Quoted('"') => Some("DoubleQuotedParser"),
        _ => None,
    }
}

/// Small inline work lists keep ordinary parser entries allocation-free. Very
/// deep/wide rule graphs can spill without recursion or a fixed analysis limit.
struct ClassicModeScan<'a> {
    siblings: [Option<std::slice::Iter<'a, Expr>>; 32],
    depth: usize,
    overflow: Vec<std::slice::Iter<'a, Expr>>,
    rules: [usize; 64],
    rule_count: usize,
    extra_rules: std::collections::BTreeSet<usize>,
}
impl<'a> ClassicModeScan<'a> {
    fn new() -> Self {
        Self {
            siblings: std::array::from_fn(|_| None),
            depth: 0,
            overflow: Vec::new(),
            rules: [0; 64],
            rule_count: 0,
            extra_rules: Default::default(),
        }
    }
    fn children(&mut self, children: &'a [Expr]) -> Option<&'a Expr> {
        let (first, rest) = children.split_first()?;
        if !rest.is_empty() {
            if self.depth < self.siblings.len() && self.overflow.is_empty() {
                self.siblings[self.depth] = Some(rest.iter());
                self.depth += 1;
            } else {
                self.overflow.push(rest.iter());
            }
        }
        Some(first)
    }
    fn next(&mut self) -> Option<&'a Expr> {
        while let Some(last) = self.overflow.last_mut() {
            if let Some(next) = last.next() {
                return Some(next);
            }
            self.overflow.pop();
        }
        while self.depth > 0 {
            if let Some(next) = self.siblings[self.depth - 1].as_mut().unwrap().next() {
                return Some(next);
            }
            self.depth -= 1;
            self.siblings[self.depth] = None;
        }
        None
    }
    fn visit_rule(&mut self, id: usize) -> bool {
        if self.rules[..self.rule_count].contains(&id) {
            return false;
        }
        if self.rule_count < self.rules.len() {
            self.rules[self.rule_count] = id;
            self.rule_count += 1;
            true
        } else {
            self.extra_rules.insert(id)
        }
    }
}
#[inline(never)]
fn requires_classic_mode<'a>(expression: &'a Expr, rules: &'a [Rule]) -> bool {
    let mut scan = ClassicModeScan::new();
    let mut current = Some(expression);
    while let Some(expression) = current.take().or_else(|| scan.next()) {
        current = match expression {
            Expr::ZeroMatch(_)
            | Expr::OwnedCapture(_, _)
            | Expr::Replay(_)
            | Expr::CaptureScope(_)
            | Expr::MatchOnly(_)
            | Expr::InvertMatch(_)
            | Expr::StopInvert(_)
            | Expr::StopConsume(_)
            | Expr::Character { .. } => return true,
            Expr::Rule(id) => {
                if scan.visit_rule(*id) {
                    rules.get(*id).map(|rule| &rule.expression)
                } else {
                    None
                }
            }
            Expr::Sequence(children)
            | Expr::NonOrdered(children)
            | Expr::Choice(children)
            | Expr::LongestChoice(children)
            | Expr::UniqueLongestChoice(children)
            | Expr::PredictiveChoice {
                alternatives: children,
                ..
            } => scan.children(children),
            Expr::Capture(_, child)
            | Expr::TextValue(child)
            | Expr::CaptureBoundary(_, child)
            | Expr::InputCaptureBoundary(_, child)
            | Expr::ValueBoundary(child)
            | Expr::Optional(child)
            | Expr::JavaOptional(child)
            | Expr::Repeat { child, .. }
            | Expr::JavaRepeat { child, .. }
            | Expr::Lookahead { child, .. }
            | Expr::RuleEffects { child, .. }
            | Expr::CaptureEquality { child, .. }
            | Expr::NamePredicate { child, .. }
            | Expr::NameResolutionScope { child, .. }
            | Expr::Recovery { child, .. }
            | Expr::TriviaScope { child, .. }
            | Expr::LexicalTriviaScope { child, .. }
            | Expr::LexicalContextScope { child, .. } => Some(child.as_ref()),
            _ => None,
        };
    }
    false
}

#[cfg(test)]
mod pure_success_memo_tests {
    use super::*;

    fn context(input: &str, expression: Expr) -> ParseContext<'_> {
        let mut context = ParseContext::with_options(
            input,
            ParseOptions::with_memoization(Memoization::SafeFailures)
                .with_diagnostics(Diagnostics::Detailed),
        );
        context.rules = share_grammar(vec![Rule {
            name: "pure",
            expression,
        }]);
        context.memo_safe_rules = memo_safe_rules(&context.rules);
        context.success_memo_safe_rules = memo_success_safe_rules(&context.rules);
        context
    }

    fn warm(context: &mut ParseContext<'_>) {
        let checkpoint = context.checkpoint();
        assert!(context.rule(0, 0).is_some());
        context.restore(checkpoint);
    }

    #[test]
    fn successful_memo_replays_only_local_diagnostics_and_independent_edges() {
        let mut context = context(
            "😀?",
            Expr::choice([
                Expr::literal("😀").then(Expr::literal("!")),
                Expr::literal("😀").capture_value().capture("x"),
            ]),
        );
        context.fail_at(5, "earlier unrelated sibling");
        warm(&mut context);
        context.farthest = 0;
        context.expected.clear();
        let root = context.rule(0, 0).unwrap();
        assert_eq!(context.memoized_success_hits(), 1);
        assert_eq!(context.failure().offset, 1);
        assert_eq!(context.failure().expected, ["!"]);
        assert_eq!(context.captured("x"), Some("😀"));
        context.nodes[root].children.clear();
        context.nodes[root].captures.clear();
        context.position = 0;
        context.matched_position = 0;
        let root = context.rule(0, 0).unwrap();
        assert_eq!(context.memoized_success_hits(), 2);
        assert_eq!(context.nodes[root].children.len(), 1);
        assert_eq!(context.nodes[root].captures.len(), 1);
        assert_eq!(context.capture_spans("x").len(), 2);
    }

    #[test]
    fn success_memo_never_shortcuts_a_different_expression_budget_or_sticky_failure() {
        let mut context = context("x", Expr::literal("x").capture_value());
        warm(&mut context);
        assert!(context.success_memo.retained_bytes > 0);
        context.expression_depth = 1024;
        assert!(context.rule(0, 0).is_none());
        assert_eq!(context.memoized_success_hits(), 0);
        assert_eq!(
            context.failure().expected,
            ["expression nesting below 1024"]
        );
        context.expression_depth = 0;
        assert!(context.rule(0, 0).is_none());
        assert_eq!(context.memoized_success_hits(), 0);
    }

    #[test]
    fn single_snapshot_budget_is_a_safe_miss() {
        let expression = Expr::Sequence((0..30000).map(|_| Expr::Empty.capture_value()).collect());
        let mut context = context("", expression);
        warm(&mut context);
        assert_eq!(context.success_memo.retained_bytes, 0);
        assert!(context.rule(0, 0).is_some());
        assert_eq!(context.memoized_success_hits(), 0);
        assert_eq!(context.nodes.len(), 30001);
        assert!(context.resource_failure.is_none());
    }

    #[test]
    fn aggregate_budget_eviction_and_replacement_return_accounted_bytes() {
        let mut context = context("a", Expr::literal("a"));
        warm(&mut context);
        let key = FailureMemoKey::new(0, 0, 0, false, 0);
        let entry = context.success_memo.get(0, &key).unwrap().clone();
        let bytes = entry.bytes;
        let mut memo = SuccessMemoBuckets::with_window(Some(1024));
        memo.max_bytes = bytes;
        memo.insert(0, key, entry.clone());
        assert_eq!(memo.retained_bytes, bytes);
        memo.insert(0, key, entry.clone());
        assert_eq!(memo.retained_bytes, bytes);
        let other = FailureMemoKey::new(1, 0, 0, false, 0);
        memo.insert(0, other, entry.clone());
        assert!(memo.get(0, &other).is_none());
        assert_eq!(memo.retained_bytes, bytes);
        memo.observe_cursor(4000);
        assert_eq!(memo.retained_bytes, 0);
        assert!(memo.probe(0, &key).is_none());
        memo.insert(0, key, entry);
        assert_eq!(memo.retained_bytes, bytes);
        assert!(memo.probe(0, &key).is_some());
    }

    #[test]
    fn aggregate_budget_parse_falls_back_without_a_resource_failure() {
        let mut context = context("a", Expr::literal("a"));
        context.success_memo.max_bytes = 0;
        warm(&mut context);
        assert!(context.rule(0, 0).is_some());
        assert_eq!(context.memoized_success_hits(), 0);
        assert!(context.resource_failure.is_none());
    }

    #[test]
    fn classic_structural_metadata_is_budgeted_and_eviction_releases_it() {
        let mut context = context("ab", Expr::literal("a").then(Expr::literal("b")));
        context.token_mode = Some(token::ParseMode::default());
        let key = FailureMemoKey::new(0, 0, 0, false, 0)
            .with_match_policy(context.match_cursor_policy)
            .with_token_mode(context.token_mode);
        let checkpoint = context.checkpoint();
        assert!(context.rule(0, 0).is_some());
        let token_bytes = token::MemoTokens::measure(&context.tokens, 0, context.nodes.len())
            .expect("pure structural tokens");
        assert!(token_bytes > 0);
        let bytes = context.success_memo.get(0, &key).unwrap().bytes;
        assert!(bytes > token_bytes);
        context.restore(checkpoint);

        context.success_memo = SuccessMemoBuckets::with_window(Some(1024));
        // The node-only payload would fit; including structural tokens must miss.
        context.success_memo.max_bytes = bytes - token_bytes;
        warm(&mut context);
        assert_eq!(context.success_memo.retained_bytes, 0);
        warm(&mut context);
        assert_eq!(context.memoized_success_hits(), 0);
        assert!(context.resource_failure.is_none());

        context.success_memo.max_bytes = bytes;
        warm(&mut context);
        assert_eq!(context.success_memo.retained_bytes, bytes);
        warm(&mut context);
        assert_eq!(context.memoized_success_hits(), 1);
        context.success_memo.observe_cursor(4000);
        assert_eq!(context.success_memo.retained_bytes, 0);
        warm(&mut context);
        assert_eq!(context.success_memo.retained_bytes, bytes);
    }

    #[test]
    fn traced_calls_keep_observations_without_success_hits_while_classic_can_replay() {
        for classic in [false, true] {
            for traced in [false, true] {
                let mut context = context("a", Expr::literal("a"));
                if classic {
                    context.token_mode = Some(token::ParseMode::default());
                }
                if traced {
                    context.token_trace =
                        Some(token_trace::Trace::new(token_trace::Limits::default()));
                }
                warm(&mut context);
                let root = context.rule(0, 0).unwrap();
                assert_eq!(context.memoized_success_hits(), usize::from(!traced));
                assert_eq!(context.nodes[root].span, Span { start: 0, end: 1 });
                if traced {
                    assert_eq!(context.token_trace().unwrap().unwrap().len(), 1);
                }
            }
        }
    }

    #[test]
    fn effectful_parent_reexecutes_while_its_pure_child_can_hit() {
        let grammar = share_grammar(vec![
            Rule {
                name: "root",
                expression: Expr::choice([
                    Expr::Rule(1).then(Expr::literal("!")),
                    Expr::Rule(1).then(Expr::literal("?")),
                ]),
            },
            Rule {
                name: "declaration",
                expression: Expr::Rule(2).capture("name").rule_effects(RuleEffects {
                    declares: Some(Declaration {
                        symbol_capture: "name",
                        description: None,
                    }),
                    ..RuleEffects::default()
                }),
            },
            Rule {
                name: "pure",
                expression: Expr::literal("a"),
            },
        ]);
        let mut observations = Vec::new();
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            let mut context =
                ParseContext::with_options("a?", ParseOptions::with_memoization(memo));
            context.rules = Arc::clone(&grammar);
            context.memo_safe_rules = memo_safe_rules(&grammar);
            let matched = context.parse(&Expr::Rule(0)).unwrap();
            assert_eq!(context.position(), 2);
            assert!(context.scopes.is_declared("a"));
            if memo == Memoization::SafeFailures {
                assert_eq!(context.memoized_success_hits(), 1);
                assert!(context.success_memo.first.keys().all(|key| key.rule == 2));
            }
            observations.push((
                context
                    .tree(matched.root_node().unwrap())
                    .unwrap()
                    .nodes
                    .len(),
                format!("{:?}", context.scopes),
                context.failure(),
            ));
        }
        assert_eq!(observations[0], observations[1]);
    }

    #[test]
    fn stronger_success_proof_taints_effectful_rules_and_all_ancestors() {
        let expressions = vec![
            Expr::Rule(1),
            Expr::Rule(2).rule_effects(RuleEffects::default()),
            Expr::literal("x").capture("value"),
            Expr::Rule(4),
            Expr::Custom(|c| Err(c.failure())),
            Expr::literal("x").capture_owned("owned"),
            Expr::literal("x").recover(RecoveryMode::Skip, ["!"], "bad"),
            Expr::Rule(8),
            Expr::Backreference("value"),
        ];
        let rules = expressions
            .into_iter()
            .map(|expression| Rule {
                name: "rule",
                expression,
            })
            .collect::<Vec<_>>();
        assert_eq!(
            memo_safe_rules(&rules),
            [true, true, true, false, false, false, false, false, false]
        );
        assert_eq!(
            memo_success_safe_rules(&rules),
            [false, false, true, false, false, false, false, false, false]
        );
    }
}
