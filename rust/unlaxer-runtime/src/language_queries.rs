//! Cursor/project-aware forwarding that maps only locations owned by the virtual document.
use crate::source::{
    validate_edits, Edit, Language, LanguageRegions, Location, Mapping, Operation, Region, Result,
    Snapshot, State,
};
use crate::Span;
use std::collections::{BTreeMap, HashMap, HashSet};
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Project {
    pub id: String,
    pub version: u64,
    pub documents: BTreeMap<String, Snapshot>,
    pub configuration: BTreeMap<String, String>,
}
pub struct Request<'a> {
    pub region: &'a Region,
    pub operation: Operation,
    pub cursor: usize,
    pub project: &'a Project,
    pub parameters: &'a BTreeMap<String, String>,
}
#[derive(Debug, Clone)]
pub struct TextEdit {
    pub location: Location,
    pub replacement: String,
}
#[derive(Debug, Clone)]
pub struct Item {
    pub label: String,
    pub detail: String,
    pub locations: Vec<Location>,
    pub edits: Vec<TextEdit>,
}
pub struct Response {
    pub snapshot: Snapshot,
    pub project: String,
    pub project_version: u64,
    pub state: State,
    pub items: Vec<Item>,
}
pub trait Provider {
    fn capabilities(&self) -> HashSet<Operation>;
    fn query(&self, request: &Request<'_>) -> Result<Response>;
}
#[derive(Debug)]
pub struct MappedItem {
    pub label: String,
    pub detail: String,
    pub locations: Vec<Mapping>,
    pub edits: Vec<Edit>,
}
#[derive(Debug)]
pub struct QueryResult {
    pub region: String,
    pub state: State,
    pub items: Vec<MappedItem>,
}
pub struct LanguageQueries {
    regions: LanguageRegions,
    project: Project,
    providers: HashMap<Language, Box<dyn Provider>>,
}
impl LanguageQueries {
    pub fn new(
        regions: LanguageRegions,
        project: Project,
        providers: HashMap<Language, Box<dyn Provider>>,
    ) -> Result<Self> {
        if project.id.is_empty()
            || project
                .documents
                .iter()
                .any(|(uri, snapshot)| uri != &snapshot.uri)
        {
            return Err("invalid project documents");
        }
        if project.documents.get(&regions.host().uri) != Some(regions.host()) {
            return Err("project host snapshot missing or stale");
        }
        Ok(Self {
            regions,
            project,
            providers,
        })
    }
    pub fn query(
        &self,
        current_host: &Snapshot,
        current_project: &Project,
        host_cursor: usize,
        operation: Operation,
        parameters: &BTreeMap<String, String>,
    ) -> Result<QueryResult> {
        if self.regions.host() != current_host || &self.project != current_project {
            return Err("stale query context");
        }
        let Some(region) = self.regions.at(host_cursor)? else {
            return Ok(QueryResult {
                region: String::new(),
                state: State::Unsupported,
                items: vec![],
            });
        };
        let unavailable = |state| QueryResult {
            region: region.id.clone(),
            state,
            items: vec![],
        };
        let Some(provider) = self.providers.get(&region.language) else {
            return Ok(unavailable(State::Unavailable));
        };
        if !provider.capabilities().contains(&operation) {
            return Ok(unavailable(State::Unsupported));
        }
        let original = Location::new(
            current_host.clone(),
            Span {
                start: host_cursor,
                end: host_cursor,
            },
        )?;
        let Some(cursor) = region.source_map.cursor(&original)? else {
            return Ok(unavailable(State::Unsupported));
        };
        let response = provider.query(&Request {
            region,
            operation,
            cursor,
            project: &self.project,
            parameters,
        })?;
        if &response.snapshot != region.source_map.output()
            || response.project != self.project.id
            || response.project_version != self.project.version
        {
            return Err("stale provider response");
        }
        if !matches!(response.state, State::Complete | State::Partial)
            && response.items.iter().any(|item| !item.edits.is_empty())
        {
            return Err("failed response contains edits");
        }
        let mut items = vec![];
        for item in response.items {
            let mut locations = vec![];
            for location in item.locations {
                location.snapshot.check(location.span)?;
                if &location.snapshot == region.source_map.output() {
                    locations.extend(region.source_map.diagnostics(location.span)?);
                } else {
                    self.check_known(&location.snapshot, region)?;
                    locations.push(Mapping {
                        location,
                        exact: true,
                    });
                }
            }
            let mut edits = vec![];
            for edit in item.edits {
                let mut location = edit.location;
                location.snapshot.check(location.span)?;
                if &location.snapshot == region.source_map.output() {
                    location = region.source_map.edit(location.span)?;
                } else {
                    self.check_known(&location.snapshot, region)?;
                }
                if &location.snapshot != current_host
                    || region.body.start > location.span.start
                    || location.span.end > region.body.end
                {
                    return Err("query edits must stay in the current host body");
                }
                edits.push(Edit {
                    span: location.span,
                    replacement: edit.replacement,
                });
            }
            validate_edits(&edits)?;
            items.push(MappedItem {
                label: item.label,
                detail: item.detail,
                locations,
                edits,
            });
        }
        Ok(QueryResult {
            region: region.id.clone(),
            state: response.state,
            items,
        })
    }
    fn check_known(&self, snapshot: &Snapshot, region: &Region) -> Result<()> {
        if snapshot == self.regions.host() {
            return Ok(());
        }
        if snapshot.uri == region.source_map.output().uri
            || self.project.documents.get(&snapshot.uri) != Some(snapshot)
        {
            return Err("unknown or stale result document");
        }
        Ok(())
    }
}
