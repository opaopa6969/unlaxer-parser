//! LSP4J 0.23.1 / Gson argument conversion at the DAP boundary only.
//!
//! The independent real-stdio oracle is docs/fixtures/dap-failures/arguments.json.
//! Typed DTO numbers use JsonTreeReader / LazilyParsedNumber, whereas launch's
//! Map<String, Object> uses Double and Java collection toString().
//! Field order and number spelling come from the original frame (see `wire`),
//! so serde_json's map order is never observable here.
use super::RequestError;
use serde_json::{Map, Value};
use std::collections::BTreeMap;
mod wire;
use wire::Wire;
const TROUBLESHOOTING: &str =
    "\nSee https://github.com/google/gson/blob/main/Troubleshooting.md#unexpected-json-structure";

#[derive(Clone, Copy)]
enum Kind {
    Int,
    Boolean,
    String,
    Enum,
    Object(&'static str),
    Array(&'static Kind),
    /// RestartArguments.arguments: LSP4J's Either adapter parses a separate
    /// JsonElement, so it always uses the tree rules and paths from `$`.
    Restart,
}

/// The converted typed arguments, plus Java `String.valueOf` of the launch
/// lookups (`Value::Null` for other commands).
pub(super) struct Converted {
    pub value: Value,
    pub launch: Value,
}

/// How Gson reads one value: JsonReader on the frame (`stream`) or a
/// JsonTreeReader whose paths start at `root`.
struct Reader<'a> {
    wire: &'a Wire<'a>,
    stream: bool,
    root: &'a str,
}

pub(super) fn read(command: &str, mut value: Value, raw: &str) -> Result<Converted, RequestError> {
    let wire = Wire::new(raw);
    let stream = wire.command_first();
    let mut path = "$.arguments";
    let mut extra_argument = None;
    if stream && known(command) && value.is_array() {
        path = "$.arguments[0]";
        if value.as_array().unwrap().is_empty() {
            let reader = Reader {
                wire: &wire,
                stream,
                root: "$.arguments",
            };
            return Err(mismatch("BEGIN_OBJECT", "END_ARRAY", path, &reader));
        }
        extra_argument = value.as_array().unwrap().get(1).map(token);
        value = value.as_array_mut().unwrap().remove(0);
    }
    let reader = Reader {
        wire: &wire,
        stream,
        root: "$.arguments",
    };
    let mut launch = Value::Null;
    if matches!(command, "launch" | "attach") {
        (value, launch) = launch_map(value, path, &reader)?;
    } else if let Some(schema) = dto(command) {
        // Unknown commands and no-argument methods (threads) are dispatched
        // without an argument DTO by LSP4J.
        object(&mut value, schema, path, &reader)?;
    }
    if let Some(actual) = extra_argument {
        // LSP4J logs this diagnostic and drops the request without a response (#938).
        // Reply with a parse failure: never dispatch the first DTO and silently discard
        // the remaining arguments (notably launch/next/configurationDone side effects).
        return Err(mismatch("END_ARRAY", actual, "$.arguments[1]", &reader));
    }
    Ok(Converted { value, launch })
}

fn token(value: &Value) -> &'static str {
    match value {
        Value::Null => "NULL",
        Value::Bool(_) => "BOOLEAN",
        Value::Number(_) => "NUMBER",
        Value::String(_) => "STRING",
        Value::Array(_) => "BEGIN_ARRAY",
        Value::Object(_) => "BEGIN_OBJECT",
    }
}

fn mismatch(expected: &str, actual: &str, path: &str, reader: &Reader) -> RequestError {
    if reader.stream {
        let expected = match expected {
            "NUMBER" => "an int",
            "STRING" => "a string",
            "BOOLEAN" => "a boolean",
            other => other,
        };
        RequestError::Parse(format!(
            "java.lang.IllegalStateException: Expected {expected} but was {actual} {}{TROUBLESHOOTING}",
            reader.wire.location(path, false)
        ))
    } else {
        let path = path.replacen(reader.root, "$", 1);
        RequestError::Parse(format!(
            "java.lang.IllegalStateException: Expected {expected} but was {actual} at path {path}"
        ))
    }
}

