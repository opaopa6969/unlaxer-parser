//! Bounded opt-in trace of committed parser terminals. No synthetic CST nodes.
use crate::{
    token::{ParseMode, TokenKind, TokenSource},
    Expr, ParseContext, Span, Tree,
};

/// Hard ceilings apply even when a caller asks for a larger budget.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Limits {
    pub leaves: usize,
    pub source_bytes: usize,
}
const HARD_LEAVES: usize = 65_536;
const HARD_SOURCE_BYTES: usize = 4 * 1024 * 1024;
impl Default for Limits {
    fn default() -> Self {
        Self {
            leaves: HARD_LEAVES,
            source_bytes: HARD_SOURCE_BYTES,
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Leaf {
    pub label: &'static str,
    pub kind: TokenKind,
    pub source: TokenSource,
    /// Actual cursor positions after this recognition, in original source code points.
    pub consumed: usize,
    pub matched: usize,
}
impl Leaf {
    pub fn span(&self) -> Span {
        match &self.source {
            TokenSource::Input(span) | TokenSource::Projected { span, .. } => *span,
            TokenSource::Generated { anchor, .. } => Span {
                start: *anchor,
                end: *anchor,
            },
        }
    }
    pub fn text<'a>(&'a self, source: &'a str) -> Option<&'a str> {
        match &self.source {
            TokenSource::Generated { text, .. } => Some(text),
            TokenSource::Projected { text, .. } => Some(text),
            TokenSource::Input(span) => {
                let start = source
                    .char_indices()
                    .nth(span.start)
                    .map(|x| x.0)
                    .or_else(|| (span.start == source.chars().count()).then_some(source.len()))?;
                let end = source
                    .char_indices()
                    .nth(span.end)
                    .map(|x| x.0)
                    .or_else(|| (span.end == source.chars().count()).then_some(source.len()))?;
                source.get(start..end)
            }
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Error {
    Limit,
    Unsupported(&'static str),
}
impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "token trace {self:?}")
    }
}
impl std::error::Error for Error {}
// Retained counts are bounded by the hard ceilings, so u32 counters leave room for
// the collector scope without enlarging every checkpoint or token store (#866).
const _: () = assert!(HARD_LEAVES <= u32::MAX as usize);
const _: () = assert!(HARD_SOURCE_BYTES <= u32::MAX as usize);
#[cfg(target_pointer_width = "64")]
const _: () = {
    assert!(std::mem::size_of::<Mark>() == 40);
    assert!(std::mem::size_of::<Trace>() == 96);
};
/// Which leaf kinds the active Classic collectors can still retain (#866).
///
/// A collector keeps only direct children of its own kind, and its node has that
/// kind. Wrappers between collectors pass nodes through unchanged, so a leaf of
/// another kind is removed when the collectors complete; it is not counted at all.
/// Rollback and collector completion return to the enclosing scope.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Scope {
    retains: Retains,
    /// Below an AST value node and no collector since. That node takes its child's
    /// source kind without filtering, and a custom result there may mix kinds, so a
    /// custom callback reached here decides its own leaves.
    value_node: bool,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Retains {
    Any,
    Only(TokenKind),
    /// Two nested collectors of different kinds: no leaf below can survive both.
    Nothing,
}
impl Scope {
    pub(crate) const OPEN: Self = Self {
        retains: Retains::Any,
        value_node: false,
    };
    fn enter(self, expression: &Expr, kind: TokenKind) -> Self {
        // Each of these collects with the kind below and runs collect_classic_trace.
        let kind = match expression {
            Expr::TextValue(_) | Expr::ValueBoundary(_) => {
                return Self {
                    value_node: true,
                    ..self
                }
            }
            Expr::MatchOnly(_) => TokenKind::MatchOnly,
            Expr::InvertMatch(_)
            | Expr::Rule(_)
            | Expr::CaptureBoundary(_, _)
            | Expr::InputCaptureBoundary(_, _) => kind,
            _ if crate::classic_collector_label(expression).is_some() => kind,
            _ => return self,
        };
        let retains = match self.retains {
            Retains::Any => Retains::Only(kind),
            Retains::Only(only) if only == kind => self.retains,
            _ => Retains::Nothing,
        };
        Self {
            retains,
            value_node: false,
        }
    }
    fn hides(self, kind: TokenKind) -> bool {
        match self.retains {
            Retains::Any => false,
            Retains::Only(only) => only != kind,
            Retains::Nothing => true,
        }
    }
}
#[derive(Clone, Debug)]
pub(crate) struct Trace {
    limits: Limits,
    leaves: Vec<Leaf>,
    nodes: Vec<Option<usize>>,
    bytes: u32,
    scope: Scope,
    error: Option<Error>,
}
impl Trace {
    pub(crate) fn new(limits: Limits) -> Self {
        let hard = Limits::default();
        Self {
            limits: Limits {
                leaves: limits.leaves.min(hard.leaves),
                source_bytes: limits.source_bytes.min(hard.source_bytes),
            },
            leaves: vec![],
            nodes: vec![],
            bytes: 0,
            scope: Scope::OPEN,
            error: None,
        }
    }
    fn result(&self) -> Result<&[Leaf], Error> {
        self.error.clone().map_or(Ok(&self.leaves), Err)
    }
}
pub(crate) struct Mark {
    leaves: u32,
    bytes: u32,
    scope: Scope,
    error: Option<Error>,
}
impl Mark {
    pub(crate) fn of(trace: Option<&Trace>) -> Self {
        trace.map_or(
            Self {
                leaves: 0,
                bytes: 0,
                scope: Scope::OPEN,
                error: None,
            },
            |t| Self {
                leaves: t.leaves.len() as u32,
                bytes: t.bytes,
                scope: t.scope,
                error: t.error.clone(),
            },
        )
    }
    /// The checkpoint of an expression entry. Only an enabled Classic trace enters
    /// the expression's collector scope; the mark keeps the enclosing one.
    pub(crate) fn enter(
        trace: Option<&mut Trace>,
        expression: Option<&Expr>,
        mode: Option<ParseMode>,
    ) -> Self {
        match trace {
            None => Self::of(None),
            Some(t) => {
                let mark = Self::of(Some(t));
                if let (Some(expression), Some(mode)) = (expression, mode) {
                    t.scope = t.scope.enter(expression, mode.kind);
                }
                mark
            }
        }
    }
    pub(crate) fn restore(self, trace: Option<&mut Trace>) {
        if let Some(t) = trace {
            t.leaves.truncate(self.leaves as usize);
            t.nodes.truncate(self.leaves as usize);
            t.bytes = self.bytes;
            t.scope = self.scope;
            t.error = self.error;
        }
    }
}
impl Tree {
    pub fn token_trace(&self) -> Option<Result<&[Leaf], Error>> {
        self.tokens.trace.as_ref().map(Trace::result)
    }
}
impl ParseContext<'_> {
    pub(crate) fn retained_tokens(&self) -> crate::token::Store {
        let mut store = (*self.tokens).clone();
        store.trace = self.token_trace.clone();
        store
    }
    /// A custom parser explicitly promises that every committed recognition is
    /// represented by traced Expr/TokenExpr calls. Raw cursor changes must not be
    /// hidden. This declaration applies only to the current custom callback;
    /// nested custom callbacks must independently declare the same contract.
    pub fn declare_complete_token_trace(&mut self) -> bool {
        if self.token_trace.is_none() {
            return false;
        }
        if let Some(contract) = self.trace_custom_contracts.last_mut() {
            *contract = true;
            true
        } else {
            false
        }
    }
    pub fn token_trace(&self) -> Option<Result<&[Leaf], Error>> {
        self.token_trace.as_ref().map(Trace::result)
    }
    pub(crate) fn record_trace(
        &mut self,
        label: &'static str,
        kind: TokenKind,
        source: &TokenSource,
    ) {
        if self.token_trace.is_none() {
            return;
        }
        let bytes = match &source {
            TokenSource::Input(span) => self.text(*span).map_or(0, str::len),
            TokenSource::Generated { text, .. } => text.len(),
            TokenSource::Projected { text, .. } => text.len(),
        };
        let consumed = self.position();
        let matched = self.matched_position();
        let trace = self.token_trace.as_mut().unwrap();
        if trace.error.is_some() {
            return;
        }
        if trace.leaves.len() >= trace.limits.leaves
            || bytes
                > trace
                    .limits
                    .source_bytes
                    .saturating_sub(trace.bytes as usize)
        {
            trace.error = Some(Error::Limit);
            return;
        }
        // Within the 4 MiB hard ceiling checked above.
        trace.bytes += bytes as u32;
        trace.nodes.push(None);
        trace.leaves.push(Leaf {
            label,
            kind,
            source: source.clone(),
            consumed,
            matched,
        });
    }
    pub(crate) fn record_trace_node(
        &mut self,
        label: &'static str,
        kind: TokenKind,
        source: &TokenSource,
        node: usize,
    ) {
        let before = match &self.token_trace {
            None => return,
            // The active collectors remove this node, so its leaf never takes budget
            // and generated text is not copied (#866).
            Some(trace) if trace.scope.hides(kind) => return,
            Some(trace) => trace.leaves.len(),
        };
        self.record_trace(label, kind, source);
        if let Some(trace) = self.token_trace.as_mut() {
            if trace.leaves.len() > before {
                trace.nodes[before] = Some(node);
            }
        }
    }
    pub(crate) fn rename_trace_node(&mut self, node: usize, label: &'static str) {
        if let Some(trace) = self.token_trace.as_mut() {
            if trace.nodes.last() == Some(&Some(node)) {
                if let Some(leaf) = trace.leaves.last_mut() {
                    leaf.label = label;
                }
            }
        }
    }
    /// Directly below an AST value node, a custom callback's mixed-kind result can
    /// reach the outer collectors under a node of its first node's kind, so they do
    /// not decide the callback's leaves. Returns the scope to reinstate afterwards.
    pub(crate) fn open_trace_scope(&mut self) -> Option<Scope> {
        let trace = self.token_trace.as_mut()?;
        let scope = trace.scope;
        if scope.value_node {
            trace.scope = Scope::OPEN;
        }
        Some(scope)
    }
    pub(crate) fn close_trace_scope(&mut self, scope: Option<Scope>) {
        if let (Some(trace), Some(scope)) = (self.token_trace.as_mut(), scope) {
            trace.scope = scope;
        }
    }
    pub(crate) fn collect_classic_trace(
        &mut self,
        mark: &Mark,
        expression: &Expr,
        roots: &[usize],
    ) {
        let Some(trace) = self.token_trace.as_mut() else {
            return;
        };
        trace.scope = mark.scope;
        // An AST value node only leaves its scope; it is not a collector.
        if matches!(expression, Expr::TextValue(_) | Expr::ValueBoundary(_)) {
            return;
        }
        let start = mark.leaves as usize;
        let mut retained = std::collections::BTreeSet::new();
        let mut pending = roots.to_vec();
        while let Some(node) = pending.pop() {
            if retained.insert(node) {
                pending.extend(&self.nodes[node].children);
            }
        }
        let trace = self.token_trace.as_mut().unwrap();
        let mut removed = 0;
        // Visit only this collector's suffix, never every earlier committed leaf.
        let leaves = trace.leaves.split_off(start);
        let nodes = trace.nodes.split_off(start);
        for (leaf, node) in leaves.into_iter().zip(nodes) {
            if node.is_none_or(|node| retained.contains(&node)) {
                trace.leaves.push(leaf);
                trace.nodes.push(node);
            } else {
                removed += match &leaf.source {
                    TokenSource::Input(span) => {
                        self.byte_offsets[span.end] - self.byte_offsets[span.start]
                    }
                    TokenSource::Generated { text, .. } => text.len(),
                    TokenSource::Projected { text, .. } => text.len(),
                };
            }
        }
        // Removed leaves were counted in bytes, which stays within the hard ceiling.
        trace.bytes -= removed as u32;
    }
    pub(crate) fn trace_empty(&mut self, label: &'static str) {
        if self.token_trace.is_some() {
            self.record_trace(
                label,
                TokenKind::Consumed,
                &TokenSource::Input(Span {
                    start: self.position(),
                    end: self.position(),
                }),
            );
        }
    }
    pub(crate) fn trace_atom(&mut self, label: &'static str, start: usize) {
        if self.token_trace.is_none() {
            return;
        }
        let matched = self.matched_position;
        // Atomic subrecognitions commit at their own source end. The outer atom's
        // existing cursor publication and diagnostic behavior are left unchanged.
        self.matched_position = self.position;
        self.record_trace(
            label,
            TokenKind::Consumed,
            &TokenSource::Input(Span {
                start: self.code_point(start),
                end: self.position(),
            }),
        );
        self.matched_position = matched;
    }
    pub(crate) fn unsupported_trace(&mut self, label: &'static str) {
        if let Some(trace) = self.token_trace.as_mut() {
            if trace.error.is_none() {
                trace.error = Some(Error::Unsupported(label));
            }
        }
    }
    pub(crate) fn trace_expression(&mut self, expression: &Expr, start: usize) {
        if self.token_trace.is_none() {
            return;
        }
        let (label, kind, begin, end) = match expression {
            Expr::Replay(_) => ("ReplayParser", TokenKind::Consumed, start, self.position),
            Expr::Literal(_) => ("WordParser", TokenKind::Consumed, start, self.position),
            Expr::CaseInsensitive(_) => (
                "UnicodeIgnoreCaseWordParser",
                TokenKind::Consumed,
                start,
                self.position,
            ),
            Expr::Grapheme => ("GraphemeParser", TokenKind::Consumed, start, self.position),
            Expr::Any => (
                "WildCardCharacterParser",
                TokenKind::Consumed,
                start,
                self.position,
            ),
            Expr::CharRange(_, _) | Expr::Except(_) => (
                "SingleCharacterParser",
                TokenKind::Consumed,
                start,
                self.position,
            ),
            // Java's Lexing.LiteralParser("") is a LexicalTokenParser that adds a zero-width token.
            Expr::Lexical(_, _) | Expr::JavaLiteral(_) | Expr::JavaEmptyLiteral => (
                "LexicalTokenParser",
                TokenKind::Consumed,
                start,
                self.position,
            ),
            Expr::Eof => (
                "EndOfSourceParser",
                TokenKind::Consumed,
                start,
                self.position,
            ),
            Expr::JavaLookahead { .. } | Expr::Lookahead { .. } => return,
            Expr::Number | Expr::Identifier | Expr::Quoted(_) => return,
            Expr::CodeStart
            | Expr::CodeEnd
            | Expr::LongCodeBlock
            | Expr::JavaUntil(_)
            | Expr::Until(_)
            | Expr::JavaEmpty
            | Expr::Backreference(_) => {
                self.unsupported_trace("compound terminal internal leaves");
                return;
            }
            // Custom parsers can opt into the explicit TokenExpr API; empty trace is not invented.
            Expr::Custom(_) | Expr::CustomWith { .. } => return,
            Expr::Recovery { .. } => {
                self.unsupported_trace("recovery");
                return;
            }
            _ => return,
        };
        self.record_trace(
            label,
            kind,
            &TokenSource::Input(Span {
                start: self.code_point(begin),
                end: self.code_point(end),
            }),
        );
    }
}
