//! Classic LSP stdio transport. Parsing remains in unlaxer-runtime.
//! Full document sync is explicit; backend calls execute serially on immutable snapshots.
use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::io::{self, BufRead, Read, Write};
use unlaxer_runtime::language_profile::{LanguageProfile, Selection};
use unlaxer_runtime::language_queries::{LanguageQueries, QueryView};
use unlaxer_runtime::source::{Operation, State};
use unlaxer_runtime::source::{Position, Snapshot};
use unlaxer_runtime::{SharedGrammar, Span};

const MAX_MESSAGE: usize = 4 * 1024 * 1024;

/// Snapshot-bound application hooks. All spans use Unicode code points.
pub trait Backend {
    fn grammar(&self) -> &str;
    fn entry(&self) -> &str;
    fn keywords(&self) -> &[String];
    fn validate(&mut self, snapshot: &Snapshot) -> Vec<Diagnostic>;
    /// Return typed/provider items for this exact immutable source and version.
    fn complete(&mut self, _snapshot: &Snapshot, _cursor: usize) -> Vec<Completion> {
        vec![]
    }
    /// Explicitly registered providers. Metadata cannot install one.
    fn query_capabilities(&self) -> HashSet<Operation> {
        HashSet::new()
    }
    /// Fresh immutable binding for the exact source. None with registration means unavailable.
    fn language_queries(&mut self, _snapshot: &Snapshot) -> Option<LanguageQueries> {
        None
    }
    /// Language-specific identifier extraction; no Rust approximation of Java Unicode categories.
    fn query_parameters(
        &self,
        _snapshot: &Snapshot,
        _cursor: usize,
        _operation: Operation,
    ) -> BTreeMap<String, String> {
        BTreeMap::new()
    }
}
#[derive(Clone, Debug)]
pub struct Diagnostic {
    pub span: Span,
    pub message: String,
    pub data: Value,
}
#[derive(Clone, Debug)]
pub struct Completion {
    pub snapshot: Snapshot,
    pub label: String,
    pub replacement: Option<(Span, String)>,
    pub detail: Option<String>,
    pub data: Value,
}
/// Generated grammar adapter. Extra semantic completion is supplied by implementing Backend.
pub struct GrammarBackend {
    pub name: String,
    pub entry: String,
    pub grammar: SharedGrammar,
    pub root: usize,
    pub whitespace: bool,
    pub keywords: Vec<String>,
}
impl Backend for GrammarBackend {
    fn grammar(&self) -> &str {
        &self.name
    }
    fn entry(&self) -> &str {
        &self.entry
    }
    fn keywords(&self) -> &[String] {
        &self.keywords
    }
    fn validate(&mut self, snapshot: &Snapshot) -> Vec<Diagnostic> {
        match unlaxer_runtime::parse_detailed_shared(
            &self.grammar,
            self.root,
            self.whitespace,
            &snapshot.text,
        ) {
            Ok(_) => vec![],
            Err(error) => {
                let mut start = error.offset.min(snapshot.len());
                if snapshot.lsp(start).is_err() {
                    start = start.saturating_sub(1);
                }
                let mut end = (start + 1).min(snapshot.len());
                // A CRLF is one line terminator; diagnostics cannot bisect it.
                if snapshot.lsp(end).is_err() {
                    end = (end + 1).min(snapshot.len());
                }
                vec![Diagnostic {
                    span: Span { start, end },
                    message: if error.expected.is_empty() {
                        format!(
                            "Parse error at offset {}",
                            snapshot.utf16(start).unwrap_or(0)
                        )
                    } else {
                        format!("Expected {}", error.expected.join(" or "))
                    },
                    data: json!({"schemaVersion":1,"code":"ULX-PARSE-001","kind":"syntax","offset":snapshot.utf16(start).unwrap_or(0),"totalLength":snapshot.utf16(snapshot.len()).unwrap_or(0),"expectedTokens":error.expected}),
                }]
            }
        }
    }
}

