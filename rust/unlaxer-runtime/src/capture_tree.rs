//! Explicit value/optional/list boundaries, independent of legacy flat captures.
use crate::{Expr, Span, Tree};
use std::collections::BTreeMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Value,
    Optional,
    List,
}
impl Kind {
    pub(crate) const fn rule(self) -> usize {
        usize::MAX
            - match self {
                Self::Value => 5,
                Self::Optional => 6,
                Self::List => 7,
            }
    }
    pub fn of_rule(rule: usize) -> Option<Self> {
        [Self::Value, Self::Optional, Self::List]
            .into_iter()
            .find(|kind| kind.rule() == rule)
    }
}
impl Expr {
    /// Preserve this entire successful child as one leaf, even when its text is empty.
    pub fn capture_value(self) -> Self {
        Self::CaptureBoundary(Kind::Value, Box::new(self))
    }
    fn capture_item(self) -> Self {
        if matches!(
            self,
            Self::CaptureBoundary(_, _) | Self::InputCaptureBoundary(_, _)
        ) {
            self
        } else {
            self.capture_value()
        }
    }
    /// Preserve absent versus present, including present empty containers.
    pub fn capture_optional(self) -> Self {
        Self::CaptureBoundary(
            Kind::Optional,
            Box::new(self.capture_item().optional_java()),
        )
    }
    /// Preserve each successful repetition, including Java's final zero-progress item.
    /// A zero maximum evaluates no child; invalid bounds fail using the usual parser contract.
    pub fn capture_list(self, min: usize, max: Option<usize>) -> Self {
        Self::CaptureBoundary(
            Kind::List,
            Box::new(self.capture_item().repeat_java(min, max)),
        )
    }
}

