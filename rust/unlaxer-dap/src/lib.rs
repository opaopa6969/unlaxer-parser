//! Typed AST DAP inspection; evaluator execution stays in explicit application hooks.
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::io::{self, BufRead, Write};
use unlaxer_runtime::{source::Snapshot, Span};

mod arguments;

#[derive(Clone, Debug)]
pub struct Step {
    pub label: String,
    /// CP range in the original snapshot (logical extent for projected tokens).
    /// Generated tokens have a zero-width anchor.
    pub span: Span,
    /// Some (including an empty string) owns generated text at a zero-width anchor.
    pub generated_text: Option<String>,
    /// Projected terminal text, independent of the input slice at its logical CP span.
    /// Both text fields are None for input/AST steps and cannot both be Some.
    pub projected_text: Option<String>,
}

enum RequestError {
    Message(String),
    Internal(String),
    Parse(String),
}
impl RequestError {
    fn unavailable() -> Self {
        Self::failed_future(
            "IllegalArgumentException",
            "invalid or unavailable debug request",
        )
    }
    fn failed_future(kind: &str, message: &str) -> Self {
        Self::Internal(format!(
            "java.util.concurrent.CompletionException: java.lang.{kind}: {message}"
        ))
    }
    fn position(message: &str) -> Self {
        Self::Internal(format!("java.lang.IllegalArgumentException: {message}"))
    }
}
impl From<String> for RequestError {
    fn from(message: String) -> Self {
        Self::Message(message)
    }
}
impl From<&str> for RequestError {
    fn from(message: &str) -> Self {
        Self::Message(message.into())
    }
}
#[derive(Clone, Debug)]
pub struct DebugSource {
    pub text: String,
    pub line_offset: usize,
}
pub trait Backend {
    /// Advertise only a real bounded parser-terminal trace, never CST leaves.
    fn supports_token_trace(&self) -> bool {
        false
    }
    /// Return strict mapped AST nodes, or a complete committed token trace when
    /// supports_token_trace() is true and token mode is requested. No partial results.
    /// Use parse_error() for syntax failures; other errors describe unavailable stepping.
    fn steps(&mut self, snapshot: &Snapshot, arguments: &Value) -> Result<Vec<Step>, String>;
    fn resolve_source(
        &mut self,
        _program: &str,
        original: &str,
        _arguments: &Value,
    ) -> Result<DebugSource, String> {
        Ok(DebugSource {
            text: original.into(),
            line_offset: 0,
        })
    }
    fn runtime_variables(
        &mut self,
        _snapshot: &Snapshot,
        _mode: &str,
        _arguments: &Value,
    ) -> Result<BTreeMap<String, String>, String> {
        Ok(BTreeMap::new())
    }
    fn before_step(&mut self, _index: usize, _label: &str) {}
    fn after_step(&mut self, _index: usize, _label: &str) {}
}
/// Generated strict mapper adapter. Implement Backend to add application evaluation/source hooks.
pub struct AstBackend {
    pub mapper: fn(&str) -> Result<Vec<Step>, String>,
}
impl Backend for AstBackend {
    fn steps(&mut self, snapshot: &Snapshot, _: &Value) -> Result<Vec<Step>, String> {
        (self.mapper)(&snapshot.text)
    }
}
/// Generated AST mapping plus committed parser-terminal trace.
pub struct ParserBackend {
    pub mapper: fn(&str) -> Result<Vec<Step>, String>,
    pub tokens: fn(&str) -> Result<Vec<Step>, String>,
}
impl Backend for ParserBackend {
    fn supports_token_trace(&self) -> bool {
        true
    }
    fn steps(&mut self, snapshot: &Snapshot, arguments: &Value) -> Result<Vec<Step>, String> {
        if stepping_mode(arguments).eq_ignore_ascii_case("ast") {
            (self.mapper)(&snapshot.text)
        } else {
            (self.tokens)(&snapshot.text)
        }
    }
}
pub fn stepping_mode(arguments: &Value) -> &str {
    arguments["steppingMode"].as_str().unwrap_or_else(|| {
        let runtime = arguments["runtimeMode"]
            .as_str()
            .unwrap_or("token")
            .trim()
            .to_lowercase()
            .replace('_', "-");
        if runtime == "ast" || runtime.ends_with("-ast") || runtime.contains("ast-evaluator") {
            "ast"
        } else {
            "token"
        }
    })
}
/// Converts a complete committed trace, retaining generated/projected text independently of
/// its anchor or logical CP range. No input slice is substituted for owned text.
pub fn token_steps(tree: &unlaxer_runtime::Tree) -> Result<Vec<Step>, String> {
    let trace = tree
        .token_trace()
        .ok_or("token trace unavailable")?
        .map_err(|error| error.to_string())?;
    let mut steps = Vec::new();
    for leaf in trace.iter().filter(|leaf| leaf.kind.is_consumed()) {
        let (generated_text, projected_text) = match &leaf.source {
            unlaxer_runtime::token::TokenSource::Input(_) => (None, None),
            unlaxer_runtime::token::TokenSource::Generated { text, .. } => {
                (Some(text.clone()), None)
            }
            unlaxer_runtime::token::TokenSource::Projected { text, .. } => {
                (None, Some(text.to_string()))
            }
        };
        steps.push(Step {
            label: leaf.label.into(),
            span: leaf.span(),
            generated_text,
            projected_text,
        });
    }
    Ok(steps)
}
/// Format the generated Java DAP's parse failure for Backend::steps.
/// A rejected root reports zero; a successful prefix reports its UTF-16 length.
/// Pass the structured diagnostic (not its farthest failure, which loses trailing_input).
pub fn parse_error(source: &str, diagnostic: &unlaxer_runtime::ParseDiagnostic) -> String {
    if diagnostic.kind == "resource_limit" {
        return format!("{:?}", diagnostic.farthest);
    }
    let offset = if diagnostic.kind == "trailing_input" {
        source
            .chars()
            .take(diagnostic.offset)
            .map(char::len_utf16)
            .sum()
    } else {
        0
    };
    format!("Parse error at offset {offset}")
}

