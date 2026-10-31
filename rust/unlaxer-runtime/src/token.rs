//! Explicit Classic token parsing and owned portable metadata.
//! This API does not add UBNF syntax or translate arbitrary Java objects.
use super::{
    Expr, Fragment, FragmentSource, Node, ParseContext, ParseMatch, ParseResult, Parser, Span, Tree,
};
use std::collections::BTreeMap;
use std::rc::Rc;
use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum TokenKind {
    MatchOnly,
    #[default]
    Consumed,
    VirtualMatchOnly,
    VirtualConsumed,
}
impl TokenKind {
    pub fn of(consume: bool) -> Self {
        if consume {
            Self::Consumed
        } else {
            Self::MatchOnly
        }
    }
    pub fn is_consumed(self) -> bool {
        matches!(self, Self::Consumed | Self::VirtualConsumed)
    }
    pub fn is_virtual(self) -> bool {
        matches!(self, Self::VirtualConsumed | Self::VirtualMatchOnly)
    }
    pub fn is_match_only(self) -> bool {
        !self.is_consumed()
    }
    pub fn is_real(self) -> bool {
        !self.is_virtual()
    }
}

/// Explicit Classic parser arguments, inherited by nested Expr/custom parsers.
/// Source provenance is independent of the virtual bit, as in Java TokenKind.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct ParseMode {
    pub kind: TokenKind,
    pub invert: bool,
}

/// A retained reference within a context and its tree snapshots. Generation and
/// owner checks prevent rolled-back arena slots or another parse from aliasing it.
#[derive(Clone, Copy, PartialEq, Eq)]
pub struct NodeId {
    owner: u64,
    generation: u64,
    index: usize,
}
impl std::fmt::Debug for NodeId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_tuple("NodeId").field(&self.index).finish()
    }
}
impl NodeId {
    pub fn index(self) -> usize {
        self.index
    }
}

/// Virtual kind and source provenance are independent: Java allows a virtual
/// kind on an input-backed token. Generated text is detached from input coordinates.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TokenSource {
    Input(Span),
    /// Concatenation of retained token sources anchored at their first child.
    /// Text can differ from the contiguous input slice at this span.
    Projected {
        span: Span,
        text: std::sync::Arc<str>,
    },
    Generated {
        anchor: usize,
        text: String,
    },
}

impl TokenSource {
    pub(crate) fn projected_text(&self) -> Option<&str> {
        if let Self::Projected { text, .. } = self {
            Some(text)
        } else {
            None
        }
    }
    pub(crate) fn owned_shared(&self) -> Option<std::sync::Arc<str>> {
        match self {
            Self::Projected { text, .. } => Some(std::sync::Arc::clone(text)),
            Self::Generated { text, .. } => Some(std::sync::Arc::from(text.as_str())),
            Self::Input(_) => None,
        }
    }
}

#[derive(Debug, Clone)]
pub struct TokenInfo {
    pub(crate) classic: bool,
    parser: Option<&'static str>,
    original: Option<Vec<usize>>,
    pub kind: TokenKind,
    pub source: TokenSource,
    extra: BTreeMap<String, String>,
    related: BTreeMap<String, NodeId>,
}
impl TokenInfo {
    /// Classic parser boundary label, separate from transactional portable extras.
    pub fn parser_label(&self) -> Option<&'static str> {
        self.parser
    }
    /// Raw tokens returned before this boundary's exact-kind CST collection.
    pub fn original_tokens(&self) -> Option<&[usize]> {
        self.original.as_deref()
    }
    pub fn extra(&self, name: &str) -> Option<&str> {
        self.extra.get(name).map(String::as_str)
    }
    pub fn related(&self, name: &str) -> Option<NodeId> {
        self.related.get(name).copied()
    }
}

#[derive(Clone)]
pub(crate) struct Store {
    owner: u64,
    pub(crate) trace: Option<crate::token_trace::Trace>,
    pub(crate) ids: BTreeMap<usize, NodeId>,
    pub(crate) values: BTreeMap<usize, TokenInfo>,
    pub(crate) calls: BTreeMap<usize, crate::shared_calls::Call>,
}
impl std::fmt::Debug for Store {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Store")
            .field("values", &self.values)
            .finish()
    }
}
impl Store {
    pub(crate) fn new() -> Self {
        static NEXT_OWNER: AtomicU64 = AtomicU64::new(1);
        Self {
            owner: NEXT_OWNER.fetch_add(1, Ordering::Relaxed),
            trace: None,
            ids: BTreeMap::new(),
            values: BTreeMap::new(),
            calls: BTreeMap::new(),
        }
    }
    fn valid(&self, id: NodeId) -> bool {
        id.owner == self.owner && self.ids.get(&id.index) == Some(&id)
    }
}

/// Only structural token data. Portable ID/extra/related values are transactional
/// caller state, never memoized; replay allocates fresh generation-checked IDs.
pub(crate) struct MemoTokens(Vec<(usize, TokenInfo, bool)>);
impl MemoTokens {
    #[cfg(test)]
    pub(crate) fn measure(store: &Store, start: usize, end: usize) -> Option<usize> {
        Self::measure_bounded(store, start, end, usize::MAX)
    }
    pub(crate) fn measure_bounded(
        store: &Store,
        start: usize,
        end: usize,
        limit: usize,
    ) -> Option<usize> {
        let mut bytes = 0_usize;
        for (&index, info) in store.values.range(start..end) {
            if !info.extra.is_empty() || !info.related.is_empty() {
                return None;
            }
            let original = info.original.as_deref().unwrap_or(&[]);
            let text = match &info.source {
                TokenSource::Input(_) => 0,
                TokenSource::Projected { text, .. } => text.len(),
                TokenSource::Generated { text, .. } => text.len(),
            };
            bytes = bytes
                .saturating_add(std::mem::size_of::<(usize, TokenInfo, bool)>())
                .saturating_add(std::mem::size_of_val(original))
                .saturating_add(text);
            if bytes > limit || original.iter().any(|&child| child < start || child >= end) {
                return None;
            }
            debug_assert!(index >= start);
        }
        Some(bytes)
    }
    pub(crate) fn capture(store: &Store, start: usize, end: usize) -> Self {
        Self(
            store
                .values
                .range(start..end)
                .map(|(&index, info)| {
                    let mut info = info.clone();
                    if let Some(original) = info.original.as_mut() {
                        for child in original {
                            *child -= start;
                        }
                    }
                    (index - start, info, store.ids.contains_key(&index))
                })
                .collect(),
        )
    }
    pub(crate) fn replay(&self, context: &mut ParseContext<'_>, start: usize) {
        for (index, info, registered) in &self.0 {
            let index = index + start;
            let mut info = info.clone();
            if let Some(original) = info.original.as_mut() {
                for child in original {
                    *child += start;
                }
            }
            if *registered {
                context
                    .token_node_id(index)
                    .expect("new memoized structural node");
            }
            Rc::make_mut(&mut context.tokens).values.insert(index, info);
        }
    }
}

