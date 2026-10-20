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
        let result = parser.parse_classic(self);
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
    /// must use a virtual kind; input-backed virtual kinds are also allowed.
    pub fn add_token_node(
        &mut self,
        rule: usize,
        kind: TokenKind,
        source: TokenSource,
    ) -> Option<NodeId> {
        self.add_token_node_inner(rule, kind, source, true)
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
        let result = self.transaction(|context| parser.parse(context, kind, invert));
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
                        Expr::Literal(word) | Expr::CaseInsensitive(word) => word,
                        Expr::LongCodeBlock => "long code block",
                        _ => "character",
                    },
                );
            }
            if mode.kind.is_consumed() && !matches!(expression, Expr::LongCodeBlock) {
                self.matched_position = self.position;
            }
            return None;
        }
        let span = Span {
            start: self.code_point(start),
            end: self.code_point(end),
        };
        if mode.kind.is_consumed() && !(matches!(expression, Expr::Lexical(_, _)) && start == end) {
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
        self.classic_until_parser(&Expr::Literal(terminator).match_only(), "Choice", depth)
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
        if self.token_mode.is_none() || !self.whitespace && self.lexical_trivia.is_none() {
            self.skip();
            return Some(());
        }
        let start = self.position;
        let definition = self.lexical_trivia.clone();
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
        let mut first = None;
        let mut single_projection = None;
        let mut length = 0;
        let mut end = 0;
        let mut projected = false;
        let mut generated = true;
        let mut parts = Vec::new();
        for &index in &fragment.nodes {
            if self.token_kind_at(index) != kind {
                continue;
            }
            let node = &self.nodes[index];
            let source = self.tokens.values.get(&index).map(|info| &info.source);
            let detached = matches!(source, Some(TokenSource::Generated { .. }));
            let text = match source {
                Some(TokenSource::Generated { text, .. }) => text.as_str(),
                _ => source
                    .and_then(TokenSource::projected_text)
                    .unwrap_or_else(|| self.text(node.span).expect("validated input source")),
            };
            if text.is_empty() && kind.is_virtual() {
                continue;
            }
            // Empty children retain their own source until a nonempty child occurs.
            if first.is_none() || length == 0 && !text.is_empty() {
                first = Some(node.span.start);
                end = node.span.start;
            }
            if !text.is_empty() {
                generated &= detached;
                projected |= detached
                    || end != node.span.start
                    || source.is_some_and(|source| source.projected_text().is_some());
                length += if detached {
                    text.chars().count()
                } else {
                    node.span.end - node.span.start
                };
                end = node.span.end;
                if parts.is_empty() {
                    single_projection = source.and_then(TokenSource::owned_shared);
                } else {
                    single_projection = None;
                }
                parts.push(text);
            }
        }
        first.map(|start| FragmentSource {
            span: Span {
                start,
                end: if generated { start } else { start + length },
            },
            kind,
            classic: true,
            generated: generated && length > 0,
            text: projected
                .then(|| single_projection.unwrap_or_else(|| std::sync::Arc::from(parts.concat()))),
        })
    }
    pub(crate) fn append_classic_fragment(
        &self,
        result: &mut Fragment,
        mut child: Fragment,
        fallback: Span,
        owner_start: usize,
    ) {
        if self.token_mode.is_none() && result.source.is_none() && child.source.is_none() {
            result.nodes.append(&mut child.nodes);
            result.captures.append(&mut child.captures);
            return;
        }
        // Collection happens once at the owner boundary. Concatenating projected
        // text on every repetition would make a long token quadratic.
        if result.source.is_none() {
            let kind = self.token_mode().kind;
            result.source = Some(Box::new(FragmentSource {
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
    fn token_source(source: &FragmentSource) -> TokenSource {
        if source.generated {
            TokenSource::Generated {
                anchor: source.span.start,
                text: source.text.as_deref().unwrap_or("").to_owned(),
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
        fragment
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