fn known(command: &str) -> bool {
    matches!(command, "launch" | "attach") || dto(command).is_some()
}

/// The single DTO parameter of each IDebugProtocolServer method (LSP4J 0.23.1).
/// The generated adapter implements only some of them; LSP4J still converts
/// the DTO of a default method before it throws UnsupportedOperationException.
fn dto(command: &str) -> Option<&'static str> {
    Some(match command {
        "cancel" => "CancelArguments",
        "initialize" => "InitializeRequestArguments",
        "configurationDone" => "ConfigurationDoneArguments",
        "restart" => "RestartArguments",
        "disconnect" => "DisconnectArguments",
        "terminate" => "TerminateArguments",
        "breakpointLocations" => "BreakpointLocationsArguments",
        "setBreakpoints" => "SetBreakpointsArguments",
        "setFunctionBreakpoints" => "SetFunctionBreakpointsArguments",
        "setExceptionBreakpoints" => "SetExceptionBreakpointsArguments",
        "dataBreakpointInfo" => "DataBreakpointInfoArguments",
        "setDataBreakpoints" => "SetDataBreakpointsArguments",
        "setInstructionBreakpoints" => "SetInstructionBreakpointsArguments",
        "continue" => "ContinueArguments",
        "next" => "NextArguments",
        "stepIn" => "StepInArguments",
        "stepOut" => "StepOutArguments",
        "stepBack" => "StepBackArguments",
        "reverseContinue" => "ReverseContinueArguments",
        "restartFrame" => "RestartFrameArguments",
        "goto" => "GotoArguments",
        "pause" => "PauseArguments",
        "stackTrace" => "StackTraceArguments",
        "scopes" => "ScopesArguments",
        "variables" => "VariablesArguments",
        "setVariable" => "SetVariableArguments",
        "source" => "SourceArguments",
        "terminateThreads" => "TerminateThreadsArguments",
        "modules" => "ModulesArguments",
        "loadedSources" => "LoadedSourcesArguments",
        "evaluate" => "EvaluateArguments",
        "setExpression" => "SetExpressionArguments",
        "stepInTargets" => "StepInTargetsArguments",
        "gotoTargets" => "GotoTargetsArguments",
        "completions" => "CompletionsArguments",
        "exceptionInfo" => "ExceptionInfoArguments",
        "readMemory" => "ReadMemoryArguments",
        "writeMemory" => "WriteMemoryArguments",
        "disassemble" => "DisassembleArguments",
        _ => return None,
    })
}

fn stream_int(value: &str, path: &str, wire: &Wire) -> Result<i32, RequestError> {
    if let Ok(n) = string_int(value) {
        return Ok(n);
    }
    let trimmed = value.trim_matches(|c| c <= '\u{20}');
    if trimmed.is_empty() {
        return Err(number_error("empty String".into()));
    }
    let number = match trimmed {
        "NaN" | "+NaN" | "-NaN" => Some(f64::NAN),
        "Infinity" | "+Infinity" => Some(f64::INFINITY),
        "-Infinity" => Some(f64::NEG_INFINITY),
        _ => {
            let decimal = trimmed
                .strip_suffix(['d', 'D', 'f', 'F'])
                .unwrap_or(trimmed);
            if decimal
                .trim_start_matches(['+', '-'])
                .get(..2)
                .is_some_and(|s| s.eq_ignore_ascii_case("0x"))
            {
                hex_double(decimal)
            } else if decimal
                .bytes()
                .all(|b| b.is_ascii_digit() || b"+-.eE".contains(&b))
            {
                decimal.parse::<f64>().ok()
            } else {
                None
            }
        }
    }
    .ok_or_else(|| number_error(format!("For input string: \"{trimmed}\"")))?;
    let integer = number as i32;
    if f64::from(integer) == number {
        Ok(integer)
    } else {
        Err(number_error(format!(
            "Expected an int but was {value} {}",
            wire.location(path, true)
        )))
    }
}

