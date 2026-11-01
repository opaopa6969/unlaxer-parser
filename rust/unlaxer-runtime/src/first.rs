//! FIRST sets derived from the combinator graph (#300, the Rust counterpart of Java #292 / case 37).
//!
//! For every rule the runtime computes, once per grammar, which code points a successful match
//! that consumes input can start with, whether the rule can succeed without consuming input
//! (`nullable`), and whether its start cannot be modelled at all (`unknown`). The table is the
//! least fixed point over the rule graph; inline expressions are evaluated structurally against
//! that table when a candidate is about to be tried.
//!
//! Sequence trivia is skipped by the runtime (`ParseContext::skip`), not by a grammar element,
//! so a sequence contributes a trivia marker instead of code points. `TriviaScope` resolves the
//! marker, and otherwise the caller's current trivia policy decides it at the check site.
//!
//! The sets are only used to skip candidates whose evaluation is certain to fail. Anything that
//! is not modelled (custom parsers, backreferences, `Until` / `JavaUntil`, and lookaheads over
//! such children) is `unknown` and therefore never skipped.
//!
//! [`FirstSets::of`] is the read-only counterpart of Java `FirstSets.of(Parser)` (#853): it runs
//! the same fixed point into a snapshot owned by the caller. The parser never reads it, so a
//! query cannot change a later parse, and nothing is computed unless it is called. Unlike the
//! parser's table, a query is bounded in depth, nodes and rounds and answers `unknown` beyond.
//! The structural walk never nests deeper than the parser may nest from the same position
//! ([`EXPRESSION_NESTING`] minus the active expression depth, #1027). A deeper child is
//! `unknown`: the candidate is evaluated, so the parser's own resource guard reports it,
//! exactly as with exclusion off, instead of this walk overflowing the stack first.
use std::fmt;
use std::sync::{Arc, Mutex, OnceLock, Weak};

use crate::{Expr, Rule};

/// Active expression nesting the parser evaluates below (`ParseContext::expression`).
pub(crate) const EXPRESSION_NESTING: usize = 1024;

/// Whether a sequence at the start of an expression may consume trivia first.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub(crate) enum Trivia {
    /// No trivia can be consumed before the first code point.
    #[default]
    None,
    /// Trivia is consumed when the caller's trivia policy is enabled.
    Dynamic,
    /// Trivia is consumed regardless of the caller's policy (an enclosing `TriviaScope(true)`).
    Always,
}

/// The code points a successful match can start with, plus whether it can succeed consuming
/// nothing (Java `FirstSet`). A conservative over-approximation: the `may_start*` queries may
/// answer `true` for a start the expression rejects, never `false` for one it could accept.
///
/// ASCII is exact; every other scalar shares one "may start with non-ASCII" flag. `unknown`
/// marks a start the analysis does not model and answers `true` everywhere. Unlike Java, a
/// sequence records that the runtime may skip trivia before its first element, see
/// [`skips_trivia`](Self::skips_trivia). Values are plain copies, so changing one never reaches
/// a parser.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Hash)]
pub struct FirstSet {
    ascii: u128,
    non_ascii: bool,
    nullable: bool,
    unknown: bool,
    trivia: Trivia,
}

impl FirstSet {
    /// Matches nothing and cannot succeed on the empty input; the identity of [`union`](Self::union).
    pub const EMPTY: Self = Self {
        ascii: 0,
        non_ascii: false,
        nullable: false,
        unknown: false,
        trivia: Trivia::None,
    };
    /// Succeeds consuming nothing, so it must be tried at every position.
    pub const EPSILON: Self = Self {
        nullable: true,
        ..Self::EMPTY
    };
    /// Never excludes anything. Used for every construct the analysis does not model.
    pub const UNKNOWN: Self = Self {
        ascii: u128::MAX,
        non_ascii: true,
        nullable: true,
        unknown: true,
        trivia: Trivia::Always,
    };
    const ANY: Self = Self {
        ascii: u128::MAX,
        non_ascii: true,
        ..Self::EMPTY
    };

    /// A set holding exactly `code_points`, none of them starting an empty match (Java
    /// `FirstSet.ofCodePoints`). `char` excludes the negative and surrogate values Java accepts.
    pub fn of_code_points(code_points: impl IntoIterator<Item = char>) -> Self {
        let mut set = Self::EMPTY;
        for c in code_points {
            set.add_char(c);
        }
        set
    }

    /// The same set, additionally able to succeed without consuming anything.
    #[must_use]
    pub const fn as_nullable(self) -> Self {
        Self {
            nullable: true,
            ..self
        }
    }