/// Owned immutable observation. Original CST remains available for typed mapping.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Element {
    kind: Kind,
    token_kind: crate::token::TokenKind,
    generated: bool,
    span: Span,
    text: String,
    children: Vec<Element>,
}
impl Element {
    pub fn generated(&self) -> bool {
        self.generated
    }
    pub fn kind(&self) -> Kind {
        self.kind
    }
    pub fn token_kind(&self) -> crate::token::TokenKind {
        self.token_kind
    }
    pub fn span(&self) -> Span {
        self.span
    }
    pub fn text(&self) -> &str {
        &self.text
    }
    pub fn children(&self) -> &[Self] {
        &self.children
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Error {
    NotBoundary,
    InvalidTree,
    Limit,
}
const MAX_DEPTH: usize = 256;
const MAX_NODES: usize = 65_536;
const MAX_BYTES: usize = 2 * 1024 * 1024;

/// Copy a committed boundary without parsing again. All input/projected/generated text is owned.
/// Reject malformed public CSTs and bound traversal depth, visits, and total retained UTF-8 bytes.
pub fn snapshot(tree: &Tree, root: usize) -> Result<Element, Error> {
    let root_node = tree.nodes.get(root).ok_or(Error::InvalidTree)?;
    if Kind::of_rule(root_node.rule).is_none() {
        return Err(Error::NotBoundary);
    }
    let mut budget = Budget {
        nodes: 0,
        bytes: 0,
        offsets: BTreeMap::new(),
    };
    // Retain only requested endpoints, never a second table proportional to the entire input.
    prepare(tree, root, 0, &mut budget)?;
    let mut chars = tree.source.chars();
    let (mut cp, mut byte) = (0, 0);
    for (&endpoint, offset) in &mut budget.offsets {
        while cp < endpoint {
            byte += chars.next().ok_or(Error::InvalidTree)?.len_utf8();
            cp += 1;
        }
        *offset = byte;
    }
    budget.nodes = 0;
    budget.bytes = 0;
    build(tree, root, 0, &mut budget)
}
struct Budget {
    nodes: usize,
    bytes: usize,
    offsets: BTreeMap<usize, usize>,
}
fn prepare(tree: &Tree, id: usize, depth: usize, budget: &mut Budget) -> Result<(), Error> {
    visit(depth, budget)?;
    let node = tree.nodes.get(id).ok_or(Error::InvalidTree)?;
    if let Some(kind) = Kind::of_rule(node.rule) {
        if node.span.start > node.span.end {
            return Err(Error::InvalidTree);
        }
        let source = tree
            .token_node_id(id)
            .and_then(|token| tree.token_info(token))
            .map(|info| &info.source);
        let minimum_bytes = match source {
            Some(crate::token::TokenSource::Projected { text, .. }) => text.len(),
            Some(crate::token::TokenSource::Generated { text, .. }) => text.len(),
            input => {
                let span = match input {
                    Some(crate::token::TokenSource::Input(span)) => *span,
                    _ => node.span,
                };
                if span.start > span.end {
                    return Err(Error::InvalidTree);
                }
                budget.offsets.insert(span.start, 0);
                budget.offsets.insert(span.end, 0);
                span.end - span.start
            }
        };
        budget.bytes = budget
            .bytes
            .checked_add(minimum_bytes)
            .ok_or(Error::Limit)?;
        if budget.bytes > MAX_BYTES {
            return Err(Error::Limit);
        }
        if kind == Kind::Value {
            return Ok(());
        }
    }
    for &child in &node.children {
        prepare(tree, child, depth + 1, budget)?;
    }
    Ok(())
}

fn visit(depth: usize, budget: &mut Budget) -> Result<(), Error> {
    budget.nodes += 1;
    if depth >= MAX_DEPTH || budget.nodes > MAX_NODES {
        return Err(Error::Limit);
    }
    Ok(())
}
fn build(tree: &Tree, id: usize, depth: usize, budget: &mut Budget) -> Result<Element, Error> {
    visit(depth, budget)?;
    let node = tree.nodes.get(id).ok_or(Error::InvalidTree)?;
    let kind = Kind::of_rule(node.rule).ok_or(Error::NotBoundary)?;
    if node.span.start > node.span.end {
        return Err(Error::InvalidTree);
    }
    let source = tree
        .token_node_id(id)
        .and_then(|token| tree.token_info(token))
        .map(|info| &info.source);
    let text = match source {
        Some(crate::token::TokenSource::Projected { text, .. }) => text.as_ref(),
        Some(crate::token::TokenSource::Generated { text, .. }) => text.as_str(),
        input => {
            let span = match input {
                Some(crate::token::TokenSource::Input(span)) => *span,
                _ => node.span,
            };
            if span.start > span.end {
                return Err(Error::InvalidTree);
            }
            let start = *budget.offsets.get(&span.start).ok_or(Error::InvalidTree)?;
            let end = *budget.offsets.get(&span.end).ok_or(Error::InvalidTree)?;
            tree.source.get(start..end).ok_or(Error::InvalidTree)?
        }
    };
    budget.bytes = budget.bytes.checked_add(text.len()).ok_or(Error::Limit)?;
    if budget.bytes > MAX_BYTES {
        return Err(Error::Limit);
    }
    let mut children = Vec::new();
    if kind != Kind::Value {
        for &child in &node.children {
            collect(tree, child, depth + 1, budget, &mut children)?;
        }
        if kind == Kind::Optional && children.len() > 1 {
            return Err(Error::InvalidTree);
        }
    }
    Ok(Element {
        kind,
        generated: !text.is_empty()
            && matches!(source, Some(crate::token::TokenSource::Generated { .. })),
        token_kind: tree
            .token_node_id(id)
            .and_then(|token| tree.token_info(token))
            .map_or(crate::token::TokenKind::Consumed, |info| info.kind),
        span: node.span,
        text: text.to_owned(),
        children,
    })
}
fn collect(
    tree: &Tree,
    id: usize,
    depth: usize,
    budget: &mut Budget,
    out: &mut Vec<Element>,
) -> Result<(), Error> {
    let node = tree.nodes.get(id).ok_or(Error::InvalidTree)?;
    if Kind::of_rule(node.rule).is_some() {
        out.push(build(tree, id, depth, budget)?);
    } else {
        visit(depth, budget)?;
        for &child in &node.children {
            collect(tree, child, depth + 1, budget, out)?;
        }
    }
    Ok(())
}