pub struct Server<B> {
    backend: B,
    seq: i64,
    configuration_pending: bool,
    terminated: bool,
    disconnected: bool,
    program: String,
    arguments: Value,
    runtime_mode: String,
    stepping_mode: String,
    ast_mode: bool,
    stop_on_entry: bool,
    snapshot: Option<Snapshot>,
    line_offset: usize,
    steps: Vec<Step>,
    index: usize,
    // Signed like Java's Set<Integer>: an unmatched line still makes the set non-empty.
    breakpoints: BTreeSet<i64>,
    // Java keeps these insertion-ordered probes until the next successful preparation.
    variables: Vec<(String, String)>,
    line_base: usize,
    column_base: usize,
}
impl<B: Backend> Server<B> {
    pub fn new(backend: B) -> Self {
        Self {
            backend,
            seq: 0,
            configuration_pending: false,
            terminated: false,
            disconnected: false,
            program: String::new(),
            arguments: Value::Null,
            runtime_mode: "token".into(),
            stepping_mode: "token".into(),
            ast_mode: false,
            stop_on_entry: false,
            snapshot: None,
            line_offset: 0,
            steps: vec![],
            index: 0,
            breakpoints: BTreeSet::new(),
            variables: vec![],
            line_base: 1,
            column_base: 1,
        }
    }
    /// Process one DAP request, with strictly increasing adapter seq on every output message.
    pub fn handle(&mut self, message: Value) -> Vec<Value> {
        let raw = message.to_string();
        self.handle_frame(message, &raw)
    }