    /// The same set, additionally able to start with any non-ASCII scalar.
    #[must_use]
    pub const fn with_non_ascii(self) -> Self {
        Self {
            non_ascii: true,
            ..self
        }
    }

    /// Both start sets, nullable if either is; [`UNKNOWN`](Self::UNKNOWN) if either is unknown.
    #[must_use]
    pub fn union(self, other: Self) -> Self {
        if self.unknown || other.unknown {
            return Self::UNKNOWN;
        }
        self.join(other)
    }

    /// Adds `other`'s start set but not its ability to match the empty input.
    #[must_use]
    pub fn union_starts(mut self, other: Self) -> Self {
        if self.unknown || other.unknown {
            return Self::UNKNOWN;
        }
        self.join_starts(other);
        self
    }

    /// Whether the analysis could not model this start; such a set excludes nothing.
    pub const fn is_unknown(self) -> bool {
        self.unknown
    }

    /// Whether a match can succeed consuming nothing; such a set excludes nothing.
    pub const fn is_nullable(self) -> bool {
        self.nullable
    }

    /// Whether the runtime may skip trivia (whitespace, `//` and `/* */` comments) before the
    /// first element when the parse's trivia policy is `whitespace`. Java has no implicit trivia.
    pub const fn skips_trivia(self, whitespace: bool) -> bool {
        match self.trivia {
            Trivia::None => false,
            Trivia::Dynamic => whitespace,
            Trivia::Always => true,
        }
    }

    /// Whether a match can start with `next` (Java `mayStartWith`); `None` is the end of input.
    /// When [`skips_trivia`](Self::skips_trivia) holds, trivia whitespace and `/` (a possible
    /// comment) are accepted as well. `whitespace` is the parse's trivia policy.
    pub fn may_start_with(self, next: Option<char>, whitespace: bool) -> bool {
        if self.unknown || self.nullable {
            return true;
        }
        let Some(c) = next else {
            return false;
        };
        if self.skips_trivia(whitespace)
            && matches!(c, ' ' | '\t' | '\n' | '\r' | '\u{b}' | '\u{c}' | '/')
        {
            return true;
        }
        if c.is_ascii() {
            self.ascii & (1 << (c as u32)) != 0
        } else {
            self.non_ascii
        }
    }

    fn of_char(c: char) -> Self {
        let mut set = Self::EMPTY;
        set.add_char(c);
        set
    }

    fn of_chars(chars: &str) -> Self {
        Self::of_code_points(chars.chars())
    }

    fn add_char(&mut self, c: char) {
        if c.is_ascii() {
            self.ascii |= 1 << (c as u32);
        } else {
            self.non_ascii = true;
        }
    }

    /// Union of the start sets; `nullable` is left to the caller.
    fn join_starts(&mut self, other: Self) {
        self.ascii |= other.ascii;
        self.non_ascii |= other.non_ascii;
        self.unknown |= other.unknown;
        self.trivia = self.trivia.max(other.trivia);
    }

    fn join(mut self, other: Self) -> Self {
        self.join_starts(other);
        self.nullable |= other.nullable;
        self
    }

    /// Whether a match may start at `rest`, the input from the candidate position; this is the
    /// check the parser itself runs. `false` proves that the expression fails there.
    pub fn may_start(self, rest: &str, whitespace: bool) -> bool {
        if self.unknown || self.nullable {
            return true;
        }
        let Some(&byte) = rest.as_bytes().first() else {
            // A non-nullable expression must consume a code point.
            return false;
        };
        let trivia = match self.trivia {
            Trivia::None => false,
            Trivia::Dynamic => whitespace,
            Trivia::Always => true,
        };
        if trivia
            && (matches!(byte, b' ' | b'\t' | b'\n' | b'\r' | 11 | 12)
                || rest.starts_with("//")
                || rest.starts_with("/*"))
        {
            return true;
        }
        if byte.is_ascii() {
            self.ascii & (1 << byte) != 0
        } else {
            self.non_ascii
        }
    }
}

/// Java `FirstSet.toString`: `FirstSet[unknown]`, or `FirstSet[` + `nullable ` + the ASCII
/// starts (printable as is, others as `\uXXXX`) + `+nonAscii` + `]`. Trivia is not shown.
impl fmt::Display for FirstSet {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        if self.unknown {
            return formatter.write_str("FirstSet[unknown]");
        }
        formatter.write_str("FirstSet[")?;
        if self.nullable {
            formatter.write_str("nullable ")?;
        }
        for code_point in 0..128u8 {
            if self.ascii & (1 << code_point) != 0 {
                if (33..127).contains(&code_point) {
                    write!(formatter, "{}", char::from(code_point))?;
                } else {
                    write!(formatter, "\\u{code_point:04x}")?;
                }
            }
        }
        if self.non_ascii {
            formatter.write_str("+nonAscii")?;
        }
        formatter.write_str("]")
    }
}