impl ParseContext<'_> {
    /// The inherited Classic arguments. Custom parsers can forward these to
    /// `parse_token`; ordinary Expr children inherit them automatically.
    pub fn token_mode(&self) -> ParseMode {
        self.token_mode.unwrap_or_default()
    }

    /// Whether a Java-style collecting transaction starts its matched cursor at
    /// the consumed cursor. Sequence sets this false for following siblings.
    pub fn reset_matched_with_consumed(&self) -> bool {
        self.match_cursor_policy.reset
    }
    pub fn set_reset_matched_with_consumed(&mut self, reset: bool) {
        self.match_cursor_policy.reset = reset;
    }

    /// Parse with explicit Classic token-kind/inversion arguments. Expr preserves
    /// Java's failed direct consuming leaf's matched-cursor reset. Custom parsers
    /// keep transactional failures unless they override Parser::parse_classic.
    /// The caller's mode is restored on success, syntax failure and budget failure.
    /// This is distinct from PEG ahead: matched tokens/cursors can be published.
    pub fn parse_with_token_mode<P: Parser + ?Sized>(
        &mut self,
        parser: &P,
        kind: TokenKind,
        invert: bool,
    ) -> ParseResult {
        if let Some(error) = &self.resource_failure {
            return Err(error.clone());
        }
        if self.call_depth >= 256 {
            return Err(self.resource_error("parser calls below 256"));
        }
        let previous = self.token_mode.replace(ParseMode { kind, invert });
        if self.options.diagnostics == super::Diagnostics::Detailed {
            let reached = self.position.max(self.matched_position);
            self.classic_max_reached = Some(self.classic_max_reached.unwrap_or(0).max(reached));
        }
        self.call_depth += 1;
        let frontier = std::mem::replace(&mut self.grammar_frontier, false);
        let result = parser.parse_classic(self);
        self.grammar_frontier = frontier;
        self.call_depth -= 1;
        let result = match &self.resource_failure {
            Some(error) => Err(error.clone()),
            None => result,
        };
        self.token_mode = previous;
        result
    }

    /// Lazily gives an existing arena node a reference that cannot survive rollback
    /// as a reference to a replacement node. The serial counter is not rolled back.
    pub fn token_node_id(&mut self, index: usize) -> Option<NodeId> {
        let node = self.nodes.get(index)?;
        if let Some(id) = self.tokens.ids.get(&index) {
            return Some(*id);
        }
        let span = node.span;
        let id = NodeId {
            owner: self.tokens.owner,
            generation: self.next_token_generation,
            index,
        };
        self.next_token_generation = self.next_token_generation.checked_add(1)?;
        let store = Rc::make_mut(&mut self.tokens);
        store.ids.insert(index, id);
        store.values.entry(index).or_insert_with(|| TokenInfo {
            classic: false,
            parser: None,
            original: None,
            kind: TokenKind::Consumed,
            source: TokenSource::Input(span),
            extra: BTreeMap::new(),
            related: BTreeMap::new(),
        });
        Some(id)
    }
    pub fn token_info(&self, id: NodeId) -> Option<&TokenInfo> {
        self.tokens
            .valid(id)
            .then(|| self.tokens.values.get(&id.index))
            .flatten()
    }
    pub fn token_text(&self, id: NodeId) -> Option<&str> {
        match &self.token_info(id)?.source {
            TokenSource::Input(span) => self.text(*span),
            TokenSource::Generated { text, .. } => Some(text),
            TokenSource::Projected { text, .. } => Some(text),
        }
    }
    pub fn put_token_extra(
        &mut self,
        id: NodeId,
        name: impl Into<String>,
        value: impl Into<String>,
    ) -> bool {
        if !self.tokens.valid(id) {
            return false;
        }
        Rc::make_mut(&mut self.tokens)
            .values
            .get_mut(&id.index)
            .unwrap()
            .extra
            .insert(name.into(), value.into());
        true
    }
    pub fn remove_token_extra(&mut self, id: NodeId, name: &str) -> Option<String> {
        if !self.tokens.valid(id) {
            return None;
        }
        Rc::make_mut(&mut self.tokens)
            .values
            .get_mut(&id.index)?
            .extra
            .remove(name)
    }
    pub fn put_related_token(
        &mut self,
        id: NodeId,
        name: impl Into<String>,
        related: NodeId,
    ) -> bool {
        if !self.tokens.valid(id) || !self.tokens.valid(related) {
            return false;
        }
        Rc::make_mut(&mut self.tokens)
            .values
            .get_mut(&id.index)
            .unwrap()
            .related
            .insert(name.into(), related);
        true
    }
    pub fn remove_related_token(&mut self, id: NodeId, name: &str) -> Option<NodeId> {
        if !self.tokens.valid(id) {
            return None;
        }
        Rc::make_mut(&mut self.tokens)
            .values
            .get_mut(&id.index)?
            .related
            .remove(name)
    }
    /// Adds a token node without advancing either input cursor. Generated sources
    /// must use a virtual kind; input-backed virtual kinds are also allowed. A custom
    /// parser that returns an input-backed token of another kind than `Consumed` is
    /// collected by that kind, as a Classic child of the kind: consumed collectors
    /// leave it out, as Java's commit does.
    pub fn add_token_node(
        &mut self,
        rule: usize,
        kind: TokenKind,
        source: TokenSource,
    ) -> Option<NodeId> {
        self.add_token_node_inner(rule, kind, source, true)
    }
    /// Publishes one custom terminal with an explicit trace label, without moving
    /// either cursor. Call instead of `add_token_node` when the terminal's source
    /// is projected or input-backed. Generated sources are recorded exactly once.
    /// Trace limits and transaction rollback apply; custom callbacks must still
    /// declare their complete trace contract. Existing parser paths do not call this API.
    pub fn add_traced_token_node(
        &mut self,
        rule: usize,
        label: &'static str,
        kind: TokenKind,
        source: TokenSource,
    ) -> Option<NodeId> {
        let id = self.add_token_node_inner(rule, kind, source.clone(), false)?;
        self.record_trace_node(label, kind, &source, id.index);
        Some(id)
    }
    fn add_token_node_inner(
        &mut self,
        rule: usize,
        kind: TokenKind,
        source: TokenSource,
        trace_generated: bool,
    ) -> Option<NodeId> {
        let span = match &source {
            TokenSource::Input(span) => {
                self.text(*span)?;
                *span
            }
            TokenSource::Projected { span, text } => {
                if span.start >= self.byte_offsets.len()
                    || span.end.checked_sub(span.start)? != text.chars().count()
                {
                    return None;
                }
                *span
            }
            TokenSource::Generated { anchor, .. } => {
                if !kind.is_virtual() || *anchor >= self.byte_offsets.len() {
                    return None;
                }
                Span {
                    start: *anchor,
                    end: *anchor,
                }
            }
        };
        let index = self.nodes.len();
        self.nodes.push(Node {
            rule,
            span,
            children: vec![],
            captures: vec![],
        });
        let id = self.token_node_id(index)?;
        if trace_generated && matches!(source, TokenSource::Generated { .. }) {
            self.record_trace_node("GeneratedToken", kind, &source, index);
        }
        let info = Rc::make_mut(&mut self.tokens)
            .values
            .get_mut(&index)
            .unwrap();
        info.kind = kind;
        info.source = source;
        Some(id)
    }
    /// Transactional entry for the explicit propagation subset below.
    pub fn parse_token(
        &mut self,
        parser: &TokenExpr,
        kind: TokenKind,
        invert: bool,
    ) -> ParseResult {
        if self.call_depth >= 256 {
            return Err(self.error("parser calls below 256"));
        }
        self.call_depth += 1;
        let frontier = std::mem::replace(&mut self.grammar_frontier, false);
        let result = self.transaction(|context| parser.parse(context, kind, invert));
        self.grammar_frontier = frontier;
        self.call_depth -= 1;
        result
    }
}

impl Tree {
    /// Returns a registered token handle from this immutable snapshot.
    pub fn token_node_id(&self, index: usize) -> Option<NodeId> {
        self.tokens.ids.get(&index).copied()
    }
    pub fn token_info(&self, id: NodeId) -> Option<&TokenInfo> {
        self.tokens
            .valid(id)
            .then(|| self.tokens.values.get(&id.index))
            .flatten()
    }
    pub fn token_text(&self, id: NodeId) -> Option<&str> {
        match &self.token_info(id)?.source {
            TokenSource::Input(span) => Some(self.text(*span)),
            TokenSource::Generated { text, .. } => Some(text),
            TokenSource::Projected { text, .. } => Some(text),
        }
    }
}

