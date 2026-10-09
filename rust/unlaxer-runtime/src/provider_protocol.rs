//! Version-one, bounded, UTF-8-hex framing for native analysis providers.
use crate::language_queries::{Item, Project, TextEdit};
use crate::source::{Language, Location, Operation, Result, Snapshot};
use crate::Span;
use std::collections::{BTreeMap, HashSet};
use std::fmt::Write;
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    Ok,
    Diagnostics,
    Unavailable,
    Unsupported,
    Timeout,
    Failed,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Identity {
    pub id: String,
    pub version: String,
}
pub struct Request<'a> {
    pub id: &'a str,
    pub provider: &'a Identity,
    pub language: &'a Language,
    pub region: &'a str,
    pub snapshot: &'a Snapshot,
    pub project: &'a Project,
    pub operation: Operation,
    pub cursor: usize,
    pub parameters: &'a BTreeMap<String, String>,
    pub execute_user_code: bool,
}
#[derive(Debug, Clone)]
pub struct Diagnostic {
    pub code: String,
    pub message: String,
    pub severity: String,
    pub locations: Vec<Location>,
}
#[derive(Debug)]
pub struct Response {
    pub status: Status,
    pub capabilities: HashSet<String>,
    pub diagnostics: Vec<Diagnostic>,
    pub items: Vec<Item>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub text: String,
    pub fingerprint: String,
}
pub fn hex(value: &str) -> String {
    let mut out = String::with_capacity(value.len() * 2);
    for byte in value.as_bytes() {
        write!(out, "{byte:02x}").unwrap();
    }
    out
}
fn unhex(value: &str) -> Result<String> {
    if value.len() % 2 != 0 || !value.is_ascii() {
        return Err("invalid hex field");
    }
    let bytes: std::result::Result<Vec<_>, _> = (0..value.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&value[i..i + 2], 16))
        .collect();
    String::from_utf8(bytes.map_err(|_| "invalid hex field")?).map_err(|_| "invalid UTF-8")
}
pub fn encode(request: &Request<'_>) -> Result<Frame> {
    if request.snapshot.version > i64::MAX as u64
        || request.project.version > i64::MAX as u64
        || request
            .project
            .documents
            .values()
            .any(|s| s.version > i64::MAX as u64)
    {
        return Err("protocol version exceeds shared integer range");
    }
    if request.region.is_empty()
        || [
            &request.language.id,
            &request.language.package_id,
            &request.language.version,
            &request.language.grammar,
            &request.language.entry,
        ]
        .iter()
        .any(|v| v.is_empty())
    {
        return Err("empty language identity");
    }
    request.snapshot.check(Span {
        start: request.cursor,
        end: request.cursor,
    })?;
    if request.id.is_empty()
        || request.provider.id.is_empty()
        || request.provider.version.is_empty()
        || request.project.id.is_empty()
    {
        return Err("empty provider identity");
    }
    if let Some(known) = request.project.documents.get(&request.snapshot.uri) {
        if known != request.snapshot {
            return Err("project snapshot collision");
        }
    }
    if request
        .project
        .documents
        .iter()
        .any(|(uri, snapshot)| uri != &snapshot.uri)
    {
        return Err("invalid project document key");
    }
    let operation = match request.operation {
        Operation::Parse => "PARSE",
        Operation::Validate => "VALIDATE",
        Operation::Completion => "COMPLETION",
        Operation::Hover => "HOVER",
        Operation::Definition => "DEFINITION",
        Operation::Rename => "RENAME",
        Operation::Format => "FORMAT",
        Operation::CodeAction => "CODE_ACTION",
    };
    let mut out = format!(
        "UNLAXER-PROVIDER\t1\nrequest\t{}\t{}\t{}\t{operation}\t{}\t{}\nproject\t{}\t{}\n",
        hex(request.id),
        hex(&request.provider.id),
        hex(&request.provider.version),
        request.cursor,
        request.execute_user_code,
        hex(&request.project.id),
        request.project.version
    );
    writeln!(
        out,
        "language\t{}\t{}\t{}\t{}\t{}\t{}",
        hex(&request.language.id),
        hex(&request.language.package_id),
        hex(&request.language.version),
        hex(&request.language.grammar),
        hex(&request.language.entry),
        hex(request.region)
    )
    .unwrap();
    document(&mut out, "snapshot", request.snapshot);
    for snapshot in request.project.documents.values() {
        document(&mut out, "document", snapshot);
    }
    for (key, value) in &request.project.configuration {
        writeln!(out, "config\t{}\t{}", hex(key), hex(value)).unwrap();
    }
    for (key, value) in request.parameters {
        writeln!(out, "parameter\t{}\t{}", hex(key), hex(value)).unwrap();
    }
    if out.len() > 512 * 1024 {
        return Err("provider request exceeds 512 KiB");
    }
    // Exact request echo avoids a new crypto dependency or a collision-prone cache hash.
    let fingerprint = hex(&out);
    out.push_str("end\n");
    Ok(Frame {
        text: out,
        fingerprint,
    })
}
fn document(out: &mut String, kind: &str, snapshot: &Snapshot) {
    writeln!(
        out,
        "{kind}\t{}\t{}\t{}",
        hex(&snapshot.uri),
        snapshot.version,
        hex(&snapshot.text)
    )
    .unwrap();
}
fn fields(line: &str, count: usize) -> Result<Vec<&str>> {
    let result: Vec<_> = line.split('\t').collect();
    if result.len() != count {
        return Err("wrong field count");
    }
    Ok(result)
}
fn number(value: &str) -> Result<u64> {
    value.parse().map_err(|_| "invalid integer")
}
fn index(value: &str) -> Result<usize> {
    value.parse().map_err(|_| "invalid index")
}
fn location(request: &Request<'_>, row: &[&str], offset: usize) -> Result<Location> {
    let uri = unhex(row[offset])?;
    let version = number(row[offset + 1])?;
    let snapshot = if uri == request.snapshot.uri {
        request.snapshot
    } else {
        request
            .project
            .documents
            .get(&uri)
            .ok_or("unknown diagnostic document")?
    };
    if snapshot.version != version {
        return Err("stale diagnostic document");
    }
    Location::new(
        snapshot.clone(),
        Span {
            start: index(row[offset + 2])?,
            end: index(row[offset + 3])?,
        },
    )
}
pub fn decode(request: &Request<'_>, frame: &Frame, wire: &str) -> Result<Response> {
    if *frame != encode(request)? {
        return Err("stale request frame");
    }
    if wire.len() > 4 * 1024 * 1024 || !wire.is_ascii() || !wire.ends_with('\n') {
        return Err("invalid response framing");
    }
    let lines: Vec<_> = wire.split('\n').collect();
    if lines.len() < 4 || lines[0] != "UNLAXER-PROVIDER\t1" || lines[lines.len() - 2] != "end" {
        return Err("invalid protocol/version");
    }
    let header = fields(lines[1], 8)?;
    if header[0] != "response"
        || unhex(header[1])? != request.id
        || unhex(header[2])? != request.provider.id
        || unhex(header[3])? != request.provider.version
        || header[4] != frame.fingerprint
        || unhex(header[5])? != request.project.id
        || number(header[6])? != request.project.version
    {
        return Err("stale or wrong provider response");
    }
    let status = match header[7] {
        "OK" => Status::Ok,
        "DIAGNOSTICS" => Status::Diagnostics,
        "UNAVAILABLE" => Status::Unavailable,
        "UNSUPPORTED" => Status::Unsupported,
        "TIMEOUT" => Status::Timeout,
        "FAILED" => Status::Failed,
        _ => return Err("unknown response status"),
    };
    let mut result = Response {
        status,
        capabilities: HashSet::new(),
        diagnostics: vec![],
        items: vec![],
    };
    for line in &lines[2..lines.len() - 2] {
        let kind = line.split('\t').next().unwrap();
        match kind {
            "capability" => {
                let row = fields(line, 2)?;
                if !result.capabilities.insert(row[1].into()) {
                    return Err("duplicate capability");
                }
            }
            "diagnostic" => {
                let row = fields(line, 8)?;
                result.diagnostics.push(Diagnostic {
                    code: unhex(row[1])?,
                    message: unhex(row[2])?,
                    severity: row[3].into(),
                    locations: vec![location(request, &row, 4)?],
                });
            }
            "origin" => {
                let row = fields(line, 6)?;
                let diagnostic = result
                    .diagnostics
                    .get_mut(index(row[1])?)
                    .ok_or("unknown diagnostic")?;
                diagnostic.locations.push(location(request, &row, 2)?);
            }
            "item" => {
                let row = fields(line, 3)?;
                result.items.push(Item {
                    label: unhex(row[1])?,
                    detail: unhex(row[2])?,
                    locations: vec![],
                    edits: vec![],
                });
            }
            "location" => {
                let row = fields(line, 6)?;
                let item = result.items.get_mut(index(row[1])?).ok_or("unknown item")?;
                item.locations.push(location(request, &row, 2)?);
            }
            "edit" => {
                let row = fields(line, 7)?;
                let item = result.items.get_mut(index(row[1])?).ok_or("unknown item")?;
                item.edits.push(TextEdit {
                    location: location(request, &row, 2)?,
                    replacement: unhex(row[6])?,
                });
            }
            _ => return Err("unknown response field"),
        }
    }
    if (status == Status::Ok && !result.diagnostics.is_empty())
        || (status == Status::Diagnostics && result.diagnostics.is_empty())
        || (!matches!(status, Status::Ok | Status::Diagnostics)
            && (!result.diagnostics.is_empty() || !result.items.is_empty()))
    {
        return Err("response status disagrees with results");
    }
    Ok(result)
}