/// FIRST sets of every rule of a grammar, computed by the same fixed point the parser uses for
/// candidate exclusion (Java `FirstSets.of(Parser)` for each rule). An owned snapshot: building
/// it never runs a custom parser or predicate, and the parser neither reads nor caches it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FirstSets {
    rules: Box<[FirstSet]>,
}

impl FirstSets {
    /// Analyses `grammar` once. A `Rule(id)` reference outside `grammar` is
    /// [`FirstSet::UNKNOWN`], as in the parser.
    ///
    /// The query is bounded like Java `FirstSets`: a grammar whose expressions nest deeper than
    /// the parser's 1,024 active expression frames, that has more than 100,000 expression
    /// nodes, or whose fixed point takes more than 1,000 rounds, gets
    /// [`FirstSet::UNKNOWN`] for every rule (still one per rule) instead of exhausting the stack
    /// or running unbounded.
    pub fn of(grammar: &[Rule]) -> Self {
        let rules = within_query_budget(grammar.iter().map(|rule| &rule.expression))
            .then(|| bounded_compute(grammar))
            .flatten()
            .unwrap_or_else(|| vec![FirstSet::UNKNOWN; grammar.len()]);
        Self {
            rules: rules.into_boxed_slice(),
        }
    }

    /// The set of rule `id`, or `None` when `grammar` has no such rule.
    pub fn rule(&self, id: usize) -> Option<FirstSet> {
        self.rules.get(id).copied()
    }

    /// The set of an inline expression whose `Rule(id)` references this grammar;
    /// [`FirstSet::UNKNOWN`] when `expression` exceeds the depth or node bound of [`of`](Self::of).
    pub fn expression(&self, expression: &Expr) -> FirstSet {
        if within_query_budget([expression]) {
            of(expression, &self.rules, MAX_QUERY_DEPTH)
        } else {
            FirstSet::UNKNOWN
        }
    }

    /// Every rule's set, indexed by rule id.
    pub fn rules(&self) -> &[FirstSet] {
        &self.rules
    }
}

/// Nested `of` frames a public query may run: the parser's own budget of active expression
/// frames ("expression nesting below 1024" in `ParseContext::expression`), so every expression
/// the parser can evaluate down to its deepest element is analysed exactly.
const MAX_QUERY_DEPTH: usize = 1024;
/// Expression nodes a public query may analyse (Java `FirstSets.MAX_NODES`).
const MAX_QUERY_NODES: usize = 100_000;
/// Fixed-point rounds a public query may run (Java `FirstSets.MAX_ROUNDS`). The lattice is
/// finite, but a rule chain that refers forward only moves one rule per round.
const MAX_QUERY_ROUNDS: usize = 1_000;