/// Classic word recognition with explicit consume/invert propagation. This is a
/// handwritten runtime API, not full arbitrary Java parser or annotation parity.
#[derive(Debug, Clone)]
pub enum TokenExpr {
    Word { rule: usize, text: &'static str },
    Sequence(Vec<Self>),
    Choice(Vec<Self>),
    Invert(Box<Self>),
    StopInvert(Box<Self>),
    StopConsume(Box<Self>),
}
impl TokenExpr {
    fn parse(&self, context: &mut ParseContext<'_>, kind: TokenKind, invert: bool) -> ParseResult {
        match self {
            Self::Word { rule, text } => {
                let start = context.code_point(if kind == TokenKind::Consumed {
                    context.position
                } else {
                    context.matched_position
                });
                let requested_end = start.saturating_add(text.chars().count());
                let end = if requested_end < context.byte_offsets.len() {
                    requested_end
                } else {
                    start
                };
                let span = Span { start, end };
                let raw = context.text(span).unwrap();
                if raw.is_empty() || ((raw == *text) == invert) {
                    // Classic failures retain the furthest of the two cursors,
                    // even when this token peeks from the consumed cursor.
                    let consumed = context.position;
                    context.position = consumed.max(context.matched_position);
                    let error = context.error(text);
                    context.position = consumed;
                    return Err(error);
                }
                if kind.is_consumed() {
                    context.position = context.byte_offsets[context.position() + end - start];
                    context.matched_position = context.position;
                } else {
                    context.matched_position = context.byte_offsets[end];
                }
                let id = context
                    .add_token_node(*rule, kind, TokenSource::Input(span))
                    .unwrap();
                context.record_trace_node("WordParser", kind, &TokenSource::Input(span), id.index);
                Ok(ParseMatch {
                    span,
                    nodes: vec![id.index],
                    captures: vec![],
                })
            }
            Self::Invert(child) => context.parse_token(child, kind, !invert),
            Self::StopInvert(child) => context.parse_token(child, kind, false),
            Self::StopConsume(child) => context.parse_token(child, TokenKind::Consumed, invert),
            Self::Sequence(children) => {
                let start = context.position();
                let mut nodes = Vec::new();
                for child in children {
                    nodes.extend(context.parse_token(child, kind, invert)?.nodes);
                }
                Ok(ParseMatch {
                    span: Span {
                        start,
                        end: context.position(),
                    },
                    nodes,
                    captures: vec![],
                })
            }
            Self::Choice(children) => {
                for child in children {
                    if let Ok(result) = context.parse_token(child, kind, invert) {
                        return Ok(result);
                    }
                }
                Err(context.failure())
            }
        }
    }
}

impl ParseContext<'_> {
    pub(crate) fn mode_cursor(&self) -> usize {
        if self.token_mode().kind == TokenKind::Consumed {
            self.position
        } else {
            self.matched_position
        }
    }
    #[inline(never)]
    pub(crate) fn classic_wrapper(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        let previous = self.token_mode;
        let mut mode = self.token_mode();
        let child = match expression {
            Expr::MatchOnly(child) => {
                mode.kind = TokenKind::MatchOnly;
                child
            }
            Expr::InvertMatch(child) => {
                mode.invert = !mode.invert;
                child
            }
            Expr::StopInvert(child) => {
                mode.invert = false;
                child
            }
            Expr::StopConsume(child) => {
                mode.kind = TokenKind::Consumed;
                child
            }
            _ => unreachable!("Classic wrapper dispatch"),
        };
        let checkpoint =
            matches!(expression, Expr::MatchOnly(_)).then(|| Box::new(self.checkpoint()));
        self.token_mode = Some(mode);
        let result = self.expression(child, depth);
        self.finish_classic_wrapper(expression, result, checkpoint, previous)
    }
    // Keep post-parse collection/state restoration out of recursive wrapper frames.
    #[inline(never)]
    fn finish_classic_wrapper(
        &mut self,
        expression: &Expr,
        result: Option<Fragment>,
        checkpoint: Option<Box<super::Checkpoint>>,
        previous: Option<ParseMode>,
    ) -> Option<Fragment> {
        let mode = self.token_mode();
        let result = result.map(|fragment| {
            if matches!(expression, Expr::MatchOnly(_) | Expr::InvertMatch(_)) {
                self.classic_meta_node(
                    fragment,
                    mode.kind,
                    if matches!(expression, Expr::MatchOnly(_)) {
                        "MatchOnly"
                    } else {
                        "NotPropagatableSource"
                    },
                )
            } else {
                fragment
            }
        });
        self.token_mode = previous;
        if let Some(checkpoint) = checkpoint {
            if result.is_some() {
                let position = self.position;
                let matched = self.matched_position;
                let mut nodes = self.nodes.split_off(checkpoint.nodes);
                let current_tokens = Rc::clone(&self.tokens);
                let trace = self.token_trace.clone();
                let first = checkpoint.nodes;
                self.restore(*checkpoint);
                self.position = position;
                self.matched_position = matched;
                self.nodes.append(&mut nodes);
                let tokens = Rc::make_mut(&mut self.tokens);
                // New CST survives; portable registrations made by the child do not.
                for (&index, value) in current_tokens.values.range(first..) {
                    let mut structural = value.clone();
                    structural.extra.clear();
                    structural.related.clear();
                    tokens.values.insert(index, structural);
                }
                self.token_trace = trace;
            } else {
                self.restore(*checkpoint);
            }
        }
        result
    }
    pub(crate) fn classic_atom(&mut self, expression: &Expr, depth: usize) -> Option<Fragment> {
        match expression {
            Expr::CodeStart | Expr::CodeEnd => {
                return self.with_trivia(false, |context| {
                    context.classic_code_fence(matches!(expression, Expr::CodeStart), depth)
                });
            }
            // Java's internal token Chain has no grammar delimiter. Its leaves
            // must never inherit the surrounding sequence's trivia policy.
            Expr::Number => {
                return self.with_trivia(false, |c| c.expression(classic_number(), depth))
            }
            Expr::Identifier => {
                return self.with_trivia(false, |c| c.expression(classic_identifier(), depth))
            }
            Expr::Quoted(quote) if matches!(quote, '\'' | '"') => {
                return self.with_trivia(false, |c| c.expression(classic_quoted(*quote), depth))
            }
            Expr::JavaEmpty => {
                return self
                    .expression(&Expr::Any.match_only().optional_java(), depth)
                    .map(|fragment| self.classic_alias(fragment, "EmptyParser"))
            }
            Expr::Eof => {
                return self
                    .expression(&Expr::Any.zero_match(), depth)
                    .map(|fragment| self.classic_alias(fragment, "EndOfSourceParser"))
            }
            Expr::JavaLookahead { pattern, positive } => {
                return self.classic_lookahead(pattern, *positive, depth)
            }
            Expr::JavaUntil(terminator) => return self.classic_until(terminator, depth),
            _ => {}
        }
        let mode = self.token_mode();
        let start = self.mode_cursor();
        let raw = &self.input[start..];
        let (label, end, accepted) = match expression {
            Expr::Literal(word) => {
                let mut input = raw.chars();
                let mut end = start;
                let mut full = !word.is_empty();
                for _ in word.chars() {
                    if let Some(cp) = input.next() {
                        end += cp.len_utf8();
                    } else {
                        full = false;
                        break;
                    }
                }
                (
                    "WordParser",
                    end,
                    full && (raw.starts_with(word) != mode.invert),
                )
            }
            Expr::JavaLiteral(word) => {
                let end = match &mut self.lexing {
                    Some(session) => {
                        session.match_at(word, true, None, start, self.lexical_context.as_ref())
                    }
                    None => match &self.lexical_context {
                        Some(goal) => {
                            super::lexing::match_goal(self.input, start, word, true, goal)
                        }
                        None => raw.starts_with(word).then_some(start + word.len()),
                    },
                };
                (
                    "LiteralParser",
                    if mode.invert {
                        start
                    } else {
                        end.unwrap_or(start)
                    },
                    end.is_some() != mode.invert,
                )
            }
            Expr::CaseInsensitive(word) => {
                let mut input = raw.chars();
                let mut end = start;
                let mut equal = true;
                let mut full = !word.is_empty();
                for expected in word.chars() {
                    match input.next() {
                        Some(actual) => {
                            end += actual.len_utf8();
                            equal &= super::unicode_case_data::key(actual)
                                == super::unicode_case_data::key(expected);
                        }
                        None => {
                            full = false;
                            break;
                        }
                    }
                }
                (
                    "UnicodeIgnoreCaseWordParser",
                    end,
                    full && (equal != mode.invert),
                )
            }
            Expr::Any | Expr::CharRange(_, _) | Expr::Except(_) | Expr::Character { .. } => {
                let next = raw.chars().next();
                let accepted = next.is_some_and(|cp| {
                    let matches = match expression {
                        Expr::CharRange(min, max) => *min <= cp && cp <= *max,
                        Expr::Except(excluded) => !excluded.contains(cp),
                        Expr::Character { predicate, .. } => predicate(cp),
                        _ => true,
                    };
                    matches != mode.invert
                });
                (
                    match expression {
                        Expr::Character { label, .. } => *label,
                        Expr::Any => "WildCardCharacterParser",
                        _ => "SingleCharacterParser",
                    },
                    start + next.map_or(0, char::len_utf8),
                    accepted,
                )
            }
            Expr::Grapheme => {
                let boundaries = self
                    .grapheme_boundaries
                    .get_or_insert_with(|| super::grapheme::Boundaries::new(self.input));
                let end = boundaries.next_utf8(start);
                (
                    "GraphemeParser",
                    end.unwrap_or(start),
                    !mode.invert && end.is_some(),
                )
            }
            Expr::Lexical(label, expression) => {
                let end = match &mut self.lexing {
                    Some(session) => session.match_at(
                        label,
                        false,
                        Some(expression),
                        start,
                        self.lexical_context.as_ref(),
                    ),
                    None => match &self.lexical_context {
                        Some(goal) => {
                            super::lexing::match_goal(self.input, start, label, false, goal)
                        }
                        None => expression.match_at(self.input, start),
                    },
                };
                (
                    *label,
                    if mode.invert {
                        start
                    } else {
                        end.unwrap_or(start)
                    },
                    end.is_some() != mode.invert,
                )
            }
            Expr::Backreference(name) => {
                let text = self.captured(name);
                let width = text.map_or(0, str::len);
                (
                    *name,
                    if mode.invert {
                        start + raw.chars().next().map_or(0, char::len_utf8)
                    } else {
                        start + width
                    },
                    if mode.invert {
                        text.is_none_or(|text| !raw.starts_with(text)) && !raw.is_empty()
                    } else {
                        text.is_some_and(|text| raw.starts_with(text))
                    },
                )
            }
            Expr::LongCodeBlock => {
                let end = (!mode.invert)
                    .then(|| super::long_code_fence::end(self.input, start))
                    .flatten();
                ("LongCodeBlockParser", end.unwrap_or(start), end.is_some())
            }
            Expr::Replay(name) => {
                let text = self.captured_text(name);
                let width = text.map_or(0, |text| text.chars().count());
                let mut end = start;
                let mut input = raw.chars();
                let mut full = text.is_some();
                for _ in 0..width {
                    if let Some(cp) = input.next() {
                        end += cp.len_utf8();
                    } else {
                        full = false;
                        break;
                    }
                }
                (
                    "ReplayParser",
                    end,
                    full && (text.is_some_and(|text| raw.starts_with(text)) != mode.invert),
                )
            }
            Expr::Until(terminator) => {
                let at = raw.find(terminator);
                (
                    "Until",
                    start + at.unwrap_or(0),
                    !mode.invert && at.is_some(),
                )
            }
            Expr::Empty => ("Empty", start, true),
            // Classic Literal("") already follows Java WordParser("").
            Expr::JavaEmptyWord => ("WordParser", start, false),
            Expr::JavaEmptyLiteral => ("LiteralParser", start, !mode.invert),
            Expr::Error(message) => {
                self.fail_at(start, message);
                return None;
            }
            _ => {
                self.fail(
                    "Classic token mode requires a mode-aware parser for this native scanner",
                );
                return None;
            }
        };
        if !accepted {
            let at = start.max(self.position).max(self.matched_position);
            if let Expr::Replay(name) = expression {
                self.fail_at(at, &format!("SAME_AS({name})"));
            } else {
                self.fail_at(
                    at,
                    match expression {
                        Expr::Literal(word)
                        | Expr::JavaLiteral(word)
                        | Expr::CaseInsensitive(word) => word,
                        Expr::JavaEmptyWord | Expr::JavaEmptyLiteral => "",
                        Expr::LongCodeBlock => "long code block",
                        _ => "character",
                    },
                );
            }
            // `expression` restores its checkpoint after this failure and then decides the
            // matched cursor from its leaf-reset list (which leaves out JavaEmptyLiteral).
            if mode.kind.is_consumed() && !matches!(expression, Expr::LongCodeBlock) {
                self.matched_position = self.position;
            }
            return None;
        }
        let span = Span {
            start: self.code_point(start),
            end: self.code_point(end),
        };
        if mode.kind.is_consumed()
            && !(matches!(
                expression,
                Expr::Lexical(_, _) | Expr::JavaLiteral(_) | Expr::JavaEmptyLiteral
            ) && start == end)
        {
            let target = self.position().checked_add(span.end - span.start)?;
            self.position = *self.byte_offsets.get(target)?;
            self.matched_position = self.position;
        } else if !mode.kind.is_consumed() {
            self.matched_position = end;
        }
        if self.options.diagnostics == super::Diagnostics::Detailed {
            let reached = self.position.max(self.matched_position);
            self.classic_max_reached = Some(self.classic_max_reached.unwrap_or(0).max(reached));
            if let Some(frame) = self.diagnostic_frames.last_mut() {
                frame.classic_max_reached =
                    Some(frame.classic_max_reached.unwrap_or(0).max(reached));
            }
        }
        let node = self.add_token_node_inner(
            super::TOKEN_LEAF_RULE,
            mode.kind,
            TokenSource::Input(span),
            false,
        )?;
        self.record_trace_node(label, mode.kind, &TokenSource::Input(span), node.index);
        let info = Rc::make_mut(&mut self.tokens)
            .values
            .get_mut(&node.index)
            .unwrap();
        info.parser = Some(label);
        info.classic = true;
        Some(Fragment {
            nodes: vec![node.index],
            captures: Vec::new(),
            source: Some(Box::new(FragmentSource {
                parsed_source: None,
                span,
                kind: mode.kind,
                classic: true,
                generated: false,
                text: None,
            })),
        })
    }
    // Mirror the pinned Tiny parser graph: line-boundary assertion has no CST,
    // while each ordinary child retains its own Classic mode and diagnostics.
    fn classic_code_fence(&mut self, opening: bool, depth: usize) -> Option<Fragment> {
        let start = self.mode_cursor();
        if self.token_mode().invert
            || (start > 0 && !matches!(self.input.as_bytes()[start - 1], b'\r' | b'\n'))
        {
            return None;
        }
        let owner_start = self.code_point(start);
        let mut result = Fragment::default();
        let fence = self.expression(&Expr::Literal("```"), depth)?;
        let fence = self.classic_alias(fence, "TripleBackTickParser");
        self.append_classic_fragment(
            &mut result,
            fence,
            Span {
                start: owner_start,
                end: self.code_point(self.mode_cursor()),
            },
            owner_start,
        );
        if opening {
            for child in [Expr::Identifier, Expr::Literal(":")] {
                let before = self.code_point(self.mode_cursor());
                let fragment = self.expression(&child, depth)?;
                self.append_classic_fragment(
                    &mut result,
                    fragment,
                    Span {
                        start: before,
                        end: self.code_point(self.mode_cursor()),
                    },
                    owner_start,
                );
            }
            let before = self.code_point(self.mode_cursor());
            let name = self.expression(classic_class_name(), depth)?;
            let name = self.classic_alias(name, "JavaClassNameParser");
            self.append_classic_fragment(
                &mut result,
                name,
                Span {
                    start: before,
                    end: self.code_point(self.mode_cursor()),
                },
                owner_start,
            );
        }
        let before = self.code_point(self.mode_cursor());
        let ending = self.expression(classic_line_end(), depth)?;
        let ending = self.classic_alias(ending, "EndOfLineParser");
        self.append_classic_fragment(
            &mut result,
            ending,
            Span {
                start: before,
                end: self.code_point(self.mode_cursor()),
            },
            owner_start,
        );
        Some(result)
    }
    #[inline(never)]
    pub(crate) fn classic_longest_choice(
        &mut self,
        alternatives: &[Expr],
        depth: usize,
        unique: bool,
    ) -> Option<Fragment> {
        if unique && !(2..=64).contains(&alternatives.len()) {
            self.unique_choice_failure_seen = true;
            self.fail("2 to 64 unique longest alternatives");
            return None;
        }
        let start = if self.token_mode().kind.is_consumed() {
            self.position
        } else {
            self.matched_position
        };
        let mut winner = None;
        let mut winner_end = None;
        let mut ties = 0;
        for (index, alternative) in alternatives.iter().enumerate() {
            let checkpoint = self.checkpoint();
            if self.match_cursor_policy.reset {
                self.matched_position = self.position;
            }
            if self.expression(alternative, depth).is_some() {
                let end = if self.token_mode().kind.is_consumed() {
                    self.position
                } else {
                    self.matched_position
                };
                if winner_end.is_none_or(|before| end > before) {
                    winner = Some(index);
                    winner_end = Some(end);
                    ties = 1;
                } else if winner_end == Some(end) {
                    ties += 1;
                }
            }
            self.restore(checkpoint);
            // A committed or labeled failure aborts the whole choice (#700).
            if self.resource_failure.is_some() || self.raised.is_some() {
                return None;
            }
        }
        let index = winner?;
        let end = winner_end.unwrap();
        if unique && (end == start || ties > 1) {
            self.unique_choice_failure_seen = true;
            self.fail_at(
                end,
                if end == start {
                    "nonempty unique longest alternative"
                } else {
                    "unique longest alternative"
                },
            );
            return None;
        }
        if self.match_cursor_policy.reset {
            self.matched_position = self.position;
        }
        self.expression(&alternatives[index], depth)
    }
    fn classic_alias(&mut self, fragment: Fragment, label: &'static str) -> Fragment {
        if let [index] = fragment.nodes.as_slice() {
            if let Some(info) = Rc::make_mut(&mut self.tokens).values.get_mut(index) {
                info.parser = Some(label);
            }
            self.rename_trace_node(*index, label);
        }
        fragment
    }
    #[inline(never)]
    fn classic_lookahead(
        &mut self,
        pattern: &'static str,
        positive: bool,
        depth: usize,
    ) -> Option<Fragment> {
        if positive {
            return self.expression(&Expr::literal(pattern).match_only(), depth);
        }
        let checkpoint = self.checkpoint();
        let previous = self.token_mode;
        let farthest = self.farthest;
        let rule_speculation = self.begin_rule_speculation();
        let expected = self.expected.clone();
        let diagnostics = self.diagnostic_frames.clone();
        let classic = (self.classic_max_reached, self.classic_failure_seen);
        self.token_mode = Some(ParseMode {
            kind: TokenKind::MatchOnly,
            ..self.token_mode()
        });
        let matched = self.expression(&Expr::Literal(pattern), depth).is_some();
        self.restore(checkpoint);
        self.end_rule_speculation(
            rule_speculation,
            !matched && self.resource_failure.is_none(),
        );
        if self.resource_failure.is_some() {
            self.token_mode = previous;
            return None;
        }
        let result = if matched {
            self.fail("negative lookahead");
            None
        } else {
            self.farthest = farthest;
            self.expected = expected;
            self.diagnostic_frames = diagnostics;
            (self.classic_max_reached, self.classic_failure_seen) = classic;
            Some(self.classic_meta_node(Fragment::default(), TokenKind::MatchOnly, "Not"))
        };
        self.token_mode = previous;
        result
    }
    #[inline(never)]
    fn classic_until(&mut self, terminator: &'static str, depth: usize) -> Option<Fragment> {
        // WildCardStringTerminatorParser(String...) terminates on Choice(MatchOnly(word)):
        // the Choice token and Occurs' commit of that CollectingParser both stay in the CST.
        let terminator = Expr::choice([Expr::Literal(terminator).match_only()]);
        self.classic_until_parser(&terminator, "Choice", depth)
    }
    #[inline(never)]
    fn classic_until_parser(
        &mut self,
        terminator: &Expr,
        collector: &'static str,
        depth: usize,
    ) -> Option<Fragment> {
        let mode = self.token_mode();
        let start = self.position();
        let mut result = Fragment::default();
        loop {
            let checkpoint = self.checkpoint();
            self.token_mode = Some(ParseMode::default());
            let term = self.expression(terminator, depth);
            self.token_mode = Some(mode);
            if self.resource_failure.is_some() {
                self.restore(checkpoint);
                return None;
            }
            if let Some(term) = term {
                if mode.kind.is_consumed() {
                    self.commit_checkpoint(checkpoint);
                    // Occurs invokes its terminator with normal default arguments;
                    // that terminator collector is Consumed even for a virtual parent.
                    let term = self.classic_meta_node(term, TokenKind::Consumed, collector);
                    let fallback = Span {
                        start: self.position(),
                        end: self.position(),
                    };
                    self.append_classic_fragment(&mut result, term, fallback, start);
                } else {
                    self.restore(checkpoint);
                }
                break;
            }
            self.restore(checkpoint);
            let before = self.mode_cursor();
            let child = self.expression(&Expr::character("WildCardStringParser", |_| true), depth);
            if let Some(child) = child {
                let fallback = Span {
                    start: self.code_point(before),
                    end: self.code_point(self.mode_cursor()),
                };
                self.append_classic_fragment(&mut result, child, fallback, start);
                if self.mode_cursor() == before {
                    break;
                }
            } else {
                if self.resource_failure.is_some() {
                    return None;
                }
                self.java_failed_atom(&Expr::Any);
                break;
            }
        }
        Some(self.classic_meta_node(result, mode.kind, "WildCardStringTerminatorParser"))
    }
}