fn hex_double(value: &str) -> Option<f64> {
    let unsigned = value.strip_prefix(['+', '-']).unwrap_or(value);
    let (mantissa, power) = unsigned.get(2..)?.split_once(['p', 'P'])?;
    if power.trim_start_matches(['+', '-']).is_empty()
        || !power
            .trim_start_matches(['+', '-'])
            .bytes()
            .all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let power = power.parse::<i64>().unwrap_or(if power.starts_with('-') {
        -1_000_000
    } else {
        1_000_000
    });
    let mut digits = Vec::new();
    let mut fraction = None;
    for c in mantissa.chars() {
        if c == '.' {
            if fraction.is_some() {
                return None;
            }
            fraction = Some(digits.len());
        } else {
            digits.push(c.to_digit(16)? as u64);
        }
    }
    if digits.is_empty() {
        return None;
    }
    let fraction = fraction.map_or(0, |i| digits.len() - i);
    let digits = &digits[digits.iter().position(|d| *d != 0).unwrap_or(digits.len())..];
    let mut significand = 0_u64;
    for digit in digits.iter().take(15) {
        significand = significand * 16 + digit;
    }
    // Retain a sticky bit below the f64 rounding bits for long hexadecimal values.
    if digits.iter().skip(15).any(|d| *d != 0) {
        significand |= 1;
    }
    let exponent = power
        .saturating_sub(4 * fraction as i64)
        .saturating_add(4 * digits.len().saturating_sub(15) as i64)
        .saturating_add(60);
    let n = if significand == 0 {
        0.0
    } else if exponent > 1100 {
        f64::INFINITY
    } else if exponent < -1100 {
        0.0
    } else {
        (significand as f64 / 2_f64.powi(60)) * 2_f64.powi(exponent as i32)
    };
    Some(if value.starts_with('-') { -n } else { n })
}

fn number_error(message: String) -> RequestError {
    RequestError::Parse(format!("java.lang.NumberFormatException: {message}"))
}

fn object(
    value: &mut Value,
    schema: &str,
    path: &str,
    reader: &Reader,
) -> Result<(), RequestError> {
    if value.is_null() {
        return Ok(());
    }
    let actual = token(value);
    let fields = value
        .as_object_mut()
        .ok_or_else(|| mismatch("BEGIN_OBJECT", actual, path, reader))?;
    // Gson checks fields in their input order, including otherwise unused fields.
    for key in reader.wire.keys(path, fields) {
        if let (Some(kind), Some(value)) = (field_kind(schema, &key), fields.get_mut(&key)) {
            convert(value, kind, &format!("{path}.{key}"), reader)?;
        }
    }
    Ok(())
}