/// Whether `of` over `expressions` stays within the query depth and node bounds. Walks with an
/// explicit stack, so measuring an arbitrarily deep or wide graph cannot exhaust the thread
/// stack. Visits exactly the children `of` may recurse into (more when a sequence stops early).
fn within_query_budget<'a>(expressions: impl IntoIterator<Item = &'a Expr>) -> bool {
    let mut pending: Vec<(&Expr, usize)> = Vec::new();
    let mut nodes = 0;
    let mut push = |pending: &mut Vec<(&'a Expr, usize)>, expression: &'a Expr, depth: usize| {
        nodes += 1;
        pending.push((expression, depth));
        depth <= MAX_QUERY_DEPTH && nodes <= MAX_QUERY_NODES
    };
    for expression in expressions {
        if !push(&mut pending, expression, 1) {
            return false;
        }
        while let Some((expression, depth)) = pending.pop() {
            let children: &[Expr] = match expression {
                Expr::Sequence(children)
                | Expr::Choice(children)
                | Expr::LongestChoice(children)
                | Expr::UniqueLongestChoice(children)
                | Expr::PredictiveChoice {
                    alternatives: children,
                    ..
                } => children,
                Expr::JavaTerminatedRepeat {
                    child, terminator, ..
                } => {
                    if !push(&mut pending, terminator, depth + 1) {
                        return false;
                    }
                    std::slice::from_ref(&**child)
                }
                Expr::OwnedCapture(_, child)
                | Expr::CaptureScope(child)
                | Expr::Capture(_, child)
                | Expr::MetadataCapture(_, child)
                | Expr::TextValue(child)
                | Expr::CaptureBoundary(_, child)
                | Expr::InputCaptureBoundary(_, child)
                | Expr::ValueBoundary(child)
                | Expr::RuleEffects { child, .. }
                | Expr::CaptureEquality { child, .. }
                | Expr::TriviaScope {
                    child,
                    line_comments: false,
                    ..
                }
                | Expr::Optional(child)
                | Expr::JavaOptional(child)
                | Expr::Repeat { child, .. }
                | Expr::JavaRepeat { child, .. }
                | Expr::Lookahead { child, .. } => std::slice::from_ref(&**child),
                // `of` answers these without looking at any child. Listed without a wildcard so
                // that a new variant must decide whether `of` recurses into it.
                Expr::Lexical(..)
                | Expr::Literal(_)
                | Expr::JavaLiteral(_)
                | Expr::CaseInsensitive(_)
                | Expr::Number
                | Expr::Identifier
                | Expr::Grapheme
                | Expr::CodeStart
                | Expr::CodeEnd
                | Expr::LongCodeBlock
                | Expr::Quoted(_)
                | Expr::Rule(_)
                | Expr::NonOrdered(_)
                | Expr::Replay(_)
                | Expr::MatchOnly(_)
                | Expr::InvertMatch(_)
                | Expr::StopInvert(_)
                | Expr::StopConsume(_)
                | Expr::NamePredicate { .. }
                | Expr::NameResolutionScope { .. }
                | Expr::SemanticPredicate { .. }
                | Expr::SemanticPredicateScope { .. }
                | Expr::LexicalTriviaScope { .. }
                | Expr::LexicalContextScope { .. }
                | Expr::TriviaScope {
                    line_comments: true,
                    ..
                }
                | Expr::ZeroMatch(_)
                | Expr::Any
                | Expr::Eof
                | Expr::Empty
                | Expr::CharRange(..)
                | Expr::Character { .. }
                | Expr::Except(_)
                | Expr::Until(_)
                | Expr::JavaLookahead { .. }
                | Expr::JavaEmpty
                | Expr::JavaEmptyWord
                | Expr::JavaEmptyLiteral
                | Expr::JavaUntil(_)
                | Expr::Error(_)
                | Expr::Recovery { .. }
                | Expr::Commit { .. }
                | Expr::Label { .. }
                | Expr::CatchLabels { .. }
                | Expr::Custom(_)
                | Expr::CustomWith { .. }
                | Expr::Backreference(_) => &[],
            };
            for child in children {
                if !push(&mut pending, child, depth + 1) {
                    return false;
                }
            }
        }
    }
    true
}

/// [`compute`] with Java's round bound; `None` when the fixed point is not reached in time.
fn bounded_compute(rules: &[Rule]) -> Option<Vec<FirstSet>> {
    let mut table = vec![FirstSet::EMPTY; rules.len()];
    for _ in 0..=MAX_QUERY_ROUNDS {
        let mut changed = false;
        for (id, rule) in rules.iter().enumerate() {
            let set = of(&rule.expression, &table, MAX_QUERY_DEPTH).join(table[id]);
            if set != table[id] {
                table[id] = set;
                changed = true;
            }
        }
        if !changed {
            return Some(table);
        }
    }
    None
}

/// The FIRST set of an expression; `Rule(id)` is looked up in `rules` (the fixed-point table).
/// `budget` is how many nested expression levels the parser may still evaluate here; a
/// subtree reaching past it is `unknown` (it propagates, so the whole set is `UNKNOWN`).
pub(crate) fn of(expression: &Expr, rules: &[FirstSet], budget: usize) -> FirstSet {
    let Some(budget) = budget.checked_sub(1) else {
        return FirstSet::UNKNOWN;
    };
    match expression {
        Expr::Repeat {
            min,
            max: Some(max),
            ..
        }
        | Expr::JavaRepeat {
            min,
            max: Some(max),
            ..
        }
        | Expr::JavaTerminatedRepeat {
            min,
            max: Some(max),
            ..
        } if *max == 0 || *max < *min => {
            if *min == 0 && *max == 0 {
                FirstSet::EPSILON
            } else {
                FirstSet::EMPTY
            }
        }
        Expr::Literal(text) | Expr::JavaLiteral(text) => text
            .chars()
            .next()
            .map_or(FirstSet::EPSILON, FirstSet::of_char),
        // Case-equivalent starting scalars may be outside the forward upper/lower pair.
        Expr::CaseInsensitive(text) => {
            if text.is_empty() {
                FirstSet::EMPTY
            } else {
                FirstSet::ANY
            }
        }
        Expr::Number => FirstSet::of_chars("+-.0123456789"),
        Expr::Identifier => {
            FirstSet::of_chars("_abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
        }
        Expr::CodeStart | Expr::CodeEnd | Expr::LongCodeBlock => FirstSet::of_char('`'),
        Expr::Quoted(quote) => FirstSet::of_char(*quote),
        // Outside the table (a grammar installed without its analysis) nothing is proven.
        Expr::Rule(id) => rules.get(*id).copied().unwrap_or(FirstSet::UNKNOWN),
        Expr::Sequence(elements) => {
            // ParseContext::skip runs before the first element when trivia is enabled.
            let mut set = FirstSet {
                nullable: true,
                trivia: Trivia::Dynamic,
                ..FirstSet::EMPTY
            };
            for element in elements {
                let element = of(element, rules, budget);
                if element.unknown {
                    return FirstSet::UNKNOWN;
                }
                set.join_starts(element);
                if !element.nullable {
                    set.nullable = false;
                    break;
                }
            }
            set
        }
        // A loop, not a fold: a closure frame per level would make this walk's debug stack
        // deeper than the parse it is bounded by (#1027).
        Expr::Choice(alternatives)
        | Expr::LongestChoice(alternatives)
        | Expr::UniqueLongestChoice(alternatives)
        | Expr::PredictiveChoice { alternatives, .. } => {
            let mut set = FirstSet::EMPTY;
            for alternative in alternatives {
                set = set.join(of(alternative, rules, budget));
            }
            set
        }
        Expr::OwnedCapture(_, child)
        | Expr::CaptureScope(child)
        | Expr::Capture(_, child)
        | Expr::MetadataCapture(_, child)
        | Expr::TextValue(child)
        | Expr::CaptureBoundary(_, child)
        | Expr::InputCaptureBoundary(_, child)
        | Expr::ValueBoundary(child)
        | Expr::RuleEffects { child, .. }
        | Expr::CaptureEquality { child, .. } => of(child, rules, budget),
        Expr::LexicalTriviaScope { .. } | Expr::LexicalContextScope { .. } => FirstSet::UNKNOWN,
        // Comment-only trivia may start with `/`; never exclude a candidate under it.
        Expr::TriviaScope {
            line_comments: true,
            ..
        } => FirstSet::UNKNOWN,
        Expr::TriviaScope {
            child, whitespace, ..
        } => {
            let mut set = of(child, rules, budget);
            set.trivia = match (set.trivia, whitespace) {
                (Trivia::Dynamic, true) => Trivia::Always,
                (Trivia::Dynamic, false) => Trivia::None,
                (trivia, _) => trivia,
            };
            set
        }
        Expr::Optional(child) | Expr::JavaOptional(child) => FirstSet {
            nullable: true,
            ..of(child, rules, budget)
        },
        Expr::Repeat { child, min, .. } | Expr::JavaRepeat { child, min, .. } => {
            let mut set = of(child, rules, budget);
            set.nullable |= *min == 0;
            set
        }
        // Java FirstSets: the body, then the terminator, which may also end the repetition.
        Expr::JavaTerminatedRepeat {
            child,
            terminator,
            min,
            ..
        } => {
            let mut set = of(child, rules, budget).join(of(terminator, rules, budget));
            set.nullable |= *min == 0;
            set
        }
        // A lookahead consumes nothing, but its child still runs at this position.
        Expr::Lookahead { child, .. } => {
            if of(child, rules, budget).unknown {
                FirstSet::UNKNOWN
            } else {
                FirstSet::EPSILON
            }
        }
        Expr::Grapheme | Expr::Any => FirstSet::ANY,
        Expr::CharRange(min, max) => {
            let mut set = FirstSet::EMPTY;
            if min <= max {
                for byte in (*min as u32)..=(*max as u32).min(127) {
                    set.ascii |= 1 << byte;
                }
                set.non_ascii = *max as u32 >= 128;
            }
            set
        }
        Expr::Except(excluded) => {
            let mut set = FirstSet::ANY;
            for c in excluded.chars().filter(char::is_ascii) {
                set.ascii &= !(1 << (c as u32));
            }
            set
        }
        Expr::Eof
        | Expr::Empty
        | Expr::JavaEmpty
        | Expr::JavaEmptyLiteral
        | Expr::JavaLookahead { .. } => FirstSet::EPSILON,
        Expr::Error(_) | Expr::JavaEmptyWord => FirstSet::EMPTY,
        Expr::Character { .. }
        | Expr::MatchOnly(_)
        | Expr::InvertMatch(_)
        | Expr::StopInvert(_)
        | Expr::StopConsume(_)
        | Expr::NonOrdered(_)
        | Expr::Lexical(_, _)
        | Expr::Until(_)
        | Expr::JavaUntil(_)
        | Expr::Custom(_)
        | Expr::CustomWith { .. }
        | Expr::Recovery { .. }
        | Expr::Replay(_)
        | Expr::ZeroMatch(_)
        | Expr::Backreference(_)
        | Expr::NamePredicate { .. }
        | Expr::SemanticPredicate { .. }
        | Expr::NameResolutionScope { .. }
        | Expr::SemanticPredicateScope { .. } => FirstSet::UNKNOWN,
        // A raising operator turns a failure at any code point into a committed or labeled
        // failure, so skipping it from its child's FIRST set would change the result (#700).
        Expr::Commit { .. } | Expr::Label { .. } | Expr::CatchLabels { .. } => FirstSet::UNKNOWN,
    }
}

/// Least fixed point of the rule FIRST sets. Convergence compares whole sets, not
/// `may_start` answers: a nullable set accepts every code point, yet its start set still
/// flows into enclosing sequences (the Java case 37 fixed-point bug). A rule body nesting
/// past `budget` is `UNKNOWN`, which is a fixed point of the union.
pub(crate) fn compute(rules: &[Rule], budget: usize) -> Vec<FirstSet> {
    let mut table = vec![FirstSet::EMPTY; rules.len()];
    loop {
        let mut changed = false;
        for (id, rule) in rules.iter().enumerate() {
            let set = of(&rule.expression, &table, budget).join(table[id]);
            if set != table[id] {
                table[id] = set;
                changed = true;
            }
        }
        if !changed {
            return table;
        }
    }
}

/// The grammar, its table and the walk budget the table was computed with.
type CacheEntry = (Weak<[Rule]>, Arc<[FirstSet]>, usize);

/// FIRST tables of long-lived shared grammars, keyed by allocation. A `Weak` keeps the
/// allocation (not the rules) alive, so another grammar cannot reuse a cached address.
fn cache() -> &'static Mutex<Vec<CacheEntry>> {
    static CACHE: OnceLock<Mutex<Vec<CacheEntry>>> = OnceLock::new();
    CACHE.get_or_init(|| Mutex::new(Vec::new()))
}

/// The FIRST table for `grammar`. Grammars shared beyond this parse (for example a generated
/// parser's `OnceLock<SharedGrammar>`) are analysed once; a grammar owned only by this parse
/// is analysed without being cached. `budget` bounds the walk on the caller's stack: a cached
/// table computed with at least this budget is reused, a smaller one is recomputed once.
pub(crate) fn table(grammar: &Arc<[Rule]>, budget: usize) -> Arc<[FirstSet]> {
    if Arc::strong_count(grammar) <= 1 {
        return Arc::from(compute(grammar, budget));
    }
    let mut entries = cache()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let cached = entries
        .iter()
        .position(|(weak, _, _)| std::ptr::eq(weak.as_ptr(), Arc::as_ptr(grammar)));
    if let Some((_, table, computed)) = cached.map(|index| &entries[index]) {
        if *computed >= budget {
            return Arc::clone(table);
        }
    }
    let table: Arc<[FirstSet]> = Arc::from(compute(grammar, budget));
    if let Some(index) = cached {
        entries[index] = (Arc::downgrade(grammar), Arc::clone(&table), budget);
        return table;
    }
    entries.retain(|(weak, _, _)| weak.strong_count() > 0);
    entries.push((Arc::downgrade(grammar), Arc::clone(&table), budget));
    table
}

/// How candidate exclusion behaves; read once from `UNLAXER_CANDIDATE_EXCLUSION`
/// (`off` / `audit`, anything else is `on`) and overridable per thread for tests.
#[doc(hidden)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CandidateExclusion {
    /// Never skip candidates (the pre-#300 behaviour).
    Off,
    /// Skip candidates whose FIRST set proves failure (deferred diagnostics only).
    On,
    /// Evaluate every candidate that would be skipped and panic if it succeeds.
    Audit,
}