impl ParseContext<'_> {
    #[inline(never)]
    pub(crate) fn sequence_trivia(
        &mut self,
        result: &mut Fragment,
        owner_start: usize,
        depth: usize,
    ) -> Option<()> {
        if self.token_mode.is_none()
            || !self.whitespace && self.lexical_trivia.is_none() && !self.line_comments
        {
            self.skip();
            return Some(());
        }
        let start = self.position;
        let definition = self.lexical_trivia.clone();
        let line_comments = self.line_comments;
        // Java generated delimiters own their lexical program. They do not run
        // through the surrounding contextual lexer or recursively delimit themselves.
        let previous_context = self.lexical_context.take();
        let previous_lexing = self.lexing.take();
        let parsed = self.with_trivia(false, |context| {
            if let Some(definition) = definition {
                context.expression(
                    &Expr::Lexical("Whitespace", definition).repeat_java(0, None),
                    depth,
                )
            } else if line_comments {
                context.expression(classic_line_comments(), depth)
            } else {
                context.expression(classic_whitespace(), depth)
            }
        });
        self.lexical_context = previous_context;
        self.lexing = previous_lexing;
        let fragment = parsed?;
        self.append_classic_fragment(
            result,
            fragment,
            self.span(start),
            self.code_point(owner_start),
        );
        Some(())
    }
    fn token_kind_at(&self, index: usize) -> TokenKind {
        self.tokens
            .values
            .get(&index)
            .map_or(TokenKind::Consumed, |info| info.kind)
    }
    fn collected_source(&self, fragment: &Fragment, kind: TokenKind) -> Option<FragmentSource> {
        if fragment.nodes.is_empty() {
            // A raw callback's cursor span is not a returned token. An empty
            // collector anchors at its active cursor, not that callback span.
            return None;
        }
        let mut collected = Collected::new();
        for &index in &fragment.nodes {
            if self.token_kind_at(index) != kind {
                continue;
            }
            let node = &self.nodes[index];
            let source = self.tokens.values.get(&index).map(|info| &info.source);
            let text = match source {
                Some(TokenSource::Generated { text, .. }) => text.as_str(),
                _ => source
                    .and_then(TokenSource::projected_text)
                    .unwrap_or_else(|| self.text(node.span).expect("validated input source")),
            };
            if text.is_empty() && kind.is_virtual() {
                continue;
            }
            collected.push(node.span, source, text);
        }
        collected.finish(kind)
    }
    /// A non-Classic collector (Sequence, NonOrdered, Repeat, Optional, Recovery) returns what
    /// Java's consumed collector returns: a projection of the current kind is collected in
    /// order, a Classic child of another kind contributes nothing, a Classic child collects
    /// what it returned, and other input is what it consumed. Only such a projection or
    /// Classic child starts a collection; plain input keeps no source.
    ///
    /// Returns true when this child started the collection at `owner_start` after children that
    /// consumed nothing. The consumed tokens over the input among the CST roots they returned (a
    /// host's) start it (see [`Self::collect_returned_input`]), and only the caller knows whether
    /// one of them returns an empty token in Java (see [`Self::anchor_native_collection`]). Plain
    /// input never starts a collection, so the ordinary path never asks.
    pub(crate) fn append_fragment(
        &self,
        result: &mut Fragment,
        child: Fragment,
        fallback: Span,
        owner_start: usize,
        collection: &mut Option<Box<NativeCollection>>,
    ) -> bool {
        let mut marker = None;
        let mut unanchored = false;
        if self.token_mode.is_none() {
            let projection = child
                .parsed_source()
                .filter(|source| native_projection(source))
                .and_then(|source| {
                    Some(TokenSource::Projected {
                        span: source.span,
                        text: std::sync::Arc::clone(source.text.as_deref()?),
                    })
                });
            // A Classic child whose collection is not its consumed input (the "" at the
            // cursor after a dropped virtual token) contributes that collection, as a Java
            // Chain collects the child's token.
            let part = projection.or_else(|| {
                child
                    .source
                    .as_deref()
                    .filter(|source| returned_input(source, fallback))
                    .map(|source| TokenSource::Input(source.span))
            });
            let other_kind = child.source.as_deref().is_some_and(other_kind_classic);
            let excluded = other_kind && fallback.start != fallback.end;
            if part.is_some() || excluded || collection.is_some() {
                // Before the first collected child, the Box append_classic_fragment made has the
                // consumed kind once a Classic child of that kind (an Optional over MatchOnly, a
                // rule) returned a token: an empty one still starts the collection, as in Java.
                let starts = collection.is_none();
                let returned = starts
                    && result
                        .source
                        .as_deref()
                        .is_some_and(|source| source.kind == TokenKind::Consumed);
                let collection = collection.get_or_insert_with(|| {
                    Box::new(NativeCollection {
                        parts: Vec::new(),
                        start: owner_start,
                        end: owner_start,
                    })
                });
                // The input before this child, including skipped trivia, is consumed text.
                let before = if part.is_some() || excluded {
                    fallback.start
                } else {
                    fallback.end
                };
                if collection.end < before || returned {
                    collection.parts.push(TokenSource::Input(Span {
                        start: collection.end,
                        end: before,
                    }));
                } else if starts {
                    self.collect_returned_input(result, collection);
                    unanchored = true;
                }
                collection.parts.extend(part);
                collection.end = collection.end.max(fallback.end);
            } else if child.source.as_deref().is_some_and(|source| source.classic) {
                // Until a collection starts, the kind of that Box records whether a Classic
                // child returned a token of the consumed kind (see above); a MatchOnly that
                // returned nothing of it does not, nor does a node-less Classic success (a PEG
                // assertion under StopInvert / StopConsume returns no token, as in Java).
                // finish_native_collection replaces the Box.
                marker = if !other_kind && !child.nodes.is_empty() {
                    Some(TokenKind::Consumed)
                } else {
                    result.source.is_none().then_some(TokenKind::MatchOnly)
                };
            }
        }
        self.append_classic_fragment(result, child, fallback, owner_start);
        if let Some((kind, source)) = marker.zip(result.source.as_deref_mut()) {
            source.kind = kind;
        }
        unanchored
    }
    /// Starts `collection` with the CST roots the earlier children of `result` returned that are
    /// tokens of the consumed kind over the input, in order: as in Java, a host's token (an empty
    /// `Token(consumed, peek(cursor, 0))`, or input it did not consume) is collected at its own
    /// position. Asked before the child that starts the collection is appended, when the earlier
    /// children consumed nothing, so their tokens are not otherwise collected (see
    /// [`Self::append_fragment`]).
    #[cold]
    #[inline(never)]
    fn collect_returned_input(&self, result: &Fragment, collection: &mut NativeCollection) {
        collection
            .parts
            .extend(result.nodes.iter().filter_map(|index| {
                let info = self.tokens.values.get(index)?;
                match info.source {
                    TokenSource::Input(span) if info.kind == TokenKind::Consumed => {
                        Some(TokenSource::Input(span))
                    }
                    _ => None,
                }
            }));
    }
    /// A Sequence child `element` left its collection unanchored (see [`Self::append_fragment`]):
    /// the elements before it, which consumed nothing, decide its start.
    #[cold]
    #[inline(never)]
    pub(crate) fn anchor_sequence(
        collection: &mut Option<Box<NativeCollection>>,
        elements: &[Expr],
        element: &Expr,
    ) {
        let index = elements
            .iter()
            .position(|candidate| std::ptr::eq(candidate, element))
            .expect("element of the sequence");
        if elements[..index].iter().any(java_returns_token) {
            Self::anchor_native_collection(collection);
        }
    }
    /// The NonOrdered form of [`Self::anchor_sequence`]: the other `selected` children, which
    /// matched before `child`, consumed nothing.
    #[cold]
    #[inline(never)]
    pub(crate) fn anchor_non_ordered(
        collection: &mut Option<Box<NativeCollection>>,
        children: &[Expr],
        selected: &[bool],
        child: &Expr,
    ) {
        if children.iter().zip(selected).any(|(earlier, &selected)| {
            selected && !std::ptr::eq(earlier, child) && java_returns_token(earlier)
        }) {
            Self::anchor_native_collection(collection);
        }
    }
    /// An earlier child of a collection that [`Self::append_fragment`] left unanchored returned
    /// an empty token of the consumed kind (see [`java_returns_token`]): as in Java, the
    /// collection starts with it at the owner's start, instead of "" at the consumed cursor. It
    /// precedes a host's token (see [`Self::collect_returned_input`]); a host token over other
    /// input than the host consumed starts the collection itself, as a Classic child's collection
    /// does, so one that came first keeps its own position.
    fn anchor_native_collection(collection: &mut Option<Box<NativeCollection>>) {
        if let Some(collection) = collection.as_deref_mut() {
            let start = collection.start;
            collection
                .parts
                .insert(0, TokenSource::Input(Span { start, end: start }));
        }
    }
    /// A non-Classic collector of one child (Optional, Recovery, a value boundary) that
    /// started at byte `start`. It updates `fragment` in place, which keeps a second
    /// fragment out of the callers' recursive frames.
    pub(crate) fn collect_native_child(&self, fragment: &mut Fragment, start: usize) {
        if self.token_mode.is_some() {
            return;
        }
        let Some(source) = fragment.source.as_deref() else {
            return;
        };
        let projection = fragment.parsed_source().is_some_and(native_projection);
        if !source.classic && !projection {
            // Not collected (see `append_fragment`): the non-Classic source is dropped, as
            // before, and the nodes stay where they are.
            fragment.source = None;
            return;
        }
        let span = self.span(start);
        let collected = projection
            || other_kind_classic(source) && span.start != span.end
            || returned_input(source, span);
        if !projection {
            // One Classic child collects one part: the input it consumed (consuming nothing,
            // nothing of this kind), the "" after a child of another kind, or the collection
            // it returned. That part is the collection, in the child's own Box.
            let span = if !collected {
                span
            } else if other_kind_classic(source) {
                Span {
                    start: span.end,
                    end: span.end,
                }
            } else {
                source.span
            };
            if let Some(source) = fragment.source.as_deref_mut() {
                *source = FragmentSource {
                    parsed_source: None,
                    span,
                    kind: TokenKind::Consumed,
                    classic: true,
                    generated: false,
                    text: None,
                };
            }
            return;
        }
        let mut result = Fragment::default();
        let mut collection = None;
        self.append_fragment(
            &mut result,
            std::mem::take(fragment),
            span,
            span.start,
            &mut collection,
        );
        self.finish_native_collection(&mut result, collection, move |_| span.start);
        *fragment = result;
    }
    /// A non-Classic Choice is a Java Choice: it returns the selected child's Parsed, while its
    /// own token collects that child in the consumed kind. A Classic child of another kind (a
    /// low-level MatchOnly, a host token of another kind) contributes nothing to it, so the
    /// Choice's collection is "" at the consumed cursor, which starts the collection of a
    /// collector around it. Only such a child pays for the Box that keeps its source.
    #[inline(never)]
    pub(crate) fn collect_native_choice(&self, fragment: &mut Fragment) {
        let Some(source) = fragment
            .source
            .as_deref_mut()
            .filter(|source| source.parsed_source.is_none() && other_kind_classic(source))
        else {
            return;
        };
        let at = self.code_point(self.position);
        let selected = std::mem::replace(
            source,
            FragmentSource {
                parsed_source: None,
                span: Span { start: at, end: at },
                kind: TokenKind::Consumed,
                classic: true,
                generated: false,
                text: None,
            },
        );
        source.parsed_source = Some(Box::new(selected));
    }
    /// Completes [`Self::append_fragment`] at the collector's end. A Classic child keeps the
    /// result Classic, so a rule boundary still collects the CST exactly as before.
    /// `owner_start` (a code point) is computed only when the result needs a source.
    pub(crate) fn finish_native_collection(
        &self,
        result: &mut Fragment,
        collection: Option<Box<NativeCollection>>,
        owner_start: impl FnOnce(&Self) -> usize,
    ) {
        if self.token_mode.is_some() {
            return;
        }
        let classic = result
            .source
            .as_deref()
            .is_some_and(|source| source.classic);
        let Some(mut collection) = collection else {
            if let Some(source) = result.source.as_deref_mut().filter(|_| classic) {
                // The consumed collection of Classic children that returned nothing of this
                // kind is the consumed input; reuse the Box append_classic_fragment made.
                *source = FragmentSource {
                    parsed_source: None,
                    span: Span {
                        start: owner_start(self),
                        end: self.code_point(self.position),
                    },
                    kind: TokenKind::Consumed,
                    classic,
                    generated: false,
                    text: None,
                };
            }
            return;
        };
        let end = self.code_point(self.position);
        if collection.end < end {
            collection.parts.push(TokenSource::Input(Span {
                start: collection.end,
                end,
            }));
        }
        let mut collected = Collected::new();
        for part in &collection.parts {
            let text = match part {
                TokenSource::Input(span) => self.text(*span).expect("validated input source"),
                _ => part.projected_text().expect("collected projection"),
            };
            let span = match part {
                TokenSource::Input(span) | TokenSource::Projected { span, .. } => *span,
                TokenSource::Generated { .. } => unreachable!("generated is not collected"),
            };
            collected.push(span, Some(part), text);
        }
        // Nothing of the current kind was returned: "" at the consumed cursor.
        let source = collected
            .finish(TokenKind::Consumed)
            .or_else(|| {
                classic.then_some(FragmentSource {
                    parsed_source: None,
                    span: Span { start: end, end },
                    kind: TokenKind::Consumed,
                    classic,
                    generated: false,
                    text: None,
                })
            })
            .map(|mut source| {
                source.classic = classic;
                source
            })
            .filter(|source| source.classic || source.text.is_some());
        result.source = source.map(Box::new);
    }
    pub(crate) fn append_classic_fragment(
        &self,
        result: &mut Fragment,
        mut child: Fragment,
        fallback: Span,
        owner_start: usize,
    ) {
        if self.token_mode.is_none()
            && !result
                .source
                .as_deref()
                .is_some_and(|source| source.classic)
            && !child.source.as_deref().is_some_and(|source| source.classic)
        {
            result.nodes.append(&mut child.nodes);
            result.captures.append(&mut child.captures);
            return;
        }
        // Collection happens once at the owner boundary. Concatenating projected
        // text on every repetition would make a long token quadratic.
        if result.source.is_none() {
            let kind = self.token_mode().kind;
            result.source = Some(Box::new(FragmentSource {
                parsed_source: None,
                span: Span {
                    start: owner_start,
                    end: fallback.start,
                },
                kind,
                classic: true,
                generated: false,
                text: None,
            }));
        }
        result.nodes.append(&mut child.nodes);
        result.captures.append(&mut child.captures);
    }
    /// CST edge of a preferred metadata capture. Classic collection later keeps only
    /// nodes of the current kind and drops a capture whose nodes all disappear, so
    /// record the already collected nodes as the former `Capture` did. The fragment
    /// itself stays uncollected: metadata must not change evaluation (#788).
    pub(crate) fn metadata_capture_nodes(&self, fragment: &Fragment) -> Vec<usize> {
        match self.token_mode {
            Some(mode)
                if fragment.source.as_deref().is_some_and(|source| {
                    source.classic
                        && source.kind != mode.kind
                        && source.text.is_none()
                        && !source.generated
                }) =>
            {
                fragment
                    .nodes
                    .iter()
                    .copied()
                    .filter(|&index| self.token_kind_at(index) == mode.kind)
                    .collect()
            }
            _ => fragment.nodes.clone(),
        }
    }
    pub(crate) fn collect_classic_fragment(
        &self,
        mut fragment: Fragment,
        kind: TokenKind,
    ) -> Fragment {
        if !fragment
            .source
            .as_deref()
            .is_some_and(|source| source.classic)
            && self.token_mode.is_none()
        {
            return fragment;
        }
        let source = self.collected_source(&fragment, kind);
        self.retain_kind_nodes(&mut fragment, kind);
        let anchor = if kind.is_real() {
            self.code_point(if kind == TokenKind::Consumed {
                self.position
            } else {
                self.matched_position
            })
        } else {
            0
        };
        fragment.source = Some(Box::new(source.unwrap_or(FragmentSource {
            parsed_source: None,
            span: Span {
                start: anchor,
                end: anchor,
            },
            kind,
            classic: true,
            generated: false,
            text: None,
        })));
        fragment
    }
    /// The CST side of a Classic collection: only nodes of `kind` stay, and a capture whose
    /// nodes all disappear is dropped.
    pub(crate) fn retain_kind_nodes(&self, fragment: &mut Fragment, kind: TokenKind) {
        fragment
            .nodes
            .retain(|index| self.token_kind_at(*index) == kind);
        fragment.captures.retain_mut(|capture| {
            if capture.nodes.is_empty() {
                return true;
            }
            capture
                .nodes
                .retain(|index| self.token_kind_at(*index) == kind);
            !capture.nodes.is_empty()
        });
    }
    fn token_source(source: &FragmentSource) -> TokenSource {
        if source.generated {
            TokenSource::Generated {
                anchor: source.span.start,
                text: source
                    .text
                    .as_deref()
                    .map_or("", |text| text.as_ref())
                    .to_owned(),
            }
        } else if let Some(text) = &source.text {
            TokenSource::Projected {
                span: source.span,
                text: std::sync::Arc::clone(text),
            }
        } else if source.kind.is_virtual() && source.span.start == source.span.end {
            TokenSource::Generated {
                anchor: 0,
                text: String::new(),
            }
        } else {
            TokenSource::Input(source.span)
        }
    }
    pub(crate) fn classic_meta_node(
        &mut self,
        fragment: Fragment,
        kind: TokenKind,
        label: &'static str,
    ) -> Fragment {
        // Java Choice は選択した子の Parsed を返す。CST の収集値で上書きしない。
        // virtual の収集は生成 "" になり得るので、入力由来の選択もそのまま返す。
        let selected_source = fragment
            .parsed_source()
            .filter(|source| {
                self.track_owned_source
                    || source.text.is_some()
                    || source.generated
                    || kind.is_virtual()
            })
            .filter(|_| matches!(label, "Choice" | "LongestChoice" | "UniqueLongestChoice"))
            .map(|source| Box::new(source.clone()));
        let mut original = fragment.nodes.clone();
        // Java Choice publishes the selected child's Parsed object, while the
        // committed CST still contains the Choice collector.
        if matches!(label, "Choice" | "LongestChoice" | "UniqueLongestChoice")
            && original.len() == 1
        {
            if let Some(child) = self
                .tokens
                .values
                .get(&original[0])
                .and_then(|info| info.original.as_ref())
            {
                original = child.clone();
            }
        }
        let mut fragment = self.collect_classic_fragment(fragment, kind);
        let source = fragment
            .source
            .as_deref()
            .cloned()
            .expect("Classic collection has source");
        let token_source = Self::token_source(&source);
        // The returned source states what this collector published: one of a virtual kind that
        // kept nothing is generated "", unlike an input-backed empty token a child returned.
        fragment
            .source
            .as_mut()
            .expect("Classic collection has source")
            .generated = matches!(token_source, TokenSource::Generated { .. });
        let id = self
            .add_token_node_inner(super::TOKEN_META_RULE, kind, token_source.clone(), false)
            .expect("validated source");
        if fragment.nodes.is_empty() {
            self.record_trace_node(label, kind, &token_source, id.index);
        }
        self.nodes[id.index].children = fragment.nodes;
        let info = Rc::make_mut(&mut self.tokens)
            .values
            .get_mut(&id.index)
            .unwrap();
        info.parser = Some(label);
        info.original = Some(original);
        info.classic = true;
        fragment.nodes = vec![id.index];
        if selected_source.is_some() {
            fragment
                .source
                .as_mut()
                .expect("Classic collection has source")
                .parsed_source = selected_source;
        }
        fragment
    }
    /// Whether a Classic result returned no token, as a Java Parsed that is neither collected nor
    /// has original tokens: a node-less success, or a Choice that selected one (its `original`),
    /// also through the rule node of such a Choice (a Java LazyChoice returns that Parsed).
    pub(crate) fn classic_returned_no_token(&self, nodes: &[usize]) -> bool {
        let selected_no_token = |index: usize| {
            self.tokens.values.get(&index).is_some_and(|info| {
                matches!(
                    info.parser,
                    Some("Choice" | "LongestChoice" | "UniqueLongestChoice")
                ) && info.original.as_deref() == Some(&[][..])
            })
        };
        self.token_mode.is_some()
            && match nodes {
                [] => true,
                &[index] => {
                    selected_no_token(index)
                        || matches!(&self.nodes[index], node
                            if node.rule < self.rules.len()
                                && matches!(node.children[..], [child] if selected_no_token(child)))
                }
                _ => false,
            }
    }
    pub(crate) fn mark_classic_node(&mut self, index: usize, source: &FragmentSource) {
        let id = self.token_node_id(index).expect("new node");
        let value = Rc::make_mut(&mut self.tokens)
            .values
            .get_mut(&id.index)
            .unwrap();
        value.kind = source.kind;
        value.classic = true;
        value.source = Self::token_source(source);
    }
}