/// Field types of the LSP4J 0.23.1 DTOs (javap of org.eclipse.lsp4j.debug).
/// `int` and `Integer` read alike; `Object` fields (adapterData, __restart)
/// accept any JSON value and are omitted.
fn field_kind(schema: &str, key: &str) -> Option<Kind> {
    use Kind::*;
    const SOURCE: Kind = Object("Source");
    const VALUE_FORMAT: Kind = Object("ValueFormat");
    Some(match (schema, key) {
        ("CancelArguments", "requestId") => Int,
        ("CancelArguments", "progressId") => String,
        (
            "InitializeRequestArguments",
            "clientID" | "clientName" | "adapterID" | "locale" | "pathFormat",
        ) => String,
        (
            "InitializeRequestArguments",
            "linesStartAt1"
            | "columnsStartAt1"
            | "supportsVariableType"
            | "supportsVariablePaging"
            | "supportsRunInTerminalRequest"
            | "supportsMemoryReferences"
            | "supportsProgressReporting"
            | "supportsInvalidatedEvent"
            | "supportsMemoryEvent"
            | "supportsArgsCanBeInterpretedByShell"
            | "supportsStartDebuggingRequest",
        ) => Boolean,
        ("RestartArguments", "arguments") => Restart,
        ("LaunchRequestArguments", "noDebug") => Boolean,
        ("DisconnectArguments", "restart" | "terminateDebuggee" | "suspendDebuggee")
        | ("TerminateArguments", "restart") => Boolean,
        ("BreakpointLocationsArguments" | "GotoTargetsArguments", "source") => SOURCE,
        ("BreakpointLocationsArguments", "line" | "column" | "endLine" | "endColumn")
        | ("GotoTargetsArguments", "line" | "column") => Int,
        ("SetBreakpointsArguments", "source") => SOURCE,
        ("SetBreakpointsArguments", "breakpoints") => Array(&Object("SourceBreakpoint")),
        ("SetBreakpointsArguments", "lines") => Array(&Int),
        ("SetBreakpointsArguments", "sourceModified") => Boolean,
        ("SourceBreakpoint", "line" | "column") => Int,
        ("SourceBreakpoint", "condition" | "hitCondition" | "logMessage" | "mode") => String,
        ("SetFunctionBreakpointsArguments", "breakpoints") => Array(&Object("FunctionBreakpoint")),
        ("FunctionBreakpoint", "name" | "condition" | "hitCondition") => String,
        ("SetExceptionBreakpointsArguments", "filters") => Array(&String),
        ("SetExceptionBreakpointsArguments", "filterOptions") => {
            Array(&Object("ExceptionFilterOptions"))
        }
        ("SetExceptionBreakpointsArguments", "exceptionOptions") => {
            Array(&Object("ExceptionOptions"))
        }
        ("ExceptionFilterOptions", "filterId" | "condition" | "mode") => String,
        ("ExceptionOptions", "path") => Array(&Object("ExceptionPathSegment")),
        ("ExceptionOptions", "breakMode") => Enum,
        ("ExceptionPathSegment", "negate") => Boolean,
        ("ExceptionPathSegment", "names") => Array(&String),
        ("DataBreakpointInfoArguments", "variablesReference" | "frameId") => Int,
        ("DataBreakpointInfoArguments", "name" | "mode") => String,
        ("SetDataBreakpointsArguments", "breakpoints") => Array(&Object("DataBreakpoint")),
        ("DataBreakpoint", "dataId" | "condition" | "hitCondition") => String,
        ("DataBreakpoint", "accessType") => Enum,
        ("SetInstructionBreakpointsArguments", "breakpoints") => {
            Array(&Object("InstructionBreakpoint"))
        }
        (
            "InstructionBreakpoint",
            "instructionReference" | "condition" | "hitCondition" | "mode",
        ) => String,
        ("InstructionBreakpoint", "offset") => Int,
        (
            "ContinueArguments"
            | "NextArguments"
            | "StepInArguments"
            | "StepOutArguments"
            | "StepBackArguments"
            | "ReverseContinueArguments"
            | "GotoArguments"
            | "PauseArguments"
            | "StackTraceArguments"
            | "ExceptionInfoArguments",
            "threadId",
        ) => Int,
        (
            "ContinueArguments"
            | "NextArguments"
            | "StepInArguments"
            | "StepOutArguments"
            | "StepBackArguments"
            | "ReverseContinueArguments",
            "singleThread",
        ) => Boolean,
        (
            "NextArguments" | "StepInArguments" | "StepOutArguments" | "StepBackArguments",
            "granularity",
        ) => Enum,
        ("StepInArguments" | "GotoArguments", "targetId") => Int,
        (
            "RestartFrameArguments"
            | "ScopesArguments"
            | "StepInTargetsArguments"
            | "EvaluateArguments"
            | "SetExpressionArguments"
            | "CompletionsArguments",
            "frameId",
        ) => Int,
        ("StackTraceArguments", "startFrame" | "levels") => Int,
        ("StackTraceArguments", "format") => Object("StackFrameFormat"),
        (
            "StackFrameFormat",
            "hex" | "parameters" | "parameterTypes" | "parameterNames" | "parameterValues" | "line"
            | "module" | "includeAll",
        )
        | ("ValueFormat", "hex") => Boolean,
        ("VariablesArguments" | "SetVariableArguments", "variablesReference")
        | ("VariablesArguments", "start" | "count") => Int,
        ("VariablesArguments", "filter") => Enum,
        (
            "VariablesArguments"
            | "SetVariableArguments"
            | "EvaluateArguments"
            | "SetExpressionArguments",
            "format",
        ) => VALUE_FORMAT,
        ("SetVariableArguments", "name" | "value") => String,
        ("SourceArguments", "source") => SOURCE,
        ("SourceArguments", "sourceReference") => Int,
        ("TerminateThreadsArguments", "threadIds") => Array(&Int),
        ("ModulesArguments", "startModule" | "moduleCount") => Int,
        ("EvaluateArguments", "expression" | "context")
        | ("SetExpressionArguments", "expression" | "value") => String,
        ("CompletionsArguments", "text") => String,
        ("CompletionsArguments", "column" | "line") => Int,
        (
            "ReadMemoryArguments" | "WriteMemoryArguments" | "DisassembleArguments",
            "memoryReference",
        ) => String,
        ("ReadMemoryArguments", "offset" | "count")
        | ("WriteMemoryArguments", "offset")
        | ("DisassembleArguments", "offset" | "instructionOffset" | "instructionCount") => Int,
        ("WriteMemoryArguments", "allowPartial") | ("DisassembleArguments", "resolveSymbols") => {
            Boolean
        }
        ("WriteMemoryArguments", "data") => String,
        ("Source", "name" | "path" | "origin") | ("Checksum", "checksum") => String,
        ("Source", "sourceReference") => Int,
        ("Source", "presentationHint") | ("Checksum", "algorithm") => Enum,
        ("Source", "sources") => Array(&SOURCE),
        ("Source", "checksums") => Array(&Object("Checksum")),
        _ => return None,
    })
}

