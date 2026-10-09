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
    fn diagnostics(&self, request: &Request<'_>) -> Result<DiagnosticResponse> {
        Ok(DiagnosticResponse {
            snapshot: request.region.source_map.output().clone(),
            project: request.project.id.clone(),
            project_version: request.project.version,
            state: State::Unsupported,
            diagnostics: vec![],
        })
    }
}
pub struct DiagnosticResponse {
    pub snapshot: Snapshot,
    pub project: String,
    pub project_version: u64,
    pub state: State,
    pub diagnostics: Vec<crate::provider_protocol::Diagnostic>,
}
pub struct DiagnosticResult {
    pub region: String,
    pub state: State,
    pub diagnostics: Vec<crate::provider_protocol::MappedDiagnostic>,
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
    pub fn host(&self) -> &Snapshot {
        self.regions.host()
    }
    pub fn project(&self) -> &Project {
        &self.project
    }
    pub fn view(
        &self,
        host: &Snapshot,
        project: &Project,
        cursor: usize,
        operation: Operation,
        parameters: &BTreeMap<String, String>,
    ) -> Result<QueryView> {
        let result = self.query(host, project, cursor, operation, parameters)?;
        let capabilities = self
            .regions
            .at(cursor)?
            .and_then(|region| self.providers.get(&region.language))
            .map_or_else(HashSet::new, |provider| provider.capabilities());
        Ok(QueryView {
            host: host.clone(),
            operation,
            cursor,
            capabilities,
            result,
        })
    }
    pub fn diagnostics_all(
        &self,
        host: &Snapshot,
        project: &Project,
        parameters: &BTreeMap<String, String>,
    ) -> Result<Vec<DiagnosticResult>> {
        if self.regions.host() != host || &self.project != project {
            return Err("stale diagnostic context");
        }
        let mut results = vec![];
        for region in self.regions.regions() {
            let Some(provider) = self.providers.get(&region.language) else {
                results.push(DiagnosticResult {
                    region: region.id.clone(),
                    state: State::Unavailable,
                    diagnostics: vec![],
                });
                continue;
            };
            if !provider.capabilities().contains(&Operation::Validate) {
                results.push(DiagnosticResult {
                    region: region.id.clone(),
                    state: State::Unsupported,
                    diagnostics: vec![],
                });
                continue;
            }
            let response = provider.diagnostics(&Request {
                region,
                operation: Operation::Validate,
                cursor: 0,
                project: &self.project,
                parameters,
            })?;
            if &response.snapshot != region.source_map.output()
                || response.project != project.id
                || response.project_version != project.version
            {
                return Err("stale diagnostic response");
            }
            if !matches!(response.state, State::Complete | State::Partial)
                && !response.diagnostics.is_empty()
            {
                return Err("failed response contains diagnostics");
            }
            let mut diagnostics = vec![];
            for diagnostic in response.diagnostics {
                if !matches!(
                    diagnostic.severity.as_str(),
                    "ERROR"
                        | "WARNING"
                        | "MANDATORY_WARNING"
                        | "INFORMATION"
                        | "HINT"
                        | "NOTE"
                        | "OTHER"
                        | "SUGGESTION"
                        | "MESSAGE"
                        | "HELP"
                        | "FAILURE_NOTE"
                ) {
                    return Err("unknown diagnostic severity");
                }
                let mut locations = vec![];
                for location in diagnostic.locations {
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
                diagnostics.push(crate::provider_protocol::MappedDiagnostic {
                    code: diagnostic.code,
                    message: diagnostic.message,
                    severity: diagnostic.severity,
                    locations,
                });
            }
            results.push(DiagnosticResult {
                region: region.id.clone(),
                state: response.state,
                diagnostics,
            });
        }
        Ok(results)
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

/// Snapshot-bound consumer envelope; all edits use original host code-point offsets.
pub struct QueryView {
    pub host: Snapshot,
    pub operation: Operation,
    pub cursor: usize,
    pub capabilities: HashSet<Operation>,
    pub result: QueryResult,
}
pub fn operation_name(operation: Operation) -> &'static str {
    match operation {
        Operation::Parse => "PARSE",
        Operation::Validate => "VALIDATE",
        Operation::Completion => "COMPLETION",
        Operation::Hover => "HOVER",
        Operation::Definition => "DEFINITION",
        Operation::Rename => "RENAME",
        Operation::Format => "FORMAT",
        Operation::CodeAction => "CODE_ACTION",
    }
}
impl QueryView {
    pub fn canonical_json(&self) -> String {
        use crate::json_string as q;
        let items = self
            .result
            .items
            .iter()
            .map(|item| {
                let locations = item
                    .locations
                    .iter()
                    .map(|mapping| {
                        let location = &mapping.location;
                        format!(
                            "{{\"uri\":{},\"version\":{},\"span\":[{},{}],\"exact\":{}}}",
                            q(&location.snapshot.uri),
                            q(&location.snapshot.version.to_string()),
                            location.span.start,
                            location.span.end,
                            mapping.exact
                        )
                    })
                    .collect::<Vec<_>>()
                    .join(",");
                let edits = item
                    .edits
                    .iter()
                    .map(|edit| {
                        format!(
                            "{{\"span\":[{},{}],\"replacement\":{}}}",
                            edit.span.start,
                            edit.span.end,
                            q(&edit.replacement)
                        )
                    })
                    .collect::<Vec<_>>()
                    .join(",");
                format!(
                    "{{\"label\":{},\"detail\":{},\"locations\":[{}],\"edits\":[{}]}}",
                    q(&item.label),
                    q(&item.detail),
                    locations,
                    edits
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        let mut capabilities: Vec<_> = self
            .capabilities
            .iter()
            .map(|op| operation_name(*op))
            .collect();
        capabilities.sort();
        format!("{{\"uri\":{},\"version\":{},\"operation\":{},\"cursor\":{},\"region\":{},\"state\":{},\"capabilities\":[{}],\"items\":[{}]}}",
            q(&self.host.uri), q(&self.host.version.to_string()), q(operation_name(self.operation)), self.cursor, q(&self.result.region),
            q(&format!("{:?}", self.result.state).to_uppercase()), capabilities.iter().map(|name|q(name)).collect::<Vec<_>>().join(","), items)
    }
}