/// Whether a non-Classic collector collects `source` as a projection of the consumed kind.
fn native_projection(source: &FragmentSource) -> bool {
    source.text.is_some() && source.kind == TokenKind::Consumed && !source.generated
}

/// Whether a Classic child of the consumed kind returned a collection other than the input it
/// consumed (`consumed`), which a non-Classic collector then collects as that child's input.
fn returned_input(source: &FragmentSource, consumed: Span) -> bool {
    source.classic
        && source.kind == TokenKind::Consumed
        && source.text.is_none()
        && !source.generated
        && source.span != consumed
}

/// Whether `source` is a Classic child of another kind (a low-level MatchOnly) that returned no
/// projection: a non-Classic consumed collector collects nothing from it.
pub(crate) fn other_kind_classic(source: &FragmentSource) -> bool {
    source.classic
        && source.kind != TokenKind::Consumed
        && source.text.is_none()
        && !source.generated
}

/// Whether a successful non-Classic `expression` returns a token of the consumed kind in Java,
/// even an empty one, which then starts the collection of its parent. A Java collector (Chain,
/// NonOrdered, a Choice, Occurs, a rule, recovery, a capture or value boundary, EmptyParser)
/// commits one, and a lexical leaf (Lexing.LiteralParser, LexicalTokenParser) adds one of the
/// text it matched. A PEG assertion restores the token stack and returns none, and NamedCapture,
/// CaptureScope, Commit, Label, InvertMatchPropagationStopper, SemanticPredicateParser,
/// SemanticPredicateScope and NameResolutionScope (none of them a CollectingParser) return what
/// their child returned. What a host returned only its CST says (see
/// `ParseContext::collect_returned_input`, and `ParseContext::expression_custom` for a token over
/// other input than the host consumed); other expressions are not known to return one.
/// Asked only when a collection starts after children that consumed nothing (see
/// [`ParseContext::append_fragment`]).
pub(crate) fn java_returns_token(mut expression: &Expr) -> bool {
    loop {
        match expression {
            Expr::OwnedCapture(_, child)
            | Expr::CaptureScope(child)
            | Expr::Commit { child, .. }
            | Expr::Label { child, .. }
            | Expr::StopInvert(child)
            | Expr::SemanticPredicate { child, .. }
            | Expr::SemanticPredicateScope { child, .. }
            | Expr::NameResolutionScope { child, .. } => expression = child,
            Expr::Sequence(_)
            | Expr::NonOrdered(_)
            | Expr::Choice(_)
            | Expr::LongestChoice(_)
            | Expr::UniqueLongestChoice(_)
            | Expr::PredictiveChoice { .. }
            | Expr::Optional(_)
            | Expr::JavaOptional(_)
            | Expr::Repeat { .. }
            | Expr::JavaRepeat { .. }
            | Expr::JavaTerminatedRepeat { .. }
            | Expr::Rule(_)
            | Expr::Recovery { .. }
            | Expr::Capture(..)
            | Expr::TextValue(_)
            | Expr::ValueBoundary(_)
            | Expr::CaptureBoundary(..)
            | Expr::InputCaptureBoundary(..)
            | Expr::JavaEmpty
            | Expr::JavaEmptyLiteral
            | Expr::Lexical(..) => return true,
            _ => return false,
        }
    }
}