fn convert(value: &mut Value, kind: Kind, path: &str, reader: &Reader) -> Result<(), RequestError> {
    if value.is_null() {
        return Ok(());
    }
    let actual = token(value);
    match kind {
        Kind::Enum => {
            if value.is_boolean() {
                return Err(mismatch("STRING", actual, path, reader));
            }
            // Unrecognised enum spellings deserialize to null in Gson. The
            // generated adapter does not use these optional presentation fields.
            convert(value, Kind::String, path, reader)?;
        }
        Kind::Int => {
            let n = match value {
                Value::Number(n) => {
                    let spelling = n.to_string();
                    let spelling = reader.wire.value(path).unwrap_or(&spelling);
                    if reader.stream {
                        stream_int(spelling, path, reader.wire)?
                    } else {
                        number_int(spelling)?
                    }
                }
                Value::String(s) => {
                    if reader.stream {
                        stream_int(s, path, reader.wire)?
                    } else {
                        string_int(s)?
                    }
                }
                _ => return Err(mismatch("NUMBER", actual, path, reader)),
            };
            *value = Value::from(n);
        }
        Kind::String => {
            *value = Value::String(match value {
                Value::String(s) => s.clone(),
                Value::Bool(b) => b.to_string(),
                Value::Number(n) => reader
                    .wire
                    .value(path)
                    .map_or_else(|| n.to_string(), str::to_owned),
                _ => return Err(mismatch("STRING", actual, path, reader)),
            });
        }
        Kind::Boolean => {
            *value = Value::Bool(match value {
                Value::Bool(b) => *b,
                Value::String(s) => s.eq_ignore_ascii_case("true"),
                _ => return Err(mismatch("BOOLEAN", actual, path, reader)),
            });
        }
        Kind::Object(schema) => object(value, schema, path, reader)?,
        Kind::Array(element) => {
            let values = value
                .as_array_mut()
                .ok_or_else(|| mismatch("BEGIN_ARRAY", actual, path, reader))?;
            for (i, value) in values.iter_mut().enumerate() {
                convert(value, *element, &format!("{path}[{i}]"), reader)?;
            }
        }
        Kind::Restart => {
            let either = Reader {
                wire: reader.wire,
                stream: false,
                root: path,
            };
            object(value, "LaunchRequestArguments", path, &either)?;
        }
    }
    Ok(())
}

