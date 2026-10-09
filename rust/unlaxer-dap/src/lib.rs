//! Typed AST DAP inspection; evaluator execution stays in explicit application hooks.
use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::io::{self, BufRead, Write};
use unlaxer_runtime::{source::Snapshot, Span};

#[derive(Clone, Debug)]
pub struct Step {
    pub label: String,
    pub span: Span,
}
#[derive(Clone, Debug)]
pub struct DebugSource {
    pub text: String,
    pub line_offset: usize,
}
pub trait Backend {
    /// Return strict, mapped AST nodes in field declaration order. Partial ASTs are not steps.
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
pub struct Server<B> {
    backend: B,
    seq: i64,
    initialized: bool,
    launched: bool,
    terminated: bool,
    disconnected: bool,
    program: String,
    arguments: Value,
    runtime_mode: String,
    stop_on_entry: bool,
    snapshot: Option<Snapshot>,
    line_offset: usize,
    steps: Vec<Step>,
    index: usize,
    breakpoints: BTreeSet<usize>,
    variables: BTreeMap<String, String>,
    line_base: usize,
    column_base: usize,
}
impl<B: Backend> Server<B> {
    pub fn new(backend: B) -> Self {
        Self {
            backend,
            seq: 0,
            initialized: false,
            launched: false,
            terminated: false,
            disconnected: false,
            program: String::new(),
            arguments: Value::Null,
            runtime_mode: "token".into(),
            stop_on_entry: false,
            snapshot: None,
            line_offset: 0,
            steps: vec![],
            index: 0,
            breakpoints: BTreeSet::new(),
            variables: BTreeMap::new(),
            line_base: 1,
            column_base: 1,
        }
    }
    /// Process one DAP request, with strictly increasing adapter seq on every output message.
    pub fn handle(&mut self, message: Value) -> Vec<Value> {
        let request_seq = message["seq"]
            .as_i64()
            .filter(|seq| *seq > 0 && *seq <= i32::MAX as i64)
            .unwrap_or(0);
        let command = message["command"].as_str().unwrap_or("").to_owned();
        let arguments = message
            .get("arguments")
            .cloned()
            .unwrap_or_else(|| json!({}));
        let mut events = vec![];
        let result: Result<Value, String> = (|| {
            if request_seq == 0 || message["type"] != "request" || command.is_empty() {
                return Err("invalid request".into());
            }
            if !arguments.is_object() && !arguments.is_null() {
                return Err("invalid arguments".into());
            }
            if self.disconnected {
                return Err("disconnected".into());
            }
            if command == "initialize" {
                self.initialized = true;
                self.line_base = usize::from(arguments["linesStartAt1"] != false);
                self.column_base = usize::from(arguments["columnsStartAt1"] != false);
                return Ok(json!({"supportsConfigurationDoneRequest":true}));
            }
            if !self.initialized {
                return Err("not initialized".into());
            }
            match command.as_str() {
                "launch" => {
                    let program = ["program", "formulaSource"]
                        .into_iter()
                        .filter_map(|key| arguments[key].as_str())
                        .find(|s| !s.trim().is_empty())
                        .ok_or("no program specified")?;
                    let runtime = arguments["runtimeMode"].as_str().unwrap_or("token");
                    let normalized = runtime.trim().to_lowercase().replace('_', "-");
                    let inferred = if normalized == "ast"
                        || normalized.ends_with("-ast")
                        || normalized.contains("ast-evaluator")
                    {
                        "ast"
                    } else {
                        "token"
                    };
                    let mode = arguments["steppingMode"].as_str().unwrap_or(inferred);
                    if !mode.eq_ignore_ascii_case("ast") {
                        return Err(
                            "AST stepping required; token leaf tracing is not implemented (#463)"
                                .into(),
                        );
                    }
                    self.program = program.into();
                    self.runtime_mode = runtime.into();
                    self.stop_on_entry = arguments["stopOnEntry"] == true;
                    self.arguments = arguments.clone();
                    self.launched = true;
                    self.terminated = false;
                    self.snapshot = None;
                    self.steps.clear();
                    self.variables.clear();
                    self.index = 0;
                    events.push(("initialized", Value::Null));
                    Ok(Value::Null)
                }
                "setBreakpoints" => {
                    let empty = vec![];
                    let requested = match arguments.get("breakpoints") {
                        None | Some(Value::Null) => &empty,
                        Some(Value::Array(values)) => values,
                        _ => return Err("invalid breakpoints".into()),
                    };
                    let mut lines = BTreeSet::new();
                    let mut results = vec![];
                    for breakpoint in requested {
                        let line = breakpoint["line"]
                            .as_u64()
                            .and_then(|line| usize::try_from(line).ok())
                            .filter(|line| *line >= self.line_base)
                            .ok_or("invalid breakpoint line")?;
                        lines.insert(line);
                        results.push(json!({"verified":true,"line":line}));
                    }
                    self.breakpoints = lines;
                    Ok(json!({"breakpoints":results}))
                }
                "configurationDone" => {
                    if !self.launched {
                        return Err("not launched".into());
                    }
                    match self.prepare() {
                        Ok(()) => {
                            if self.stop_on_entry {
                                self.index = 0;
                                events.push(stopped("entry"));
                            } else if let Some(index) = self.breakpoint_after(None) {
                                self.index = index;
                                events.push(stopped("breakpoint"));
                            } else {
                                self.finish(&mut events, "Parsed successfully");
                            }
                        }
                        Err(error) => {
                            events.push(("output",json!({"category":"stderr","output":format!("AST stepping unavailable: {error}\n")})));
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
                        let position = snapshot.lsp(step.span.start).map_err(str::to_owned)?;
                        let end = snapshot.lsp(step.span.end).map_err(str::to_owned)?;
                        vec![
                            json!({"id":0,"name":format!("{} ({}/{})",step.label,self.index+1,self.steps.len()),"line":position.line+self.line_offset+self.line_base,"column":position.character+self.column_base,"endLine":end.line+self.line_offset+self.line_base,"endColumn":end.character+self.column_base,"source":{"path":self.program,"name":std::path::Path::new(&self.program).file_name().unwrap_or_default().to_string_lossy()}}),
                        ]
                    } else {
                        vec![]
                    };
                    Ok(json!({"stackFrames":frames,"totalFrames":frames.len()}))
                }
                "scopes" => {
                    if arguments["frameId"] != 0 {
                        return Err("invalid frame".into());
                    }
                    Ok(
                        json!({"scopes":if self.current().is_some(){vec![json!({"name":"Current AST Node","variablesReference":1,"expensive":false})]}else{vec![]}}),
                    )
                }
                "variables" => {
                    if arguments["variablesReference"] != 1 {
                        return Err("invalid variables reference".into());
                    }
                    let mut variables = vec![];
                    if let Some(step) = self.current() {
                        let snapshot = self.snapshot.as_ref().unwrap();
                        let text = unlaxer_runtime::strip_capture(
                            snapshot.slice(step.span).map_err(str::to_owned)?,
                        );
                        variables.push(variable(
                            &step.label,
                            &format!("\"{}\"", text.replace('"', "\\\"")),
                            "ASTNode",
                        ));
                        variables.push(variable("runtimeMode", &self.runtime_mode, "String"));
                        variables.push(variable(
                            "astNodeCount",
                            &self.steps.len().to_string(),
                            "int",
                        ));
                        variables.push(variable("astCurrentNode", &step.label, "String"));
                    }
                    for (name, value) in &self.variables {
                        if name != "runtimeMode" {
                            variables.push(variable(name, value, "String"));
                        }
                    }
                    Ok(json!({"variables":variables}))
                }
                "next" => {
                    self.check_thread(&arguments)?;
                    let label = self.current().ok_or("notStopped")?.label.clone();
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
                        return Err("notStopped".into());
                    }
                    if let Some(index) = self.breakpoint_after(Some(self.index)) {
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
                    self.snapshot = None;
                    self.steps.clear();
                    self.variables.clear();
                    Ok(Value::Null)
                }
                _ => Err("unsupported command".into()),
            }
        })();
        let mut output = vec![];
        // Complete the request before sending resulting events (initialized is never pre-initialize).
        let mut response = json!({"seq":self.next_seq(),"type":"response","request_seq":request_seq,"command":command,"success":result.is_ok()});
        match result {
            Ok(body) => {
                if !body.is_null() {
                    response["body"] = body;
                }
            }
            Err(message) => {
                response["message"] = json!(message);
            }
        }
        output.push(response);
        for (event, body) in events {
            let mut value = json!({"seq":self.next_seq(),"type":"event","event":event});
            if !body.is_null() {
                value["body"] = body;
            }
            output.push(value);
        }
        output
    }
    fn next_seq(&mut self) -> i64 {
        self.seq += 1;
        self.seq
    }
    fn check_thread(&self, arguments: &Value) -> Result<(), String> {
        if arguments["threadId"] == 1 {
            Ok(())
        } else {
            Err("invalid thread".into())
        }
    }
    fn current(&self) -> Option<&Step> {
        if self.terminated {
            None
        } else {
            self.steps.get(self.index)
        }
    }
    fn breakpoint_after(&self, from: Option<usize>) -> Option<usize> {
        let snapshot = self.snapshot.as_ref()?;
        ((from.map_or(0, |i| i + 1))..self.steps.len()).find(|&index| {
            snapshot
                .lsp(self.steps[index].span.start)
                .is_ok_and(|position| {
                    self.breakpoints
                        .contains(&(position.line + self.line_offset + self.line_base))
                })
        })
    }
    fn prepare(&mut self) -> Result<(), String> {
        let metadata = std::fs::metadata(&self.program).map_err(|e| e.to_string())?;
        if metadata.len() > 4 * 1024 * 1024 {
            return Err("source limit exceeded".into());
        }
        let original = std::fs::read_to_string(&self.program).map_err(|e| e.to_string())?;
        let source = self
            .backend
            .resolve_source(&self.program, &original, &self.arguments)?;
        let snapshot =
            Snapshot::new(self.program.clone(), 0, source.text).map_err(str::to_owned)?;
        let steps = self.backend.steps(&snapshot, &self.arguments)?;
        if steps.is_empty() {
            return Err("no mapped AST nodes".into());
        }
        for step in &steps {
            snapshot.check(step.span).map_err(str::to_owned)?;
            snapshot.lsp(step.span.start).map_err(str::to_owned)?;
            snapshot.lsp(step.span.end).map_err(str::to_owned)?;
            if step.label.is_empty() {
                return Err("empty AST label".into());
            }
        }
        let mut variables = self
            .backend
            .runtime_variables(&snapshot, &self.runtime_mode, &self.arguments)
            .unwrap_or_else(|error| BTreeMap::from([("debugRuntimeError".into(), error)]));
        variables.insert("runtimeMode".into(), self.runtime_mode.clone());
        variables.insert("steppingMode".into(), "ast".into());
        self.line_offset = source.line_offset;
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
            for message in self.handle(message) {
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
fn variable(name: &str, value: &str, kind: &str) -> Value {
    json!({"name":name,"value":value,"type":kind,"variablesReference":0})
}