/// Input and projections a non-Classic collector returned, in order (see `append_fragment`).
/// It exists only from the first projection or excluded Classic child on; after that every
/// child appends the input it consumed as one part (adjacent input parts are not merged).
pub(crate) struct NativeCollection {
    parts: Vec<TokenSource>,
    // The owner's start (a code point), where an unanchored collection starts.
    start: usize,
    end: usize,
}

/// Java's collected source of a token sequence: one shared rule for the Classic and the
/// non-Classic collectors.
struct Collected<'t> {
    first: Option<usize>,
    single_projection: Option<std::sync::Arc<str>>,
    length: usize,
    end: usize,
    projected: bool,
    generated: bool,
    // An empty projection before any nonempty child stays a projection, as Java's sub-source.
    empty_projection: bool,
    parts: Vec<&'t str>,
}

impl<'t> Collected<'t> {
    fn new() -> Self {
        Self {
            first: None,
            single_projection: None,
            length: 0,
            end: 0,
            projected: false,
            generated: true,
            empty_projection: false,
            parts: Vec::new(),
        }
    }
    fn push(&mut self, span: Span, source: Option<&TokenSource>, text: &'t str) {
        let detached = matches!(source, Some(TokenSource::Generated { .. }));
        let projection = source.is_some_and(|source| source.projected_text().is_some());
        // Empty children retain their own source until a nonempty child occurs.
        if self.first.is_none() || self.length == 0 && !text.is_empty() {
            self.first = Some(span.start);
            self.end = span.start;
            self.empty_projection = text.is_empty() && projection;
        }
        if !text.is_empty() {
            self.generated &= detached;
            self.projected |= detached || self.end != span.start || projection;
            self.length += if detached {
                text.chars().count()
            } else {
                span.end - span.start
            };
            self.end = span.end;
            if self.parts.is_empty() {
                self.single_projection = source.and_then(TokenSource::owned_shared);
            } else {
                self.single_projection = None;
            }
            self.parts.push(text);
        }
    }
    fn finish(self, kind: TokenKind) -> Option<FragmentSource> {
        let Self {
            first,
            single_projection,
            length,
            projected,
            generated,
            empty_projection,
            parts,
            ..
        } = self;
        first.map(|start| FragmentSource {
            parsed_source: None,
            span: Span {
                start,
                end: if generated { start } else { start + length },
            },
            kind,
            classic: true,
            generated: generated && length > 0,
            text: if projected {
                Some(single_projection.unwrap_or_else(|| std::sync::Arc::from(parts.concat())))
            } else {
                empty_projection.then(|| std::sync::Arc::from(""))
            }
            .map(Box::new),
        })
    }
}