#[derive(Debug, Clone)]
pub struct OwnedRequest {
    pub id: String,
    pub provider: Identity,
    pub language: Language,
    pub region: String,
    pub snapshot: Snapshot,
    pub project: Project,
    pub operation: Operation,
    pub cursor: usize,
    pub parameters: BTreeMap<String, String>,
    pub execute_user_code: bool,
}
impl OwnedRequest {
    pub fn as_request(&self) -> Request<'_> {
        Request {
            id: &self.id,
            provider: &self.provider,
            language: &self.language,
            region: &self.region,
            snapshot: &self.snapshot,
            project: &self.project,
            operation: self.operation,
            cursor: self.cursor,
            parameters: &self.parameters,
            execute_user_code: self.execute_user_code,
        }
    }
}
pub fn read_request(wire: &str) -> Result<OwnedRequest> {
    if wire.len() > 512 * 1024 + 4 || !wire.is_ascii() || !wire.ends_with("end\n") {
        return Err("invalid request frame");
    }
    let lines: Vec<_> = wire.split('\n').collect();
    if lines.len() < 7 || lines[0] != "UNLAXER-PROVIDER\t1" || lines[lines.len() - 2] != "end" {
        return Err("invalid protocol/version");
    }
    let header = fields(lines[1], 7)?;
    let project = fields(lines[2], 3)?;
    let language = fields(lines[3], 7)?;
    let snapshot = fields(lines[4], 4)?;
    if header[0] != "request"
        || project[0] != "project"
        || language[0] != "language"
        || snapshot[0] != "snapshot"
        || !["true", "false"].contains(&header[6])
    {
        return Err("invalid request header");
    }
    let mut result = OwnedRequest {
        id: unhex(header[1])?,
        provider: Identity {
            id: unhex(header[2])?,
            version: unhex(header[3])?,
        },
        language: Language {
            id: unhex(language[1])?,
            package_id: unhex(language[2])?,
            version: unhex(language[3])?,
            grammar: unhex(language[4])?,
            entry: unhex(language[5])?,
        },
        region: unhex(language[6])?,
        snapshot: Snapshot::new(
            unhex(snapshot[1])?,
            number(snapshot[2])?,
            unhex(snapshot[3])?,
        )?,
        project: Project {
            id: unhex(project[1])?,
            version: number(project[2])?,
            documents: BTreeMap::new(),
            configuration: BTreeMap::new(),
        },
        operation: match header[4] {
            "PARSE" => Operation::Parse,
            "VALIDATE" => Operation::Validate,
            "COMPLETION" => Operation::Completion,
            "HOVER" => Operation::Hover,
            "DEFINITION" => Operation::Definition,
            "RENAME" => Operation::Rename,
            "FORMAT" => Operation::Format,
            "CODE_ACTION" => Operation::CodeAction,
            _ => return Err("unknown operation"),
        },
        cursor: index(header[5])?,
        parameters: BTreeMap::new(),
        execute_user_code: header[6] == "true",
    };
    for line in &lines[5..lines.len() - 2] {
        let kind = line.split('\t').next().unwrap();
        match kind {
            "document" => {
                let row = fields(line, 4)?;
                let document = Snapshot::new(unhex(row[1])?, number(row[2])?, unhex(row[3])?)?;
                if result
                    .project
                    .documents
                    .insert(document.uri.clone(), document)
                    .is_some()
                {
                    return Err("duplicate document");
                }
            }
            "config" | "parameter" => {
                let row = fields(line, 3)?;
                let target = if kind == "config" {
                    &mut result.project.configuration
                } else {
                    &mut result.parameters
                };
                if target.insert(unhex(row[1])?, unhex(row[2])?).is_some() {
                    return Err("duplicate configuration");
                }
            }
            _ => return Err("unknown request field"),
        }
    }
    encode(&result.as_request())?;
    Ok(result)
}

#[derive(Debug, Clone)]
pub struct MappedDiagnostic {
    pub code: String,
    pub message: String,
    pub severity: String,
    pub locations: Vec<crate::source::Mapping>,
}
pub fn map_diagnostics(
    response: &Response,
    source_map: &crate::source::SourceMap,
) -> Result<Vec<MappedDiagnostic>> {
    response
        .diagnostics
        .iter()
        .map(|diagnostic| {
            let mut locations = vec![];
            for location in &diagnostic.locations {
                if &location.snapshot == source_map.output() {
                    locations.extend(source_map.diagnostics(location.span)?);
                } else {
                    if location.snapshot.uri == source_map.output().uri {
                        return Err("stale diagnostic snapshot");
                    }
                    locations.push(crate::source::Mapping {
                        location: location.clone(),
                        exact: true,
                    });
                }
            }
            Ok(MappedDiagnostic {
                code: diagnostic.code.clone(),
                message: diagnostic.message.clone(),
                severity: diagnostic.severity.clone(),
                locations,
            })
        })
        .collect()
}
