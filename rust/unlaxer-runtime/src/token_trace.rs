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
    /// The UTF-8 length this leaf was counted with against the byte budget.
    fn source_len(&self, byte_offsets: &[usize]) -> usize {
        match &self.source {
            TokenSource::Input(span) => byte_offsets[span.end] - byte_offsets[span.start],
            TokenSource::Generated { text, .. } => text.len(),
            TokenSource::Projected { text, .. } => text.len(),
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
    /// While `error` is a Limit: a node-less leaf or an Unsupported was dropped, or the
    /// Limit came from a node-less leaf. Removing node leaves then cannot undo it.
    /// Set whenever a Limit is raised; a restored Limit keeps it (a stale `true` only
    /// keeps that Limit), and it means nothing without one.
    suppressed: bool,
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
            suppressed: false,
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
        self.record_trace_leaf(label, kind, source, None);
    }
    fn record_trace_leaf(
        &mut self,
        label: &'static str,
        kind: TokenKind,
        source: &TokenSource,
        node: Option<usize>,
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
            trace.suppressed |= node.is_none();
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
            trace.suppressed = node.is_none();
            return;
        }
        // Within the 4 MiB hard ceiling checked above.
        trace.bytes += bytes as u32;
        trace.nodes.push(node);
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
        match &self.token_trace {
            None => {}
            // The active collectors remove this node, so its leaf never takes budget
            // and generated text is not copied (#866).
            Some(trace) if trace.scope.hides(kind) => {}
            Some(_) => self.record_trace_leaf(label, kind, source, Some(node)),
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
    /// not decide the callback's leaves until the value node completes. Below two
    /// collectors of different kinds no node survives, whatever its kind. Returns the
    /// scope to reinstate afterwards.
    pub(crate) fn open_trace_scope(&mut self) -> Option<Scope> {
        let trace = self.token_trace.as_mut()?;
        let scope = trace.scope;
        if scope.value_node && scope.retains != Retains::Nothing {
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
        let start = mark.leaves as usize;
        // An AST value node is not a collector. Once its kind is known, the enclosing
        // collectors are certain to remove it (and every node leaf counted below it,
        // e.g. by a custom callback) if the enclosing scope hides that kind.
        if matches!(expression, Expr::TextValue(_) | Expr::ValueBoundary(_)) {
            if roots
                .first()
                .is_some_and(|&node| mark.scope.hides(self.token_kind_at(node)))
            {
                self.remove_hidden_trace(mark, start);
            }
            return;
        }
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
                removed += leaf.source_len(&self.byte_offsets);
            }
        }
        // Removed leaves were counted in bytes, which stays within the hard ceiling.
        trace.bytes -= removed as u32;
    }
    /// Drops the node leaves recorded since `mark` below a value node the enclosing
    /// collectors remove. A Limit raised after `mark` only by such leaves does not
    /// describe the retained trace, so it returns to the mark's state; any dropped
    /// node-less leaf or Unsupported keeps it.
    fn remove_hidden_trace(&mut self, mark: &Mark, start: usize) {
        let trace = self.token_trace.as_mut().unwrap();
        let mut removed = 0;
        let leaves = trace.leaves.split_off(start);
        let nodes = trace.nodes.split_off(start);
        for (leaf, node) in leaves.into_iter().zip(nodes) {
            if node.is_none() {
                trace.leaves.push(leaf);
                trace.nodes.push(node);
            } else {
                removed += leaf.source_len(&self.byte_offsets);
            }
        }
        trace.bytes -= removed as u32;
        if mark.error.is_none() && trace.error == Some(Error::Limit) && !trace.suppressed {
            trace.error = None;
        }
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
            } else {
                trace.suppressed = true;
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
#[cfg(test)]
mod tests {
    use super::*;
    use crate::ParseOptions;

    // No public token-mode path records a node-less leaf below a value node today,
    // so the guard that keeps such a Limit is checked directly.
    #[test]
    fn removing_hidden_node_leaves_keeps_a_limit_that_dropped_a_retained_leaf() {
        let a = TokenSource::Input(Span { start: 0, end: 1 });
        let b = TokenSource::Input(Span { start: 1, end: 2 });
        // (node of the leaf that raises the Limit, a later node-less leaf, Limit kept)
        for (raising, later, kept) in [
            (Some(2), false, false),
            (Some(2), true, true),
            (None, false, true),
        ] {
            let mut context = ParseContext::with_options(
                "ab",
                ParseOptions::default().with_token_trace(Limits {
                    leaves: 2,
                    source_bytes: 2,
                }),
            );
            let mark = Mark::of(context.token_trace.as_ref());
            // A node-less leaf is retained by every collector.
            context.record_trace("Atom", TokenKind::Consumed, &a);
            context.record_trace_leaf("A", TokenKind::Consumed, &b, Some(0));
            context.record_trace_leaf("B", TokenKind::Consumed, &b, raising);
            if later {
                context.record_trace("Atom", TokenKind::Consumed, &b);
            }
            assert_eq!(context.token_trace(), Some(Err(Error::Limit)));
            context.remove_hidden_trace(&mark, 0);
            let trace = context.token_trace().unwrap().map(|leaves| {
                let labels = leaves.iter().map(|leaf| leaf.label).collect::<Vec<_>>();
                (labels, context.token_trace.as_ref().unwrap().bytes)
            });
            let expected = if kept {
                Err(Error::Limit)
            } else {
                Ok((vec!["Atom"], 1))
            };
            assert_eq!(trace, expected, "{raising:?} {later}");
        }
    }
}
