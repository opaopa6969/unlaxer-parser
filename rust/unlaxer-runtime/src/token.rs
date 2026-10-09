//! Explicit Classic token parsing and owned portable metadata.
//! This API does not add UBNF syntax or translate arbitrary Java objects.
use super::{Node, ParseContext, ParseMatch, ParseResult, Span, Tree};
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
    Generated { anchor: usize, text: String },
}

#[derive(Debug, Clone)]
pub struct TokenInfo {
    pub kind: TokenKind,
    pub source: TokenSource,
    extra: BTreeMap<String, String>,
    related: BTreeMap<String, NodeId>,
}
impl TokenInfo {
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
            ids: BTreeMap::new(),
            values: BTreeMap::new(),
            calls: BTreeMap::new(),
        }
    }
    fn valid(&self, id: NodeId) -> bool {
        id.owner == self.owner && self.ids.get(&id.index) == Some(&id)
    }
}

impl ParseContext<'_> {
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
        store.values.insert(
            index,
            TokenInfo {
                kind: TokenKind::Consumed,
                source: TokenSource::Input(span),
                extra: BTreeMap::new(),
                related: BTreeMap::new(),
            },
        );
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
        let span = match &source {
            TokenSource::Input(span) => {
                self.text(*span)?;
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