struct StagedLanguageDiagnostics {
    values: Vec<(Snapshot, Value)>,
    warnings: Vec<Value>,
}
struct DiagnosticDocument {
    snapshot: Snapshot,
    diagnostics: Vec<Value>,
}

pub struct Server<B> {
    backend: B,
    documents: HashMap<String, Snapshot>,
    diagnostic_contributions: BTreeMap<String, BTreeMap<String, DiagnosticDocument>>,
    profile: Option<Selection>,
    initialized: bool,
    shutdown: bool,
    exited: bool,
}
impl<B: Backend> Server<B> {
    pub fn new(backend: B) -> Self {
        Self {
            backend,
            documents: HashMap::new(),
            diagnostic_contributions: BTreeMap::new(),
            profile: None,
            initialized: false,
            shutdown: false,
            exited: false,
        }
    }
    pub fn snapshot(&self, uri: &str) -> Option<&Snapshot> {
        self.documents.get(uri)
    }
    fn allows(&self, capability: &str) -> bool {
        self.profile.as_ref().is_none_or(|p| {
            p.allows(
                capability,
                operation(capability)
                    .is_some_and(|op| self.backend.query_capabilities().contains(&op)),
            )
        })
    }
    /// Process one message; emitted notifications precede a request response.
    pub fn handle(&mut self, message: Value) -> Vec<Value> {
        let id = message.get("id").cloned();
        let request = id.is_some();
        if !message.is_object()
            || message.get("jsonrpc") != Some(&json!("2.0"))
            || !message.get("method").is_some_and(Value::is_string)
            || id
                .as_ref()
                .is_some_and(|v| !(v.is_string() || v.is_i64() || v.is_u64()))
        {
            return vec![failure(Value::Null, -32600, "Invalid Request")];
        }
        let method = message["method"].as_str().unwrap();
        let params = message.get("params").cloned().unwrap_or(Value::Null);
        let mut notifications = vec![];
        let result: Result<Value, (i64, String)> = (|| {
            if method == "exit" && !request {
                self.exited = true;
                return Ok(Value::Null);
            }
            if self.exited || self.shutdown {
                return Err((-32600, "Server has shut down".into()));
            }
            if method == "initialize" {
                let selection = self.select_profile(&params).map_err(invalid)?;
                self.profile = selection;
                self.initialized = true;
                let mut caps = json!({"positionEncoding":"utf-16","textDocumentSync":1});
                if self.allows("COMPLETION") {
                    caps["completionProvider"] = json!({"resolveProvider":false});
                }
                if self.allows("HOVER") {
                    caps["hoverProvider"] = json!(true);
                }
                if self.allows("DEFINITION")
                    && self
                        .backend
                        .query_capabilities()
                        .contains(&Operation::Definition)
                {
                    caps["definitionProvider"] = json!(true);
                }
                if let Some(p) = &self.profile {
                    let l = &p.language;
                    caps["experimental"] = json!({"languageProfile":{"schemaVersion":1,"tsv":p.profile.canonical_tsv(),"entry":l.entry,"grammar":l.grammar,"language":l.id,"package":l.package_id,"version":l.version}});
                }
                return Ok(
                    json!({"capabilities":caps,"serverInfo":{"name":"unlaxer-classic-rust"}}),
                );
            }
            if !self.initialized {
                return Err((-32002, "Server not initialized".into()));
            }
            match method {
                "initialized" | "$/cancelRequest" => Ok(Value::Null),
                "shutdown" if request => {
                    self.shutdown = true;
                    Ok(Value::Null)
                }
                "textDocument/didOpen" => {
                    let doc = &params["textDocument"];
                    let uri = string(doc, "uri")?;
                    let version = version(doc)?;
                    let text = string(doc, "text")?;
                    let snapshot = Snapshot::new(uri, version, text).map_err(invalid)?;
                    if self
                        .documents
                        .get(uri)
                        .is_some_and(|old| old.version >= version)
                    {
                        return Ok(Value::Null);
                    }
                    self.documents.insert(uri.into(), snapshot.clone());
                    self.diagnostics(&snapshot, &mut notifications);
                    Ok(Value::Null)
                }
                "textDocument/didChange" => {
                    let uri = string(&params["textDocument"], "uri")?;
                    let version = version(&params["textDocument"])?;
                    let old = self
                        .documents
                        .get(uri)
                        .ok_or_else(|| invalid("unopened document"))?;
                    if version <= old.version {
                        return Ok(Value::Null);
                    }
                    let changes = params["contentChanges"]
                        .as_array()
                        .filter(|a| !a.is_empty())
                        .ok_or_else(|| invalid("empty changes"))?;
                    // Full sync only: reject every ranged edit, never mistake it for the full source.
                    if changes
                        .iter()
                        .any(|c| !c.is_object() || !c["range"].is_null() || !c["text"].is_string())
                    {
                        return Err(invalid("full sync required"));
                    }
                    let text = string(changes.last().unwrap(), "text")?;
                    let snapshot = Snapshot::new(uri, version, text).map_err(invalid)?;
                    self.documents.insert(uri.into(), snapshot.clone());
                    self.diagnostics(&snapshot, &mut notifications);
                    Ok(Value::Null)
                }
                "textDocument/didClose" => {
                    let uri = string(&params["textDocument"], "uri")?;
                    self.documents.remove(uri);
                    let mut affected = std::collections::BTreeSet::from([uri.to_owned()]);
                    if let Some(previous) = self.diagnostic_contributions.remove(uri) {
                        affected.extend(previous.into_keys());
                    }
                    self.publish_diagnostic_contributions(affected, &mut notifications);
                    Ok(Value::Null)
                }
                "textDocument/didSave" => {
                    let uri = string(&params["textDocument"], "uri")?;
                    if let Some(snapshot) = self.documents.get(uri).cloned() {
                        self.diagnostics(&snapshot, &mut notifications);
                    }
                    Ok(Value::Null)
                }
                "textDocument/completion" => {
                    if !self.allows("COMPLETION") {
                        return Ok(json!([]));
                    }
                    let uri = string(&params["textDocument"], "uri")?;
                    let line = number(&params["position"], "line")?;
                    let character = number(&params["position"], "character")?;
                    let mut items = vec![];
                    let snapshot = self.documents.get(uri).cloned();
                    let cursor = snapshot.as_ref().map(|s| {
                        s.from_lsp(Position {
                            line: usize::try_from(line).unwrap_or(usize::MAX),
                            character: usize::try_from(character).unwrap_or(usize::MAX),
                        })
                    });
                    if matches!(cursor, Some(Err(_))) {
                        return Ok(json!([]));
                    }
                    if let (Some(host), Some(Ok(point))) = (&snapshot, &cursor) {
                        if let Some(view) = self.query(host, *point, Operation::Completion) {
                            return Ok(query_completions(host, view));
                        }
                    } else if !self.backend.query_capabilities().is_empty() {
                        return Ok(json!([]));
                    }
                    for keyword in self.backend.keywords() {
                        items.push(json!({"label":keyword,"kind":14}));
                    }
                    if let (Some(snapshot), Some(Ok(cursor))) = (snapshot, cursor) {
                        for item in self.backend.complete(&snapshot, cursor) {
                            if item.snapshot != snapshot {
                                continue;
                            }
                            let mut value = json!({"label":item.label,"kind":6,"data":item.data});
                            if let Some(detail) = item.detail {
                                value["detail"] = json!(detail);
                            }
                            if let Some((span, replacement)) = item.replacement {
                                let Ok(range) = range(&snapshot, span) else {
                                    continue;
                                };
                                value["textEdit"] = json!({"range":range,"newText":replacement});
                            }
                            items.push(value);
                        }
                    }
                    Ok(json!(items))
                }
                "textDocument/hover" | "textDocument/definition" => {
                    let op = if method.ends_with("hover") {
                        Operation::Hover
                    } else {
                        Operation::Definition
                    };
                    let empty = if op == Operation::Hover {
                        Value::Null
                    } else {
                        json!([])
                    };
                    if !self.allows(unlaxer_runtime::language_queries::operation_name(op)) {
                        return Ok(empty);
                    }
                    let uri = string(&params["textDocument"], "uri")?;
                    let Some(snapshot) = self.documents.get(uri).cloned() else {
                        return Ok(empty);
                    };
                    let line = number(&params["position"], "line")?;
                    let character = number(&params["position"], "character")?;
                    let Ok(cursor) = snapshot.from_lsp(Position {
                        line: usize::try_from(line).unwrap_or(usize::MAX),
                        character: usize::try_from(character).unwrap_or(usize::MAX),
                    }) else {
                        return Ok(empty);
                    };
                    if let Some(view) = self.query(&snapshot, cursor, op) {
                        let Some(view) = view else {
                            return Ok(empty);
                        };
                        if !matches!(view.result.state, State::Complete | State::Partial) {
                            return Ok(empty);
                        }
                        if op == Operation::Hover {
                            let text = view
                                .result
                                .items
                                .iter()
                                .map(|item| format!("{}: {}", item.label, item.detail))
                                .collect::<Vec<_>>()
                                .join("\n");
                            return Ok(if text.is_empty() {
                                Value::Null
                            } else {
                                json!({"contents":{"kind":"plaintext","value":text}})
                            });
                        }
                        let mut locations = vec![];
                        for item in view.result.items {
                            for mapping in item.locations {
                                if let Ok(range) =
                                    range(&mapping.location.snapshot, mapping.location.span)
                                {
                                    locations.push(
                                        json!({"uri":mapping.location.snapshot.uri,"range":range}),
                                    );
                                }
                            }
                        }
                        return Ok(json!(locations));
                    }
                    if op == Operation::Definition {
                        return Ok(empty);
                    }
                    let diagnostics = self.backend.validate(&snapshot);
                    let text = diagnostics.first().map_or_else(
                        || format!("Valid {}", self.backend.grammar()),
                        |diagnostic| diagnostic.message.clone(),
                    );
                    Ok(json!({"contents":{"kind":"plaintext","value":text}}))
                }
                _ if !request => Ok(Value::Null),
                _ => Err((-32601, "Method not found".into())),
            }
        })();
        if let Some(id) = id {
            notifications.push(match result {
                Ok(result) => json!({"jsonrpc":"2.0","id":id,"result":result}),
                Err((code, msg)) => failure(id, code, &msg),
            });
        }
        notifications
    }
    fn query(
        &mut self,
        snapshot: &Snapshot,
        cursor: usize,
        operation: Operation,
    ) -> Option<Option<QueryView>> {
        let queries = self.backend.language_queries(snapshot);
        let Some(queries) = queries else {
            return if self.backend.query_capabilities().is_empty() {
                None
            } else {
                Some(None)
            };
        };
        let parameters = self.backend.query_parameters(snapshot, cursor, operation);
        Some(
            queries
                .view(snapshot, queries.project(), cursor, operation, &parameters)
                .ok(),
        )
    }
    fn select_profile(&self, params: &Value) -> Result<Option<Selection>, String> {
        let value = &params["initializationOptions"]["languageProfile"];
        if params["initializationOptions"]
            .as_object()
            .is_none_or(|o| !o.contains_key("languageProfile"))
        {
            return Ok(None);
        }
        let object = value
            .as_object()
            .ok_or("invalid language profile options")?;
        if object.len() != 2 || !object.contains_key("tsv") || !object.contains_key("entry") {
            return Err("invalid language profile options".into());
        }
        let tsv = value["tsv"]
            .as_str()
            .ok_or("invalid language profile TSV")?;
        let entry = value["entry"]
            .as_str()
            .ok_or("invalid language profile entry")?;
        if entry != self.backend.entry() {
            return Err("profile entry differs from generated root".into());
        }
        Ok(Some(
            LanguageProfile::parse(tsv)?.select(self.backend.grammar(), entry)?,
        ))
    }
    fn diagnostics(&mut self, snapshot: &Snapshot, output: &mut Vec<Value>) {
        let mut diagnostics = vec![];
        if self.allows("VALIDATE") {
            for diagnostic in self.backend.validate(snapshot) {
                let Ok(range) = range(snapshot, diagnostic.span) else {
                    continue;
                };
                diagnostics.push(json!({"range":range,"severity":1,"code":"ULX-PARSE-001","source":"unlaxer","message":diagnostic.message,"data":diagnostic.data}));
            }
        }
        let mut staged = BTreeMap::from([(
            snapshot.uri.clone(),
            DiagnosticDocument {
                snapshot: snapshot.clone(),
                diagnostics: diagnostics.clone(),
            },
        )]);
        if self.allows("VALIDATE") {
            if let Some(queries) = self.backend.language_queries(snapshot) {
                let collect = || -> Result<StagedLanguageDiagnostics, &'static str> {
                    for source in queries.project().documents.values() {
                        if self
                            .documents
                            .get(&source.uri)
                            .is_some_and(|opened| opened != source)
                        {
                            return Err("stale project document");
                        }
                    }
                    let mut values = vec![];
                    let mut warnings = vec![];
                    for result in
                        queries.diagnostics_all(snapshot, queries.project(), &BTreeMap::new())?
                    {
                        if matches!(result.state, State::Timeout | State::Failed) {
                            warnings.push(notification("window/logMessage",json!({"type":2,"message":format!("Language diagnostics {} for {}",format!("{:?}",result.state).to_uppercase(),result.region)})));
                        }
                        for diagnostic in result.diagnostics {
                            let mut related = vec![];
                            for mapping in &diagnostic.locations {
                                let location = &mapping.location;
                                related.push(json!({"location":{"uri":location.snapshot.uri,"range":range(&location.snapshot,location.span)?},"message":diagnostic.message}));
                            }
                            for mapping in &diagnostic.locations {
                                let location = &mapping.location;
                                let severity = match diagnostic.severity.as_str() {
                                    "ERROR" => 1,
                                    "WARNING" | "MANDATORY_WARNING" => 2,
                                    "HINT" | "HELP" | "SUGGESTION" => 4,
                                    _ => 3,
                                };
                                let mut item = json!({"range":range(&location.snapshot,location.span)?,"severity":severity,"code":diagnostic.code,"source":"unlaxer-language","message":diagnostic.message,
                                    "data":{"region":result.region,"state":format!("{:?}",result.state).to_uppercase(),"exact":mapping.exact,"uri":location.snapshot.uri,"version":location.snapshot.version.to_string(),"severity":diagnostic.severity}});
                                if related.len() > 1 {
                                    item["relatedInformation"] = json!(related);
                                }
                                values.push((location.snapshot.clone(), item));
                            }
                        }
                    }
                    Ok(StagedLanguageDiagnostics { values, warnings })
                };
                match collect() {
                    Ok(StagedLanguageDiagnostics { values, warnings }) => {
                        output.extend(warnings);
                        for (source, item) in values {
                            let document = staged.entry(source.uri.clone()).or_insert_with(|| {
                                DiagnosticDocument {
                                    snapshot: source.clone(),
                                    diagnostics: vec![],
                                }
                            });
                            document.diagnostics.push(item);
                        }
                    }
                    Err(error) => {
                        staged = BTreeMap::from([(
                            snapshot.uri.clone(),
                            DiagnosticDocument {
                                snapshot: snapshot.clone(),
                                diagnostics,
                            },
                        )]);
                        output.push(notification("window/logMessage",json!({"type":2,"message":format!("Language diagnostics unavailable: {error}")})));
                    }
                }
            }
        }
        if self.documents.get(&snapshot.uri) != Some(snapshot) {
            return;
        }
        let mut affected: std::collections::BTreeSet<String> = staged.keys().cloned().collect();
        if let Some(previous) = self
            .diagnostic_contributions
            .insert(snapshot.uri.clone(), staged)
        {
            affected.extend(previous.into_keys());
        }
        self.publish_diagnostic_contributions(affected, output);
    }
    fn publish_diagnostic_contributions(
        &self,
        uris: std::collections::BTreeSet<String>,
        output: &mut Vec<Value>,
    ) {
        for uri in uris {
            let opened = self.documents.get(&uri);
            let mut expected = opened;
            let mut ambiguous = false;
            let mut diagnostics = vec![];
            for contributions in self.diagnostic_contributions.values() {
                let Some(contribution) = contributions.get(&uri) else {
                    continue;
                };
                if expected.is_none() {
                    expected = Some(&contribution.snapshot);
                }
                if expected != Some(&contribution.snapshot) {
                    if opened.is_none() {
                        ambiguous = true;
                    }
                    continue;
                }
                diagnostics.extend(contribution.diagnostics.iter().cloned());
            }
            if ambiguous {
                diagnostics.clear();
            }
            let mut params = json!({"uri":uri,"diagnostics":diagnostics});
            if !ambiguous {
                if let Some(snapshot) = expected {
                    if snapshot.version <= i32::MAX as u64 {
                        params["version"] = json!(snapshot.version);
                    }
                }
            }
            output.push(notification("textDocument/publishDiagnostics", params));
        }
    }
    /// Serve Content-Length frames until exit/EOF. Framing failures are I/O errors.
    pub fn serve<R: BufRead, W: Write>(&mut self, input: &mut R, output: &mut W) -> io::Result<()> {
        while !self.exited {
            let Some(bytes) = read_frame(input)? else {
                break;
            };
            let messages = match serde_json::from_slice(&bytes) {
                Ok(message) => self.handle(message),
                Err(_) => vec![failure(Value::Null, -32700, "Parse error")],
            };
            for message in messages {
                write_frame(output, &message)?;
            }
        }
        output.flush()
    }
    pub fn serve_stdio(&mut self) -> io::Result<()> {
        self.serve(&mut io::stdin().lock(), &mut io::stdout().lock())
    }
}
fn invalid(message: impl ToString) -> (i64, String) {
    (-32602, message.to_string())
}
fn string<'a>(value: &'a Value, key: &str) -> Result<&'a str, (i64, String)> {
    value[key]
        .as_str()
        .ok_or_else(|| invalid(format!("invalid {key}")))
}
fn number(value: &Value, key: &str) -> Result<u64, (i64, String)> {
    value[key]
        .as_u64()
        .ok_or_else(|| invalid(format!("invalid {key}")))
}
fn notification(method: &str, params: Value) -> Value {
    json!({"jsonrpc":"2.0","method":method,"params":params})
}
fn failure(id: Value, code: i64, message: &str) -> Value {
    json!({"jsonrpc":"2.0","id":id,"error":{"code":code,"message":message}})
}
fn range(snapshot: &Snapshot, span: Span) -> Result<Value, &'static str> {
    snapshot.check(span)?;
    let start = snapshot.lsp(span.start)?;
    let end = snapshot.lsp(span.end)?;
    Ok(
        json!({"start":{"line":start.line,"character":start.character},"end":{"line":end.line,"character":end.character}}),
    )
}
/// Strict ASCII CRLF headers, bounded byte length, UTF-8 JSON body.
pub fn read_frame<R: BufRead>(input: &mut R) -> io::Result<Option<Vec<u8>>> {
    let mut length = None;
    let mut headers = 0;
    let mut count = 0;
    loop {
        let mut bytes = Vec::new();
        let read = input
            .take((8192 - count + 1) as u64)
            .read_until(b'\n', &mut bytes)?;
        if read == 0 && count == 0 {
            return Ok(None);
        }
        count += read;
        if count > 8192 || read == 0 || !bytes.ends_with(b"\r\n") || !bytes.is_ascii() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "invalid LSP header",
            ));
        }
        if bytes == b"\r\n" {
            break;
        }
        headers += 1;
        if headers > 32 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "too many LSP headers",
            ));
        }
        let line = std::str::from_utf8(&bytes[..bytes.len() - 2]).unwrap();
        let (key, value) = line
            .split_once(':')
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid LSP header"))?;
        if key.eq_ignore_ascii_case("Content-Length") {
            let digits = value.trim();
            let parsed = if !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_digit()) {
                digits.parse::<usize>().ok().filter(|n| *n <= MAX_MESSAGE)
            } else {
                None
            };
            if length.is_some() || parsed.is_none() {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "invalid Content-Length",
                ));
            }
            length = parsed;
        }
        if key.eq_ignore_ascii_case("Content-Type")
            && value.contains("charset=")
            && !value.ends_with("charset=utf-8")
            && !value.ends_with("charset=utf8")
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "unsupported charset",
            ));
        }
    }
    let size = length
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "missing Content-Length"))?;
    let mut body = vec![0; size];
    input.read_exact(&mut body)?;
    Ok(Some(body))
}
pub fn write_frame<W: Write>(output: &mut W, value: &Value) -> io::Result<()> {
    let bytes = serde_json::to_vec(value).map_err(io::Error::other)?;
    write!(output, "Content-Length: {}\r\n\r\n", bytes.len())?;
    output.write_all(&bytes)?;
    output.flush()
}

