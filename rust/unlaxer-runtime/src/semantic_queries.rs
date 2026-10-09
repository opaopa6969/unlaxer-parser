//! Language-query adapter for an already built, immutable semantic project index.
use crate::language_queries::{Item, Provider, Request, Response, TextEdit};
use crate::semantic::UNKNOWN;
use crate::semantic_project::{Definition, Module, ProjectSymbolIndex};
use crate::source::{Location, Operation, Result, Snapshot, State};
use std::collections::HashSet;
pub struct ProjectQueryProvider {
    index: ProjectSymbolIndex,
}
impl ProjectQueryProvider {
    pub fn new(index: ProjectSymbolIndex) -> Self {
        Self { index }
    }
    fn snapshot(module: &Module) -> Result<Snapshot> {
        Snapshot::new(
            module.model.uri(),
            module.model.version() as u64,
            module.model.source(),
        )
    }
    fn location(&self, definition: &Definition) -> Result<Location> {
        let modules = if definition.identity.dependency.is_empty() {
            self.index.modules()
        } else {
            &self
                .index
                .dependencies()
                .iter()
                .find(|dependency| dependency.id == definition.identity.dependency)
                .ok_or("missing definition dependency")?
                .modules
        };
        let owner = modules
            .iter()
            .find(|module| module.id == definition.identity.module)
            .ok_or("missing definition module")?;
        let snapshot = Self::snapshot(owner)?;
        if snapshot.uri != definition.uri || snapshot.version != definition.version as u64 {
            return Err("stale definition snapshot");
        }
        Location::new(snapshot, definition.span)
    }
}
impl Provider for ProjectQueryProvider {
    fn capabilities(&self) -> HashSet<Operation> {
        [
            Operation::Completion,
            Operation::Hover,
            Operation::Definition,
        ]
        .into()
    }
    fn query(&self, request: &Request<'_>) -> Result<Response> {
        let snapshot = request.region.source_map.output();
        if request.project.id != self.index.project()
            || request.project.version != self.index.version() as u64
        {
            return Err("stale semantic project");
        }
        let module = self
            .index
            .modules()
            .iter()
            .find(|module| module.model.uri() == snapshot.uri)
            .ok_or("unknown semantic document")?;
        if &Self::snapshot(module)? != snapshot {
            return Err("stale semantic document");
        }
        let mut items = vec![];
        let mut state = State::Complete;
        match request.operation {
            Operation::Completion => {
                let prefix = request
                    .parameters
                    .get("prefix")
                    .map(String::as_str)
                    .unwrap_or("");
                let expected = request
                    .parameters
                    .get("expectedType")
                    .map(String::as_str)
                    .unwrap_or(UNKNOWN);
                for completion in self
                    .index
                    .complete(
                        &module.id,
                        self.index.version(),
                        module.model.version(),
                        request.cursor,
                        prefix,
                        expected,
                    )
                    .map_err(|_| "invalid semantic completion")?
                {
                    let definition = &completion.candidate.definition;
                    let edit = completion.edit;
                    if edit.uri != snapshot.uri || edit.version as u64 != snapshot.version {
                        return Err("completion edit owns another snapshot");
                    }
                    items.push(Item {
                        label: completion.candidate.name,
                        detail: completion.reason,
                        locations: vec![self.location(definition)?],
                        edits: vec![TextEdit {
                            location: Location::new(snapshot.clone(), edit.span)?,
                            replacement: edit.text,
                        }],
                    });
                }
            }
            Operation::Hover | Operation::Definition => {
                let name = request
                    .parameters
                    .get("name")
                    .filter(|name| !name.is_empty())
                    .ok_or("query requires a token name")?;
                let binding = self
                    .index
                    .resolve(
                        &module.id,
                        self.index.version(),
                        module.model.version(),
                        request.cursor,
                        name,
                    )
                    .map_err(|_| "invalid semantic lookup")?;
                if binding.status() != "RESOLVED" {
                    state = if binding.candidates.is_empty() {
                        State::Failed
                    } else {
                        State::Partial
                    };
                }
                for candidate in binding.candidates {
                    items.push(Item {
                        locations: vec![self.location(&candidate.definition)?],
                        label: candidate.name,
                        detail: candidate.type_id,
                        edits: vec![],
                    });
                }
            }
            _ => state = State::Unsupported,
        }
        Ok(Response {
            snapshot: snapshot.clone(),
            project: self.index.project().into(),
            project_version: self.index.version() as u64,
            state,
            items,
        })
    }
}
