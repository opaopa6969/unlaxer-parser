//! Immutable editor result contract. Typed strict ASTs exist only for complete input.
use crate::semantic::{Completion, SemanticModel};
use crate::Span;
use std::collections::HashSet;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Complete,
    Partial,
    Failed,
}
impl Status {
    pub fn name(self) -> &'static str {
        match self {
            Self::Complete => "COMPLETE",
            Self::Partial => "PARTIAL",
            Self::Failed => "FAILED",
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NodeKind {
    Missing,
    Error,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Node {
    pub kind: NodeKind,
    pub span: Span,
    pub candidate_rules: Vec<String>,
    pub region_id: Option<String>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CallSite {
    pub call_id: String,
    pub argument_index: usize,
    pub region_id: Option<String>,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Utf16Span {
    pub start: usize,
    pub end: usize,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EditorError {
    pub code: &'static str,
    pub span: Span,
}
impl std::fmt::Display for EditorError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            formatter,
            "{} at {}..{}",
            self.code, self.span.start, self.span.end
        )
    }
}
impl std::error::Error for EditorError {}
fn error(code: &'static str, span: Span) -> EditorError {
    EditorError { code, span }
}
type Result<T> = std::result::Result<T, EditorError>;

#[derive(Debug, Clone)]
pub struct EditorParseResult<T> {
    uri: String,
    version: i64,
    source: String,
    status: Status,
    ast: Option<T>,
    nodes: Vec<Node>,
    semantics: Option<SemanticModel>,
    calls: Vec<CallSite>,
    length: usize,
}
impl<T> EditorParseResult<T> {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        uri: String,
        version: i64,
        source: String,
        status: Status,
        ast: Option<T>,
        nodes: Vec<Node>,
        semantics: Option<SemanticModel>,
        calls: Vec<CallSite>,
    ) -> Result<Self> {
        let length = source.chars().count();
        let document = Span {
            start: 0,
            end: length,
        };
        if uri.is_empty() || version < 0 || length > i32::MAX as usize {
            return Err(error("EDITOR_INVALID_DOCUMENT", document));
        }
        if (status == Status::Complete) != ast.is_some()
            || status == Status::Complete && !nodes.is_empty()
            || status == Status::Partial && nodes.is_empty()
        {
            return Err(error("EDITOR_INVALID_STATUS", document));
        }
        let result = Self {
            uri,
            version,
            source,
            status,
            ast,
            nodes,
            semantics,
            calls,
            length,
        };
        for node in &result.nodes {
            result.check_span(node.span)?;
            Self::check_region(node.region_id.as_deref(), node.span)?;
            if (node.kind == NodeKind::Missing) != (node.span.start == node.span.end) {
                return Err(error("EDITOR_INVALID_NODE", node.span));
            }
            let mut seen = HashSet::new();
            for candidate in &node.candidate_rules {
                if candidate.is_empty() || !seen.insert(candidate) {
                    return Err(error("EDITOR_INVALID_CANDIDATE", node.span));
                }
            }
        }
        if let Some(model) = &result.semantics {
            if model.uri() != result.uri
                || model.version() != result.version
                || model.source() != result.source
            {
                return Err(error("EDITOR_SNAPSHOT_MISMATCH", document));
            }
        }
        let mut seen = HashSet::new();
        for site in &result.calls {
            let slot = result
                .semantics
                .as_ref()
                .and_then(|model| {
                    model
                        .data()
                        .calls
                        .iter()
                        .find(|call| call.id == site.call_id)
                })
                .and_then(|call| call.arguments.get(site.argument_index))
                .ok_or_else(|| error("EDITOR_INVALID_CALL_SITE", document))?
                .span;
            Self::check_region(site.region_id.as_deref(), slot)?;
            if !seen.insert((&site.call_id, site.argument_index)) {
                return Err(error("EDITOR_DUPLICATE_CALL_SITE", slot));
            }
        }
        Ok(result)
    }
    fn check_span(&self, span: Span) -> Result<()> {
        if span.start > span.end {
            return Err(error("INVALID_SPAN", span));
        }
        if span.end > self.length {
            return Err(error("EDITOR_SPAN_OUTSIDE_DOCUMENT", span));
        }
        Ok(())
    }
    fn check_region(region: Option<&str>, span: Span) -> Result<()> {
        if region == Some("") {
            return Err(error("EDITOR_INVALID_REGION", span));
        }
        Ok(())
    }
    pub fn uri(&self) -> &str {
        &self.uri
    }
    pub fn version(&self) -> i64 {
        self.version
    }
    pub fn source(&self) -> &str {
        &self.source
    }
    pub fn status(&self) -> Status {
        self.status
    }
    pub fn strict_ast(&self) -> Option<&T> {
        self.ast.as_ref()
    }
    pub fn nodes(&self) -> &[Node] {
        &self.nodes
    }
    pub fn semantics(&self) -> Option<&SemanticModel> {
        self.semantics.as_ref()
    }
    pub fn calls(&self) -> &[CallSite] {
        &self.calls
    }
    pub fn utf16_span(&self, span: Span) -> Result<Utf16Span> {
        self.check_span(span)?;
        Ok(Utf16Span {
            start: self
                .source
                .chars()
                .take(span.start)
                .map(char::len_utf16)
                .sum(),
            end: self
                .source
                .chars()
                .take(span.end)
                .map(char::len_utf16)
                .sum(),
        })
    }
    fn slot(&self, site: &CallSite) -> Span {
        self.semantics
            .as_ref()
            .unwrap()
            .data()
            .calls
            .iter()
            .find(|call| call.id == site.call_id)
            .unwrap()
            .arguments[site.argument_index]
            .span
    }
    /// Innermost argument slot with an optional exact region filter. Endpoints are inclusive.
    pub fn call_at(&self, cursor: usize, region_id: Option<&str>) -> Result<Option<&CallSite>> {
        if cursor > self.length {
            return Err(error(
                "EDITOR_INVALID_CURSOR",
                Span {
                    start: cursor,
                    end: cursor,
                },
            ));
        }
        Ok(self
            .calls
            .iter()
            .filter(|site| region_id.is_none() || region_id == site.region_id.as_deref())
            .filter(|site| {
                let span = self.slot(site);
                span.start <= cursor && cursor <= span.end
            })
            .min_by(|left, right| {
                let a = self.slot(left);
                let b = self.slot(right);
                (a.end - a.start, &left.call_id, left.argument_index).cmp(&(
                    b.end - b.start,
                    &right.call_id,
                    right.argument_index,
                ))
            }))
    }
    pub fn expected_types_at(&self, cursor: usize, region_id: Option<&str>) -> Result<Vec<String>> {
        let Some(site) = self.call_at(cursor, region_id)? else {
            return Ok(Vec::new());
        };
        self.semantics
            .as_ref()
            .unwrap()
            .expected_types(&site.call_id, site.argument_index)
            .map_err(|failure| error(failure.code, failure.span))
    }
    pub fn complete_at(
        &self,
        cursor: usize,
        region_id: Option<&str>,
        snapshot_version: i64,
        prefix: &str,
    ) -> Result<Vec<Completion>> {
        if snapshot_version != self.version {
            return Err(error(
                "EDITOR_STALE_SNAPSHOT",
                Span {
                    start: 0,
                    end: self.length,
                },
            ));
        }
        let Some(site) = self.call_at(cursor, region_id)? else {
            return Ok(Vec::new());
        };
        self.semantics
            .as_ref()
            .unwrap()
            .complete_argument(
                &site.call_id,
                site.argument_index,
                cursor,
                snapshot_version,
                prefix,
            )
            .map_err(|failure| error(failure.code, failure.span))
    }
}