fn operation(capability: &str) -> Option<Operation> {
    match capability {
        "COMPLETION" => Some(Operation::Completion),
        "HOVER" => Some(Operation::Hover),
        "DEFINITION" => Some(Operation::Definition),
        "VALIDATE" => Some(Operation::Validate),
        _ => None,
    }
}
fn query_completions(snapshot: &Snapshot, view: Option<QueryView>) -> Value {
    let Some(view) = view else {
        return json!([]);
    };
    if view.host != *snapshot || !matches!(view.result.state, State::Complete | State::Partial) {
        return json!([]);
    }
    let mut items = vec![];
    for item in view.result.items {
        let mut value = json!({"label":item.label,"kind":6,"detail":item.detail,"data":{"uri":snapshot.uri,"version":snapshot.version.to_string(),"region":view.result.region,"state":format!("{:?}",view.result.state).to_uppercase()}});
        let mut edits = vec![];
        let mut invalid = false;
        for edit in item.edits {
            match range(snapshot, edit.span) {
                Ok(range) => edits.push(json!({"range":range,"newText":edit.replacement})),
                Err(_) => {
                    invalid = true;
                    break;
                }
            }
        }
        if invalid {
            continue;
        }
        if edits.is_empty() {
            value["insertText"] = json!("");
        } else {
            value["textEdit"] = edits.remove(0);
            if !edits.is_empty() {
                value["additionalTextEdits"] = json!(edits);
            }
        }
        items.push(value);
    }
    json!(items)
}
/// Adapt a checked partial/complete typed editor result. The caller supplies the language's prefix span.
pub fn editor_completions<T>(
    snapshot: &Snapshot,
    cursor: usize,
    prefix: Span,
    result: &unlaxer_runtime::editor::EditorParseResult<T>,
) -> Vec<Completion> {
    if result.uri() != snapshot.uri
        || result.source() != snapshot.text
        || u64::try_from(result.version()).ok() != Some(snapshot.version)
        || prefix.end != cursor
    {
        return vec![];
    }
    let Ok(text) = snapshot.slice(prefix) else {
        return vec![];
    };
    let Ok(candidates) = result.complete_at(cursor, None, result.version(), text) else {
        return vec![];
    };
    candidates.into_iter().map(|candidate| Completion {
        snapshot:snapshot.clone(),label:candidate.symbol.name.clone(),replacement:Some((prefix,candidate.symbol.name)),
        detail:Some(format!("{} · {}",candidate.symbol.type_id,candidate.expected_types.join(", "))),
        data:json!({"status":result.status().name(),"version":snapshot.version,"expectedTypes":candidate.expected_types})
    }).collect()
}

fn version(document: &Value) -> Result<u64, (i64, String)> {
    number(document, "version").and_then(|value| {
        if value <= i32::MAX as u64 {
            Ok(value)
        } else {
            Err(invalid("invalid version"))
        }
    })
}