fn string_int(value: &str) -> Result<i32, RequestError> {
    // Integer.parseInt uses Character.digit(char, 10): BMP decimal digits only.
    const ZEROES: &[u32] = &[
        0x30, 0x660, 0x6f0, 0x7c0, 0x966, 0x9e6, 0xa66, 0xae6, 0xb66, 0xbe6, 0xc66, 0xce6, 0xd66,
        0xde6, 0xe50, 0xed0, 0xf20, 0x1040, 0x1090, 0x17e0, 0x1810, 0x1946, 0x19d0, 0x1a80, 0x1a90,
        0x1b50, 0x1bb0, 0x1c40, 0x1c50, 0xa620, 0xa8d0, 0xa900, 0xa9d0, 0xa9f0, 0xaa50, 0xabf0,
        0xff10,
    ];
    let mut normalized = String::new();
    for (i, c) in value.chars().enumerate() {
        if i == 0 && matches!(c, '+' | '-') {
            normalized.push(c);
        } else if let Some(zero) = ZEROES.iter().find(|&&z| (z..z + 10).contains(&(c as u32))) {
            normalized.push(char::from(b'0' + (c as u32 - zero) as u8));
        } else {
            return Err(number_error(format!("For input string: \"{value}\"")));
        }
    }
    normalized
        .parse()
        .map_err(|_| number_error(format!("For input string: \"{value}\"")))
}

fn number_int(value: &str) -> Result<i32, RequestError> {
    if let Ok(n) = value.parse::<i64>() {
        return Ok(n as i32);
    }
    // BigDecimal.intValue truncates toward zero, then keeps the low 32 bits.
    // Work on decimal digits, never f64 (rounding would change those bits).
    if value.len() > 10_000 {
        return Err(number_error(format!(
            "Number string too large: {}...",
            &value[..30]
        )));
    }
    let (mantissa, exponent) = value.split_once(['e', 'E']).unwrap_or((value, "0"));
    let significant = exponent
        .trim_start_matches(['+', '-'])
        .trim_start_matches('0');
    if significant.len() > 10 {
        return Err(number_error("Too many nonzero exponent digits.".into()));
    }
    let exponent: i64 = exponent
        .parse()
        .map_err(|_| number_error("Exponent overflow.".into()))?;
    let fraction = mantissa.split_once('.').map_or(0, |(_, s)| s.len()) as i64;
    let scale = fraction - exponent;
    if i32::try_from(scale).is_err() {
        return Err(number_error("Exponent overflow.".into()));
    }
    if scale.abs() >= 10_000 {
        return Err(number_error(format!(
            "Number has unsupported scale: {value}"
        )));
    }
    let digits: Vec<_> = mantissa.bytes().filter(u8::is_ascii_digit).collect();
    let count = digits.len().saturating_sub(scale.max(0) as usize);
    let mut n = 0_u32;
    for digit in &digits[..count] {
        n = n.wrapping_mul(10).wrapping_add(u32::from(digit - b'0'));
    }
    if scale <= -32 {
        n = 0;
    } else {
        for _ in 0..(-scale).max(0) {
            n = n.wrapping_mul(10);
        }
    }
    Ok(if value.starts_with('-') {
        n.wrapping_neg()
    } else {
        n
    } as i32)
}

