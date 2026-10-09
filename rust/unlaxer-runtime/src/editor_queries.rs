//! Original-source partial editor completion; host forwarding enforces unique edit ownership.
use crate::{
    editor::{EditorParseResult, Status},
    language_queries::{Item, Provider, Request, Response, TextEdit},
    source::{Location, Operation, Result, State},
    Span,
};
use std::collections::HashSet;
pub struct EditorQueryProvider<T, F>
where
    F: Fn(&Request<'_>) -> Result<EditorParseResult<T>>,
{
    project: String,
    version: u64,
    parser: F,
}
impl<T, F> EditorQueryProvider<T, F>
where
    F: Fn(&Request<'_>) -> Result<EditorParseResult<T>>,
{
    pub fn new(project: String, version: u64, parser: F) -> Result<Self> {
        if project.is_empty() {
            return Err("invalid editor project");
        }
        Ok(Self {
            project,
            version,
            parser,
        })
    }
}
impl<T, F> Provider for EditorQueryProvider<T, F>
where
    F: Fn(&Request<'_>) -> Result<EditorParseResult<T>>,
{
    fn capabilities(&self) -> HashSet<Operation> {
        [Operation::Completion].into()
    }
    fn query(&self, request: &Request<'_>) -> Result<Response> {
        let snapshot = request.region.source_map.output();
        if request.project.id != self.project || request.project.version != self.version {
            return Err("stale editor project");
        }
        let response = |state, items| Response {
            snapshot: snapshot.clone(),
            project: self.project.clone(),
            project_version: self.version,
            state,
            items,
        };
        if request.operation != Operation::Completion {
            return Ok(response(State::Unsupported, vec![]));
        }
        let result = (self.parser)(request)?;
        if result.uri() != snapshot.uri
            || result.version() < 0
            || result.version() as u64 != snapshot.version
            || result.source() != snapshot.text
        {
            return Err("stale editor result");
        }
        let state = match result.status() {
            Status::Complete => State::Complete,
            Status::Partial => State::Partial,
            Status::Failed => State::Failed,
        };
        if state == State::Failed {
            return Ok(response(state, vec![]));
        }
        let prefix = request
            .parameters
            .get("prefix")
            .map(String::as_str)
            .unwrap_or("");
        let start = request
            .cursor
            .checked_sub(prefix.chars().count())
            .ok_or("completion prefix is not original source")?;
        if snapshot.slice(Span {
            start,
            end: request.cursor,
        })? != prefix
        {
            return Err("completion prefix is not original source");
        }
        let mut items = vec![];
        for completion in result
            .complete_at(
                request.cursor,
                Some(&request.region.id),
                result.version(),
                prefix,
            )
            .map_err(|_| "invalid editor completion")?
        {
            let symbol = completion.symbol;
            items.push(Item {
                label: symbol.name.clone(),
                detail: format!(
                    "{}; expected={}",
                    symbol.type_id,
                    completion.expected_types.join(",")
                ),
                locations: vec![Location::new(snapshot.clone(), symbol.declaration)?],
                edits: vec![TextEdit {
                    location: Location::new(
                        snapshot.clone(),
                        Span {
                            start,
                            end: request.cursor,
                        },
                    )?,
                    replacement: symbol.name,
                }],
            });
        }
        Ok(response(state, items))
    }
}