fn classic_number() -> &'static Expr {
    static VALUE: std::sync::OnceLock<Expr> = std::sync::OnceLock::new();
    VALUE.get_or_init(|| {
        let digit = Expr::character("DigitParser", |cp| cp.is_ascii_digit());
        let sign = Expr::character("SignParser", |cp| matches!(cp, '+' | '-'));
        let point = Expr::character("PointParser", |cp| cp == '.');
        let digits = digit.clone().repeat_java(1, None);
        let exponent = Expr::sequence([
            Expr::character("EParser", |cp| matches!(cp, 'e' | 'E')),
            sign.clone().optional_java(),
            digits.clone(),
        ]);
        Expr::sequence([
            sign.optional_java(),
            Expr::choice([
                Expr::sequence([digits.clone(), point.clone(), digits.clone()]),
                Expr::sequence([digits.clone(), point.clone()]),
                Expr::sequence([digits.clone()]),
                Expr::sequence([point, digits]),
            ]),
            exponent.optional_java(),
        ])
    })
}
fn classic_identifier() -> &'static Expr {
    static VALUE: std::sync::OnceLock<Expr> = std::sync::OnceLock::new();
    VALUE.get_or_init(|| {
        Expr::sequence([
            Expr::character("AlphabetUnderScoreParser", |cp| {
                cp.is_ascii_alphabetic() || cp == '_'
            }),
            Expr::character("AlphabetNumericUnderScoreParser", |cp| {
                cp.is_ascii_alphanumeric() || cp == '_'
            })
            .repeat_java(0, None),
        ])
    })
}
fn classic_quoted(quote: char) -> &'static Expr {
    static SINGLE: std::sync::OnceLock<Expr> = std::sync::OnceLock::new();
    static DOUBLE: std::sync::OnceLock<Expr> = std::sync::OnceLock::new();
    (if quote == '\'' { &SINGLE } else { &DOUBLE }).get_or_init(|| {
        let delimiter = if quote == '\'' {
            Expr::character("SingleQuoteParser", |cp| cp == '\'')
        } else {
            Expr::character("DoubleQuoteParser", |cp| cp == '"')
        };
        let fixed = delimiter.clone().stop_consume().stop_invert();
        let escape = Expr::sequence([
            Expr::character("BackSlashParser", |cp| cp == '\\'),
            Expr::character("WildCardStringParser", |_| true),
        ]);
        Expr::sequence([
            fixed.clone(),
            Expr::choice([escape, delimiter.invert_match()]).repeat_java(0, None),
            fixed,
        ])
    })
}