    fn handle_frame(&mut self, message: Value, raw: &str) -> Vec<Value> {
        let request_seq = message["seq"]
            .as_i64()
            .filter(|seq| *seq > 0 && *seq <= i32::MAX as i64)
            .unwrap_or(0);
        let command = message["command"].as_str().unwrap_or("").to_owned();
        let arguments = message.get("arguments").cloned().unwrap_or(Value::Null);
        let mut events = vec![];
        let result: Result<Value, RequestError> = (|| {
            if request_seq == 0 || message["type"] != "request" || command.is_empty() {
                return Err("invalid request".into());
            }
            // LSP4J converts the entire argument DTO before invoking the adapter.
            // In particular, a rejected conversion must not mutate session state.
            let arguments::Converted {
                value: arguments,
                launch: strings,
            } = arguments::read(&command, arguments, raw)?;
            if self.disconnected {
                return Err("disconnected".into());
            }
            if command == "initialize" {
                self.line_base = usize::from(arguments["linesStartAt1"] != false);
                self.column_base = usize::from(arguments["columnsStartAt1"] != false);
                return Ok(json!({"supportsConfigurationDoneRequest":true}));
            }
            // Generated Java adapters accept requests before initialize; each request
            // validates its own arguments and lifecycle state.
            match command.as_str() {
                "launch" => {
                    let program = ["program", "formulaSource"]
                        .into_iter()
                        .filter_map(|key| strings[key].as_str())
                        .find(|s| !s.trim().is_empty())
                        .ok_or_else(|| {
                            RequestError::failed_future(
                                "IllegalArgumentException",
                                "invalid program or stepping mode",
                            )
                        })?;
                    let runtime = strings["runtimeMode"].as_str().unwrap_or("token");
                    let mode = stepping_mode(&strings);
                    if !mode.eq_ignore_ascii_case("ast") && !mode.eq_ignore_ascii_case("token") {
                        return Err(RequestError::failed_future(
                            "IllegalArgumentException",
                            "invalid program or stepping mode",
                        ));
                    }
                    if mode.eq_ignore_ascii_case("token") && !self.backend.supports_token_trace() {
                        return Err("backend does not provide parser token tracing".into());
                    }
                    if normalized_debug_path(program) != normalized_debug_path(&self.program) {
                        self.breakpoints.clear();
                    }
                    self.ast_mode = mode.eq_ignore_ascii_case("ast");
                    self.stepping_mode = mode.into();
                    self.program = program.into();
                    self.runtime_mode = runtime.into();
                    self.stop_on_entry = arguments["stopOnEntry"] == true;
                    self.arguments = arguments.clone();
                    self.configuration_pending = true;
                    self.terminated = false;
                    self.snapshot = None;
                    self.steps.clear();
                    // launch resets steps, but Java retains the last prepared probes/offset.
                    self.index = 0;
                    events.push(("initialized", Value::Null));
                    Ok(Value::Null)
                }
                "setBreakpoints" => {
                    if arguments.is_null() {
                        return Err(RequestError::Internal(
                            "java.lang.NullPointerException: missing setBreakpoints arguments"
                                .into(),
                        ));
                    }
                    let target = normalized_debug_path(&self.program);
                    let requested_path = match arguments.get("source") {
                        None | Some(Value::Null) => target.clone(),
                        Some(source) => source["path"].as_str().and_then(normalized_debug_path),
                    };
                    let same_source = target.is_some() && target == requested_path;
                    let empty = vec![];
                    let requested = match arguments.get("breakpoints") {
                        None | Some(Value::Null) => &empty,
                        Some(Value::Array(values)) => values,
                        _ => return Err("invalid breakpoints".into()),
                    };
                    let mut lines = BTreeSet::new();
                    let mut results = vec![];
                    for breakpoint in requested {
                        if breakpoint.is_null() {
                            return Err(RequestError::Internal(
                                "java.lang.NullPointerException: null breakpoint".into(),
                            ));
                        }
                        // LSP4J accepts signed lines and defaults a missing primitive to 0.
                        // Negative lines never match a position, but still enable the breakpoint
                        // search in configurationDone, as in Java.
                        let line = match breakpoint.get("line") {
                            None | Some(Value::Null) => 0,
                            Some(line) => line
                                .as_i64()
                                .filter(|line| i32::try_from(*line).is_ok())
                                .ok_or("invalid breakpoint line")?,
                        };
                        lines.insert(line);
                        let mut result = json!({"verified":same_source,"line":line});
                        if !same_source {
                            result["message"] = json!("source does not match launch program");
                        }
                        results.push(result);
                    }
                    if same_source {
                        self.breakpoints = lines;
                    }
                    Ok(json!({"breakpoints":results}))
                }
                "configurationDone" => {
                    if !self.configuration_pending {
                        return Err(RequestError::failed_future(
                            "IllegalStateException",
                            "configurationDone requires a new launch",
                        ));
                    }
                    self.configuration_pending = false;
                    match self.prepare() {
                        Ok(()) => {
                            if self.stop_on_entry {
                                self.index = 0;
                                events.push(stopped("entry"));
                            } else if let Some(index) = if self.breakpoints.is_empty() {
                                None
                            } else {
                                self.breakpoint_after(None)?
                            } {
                                self.index = index;
                                events.push(stopped("breakpoint"));
                            } else {
                                self.finish(&mut events, "Parsed successfully");
                            }
                        }
                        Err(error) => {
                            events.push(("output", json!({"category":"stderr","output":error})));
                            self.finish_events(&mut events);
                        }
                    }
                    Ok(Value::Null)
                }
                "threads" => Ok(json!({"threads":[{"id":1,"name":"main"}]})),
                "stackTrace" => {
                    self.check_thread(&arguments)?;
                    let frames = if self.current().is_some() {
                        let step = self.current().unwrap();
                        let snapshot = self.snapshot.as_ref().unwrap();
                        let position = snapshot
                            .lsp(step.span.start)
                            .map_err(RequestError::position)?;
                        let end = snapshot
                            .lsp(step.span.end)
                            .map_err(RequestError::position)?;
                        vec![
                            json!({"id":0,"name":format!("{} ({}/{})",step.label,self.index+1,self.steps.len()),"line":position.line+self.line_offset+self.line_base,"column":position.character+self.column_base,"endLine":end.line+self.line_offset+self.line_base,"endColumn":end.character+self.column_base,"source":{"path":self.program,"name":std::path::Path::new(&self.program).file_name().unwrap_or_default().to_string_lossy()}}),
                        ]
                    } else if !self.ast_mode && !self.terminated && !self.program.is_empty() {
                        vec![
                            json!({"id":0,"name":"<program>","source":{"path":self.program},
                            "line":self.line_offset+self.line_base,"column":self.column_base}),
                        ]
                    } else {
                        vec![]
                    };
                    Ok(json!({"stackFrames":frames}))
                }
                "scopes" => {
                    if !arguments.is_object()
                        || arguments
                            .get("frameId")
                            .is_some_and(|id| id != 0 && !id.is_null())
                    {
                        return Err(RequestError::unavailable());
                    }
                    Ok(
                        json!({"scopes":if self.current().is_some(){vec![json!({"name":if self.ast_mode {"Current AST Node"}else{"Current Token"},"variablesReference":1,"expensive":false})]}else{vec![]}}),
                    )
                }
                "variables" => {
                    if arguments["variablesReference"] != 1 {
                        return Err(RequestError::unavailable());
                    }
                    let mut variables = vec![];
                    if let Some(step) = self.current() {
                        let snapshot = self.snapshot.as_ref().unwrap();
                        let source_text = match step
                            .generated_text
                            .as_ref()
                            .or(step.projected_text.as_ref())
                        {
                            Some(text) => text.as_str(),
                            None => snapshot.slice(step.span).map_err(str::to_owned)?,
                        };
                        let text = unlaxer_runtime::strip_capture(source_text);
                        variables.push(variable(
                            &step.label,
                            &format!("\"{}\"", text.replace('"', "\\\"")),
                            if self.ast_mode { "ASTNode" } else { "Token" },
                        ));
                        variables.push(variable("runtimeMode", &self.runtime_mode, "String"));
                        variables.push(variable(
                            "astNodeCount",
                            &(if self.ast_mode { self.steps.len() } else { 0 }).to_string(),
                            "int",
                        ));
                        variables.push(variable(
                            "astCurrentNode",
                            if self.ast_mode { &step.label } else { "" },
                            "String",
                        ));
                    }
                    for (name, value) in &self.variables {
                        if name != "runtimeMode" || self.current().is_none() {
                            variables.push(variable(name, value, "String"));
                        }
                    }
                    Ok(json!({"variables":variables}))
                }
                "next" => {
                    self.check_thread(&arguments)?;
                    let label = self
                        .current()
                        .ok_or_else(RequestError::unavailable)?
                        .label
                        .clone();
                    self.backend.before_step(self.index + 1, &label);
                    self.index += 1;
                    let label = self
                        .current()
                        .map_or("step", |s| s.label.as_str())
                        .to_owned();
                    self.backend.after_step(self.index, &label);
                    if self.current().is_some() {
                        events.push(stopped("step"));
                    } else {
                        self.finish(&mut events, "Completed");
                    }
                    Ok(Value::Null)
                }
                "continue" => {
                    self.check_thread(&arguments)?;
                    if self.current().is_none() {
                        return Err(RequestError::unavailable());
                    }
                    if let Some(index) = self.breakpoint_after(Some(self.index))? {
                        self.index = index;
                        events.push(stopped("breakpoint"));
                    } else {
                        self.index = self.steps.len();
                        self.finish(&mut events, "Completed");
                    }
                    Ok(json!({"allThreadsContinued":true}))
                }
                "disconnect" => {
                    self.disconnected = true;
                    self.configuration_pending = false;
                    self.snapshot = None;
                    self.steps.clear();
                    self.variables.clear();
                    Ok(Value::Null)
                }
                // Default IDebugProtocolServer methods throw synchronously in LSP4J 0.23.1.
                "cancel"
                | "attach"
                | "restart"
                | "terminate"
                | "breakpointLocations"
                | "setFunctionBreakpoints"
                | "setExceptionBreakpoints"
                | "dataBreakpointInfo"
                | "setDataBreakpoints"
                | "setInstructionBreakpoints"
                | "stepIn"
                | "stepOut"
                | "stepBack"
                | "reverseContinue"
                | "restartFrame"
                | "goto"
                | "pause"
                | "setVariable"
                | "source"
                | "terminateThreads"
                | "modules"
                | "loadedSources"
                | "evaluate"
                | "setExpression"
                | "stepInTargets"
                | "gotoTargets"
                | "completions"
                | "exceptionInfo"
                | "readMemory"
                | "writeMemory"
                | "disassemble" => Err(RequestError::Internal(
                    "java.lang.UnsupportedOperationException".into(),
                )),
                _ => Err(format!("Unsupported request method: {command}").into()),
            }
        })();
        let mut output = vec![];
        // LSP4J emits notifications inside the adapter method, before its future
        // completes. DAP NextRequest explicitly requires response-before-stopped,
        // so keep that exception (including the terminating next) on Rust.
        // Allocate seq only in wire order; request_seq always identifies the request.
        let response_first = command == "next";
        if !response_first {
            self.append_events(&mut output, &mut events);
        }
        let mut response = json!({"seq":self.next_seq(),"type":"response","request_seq":request_seq,"command":command,"success":result.is_ok()});
        match result {
            Ok(body) => {
                if !body.is_null() {
                    response["body"] = body;
                }
            }
            Err(RequestError::Message(message)) => {
                response["message"] = json!(message);
            }
            Err(RequestError::Internal(diagnostic)) => {
                // LSP4J's generated Java adapter returns a string body, not an ErrorResponse.
                // Preserve its diagnostic without fabricating JVM-specific stack frames.
                response["message"] = json!("Internal error.");
                response["body"] = json!(format!("{diagnostic}\n"));
            }
            Err(RequestError::Parse(diagnostic)) => {
                response["message"] = json!("Message could not be parsed.");
                response["body"] = json!({"message":diagnostic});
            }
        }
        output.push(response);
        self.append_events(&mut output, &mut events);
        output
    }
    fn append_events(&mut self, output: &mut Vec<Value>, events: &mut Vec<(&str, Value)>) {
        for (event, body) in events.drain(..) {
            let mut value = json!({"seq":self.next_seq(),"type":"event","event":event});
            if !body.is_null() {
                value["body"] = body;
            }
            output.push(value);
        }
    }
    fn next_seq(&mut self) -> i64 {
        self.seq += 1;
        self.seq
    }
    fn check_thread(&self, arguments: &Value) -> Result<(), RequestError> {
        if arguments["threadId"] == 1 {
            Ok(())
        } else {
            Err(RequestError::unavailable())
        }
    }
    fn current(&self) -> Option<&Step> {
        if self.terminated {
            None
        } else {
            self.steps.get(self.index)
        }
    }
    fn breakpoint_after(&self, from: Option<usize>) -> Result<Option<usize>, RequestError> {
        let Some(snapshot) = self.snapshot.as_ref() else {
            return Ok(None);
        };
        // Java evaluates each start position even when continue has no breakpoints.
        // Do not skip invalid positions: the failed request must leave index intact.
        for index in from.map_or(0, |i| i + 1)..self.steps.len() {
            let position = snapshot
                .lsp(self.steps[index].span.start)
                .map_err(RequestError::position)?;
            if i64::try_from(position.line + self.line_offset + self.line_base)
                .is_ok_and(|line| self.breakpoints.contains(&line))
            {
                return Ok(Some(index));
            }
        }
        Ok(None)
    }
    fn prepare(&mut self) -> Result<(), String> {
        let original = self
            .read_source()
            .map_err(|detail| self.source_error(&detail))?;
        let source = self
            .backend
            .resolve_source(&self.program, &original, &self.arguments)
            .map_err(|detail| self.source_error(&detail))?;
        self.prepare_steps(source).map_err(|detail| {
            // The String-returning Backend API is retained. Generated backends use
            // parse_error() for this exact diagnostic, rather than leaking parser Debug text.
            if detail
                .strip_prefix("Parse error at offset ")
                .is_some_and(|offset| {
                    !offset.is_empty() && offset.bytes().all(|b| b.is_ascii_digit())
                })
            {
                format!("{detail}\n")
            } else {
                format!(
                    "{} stepping unavailable: {detail}\n",
                    if self.ast_mode { "AST" } else { "Token" }
                )
            }
        })
    }
    fn source_error(&self, detail: &str) -> String {
        format!("Cannot prepare debug source: {} ({detail})\n", self.program)
    }
    fn read_source(&self) -> Result<String, String> {
        if self.program.contains('\0') {
            return Err(format!(
                "InvalidPathException: Nul character not allowed: {}",
                self.program
            ));
        }
        let io_error = |error: io::Error| match error.kind() {
            io::ErrorKind::NotFound => format!("NoSuchFileException: {}", self.program),
            io::ErrorKind::PermissionDenied => format!("AccessDeniedException: {}", self.program),
            io::ErrorKind::IsADirectory => "IOException: Is a directory".into(),
            _ => format!("IOException: {error}"),
        };
        let metadata = std::fs::metadata(&self.program).map_err(io_error)?;
        if metadata.len() > 4 * 1024 * 1024 {
            return Err("source limit exceeded".into());
        }
        let bytes = std::fs::read(&self.program).map_err(io_error)?;
        String::from_utf8(bytes).map_err(|error| {
            // Files.readString uses String's non-replacing UTF-8 path, whose
            // malformed length differs from std::str::Utf8Error::error_len().
            let invalid = &error.as_bytes()[error.utf8_error().valid_up_to()..];
            let length = match invalid[0] {
                0xe0..=0xef if invalid.len() >= 3 => 3,
                0xe0..=0xef
                    if invalid.len() == 2
                        && (invalid[1] & 0xc0 != 0x80
                            || invalid[0] == 0xe0 && invalid[1] < 0xa0) =>
                {
                    2
                }
                0xf0..=0xf7 if invalid.len() >= 4 => 4,
                _ => 1,
            };
            format!("MalformedInputException: Input length = {length}")
        })
    }
    fn prepare_steps(&mut self, source: DebugSource) -> Result<(), String> {
        // Java stores sourceLineOffset as soon as the source resolves, before parsing or
        // collecting steps; a later failure still moves the next pending frame (#938).
        self.line_offset = source.line_offset;
        let snapshot =
            Snapshot::new(self.program.clone(), 0, source.text).map_err(str::to_owned)?;
        let steps = self.backend.steps(&snapshot, &self.arguments)?;
        if steps.is_empty() {
            return Err(if self.ast_mode {
                "no mapped AST nodes"
            } else {
                "no parser steps"
            }
            .into());
        }
        for step in &steps {
            if step.projected_text.is_some() && (self.ast_mode || step.generated_text.is_some()) {
                return Err("projected text requires an unambiguous token source".into());
            }
            if step.generated_text.is_some() && (self.ast_mode || step.span.start != step.span.end)
            {
                return Err("generated text requires a zero-width token anchor".into());
            }
            // Java retains concatenated terminals whose logical end exceeds the input.
            // The end fails in stackTrace; a CRLF-interior start also fails breakpoint search.
            // Keep rejecting malformed hand-built spans and every other source kind.
            let projected_overflow = step.projected_text.as_ref().is_some_and(|text| {
                step.span.end > snapshot.len()
                    && step.span.end.checked_sub(step.span.start) == Some(text.chars().count())
            });
            if !projected_overflow {
                snapshot.check(step.span).map_err(str::to_owned)?;
            }
            // CP ranges may straddle CRLF. Java defers line/column conversion to
            // stackTrace and breakpoint search; keep the token text and range intact.
            snapshot
                .check(unlaxer_runtime::Span {
                    start: step.span.start,
                    end: step.span.start,
                })
                .map_err(str::to_owned)?;
            if self.ast_mode {
                snapshot.lsp(step.span.start).map_err(str::to_owned)?;
                snapshot.lsp(step.span.end).map_err(str::to_owned)?;
            }
            if step.label.is_empty() {
                return Err(if self.ast_mode {
                    "empty AST label"
                } else {
                    "empty step label"
                }
                .into());
            }
        }
        let probes = self
            .backend
            .runtime_variables(&snapshot, &self.runtime_mode, &self.arguments)
            .unwrap_or_else(|error| BTreeMap::from([("debugRuntimeError".into(), error)]));
        let mut variables = vec![
            ("runtimeMode".into(), self.runtime_mode.clone()),
            ("steppingMode".into(), self.stepping_mode.clone()),
        ];
        for (name, value) in probes {
            if let Some((_, previous)) = variables.iter_mut().find(|(key, _)| key == &name) {
                *previous = value;
            } else {
                variables.push((name, value));
            }
        }
        self.snapshot = Some(snapshot);
        self.steps = steps;
        self.variables = variables;
        self.index = 0;
        Ok(())
    }
    fn finish(&mut self, events: &mut Vec<(&'static str, Value)>, label: &str) {
        events.push((
            "output",
            json!({"category":"stdout","output":format!("{label}: {}\n",self.program)}),
        ));
        self.finish_events(events);
    }
    fn finish_events(&mut self, events: &mut Vec<(&'static str, Value)>) {
        self.terminated = true;
        events.push(("terminated", json!({})));
        events.push(("exited", json!({"exitCode":0})));
    }
    pub fn serve<R: BufRead, W: Write>(&mut self, input: &mut R, output: &mut W) -> io::Result<()> {
        while !self.disconnected {
            let Some(bytes) = unlaxer_protocol::read_frame(input)? else {
                break;
            };
            let message: Value = serde_json::from_slice(&bytes)
                .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
            let raw = std::str::from_utf8(&bytes).map_err(io::Error::other)?;
            for message in self.handle_frame(message, raw) {
                let body = serde_json::to_vec(&message).map_err(io::Error::other)?;
                unlaxer_protocol::write_frame(output, &body)?;
            }
        }
        output.flush()
    }
    pub fn serve_stdio(&mut self) -> io::Result<()> {
        self.serve(&mut io::stdin().lock(), &mut io::stdout().lock())
    }
}
fn stopped(reason: &str) -> (&'static str, Value) {
    (
        "stopped",
        json!({"reason":reason,"threadId":1,"allThreadsStopped":true}),
    )
}
fn normalized_debug_path(path: &str) -> Option<std::path::PathBuf> {
    use std::path::{Component, Path, PathBuf};
    if path.trim().is_empty() || path.contains('\0') {
        return None;
    }
    let path = Path::new(path);
    let absolute = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().ok()?.join(path)
    };
    let mut normalized = PathBuf::new();
    for component in absolute.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                normalized.pop();
            }
            other => normalized.push(other.as_os_str()),
        }
    }
    Some(normalized)
}
fn variable(name: &str, value: &str, kind: &str) -> Value {
    json!({"name":name,"value":value,"type":kind,"variablesReference":0})
}