/// Gson's Map<String, Object>, plus Java `String.valueOf` of the launch lookups.
/// Application hooks receive the map itself, including numeric/object/array values.
fn launch_map(value: Value, root: &str, reader: &Reader) -> Result<(Value, Value), RequestError> {
    let mut paths = BTreeMap::new();
    let value = match value {
        Value::Null => value,
        Value::Object(fields) => {
            for key in fields.keys() {
                paths.insert(key.clone(), format!("{root}.{key}"));
            }
            Value::Object(fields)
        }
        Value::Array(entries) => {
            let mut map = Map::new();
            for (i, entry) in entries.into_iter().enumerate() {
                let path = format!("{root}[{i}]");
                let actual = token(&entry);
                let Value::Array(mut pair) = entry else {
                    return Err(mismatch("BEGIN_ARRAY", actual, &path, reader));
                };
                if pair.is_empty() {
                    return Err(mismatch(
                        "STRING",
                        "END_ARRAY",
                        &format!("{path}[0]"),
                        reader,
                    ));
                }
                convert(&mut pair[0], Kind::String, &format!("{path}[0]"), reader)?;
                if pair.len() != 2 {
                    return Err(mismatch(
                        "END_ARRAY",
                        pair.get(2).map_or("END_ARRAY", token),
                        &format!("{path}[2]"),
                        reader,
                    ));
                }
                let key = pair[0].as_str().unwrap_or("null").to_owned();
                if map
                    .insert(key.clone(), pair.remove(1))
                    .is_some_and(|old| !old.is_null())
                {
                    return Err(RequestError::Parse(format!("duplicate key: {key}")));
                }
                paths.insert(key, format!("{path}[1]"));
            }
            Value::Object(map)
        }
        _ => return Err(mismatch("BEGIN_OBJECT", token(&value), root, reader)),
    };
    let mut strings = Map::new();
    for key in ["program", "formulaSource", "runtimeMode", "steppingMode"] {
        if let (Some(field), Some(path)) = (value.get(key), paths.get(key)) {
            if !field.is_null() || matches!(key, "runtimeMode" | "steppingMode") {
                strings.insert(
                    key.into(),
                    Value::String(map_string(field, path, reader.wire)),
                );
            }
        }
    }
    Ok((value, Value::Object(strings)))
}

/// Java `String.valueOf` of a Gson ObjectTypeAdapter value (LinkedTreeMap keeps
/// the input order, numbers are Double).
fn map_string(value: &Value, path: &str, wire: &Wire) -> String {
    match value {
        Value::Null => "null".into(),
        Value::Bool(b) => b.to_string(),
        Value::String(s) => s.clone(),
        Value::Number(n) => java_double(n.to_string().parse().unwrap_or(f64::NAN)),
        Value::Array(values) => format!(
            "[{}]",
            values
                .iter()
                .enumerate()
                .map(|(i, v)| map_string(v, &format!("{path}[{i}]"), wire))
                .collect::<Vec<_>>()
                .join(", ")
        ),
        Value::Object(fields) => format!(
            "{{{}}}",
            wire.keys(path, fields)
                .into_iter()
                .map(|k| format!(
                    "{k}={}",
                    map_string(&fields[&k], &format!("{path}.{k}"), wire)
                ))
                .collect::<Vec<_>>()
                .join(", ")
        ),
    }
}

fn java_double(n: f64) -> String {
    if !n.is_finite() {
        return if n.is_nan() {
            "NaN"
        } else if n.is_sign_negative() {
            "-Infinity"
        } else {
            "Infinity"
        }
        .into();
    }
    if n == 0.0 {
        return if n.is_sign_negative() { "-0.0" } else { "0.0" }.into();
    }
    if (0.001..10_000_000.0).contains(&n.abs()) {
        let s = n.to_string();
        return if s.contains('.') { s } else { format!("{s}.0") };
    }
    let mut s = format!("{n:e}");
    if !s.contains('.') {
        // Double.toString chooses at least two significant digits. This matters
        // for subnormal values such as Double.MIN_VALUE (4.9E-324, not 5.0E-324).
        s = format!("{n:.1e}");
    }
    let (mantissa, exponent) = s.split_once('e').unwrap();
    format!(
        "{mantissa}{}E{exponent}",
        if mantissa.contains('.') { "" } else { ".0" }
    )
}