fn classic_whitespace() -> &'static Expr {
    static VALUE: std::sync::OnceLock<Expr> = std::sync::OnceLock::new();
    VALUE.get_or_init(|| {
        Expr::choice([
            Expr::character("SpaceParser", |cp| {
                matches!(cp, ' ' | '\t' | '\n' | '\r' | '\u{b}' | '\u{c}')
            }),
            Expr::sequence([Expr::literal("//"), Expr::Custom(classic_line_body)]),
            Expr::sequence([
                Expr::literal("/*"),
                Expr::JavaUntil("*/"),
                Expr::literal("*/"),
            ]),
        ])
        .repeat_java(0, None)
    })
}
/// Java `ZeroOrMore(CPPComment)` of a global `@comment` (#712).
fn classic_line_comments() -> &'static Expr {
    static VALUE: std::sync::OnceLock<Expr> = std::sync::OnceLock::new();
    VALUE.get_or_init(|| {
        Expr::sequence([Expr::literal("//"), Expr::Custom(classic_line_body)]).repeat_java(0, None)
    })
}
fn classic_line_body(context: &mut ParseContext<'_>) -> ParseResult {
    context.declare_complete_token_trace();
    let terminator = Expr::choice([
        Expr::literal("\r\n"),
        Expr::literal("\r"),
        Expr::literal("\n"),
        Expr::Eof,
    ])
    .match_only();
    let fragment = context
        .classic_until_parser(&terminator, "MatchOnly", context.call_depth)
        .ok_or_else(|| context.failure())?;
    let span = fragment.source.as_ref().map(|s| s.span).unwrap_or(Span {
        start: context.position(),
        end: context.position(),
    });
    Ok(ParseMatch {
        span,
        nodes: fragment.nodes,
        captures: fragment.captures,
    })
}

fn classic_class_name() -> &'static Expr {
    static VALUE: std::sync::OnceLock<Expr> = std::sync::OnceLock::new();
    VALUE.get_or_init(|| {
        Expr::sequence([
            Expr::Identifier,
            Expr::sequence([
                Expr::character("DotParser", |cp| cp == '.'),
                Expr::Identifier,
            ])
            .repeat_java(0, None),
        ])
    })
}
fn classic_line_end() -> &'static Expr {
    static VALUE: std::sync::OnceLock<Expr> = std::sync::OnceLock::new();
    VALUE.get_or_init(|| {
        Expr::choice([
            Expr::Literal("\r\n"),
            Expr::Literal("\r"),
            Expr::Literal("\n"),
            Expr::Eof,
        ])
    })
}

#[cfg(test)]
mod atomic_trivia_tests {
    use super::*;
    #[test]
    fn lexical_scope_restores_parent_policy_when_internal_frame_hits_resource_limit() {
        for expression in [
            Expr::Number,
            Expr::Identifier,
            Expr::Quoted('\''),
            Expr::Quoted('"'),
            Expr::sequence([Expr::literal("A"), Expr::literal("B")]),
        ] {
            let mut context = ParseContext::new("A B");
            context.whitespace = true;
            context.lexical_context = Some(std::sync::Arc::from([]));
            let definition = crate::lexical::LexicalExpression {
                op: crate::lexical::Op::LITERAL,
                text: " ",
                min: 0,
                max: 0,
                children: vec![],
            };
            context.lexical_trivia = Some(definition.clone());
            // The outer token enters, then its internal graph exhausts the shared budget.
            context.expression_depth = 1023;
            assert!(context
                .parse_with_token_mode(&expression, TokenKind::Consumed, false)
                .is_err());
            assert!(context.resource_failure.is_some());
            assert!(context.whitespace);
            assert!(context.lexical_context.is_some());
            assert_eq!(context.lexical_trivia, Some(definition));
            assert_eq!(context.expression_depth, 1023);
            assert_eq!((context.position(), context.matched_position()), (0, 0));
            assert!(context.token_mode.is_none());
        }
    }
}
