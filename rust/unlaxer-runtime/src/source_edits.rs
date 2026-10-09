//! Original-source ownership and checked source-preserving edit plans.
use crate::{
    source::{validate_edits, Edit, LanguageRegions, Result, Snapshot},
    Span,
};
use std::collections::HashMap;
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Token,
    Whitespace,
    Comment,
    Unparsed,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Operation {
    Rename,
    Format,
    CodeAction,
}
#[derive(Debug, Clone)]
pub struct Piece {
    pub id: String,
    pub span: Span,
    pub kind: Kind,
    pub owner: String,
}
#[derive(Debug, Clone)]
pub struct Plan {
    snapshot: Snapshot,
    operation: Operation,
    allowed: Span,
    edits: Vec<Edit>,
}
fn contains(outer: Span, inner: Span) -> bool {
    outer.start <= inner.start && inner.start <= inner.end && inner.end <= outer.end
}
fn whitespace(text: &str) -> bool {
    text.chars()
        .all(|character| matches!(character, ' ' | '\t' | '\r' | '\n'))
}
impl Plan {
    pub fn operation(&self) -> Operation {
        self.operation
    }
    pub fn allowed(&self) -> Span {
        self.allowed
    }
    pub fn snapshot(&self) -> &Snapshot {
        &self.snapshot
    }
    pub fn edits(&self) -> &[Edit] {
        &self.edits
    }
    pub fn apply(&self, current: &Snapshot, version: u64) -> Result<Snapshot> {
        LanguageRegions::new(self.snapshot.clone(), vec![])?.apply(current, version, &self.edits)
    }
}
pub struct SourceEdits {
    snapshot: Snapshot,
    pieces: Vec<Piece>,
    by_id: HashMap<String, usize>,
}
impl SourceEdits {
    pub fn new(snapshot: Snapshot, pieces: Vec<Piece>) -> Result<Self> {
        let mut by_id = HashMap::new();
        let mut cursor = 0;
        for (index, piece) in pieces.iter().enumerate() {
            snapshot.check(piece.span)?;
            if piece.id.is_empty() || piece.span.start == piece.span.end {
                return Err("invalid piece");
            }
            if piece.span.start != cursor || by_id.insert(piece.id.clone(), index).is_some() {
                return Err("pieces must uniquely partition source");
            }
            if piece.kind == Kind::Whitespace && !whitespace(snapshot.slice(piece.span)?) {
                return Err("non-whitespace trivia");
            }
            if matches!(piece.kind, Kind::Token | Kind::Unparsed) && !piece.owner.is_empty() {
                return Err("only trivia can have an owner");
            }
            cursor = piece.span.end;
        }
        if cursor != snapshot.len() {
            return Err("incomplete piece inventory");
        }
        for piece in &pieces {
            if piece.owner.is_empty() {
                continue;
            }
            let owner = &pieces[*by_id.get(&piece.owner).ok_or("invalid trivia owner")?];
            if owner.kind != Kind::Token {
                return Err("invalid trivia owner");
            }
            let gap = if owner.span.end <= piece.span.start {
                Span {
                    start: owner.span.end,
                    end: piece.span.start,
                }
            } else {
                Span {
                    start: piece.span.end,
                    end: owner.span.start,
                }
            };
            for between in &pieces {
                if contains(gap, between.span)
                    && matches!(between.kind, Kind::Token | Kind::Unparsed)
                {
                    return Err("trivia crosses another token");
                }
            }
        }
        Ok(Self {
            snapshot,
            pieces,
            by_id,
        })
    }
    pub fn snapshot(&self) -> &Snapshot {
        &self.snapshot
    }
    pub fn pieces(&self) -> &[Piece] {
        &self.pieces
    }
    pub fn token(&self, id: &str) -> Result<&Piece> {
        let piece = &self.pieces[*self.by_id.get(id).ok_or("not a token")?];
        if piece.kind != Kind::Token {
            return Err("not a token");
        }
        Ok(piece)
    }
    pub fn round_trip(&self) -> String {
        self.pieces
            .iter()
            .map(|piece| self.snapshot.slice(piece.span).expect("validated piece"))
            .collect()
    }
    pub fn plan(&self, operation: Operation, allowed: Span, edits: Vec<Edit>) -> Result<Plan> {
        self.snapshot.check(allowed)?;
        validate_edits(&edits)?;
        for edit in &edits {
            self.snapshot.check(edit.span)?;
            if !contains(allowed, edit.span) {
                return Err("edit outside allowed range");
            }
            match operation {
                Operation::Rename => {
                    if !self
                        .pieces
                        .iter()
                        .any(|piece| piece.kind == Kind::Token && piece.span == edit.span)
                    {
                        return Err("rename must replace a complete token");
                    }
                }
                Operation::Format => {
                    if !whitespace(&edit.replacement) {
                        return Err("format replacement is not whitespace");
                    }
                    if !self.pieces.iter().any(|piece| {
                        piece.kind == Kind::Whitespace && contains(piece.span, edit.span)
                    }) {
                        return Err("format may only edit existing whitespace");
                    }
                }
                Operation::CodeAction => (),
            }
        }
        Ok(Plan {
            snapshot: self.snapshot.clone(),
            operation,
            allowed,
            edits,
        })
    }
    pub fn map(&self, child: &Plan, map: &crate::source::SourceMap, allowed: Span) -> Result<Plan> {
        if child.snapshot() != map.output() {
            return Err("stale source map");
        }
        let mut edits = vec![];
        for edit in child.edits() {
            let origin = map.edit(edit.span)?;
            if origin.snapshot != self.snapshot {
                return Err("different host snapshot");
            }
            edits.push(Edit {
                span: origin.span,
                replacement: edit.replacement.clone(),
            });
        }
        self.plan(child.operation(), allowed, edits)
    }
}

/// Build all new snapshots atomically; original documents remain immutable.
pub fn apply_all(
    plans: &[Plan],
    current: &HashMap<String, Snapshot>,
    versions: &HashMap<String, u64>,
) -> Result<HashMap<String, Snapshot>> {
    let mut result = HashMap::new();
    for plan in plans {
        let uri = &plan.snapshot().uri;
        if result.contains_key(uri) {
            return Err("duplicate or missing document");
        }
        let version = *versions.get(uri).ok_or("duplicate or missing document")?;
        let current = current.get(uri).ok_or("duplicate or missing document")?;
        result.insert(uri.clone(), plan.apply(current, version)?);
    }
    Ok(result)
}