thread_local! {
    static OVERRIDE: std::cell::Cell<Option<CandidateExclusion>> =
        const { std::cell::Cell::new(None) };
}

/// Test and benchmark hook: overrides the exclusion mode for contexts created on this thread.
#[doc(hidden)]
pub fn set_candidate_exclusion_for_current_thread(mode: Option<CandidateExclusion>) {
    OVERRIDE.with(|cell| cell.set(mode));
}

pub(crate) fn mode() -> CandidateExclusion {
    if let Some(mode) = OVERRIDE.with(std::cell::Cell::get) {
        return mode;
    }
    static MODE: OnceLock<CandidateExclusion> = OnceLock::new();
    *MODE.get_or_init(
        || match std::env::var("UNLAXER_CANDIDATE_EXCLUSION").as_deref() {
            Ok("off") => CandidateExclusion::Off,
            Ok("audit") => CandidateExclusion::Audit,
            _ => CandidateExclusion::On,
        },
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rule(name: &'static str, expression: Expr) -> Rule {
        Rule { name, expression }
    }

    #[test]
    fn a_nullable_repetition_keeps_growing_its_start_set_until_the_fixed_point() {
        // Java case 37's convergence bug: `Annotation*` is nullable in every round, so
        // comparing `may_start` answers would stop before `@` reached the declaration.
        let rules = vec![
            rule(
                "declaration",
                Expr::sequence([Expr::Rule(1).zero_or_more(), Expr::Identifier]),
            ),
            rule(
                "annotation",
                Expr::sequence([Expr::literal("@"), Expr::Rule(2)]),
            ),
            rule("name", Expr::Identifier),
        ];
        let table = compute(&rules, EXPRESSION_NESTING);
        assert!(table[0].may_start("@root x", false));
        assert!(table[0].may_start("x", false));
        assert!(!table[0].may_start("1", false));
        assert!(!table[0].may_start("", false));
        assert!(!table[1].nullable);
    }

    #[test]
    fn left_recursion_through_nullable_prefixes_converges() {
        let rules = vec![rule(
            "list",
            Expr::choice([
                Expr::sequence([Expr::Rule(0), Expr::literal(",")]),
                Expr::literal("x").optional(),
            ]),
        )];
        let table = compute(&rules, EXPRESSION_NESTING);
        assert!(table[0].nullable);
        assert_eq!(table[0].ascii, (1 << b'x') | (1 << b','));
    }

    #[test]
    fn the_bounded_query_reaches_the_parser_fixed_point() {
        let grammars = [
            vec![
                rule(
                    "declaration",
                    Expr::sequence([Expr::Rule(1).zero_or_more(), Expr::Identifier]),
                ),
                rule(
                    "annotation",
                    Expr::sequence([Expr::literal("@"), Expr::Rule(2)]),
                ),
                rule("name", Expr::Identifier),
            ],
            vec![rule(
                "list",
                Expr::choice([
                    Expr::sequence([Expr::Rule(0), Expr::literal(",")]),
                    Expr::literal("x").optional(),
                ]),
            )],
            vec![rule("dangling", Expr::Rule(3))],
            vec![],
        ];
        for rules in grammars {
            assert_eq!(
                bounded_compute(&rules),
                Some(compute(&rules, MAX_QUERY_DEPTH))
            );
        }
        // Rule i refers to rule i + 1: n rules take n + 1 rounds, the last one unchanged.
        let chain = |count: usize| -> Vec<Rule> {
            (0..count)
                .map(|id| match id + 1 {
                    next if next < count => rule("chain", Expr::Rule(next)),
                    _ => rule("chain", Expr::literal("a")),
                })
                .collect()
        };
        let longest = chain(MAX_QUERY_ROUNDS);
        assert_eq!(
            bounded_compute(&longest),
            Some(compute(&longest, MAX_QUERY_DEPTH))
        );
        assert_eq!(bounded_compute(&chain(MAX_QUERY_ROUNDS + 1)), None);
    }

    #[test]
    fn sequence_trivia_follows_the_caller_policy_unless_a_scope_fixes_it() {
        let sequence = Expr::sequence([Expr::literal("a")]);
        let set = of(&sequence, &[], EXPRESSION_NESTING);
        assert!(set.may_start(" a", true));
        assert!(set.may_start("/* c */a", true));
        assert!(set.may_start("// c\na", true));
        assert!(!set.may_start(" a", false));
        assert!(!set.may_start("/a", true));
        let fixed = of(
            &sequence.clone().trivia_scope(true),
            &[],
            EXPRESSION_NESTING,
        );
        assert!(fixed.may_start(" a", false));
        let off = of(&sequence.trivia_scope(false), &[], EXPRESSION_NESTING);
        assert!(!off.may_start(" a", true));
        // An atom never skips trivia.
        assert!(!of(&Expr::literal("a"), &[], EXPRESSION_NESTING).may_start(" a", true));
    }

    #[test]
    fn unmodelled_starts_are_unknown_and_never_excluded() {
        fn custom(context: &mut crate::ParseContext<'_>) -> crate::ParseResult {
            Err(context.error("custom"))
        }
        for expression in [
            Expr::Custom(custom),
            Expr::Backreference("name"),
            Expr::Until("x"),
            Expr::JavaUntil("x"),
            Expr::Custom(custom).ahead(),
            Expr::sequence([Expr::literal("a").optional(), Expr::Custom(custom)]),
            Expr::choice([Expr::literal("a"), Expr::Backreference("name")]),
            Expr::Rule(7),
        ] {
            let set = of(&expression, &[], EXPRESSION_NESTING);
            assert!(set.unknown, "{expression:?}");
            assert!(set.may_start("", false), "{expression:?}");
        }
        // After a consumed prefix, an unmodelled parser does not widen the start set.
        let set = of(
            &Expr::sequence([Expr::literal("a"), Expr::Custom(custom)]),
            &[],
            EXPRESSION_NESTING,
        );
        assert!(!set.unknown);
        assert!(!set.may_start("b", false));
    }

    fn nested(depth: usize) -> Expr {
        let mut expression = Expr::literal("a");
        for _ in 1..depth {
            expression = expression.optional();
        }
        expression
    }

    #[test]
    fn the_walk_stops_where_the_parser_reports_expression_nesting() {
        // `budget` levels are walked exactly; one more is the parser's resource failure (#1027).
        let exact = of(&nested(8), &[], 8);
        assert!(!exact.unknown && exact.nullable);
        assert!(exact.may_start("a", false) && exact.ascii == 1 << b'a');
        assert_eq!(of(&nested(8), &[], 7), FirstSet::UNKNOWN);
        assert_eq!(of(&Expr::literal("a"), &[], 0), FirstSet::UNKNOWN);
        // Only the nesting the walk visits counts: elements after a consuming one are skipped.
        let late = Expr::sequence([Expr::literal("b"), nested(8)]);
        assert_eq!(of(&late, &[], 2), of(&late, &[], EXPRESSION_NESTING));
        assert!(!of(&late, &[], 2).may_start("a", false));
        assert_eq!(of(&late, &[], 1), FirstSet::UNKNOWN);
        // A too-deep alternative is not dropped from the union: the choice becomes unknown.
        let choice = Expr::choice([Expr::literal("b"), nested(3)]);
        assert!(!of(&choice, &[], 4).unknown);
        assert_eq!(of(&choice, &[], 3), FirstSet::UNKNOWN);
    }

    #[test]
    fn cached_tables_are_recomputed_for_a_larger_walk_budget() {
        let grammar: Arc<[Rule]> = Arc::from(vec![
            rule("deep", nested(4)),
            rule("flat", Expr::literal("b")),
        ]);
        let shared = Arc::clone(&grammar); // Shared beyond one parse: the table is cached.
        let small = table(&grammar, 3);
        assert_eq!(small[0], FirstSet::UNKNOWN);
        assert_eq!(small[1], FirstSet::of_char('b'));
        let full = table(&grammar, EXPRESSION_NESTING);
        assert!(!Arc::ptr_eq(&small, &full));
        assert_eq!(&full[..], &compute(&grammar, EXPRESSION_NESTING)[..]);
        assert!(!full[0].unknown);
        // A table computed with a larger budget serves a deeper (smaller budget) entry.
        assert!(Arc::ptr_eq(&table(&grammar, 3), &full));
        assert!(Arc::ptr_eq(&table(&grammar, EXPRESSION_NESTING), &full));
        drop(shared);
    }

    #[test]
    fn lexical_atoms_have_exact_ascii_sets() {
        let digit = of(&Expr::Number, &[], EXPRESSION_NESTING);
        for start in ["1", "+", "-", "."] {
            assert!(digit.may_start(start, false), "{start}");
        }
        assert!(!digit.may_start("x", false));
        let range = of(&Expr::CharRange('a', 'c'), &[], EXPRESSION_NESTING);
        assert!(range.may_start("b", false) && !range.may_start("d", false));
        assert!(!range.may_start("é", false));
        let wide = of(&Expr::CharRange('x', 'é'), &[], EXPRESSION_NESTING);
        assert!(wide.may_start("é", false) && wide.may_start("z", false));
        let except = of(&Expr::Except("ab"), &[], EXPRESSION_NESTING);
        assert!(!except.may_start("a", false) && except.may_start("c", false));
        assert!(except.may_start("é", false));
        assert!(!of(&Expr::Error("never"), &[], EXPRESSION_NESTING).may_start("x", false));
        assert!(of(&Expr::Eof, &[], EXPRESSION_NESTING).may_start("x", false));
        assert!(of(&Expr::literal(""), &[], EXPRESSION_NESTING).may_start("x", false));
    }
}
