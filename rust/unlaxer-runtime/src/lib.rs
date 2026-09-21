//! Experimental UBNF structural subset. No JVM, unsafe code, or external dependencies.
use std::any::Any;
use std::collections::HashMap;
use std::hash::{BuildHasher, Hasher};
use std::rc::Rc;
use std::sync::Arc;

mod scope;
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

#[derive(Debug, Clone)]
pub enum Expr {
    Literal(&'static str),
    Number,
    /// Java clang IdentifierParser: ASCII letter/underscore, then ASCII alphanumeric/underscore.
    Identifier,
    /// tinyexpression CodeStartParser: an atomic line-oriented opening code fence.
    CodeStart,
    /// tinyexpression CodeEndParser: an atomic line-oriented closing code fence.
    CodeEnd,
    /// Java single/double-quoted text: backslash followed by any scalar, no escape decoding.
    Quoted(char),
    Rule(usize),
    Sequence(Vec<Expr>),
    Choice(Vec<Expr>),
    /// Try every alternative from the same transactional state and commit the
    /// one that consumes the most input. Equal-length matches keep declaration order.
    LongestChoice(Vec<Expr>),
    /// Ordered choice that skips alternatives whose conservative FIRST
    /// predictor proves they cannot match. `Any` preserves ordinary choice.
    PredictiveChoice {
        alternatives: Vec<Expr>,
        predictors: Vec<Predictor>,
    },
    Capture(&'static str, Box<Expr>),
    /// Wrap successful child nodes in a text-projection boundary without changing parsing.
    TextValue(Box<Expr>),
    /// Preserve a scalar mixed-value capture span without deciding text versus node.
    ValueBoundary(Box<Expr>),
    /// Apply rule-local symbol effects to this child's captures after a successful parse.
    RuleEffects {
        child: Box<Expr>,
        effects: RuleEffects,
    },
    /// Apply a local trivia policy to sequence boundaries, restoring the caller afterwards.
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
    Custom(fn(&mut ParseContext<'_>) -> ParseResult),
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
    pub fn literal(text: &'static str) -> Self {
        Self::Literal(text)
    }
    pub fn sequence(elements: impl IntoIterator<Item = Self>) -> Self {
        Self::Sequence(elements.into_iter().collect())
    }
    pub fn choice(alternatives: impl IntoIterator<Item = Self>) -> Self {
        Self::Choice(alternatives.into_iter().collect())
    }
    pub fn longest_choice(alternatives: impl IntoIterator<Item = Self>) -> Self {
        Self::LongestChoice(alternatives.into_iter().collect())
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
    /// Preserve this expression's consumed source as one text value, including trivia.
    /// Inner nodes/captures remain available; no quote stripping or text decoding occurs.
    pub fn text_value(self) -> Self {
        Self::TextValue(Box::new(self))
    }
    /// Preserve a mixed scalar/optional capture boundary; parsing remains transparent.
    pub fn value_boundary(self) -> Self {
        Self::ValueBoundary(Box::new(self))
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
    pub fn ahead(self) -> Self {
        Self::Lookahead {
            child: Box::new(self),
            positive: true,
        }
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
    /// Cache failures only for rules that cannot reach context-dependent expressions.
    SafeFailures,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ParseOptions {
    pub memoization: Memoization,
}

impl ParseOptions {
    pub const fn with_memoization(memoization: Memoization) -> Self {
        Self { memoization }
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
    /// Grammar-local rule index, [`TEXT_VALUE_RULE`], or [`VALUE_BOUNDARY_RULE`].
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
}

impl Tree {
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
}

/// Shared entry point for handwritten and generated parsers. Use context.parse(parser)
/// when calling a custom implementation so failed calls roll back transactional state.
pub trait Parser {
    fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult;
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
    values: HashMap<String, Vec<Span>>,
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
        self.values.entry(name.to_owned()).or_default().push(span);
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
    position: usize,
    matched_position: usize,
    nodes: usize,
    metrics_counted: bool,
    captures_journal_mark: usize,
    state: Option<Rc<StateMap>>,
    scopes_journal_mark: usize,
}

struct ChoiceWinner {
    position: usize,
    matched_position: usize,
    node_start: usize,
    nodes: Vec<Node>,
    captures: CaptureStore,
    state: Rc<StateMap>,
    scopes: ScopeStore,
    fragment: Fragment,
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
    farthest: Option<usize>,
    expected: ExpectedIds,
}

impl FailureDiagnostic {
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
    depth_and_whitespace: usize,
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
            depth_and_whitespace: depth * 2 + usize::from(whitespace),
        }
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

type FailureMemoMap = HashMap<FailureMemoKey, FailureDiagnostic, FailureMemoBuildHasher>;

const FAILURE_MEMO_BUCKET_SIZE: usize = 256;

/// Keep nearby failures together for lookup and destruction. The first bucket is
/// inline so inputs shorter than one bucket need no additional allocation.
#[derive(Default)]
struct FailureMemoBuckets {
    first: FailureMemoMap,
    remaining: Vec<FailureMemoMap>,
}

impl FailureMemoBuckets {
    fn get(&self, bucket: usize, key: &FailureMemoKey) -> Option<&FailureDiagnostic> {
        if bucket == 0 {
            self.first.get(key)
        } else {
            self.remaining.get(bucket - 1)?.get(key)
        }
    }

    fn insert(&mut self, bucket: usize, key: FailureMemoKey, diagnostic: FailureDiagnostic) {
        let map = if bucket == 0 {
            &mut self.first
        } else {
            if self.remaining.len() < bucket {
                // Empty maps allocate no entry storage, including skipped buckets.
                self.remaining.resize_with(bucket, FailureMemoMap::default);
            }
            &mut self.remaining[bucket - 1]
        };
        map.insert(key, diagnostic);
    }
}

/// Per-parse state, shared by generated rules and custom parsers. Transactions restore
/// cursors, CST nodes, captures and cloneable user values, but retain failure diagnostics.
/// Clone must isolate mutable values: external effects and shared interior state are not rolled back.
/// Rollback applies to Result::Err, not to panic unwinding. Discard the context after a panic.
/// Capture names are context-wide, and backreferences use the most recent successful capture.
/// Lexical symbols and semantic diagnostics use a separate, owned transactional ScopeStore.
pub struct ParseContext<'a> {
    input: &'a str,
    rules: Arc<[Rule]>,
    whitespace: bool,
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
    state: Rc<StateMap>,
    scopes: ScopeStore,
    call_depth: usize,
    options: ParseOptions,
    grammar_session: u64,
    next_grammar_session: u64,
    memo_safe_rules: Vec<bool>,
    failure_memo: FailureMemoBuckets,
    diagnostic_frames: Vec<FailureDiagnostic>,
    memoized_failure_hits: usize,
    checkpoint_metrics: Option<CheckpointMetrics>,
    checkpoint_metrics_scope_journal_base: u64,
}

/// Ordered choice with rollback and full-input acceptance. Rule nesting is bounded at 256.
pub fn parse(
    rules: &[Rule],
    root: usize,
    whitespace: bool,
    input: &str,
) -> Result<Tree, ParseError> {
    parse_detailed(rules, root, whitespace, input).map_err(|diagnostic| diagnostic.farthest)
}

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

pub fn parse_detailed_with_options(
    rules: &[Rule],
    root: usize,
    whitespace: bool,
    input: &str,
    options: ParseOptions,
) -> Result<Tree, ParseDiagnostic> {
    parse_detailed_owned(Arc::from(rules), root, whitespace, input, options)
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

pub fn parse_detailed_shared_with_options(
    grammar: &SharedGrammar,
    root: usize,
    whitespace: bool,
    input: &str,
    options: ParseOptions,
) -> Result<Tree, ParseDiagnostic> {
    parse_detailed_owned(Arc::clone(grammar), root, whitespace, input, options)
}

fn parse_detailed_owned(
    rules: SharedGrammar,
    root: usize,
    whitespace: bool,
    input: &str,
    options: ParseOptions,
) -> Result<Tree, ParseDiagnostic> {
    let mut parser = ParseContext::with_options(input, options);
    parser.rules = rules;
    parser.whitespace = whitespace;
    if options.memoization == Memoization::SafeFailures {
        parser.memo_safe_rules = memo_safe_rules(&parser.rules);
    }
    let mut trailing_offset = None;
    if let Some(root) = parser.rule(root, 0) {
        if parser.position == input.len() {
            return Ok(Tree {
                source: input.to_owned(),
                nodes: parser.nodes,
                root,
                byte_offsets: parser.byte_offsets,
                scopes: parser.scopes,
            });
        }
        trailing_offset = Some(parser.code_point(parser.position));
        parser.fail("end of input");
    }
    let farthest = ParseError {
        offset: parser.code_point(parser.farthest),
        expected: parser.expected_names.strings(&parser.expected),
    };
    Err(ParseDiagnostic {
        kind: if trailing_offset.is_some() {
            "trailing_input"
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

    pub fn with_options(input: &'a str, options: ParseOptions) -> Self {
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
            input,
            rules: Arc::from([]),
            whitespace: false,
            position: 0,
            matched_position: 0,
            nodes: vec![],
            farthest: 0,
            expected: vec![],
            expected_names: ExpectedNames::default(),
            byte_offsets,
            code_point_offsets,
            captures: CaptureStore::default(),
            state: Rc::new(StateMap::default()),
            scopes: ScopeStore::default(),
            call_depth: 0,
            options,
            grammar_session: 0,
            next_grammar_session: 1,
            memo_safe_rules: vec![],
            failure_memo: FailureMemoBuckets::default(),
            diagnostic_frames: vec![],
            memoized_failure_hits: 0,
            checkpoint_metrics: None,
            checkpoint_metrics_scope_journal_base: 0,
        }
    }

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

    #[cfg(test)]
    fn memoized_failure_hits(&self) -> usize {
        self.memoized_failure_hits
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
        })
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
    pub fn capture_spans(&self, name: &str) -> &[Span] {
        self.captures.get(name).map(Vec::as_slice).unwrap_or(&[])
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
    pub fn error(&mut self, expected: &str) -> ParseError {
        self.fail(expected);
        self.failure()
    }
    pub fn failure(&self) -> ParseError {
        ParseError {
            offset: self.code_point(self.farthest),
            expected: self.expected_names.strings(&self.expected),
        }
    }

    pub fn parse<P: Parser + ?Sized>(&mut self, parser: &P) -> ParseResult {
        if self.call_depth >= 256 {
            return Err(self.error("parser calls below 256"));
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
        let checkpoint = self.checkpoint();
        let result = operation(self);
        if let Err(error) = &result {
            if let Some(&byte) = self.byte_offsets.get(error.offset) {
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
    /// ordinary parse failure.
    pub fn parse_shared_grammar(
        &mut self,
        grammar: &SharedGrammar,
        root: usize,
        whitespace: bool,
    ) -> ParseResult {
        let previous_rules = std::mem::replace(&mut self.rules, Arc::clone(grammar));
        let previous_whitespace = std::mem::replace(&mut self.whitespace, whitespace);
        let safe_rules = if self.options.memoization == Memoization::SafeFailures {
            memo_safe_rules(grammar)
        } else {
            vec![]
        };
        let previous_safe_rules = std::mem::replace(&mut self.memo_safe_rules, safe_rules);
        let previous_failure_memo = std::mem::take(&mut self.failure_memo);
        let previous_session = self.grammar_session;
        let active_session = self.next_grammar_session;
        self.next_grammar_session = self.next_grammar_session.wrapping_add(1);
        self.grammar_session = active_session;
        let result = self.parse_expression(&Expr::Rule(root));
        self.failure_memo = previous_failure_memo;
        self.rules = previous_rules;
        self.whitespace = previous_whitespace;
        self.memo_safe_rules = previous_safe_rules;
        self.grammar_session = previous_session;
        result
    }

    /// Temporarily changes sequence trivia handling, on both success and failure.
    /// Atomic parsers still consume only their own token syntax.
    pub fn with_trivia<T>(
        &mut self,
        whitespace: bool,
        operation: impl FnOnce(&mut Self) -> T,
    ) -> T {
        let previous = std::mem::replace(&mut self.whitespace, whitespace);
        let result = operation(self);
        self.whitespace = previous;
        result
    }

    fn parse_expression(&mut self, expression: &Expr) -> ParseResult {
        let start = self.position();
        match self.expression(expression, self.call_depth) {
            Some(fragment) => Ok(ParseMatch {
                span: Span {
                    start,
                    end: self.position(),
                },
                nodes: fragment.nodes,
                captures: fragment.captures,
            }),
            None => Err(self.failure()),
        }
    }

    fn checkpoint(&mut self) -> Checkpoint {
        let captures_nonempty = !self.captures.is_empty();
        let captures_journal_mark = self.captures.checkpoint();
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
            position: self.position,
            matched_position: self.matched_position,
            nodes: self.nodes.len(),
            metrics_counted,
            captures_journal_mark,
            scopes_journal_mark,
            state,
        }
    }

    fn commit_checkpoint(&mut self, checkpoint: Checkpoint) {
        self.captures.commit_checkpoint();
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
        self.position = checkpoint.position;
        self.matched_position = checkpoint.matched_position;
        self.nodes.truncate(checkpoint.nodes);
        self.captures
            .rollback_checkpoint(checkpoint.captures_journal_mark);
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

    fn fail_at(&mut self, position: usize, expected: &str) {
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
        if depth >= 256 {
            self.fail("rule nesting below 256");
            return None;
        }
        let rules = Arc::clone(&self.rules);
        let Some(rule) = rules.get(id) else {
            self.fail("valid rule reference");
            return None;
        };
        let memo_key = (self.options.memoization == Memoization::SafeFailures
            && self.memo_safe_rules.get(id).copied().unwrap_or(false))
        .then(|| {
            (
                // Route by scalar position; keep the existing byte-based key intact.
                self.code_point(self.position) / FAILURE_MEMO_BUCKET_SIZE,
                FailureMemoKey::new(
                    id,
                    self.position,
                    self.matched_position,
                    self.whitespace,
                    depth,
                ),
            )
        });
        if let Some((bucket, key)) = memo_key {
            if let Some(diagnostic) = self.failure_memo.get(bucket, &key).cloned() {
                self.memoized_failure_hits += 1;
                self.replay_failure(&diagnostic);
                return None;
            }
        }
        if memo_key.is_some() {
            self.diagnostic_frames.push(FailureDiagnostic::default());
        }
        let start = self.position;
        let count = self.nodes.len();
        let result = match self.expression(&rule.expression, depth + 1) {
            Some(fragment) => {
                let node_id = self.nodes.len();
                self.nodes.push(Node {
                    rule: id,
                    span: self.span(start),
                    children: fragment.nodes,
                    captures: fragment.captures,
                });
                Some(node_id)
            }
            None => {
                self.position = start;
                self.nodes.truncate(count);
                None
            }
        };
        if let Some((bucket, key)) = memo_key {
            let diagnostic = self
                .diagnostic_frames
                .pop()
                .expect("memoized rule installed a diagnostic frame");
            if let Some(parent) = self.diagnostic_frames.last_mut() {
                parent.merge(&diagnostic);
            }
            if result.is_none() {
                self.failure_memo.insert(bucket, key, diagnostic);
            }
        }
        result
    }

    fn expression(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        let checkpoint = self.checkpoint();
        let result = self.expression_inner(expression, depth);
        if result.is_none() {
            self.restore(checkpoint);
        } else {
            self.commit_checkpoint(checkpoint);
        }
        result
    }

    fn expression_inner(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::Custom(parser) => {
                self.transaction(|context| parser(context))
                    .ok()
                    .map(|matched| Fragment {
                        nodes: matched.nodes,
                        captures: matched.captures,
                    })
            }
            Expr::Backreference(name) => {
                let text = self.captured(name);
                if let Some(text) = text.filter(|text| self.remaining().starts_with(text)) {
                    self.position += text.len();
                    self.matched_position = self.position;
                    Some(Fragment::default())
                } else {
                    self.fail(name);
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
                self.fail(message);
                None
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
            Expr::Optional(child) | Expr::JavaOptional(child) => {
                let start = self.position;
                let count = self.nodes.len();
                match self.expression(child, depth) {
                    Some(fragment) => Some(fragment),
                    None => {
                        self.position = start;
                        self.nodes.truncate(count);
                        if matches!(expression, Expr::JavaOptional(_)) {
                            self.java_failed_atom(child);
                        }
                        Some(Fragment::default())
                    }
                }
            }
            Expr::Repeat { child, min, max } | Expr::JavaRepeat { child, min, max } => {
                let java = matches!(expression, Expr::JavaRepeat { .. });
                if max.is_some_and(|max| max < *min) {
                    self.fail("valid repetition bounds");
                    return None;
                }
                let mut result = Fragment::default();
                let mut iterations = 0;
                while (java && iterations == 0) || max.is_none_or(|max| iterations < max) {
                    let start = self.position;
                    let count = self.nodes.len();
                    match self.expression(child, depth) {
                        Some(mut fragment) => {
                            if self.position == start && max.is_none() && !java {
                                self.nodes.truncate(count);
                                self.fail("progress in unbounded repetition");
                                return None;
                            }
                            iterations += 1;
                            result.nodes.append(&mut fragment.nodes);
                            result.captures.append(&mut fragment.captures);
                            if java && self.position == start {
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
                (iterations >= *min && max.is_none_or(|max| iterations <= max)).then_some(result)
            }
            Expr::Lookahead { child, positive } => {
                let checkpoint = self.checkpoint();
                let farthest = self.farthest;
                let expected = self.expected.clone();
                let diagnostic_frames = self.diagnostic_frames.clone();
                let matched = self.expression(child, depth).is_some();
                self.restore(checkpoint);
                if !*positive || matched {
                    self.farthest = farthest;
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
            Expr::Literal(literal) => {
                if self.input[self.position..].starts_with(literal) {
                    self.position += literal.len();
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
                self.position += 1;
                while bytes
                    .get(self.position)
                    .is_some_and(|c| c.is_ascii_alphanumeric() || *c == b'_')
                {
                    self.position += 1;
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
            Expr::Quoted(quote) => {
                if !matches!(quote, '\'' | '"') {
                    self.fail("single or double quote delimiter");
                    return None;
                }
                if !self.remaining().starts_with(*quote) {
                    self.fail("opening quote");
                    return None;
                }
                self.position += quote.len_utf8();
                loop {
                    let Some(next) = self.remaining().chars().next() else {
                        self.fail("closing quote");
                        return None;
                    };
                    self.position += next.len_utf8();
                    if next == *quote {
                        self.matched_position = self.position;
                        return Some(Fragment::default());
                    }
                    if next == '\\' {
                        let Some(escaped) = self.remaining().chars().next() else {
                            self.fail("closing quote");
                            return None;
                        };
                        self.position += escaped.len_utf8();
                    }
                }
            }
            Expr::Rule(rule) => self.rule(*rule, depth).map(|id| Fragment {
                nodes: vec![id],
                captures: vec![],
            }),
            Expr::Sequence(elements) => {
                let mut result = Fragment::default();
                self.skip();
                for element in elements {
                    let mut fragment = self.expression(element, depth)?;
                    result.nodes.append(&mut fragment.nodes);
                    result.captures.append(&mut fragment.captures);
                    self.skip();
                }
                Some(result)
            }
            Expr::Choice(alternatives) => self.ordered_choice(alternatives.iter(), depth),
            Expr::LongestChoice(alternatives) => self.longest_choice(alternatives, depth),
            Expr::PredictiveChoice {
                alternatives,
                predictors,
            } => self.predictive_choice(alternatives, predictors, depth),
            Expr::Capture(name, expression) => {
                let start = self.position;
                let mut fragment = self.expression(expression, depth)?;
                let span = self.span(start);
                self.captures.push(name, span);
                fragment.captures.push(Capture {
                    name,
                    span: self.span(start),
                    nodes: fragment.nodes.clone(),
                });
                Some(fragment)
            }
            Expr::RuleEffects { child, effects } => {
                if effects.scope_mode.is_some() {
                    self.scopes_mut().enter();
                }
                let fragment = self.expression(child, depth)?;
                if effects.scope_mode.is_some() {
                    self.scopes_mut().leave();
                }
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
                Some(fragment)
            }
            Expr::TriviaScope { child, whitespace } => {
                self.with_trivia(*whitespace, |context| context.expression(child, depth))
            }
            Expr::TextValue(child) | Expr::ValueBoundary(child) => {
                let start = self.position;
                let fragment = self.expression(child, depth)?;
                let node_id = self.nodes.len();
                self.nodes.push(Node {
                    rule: if matches!(expression, Expr::TextValue(_)) {
                        TEXT_VALUE_RULE
                    } else {
                        VALUE_BOUNDARY_RULE
                    },
                    span: self.span(start),
                    children: fragment.nodes,
                    captures: vec![],
                });
                Some(Fragment {
                    nodes: vec![node_id],
                    captures: fragment.captures,
                })
            }
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

    fn longest_choice(&mut self, alternatives: &[Expr], depth: usize) -> Option<Fragment> {
        let start = self.position;
        let node_start = self.nodes.len();
        let mut winner: Option<ChoiceWinner> = None;
        for alternative in alternatives {
            let checkpoint = self.checkpoint();
            if let Some(fragment) = self.expression(alternative, depth) {
                let consumed = self.position - start;
                let replaces = winner
                    .as_ref()
                    .is_none_or(|current| consumed > current.position - start);
                if replaces {
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
                        state: Rc::clone(&self.state),
                        scopes: {
                            let mut scopes = self.scopes.clone();
                            scopes.commit_checkpoint();
                            scopes
                        },
                        fragment,
                    });
                }
            }
            self.restore(checkpoint);
        }
        winner.map(|mut winner| {
            debug_assert_eq!(self.nodes.len(), winner.node_start);
            self.position = winner.position;
            self.matched_position = winner.matched_position;
            self.nodes.extend(winner.nodes);
            self.captures = winner.captures;
            self.state = winner.state;
            winner
                .scopes
                .retain_journal_entry_count(self.scopes.journal_entries_created());
            self.scopes = winner.scopes;
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
            if let Some(fragment) = self.expression(alternative, depth) {
                return Some(fragment);
            }
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
        if alternatives.len() != predictors.len() {
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

    fn prediction_after_trivia<'b>(&self, raw: &'b str) -> &'b str {
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

    fn skip(&mut self) {
        if !self.whitespace {
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
        if let Expr::TextValue(child)
        | Expr::ValueBoundary(child)
        | Expr::TriviaScope { child, .. } = child
        {
            self.java_failed_atom(child);
            return;
        }
        if matches!(
            child,
            Expr::Literal(_) | Expr::Any | Expr::CharRange(_, _) | Expr::Except(_)
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
            self.position += 1;
            after = self.digits();
        }
        if !before && !after {
            self.fail("number");
            self.position = start;
            return false;
        }
        let exponent = self.position;
        if self
            .input
            .as_bytes()
            .get(self.position)
            .is_some_and(|c| matches!(c, b'e' | b'E'))
        {
            self.position += 1;
            self.sign();
            if !self.digits() {
                self.position = exponent;
            }
        }
        true
    }

    fn sign(&mut self) {
        if self
            .input
            .as_bytes()
            .get(self.position)
            .is_some_and(|c| matches!(c, b'+' | b'-'))
        {
            self.position += 1;
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
            self.position += 1;
        }
        self.position > start
    }
}

fn memo_safe_rules(rules: &[Rule]) -> Vec<bool> {
    let mut references = vec![Vec::new(); rules.len()];
    let mut safe = rules
        .iter()
        .enumerate()
        .map(|(id, rule)| expression_is_memo_safe(&rule.expression, &mut references[id]))
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

fn expression_is_memo_safe(expression: &Expr, references: &mut Vec<usize>) -> bool {
    match expression {
        Expr::Custom(_) | Expr::Backreference(_) => false,
        Expr::Rule(id) => {
            references.push(*id);
            true
        }
        Expr::Sequence(children) | Expr::Choice(children) | Expr::LongestChoice(children) => {
            children
                .iter()
                .all(|child| expression_is_memo_safe(child, references))
        }
        Expr::PredictiveChoice { alternatives, .. } => alternatives
            .iter()
            .all(|child| expression_is_memo_safe(child, references)),
        Expr::Capture(_, child)
        | Expr::TextValue(child)
        | Expr::ValueBoundary(child)
        | Expr::Optional(child)
        | Expr::JavaOptional(child)
        | Expr::Repeat { child, .. }
        | Expr::JavaRepeat { child, .. }
        | Expr::Lookahead { child, .. }
        | Expr::RuleEffects { child, .. }
        | Expr::TriviaScope { child, .. } => expression_is_memo_safe(child, references),
        _ => true,
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
