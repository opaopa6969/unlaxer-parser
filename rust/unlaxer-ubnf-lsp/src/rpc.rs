//! JSON-RPC/lsp4j compatibility at the authoring transport boundary (#994/#1001).
use crate::Error;
use serde_json::Value;

pub(crate) fn internal() -> Error {
    (-32603, "Internal error.".into())
}

fn parse_error() -> Error {
    (-32700, "Message could not be parsed.".into())
}

fn object(value: &Value) -> bool {
    value.is_null() || value.is_object()
}

// Gson's String adapter coerces scalar numbers/booleans, not containers.
fn string(value: &Value) -> bool {
    !value.is_array() && !value.is_object()
}

fn integer(value: &Value) -> bool {
    match value {
        Value::Null => true,
        Value::Number(n) => stream_int(&n.to_string()).is_some(),
        Value::String(s) => stream_int(s).is_some(),
        _ => false,
    }
}

fn boolean(value: &Value) -> bool {
    value.is_null() || value.is_boolean() || value.is_string()
}

fn strings(value: &Value, fields: &[&str]) -> bool {
    fields.iter().all(|key| string(&value[key]))
}

// Deliberately bounded DTO coverage: the observed top-level fields and nested
// representatives are listed in docs/ubnf-authoring-lsp.md (#1001). Unknown
// properties and untyped initializationOptions/experimental stay untyped.
fn initialize_dto(params: &Value) -> bool {
    let caps = &params["capabilities"];
    let text = &caps["textDocument"];
    let completion = &text["completion"];
    let folders = &params["workspaceFolders"];
    integer(&params["processId"])
        && strings(params, &["rootPath", "rootUri", "locale", "trace"])
        && object(&params["clientInfo"])
        && strings(&params["clientInfo"], &["name", "version"])
        && (folders.is_null()
            || folders.as_array().is_some_and(|folders| {
                folders.iter().all(|f| object(f) && strings(f, &["uri", "name"]))
            }))
        && object(caps)
        && ["workspace", "textDocument", "notebookDocument", "window", "general"]
            .iter()
            .all(|field| object(&caps[field]))
        && object(&text["hover"])
        && object(completion)
        && object(&completion["completionItem"])
        && boolean(&completion["completionItem"]["snippetSupport"])
        && object(&caps["workspace"]["workspaceEdit"])
        && boolean(&caps["window"]["workDoneProgress"])
        // Invalid container/bool workDoneToken makes Java's Either adapter
        // stop without a response. Keep native's deterministic acceptance.
        && (!params["workDoneToken"].is_number() || integer(&params["workDoneToken"]))
}

pub(crate) fn typed_request(method: &str) -> bool {
    unimplemented_request(method)
        || matches!(
            method,
            "initialize"
                | "unlaxer/configure"
                | "ubnf/packageSource"
                | "textDocument/hover"
                | "textDocument/completion"
                | "textDocument/definition"
                | "textDocument/references"
                | "textDocument/rename"
                | "textDocument/documentSymbol"
                | "textDocument/foldingRange"
                | "textDocument/linkedEditingRange"
                | "textDocument/codeAction"
                | "textDocument/signatureHelp"
                | "textDocument/semanticTokens/full"
        )
}

pub(crate) fn params_shape(method: &str, params: &Value) -> Result<(), Error> {
    if !typed_request(method) {
        return Ok(());
    }
    let params = initialize_params(params);
    let valid = object(params)
        && if matches!(method, "initialize" | "unlaxer/configure") {
            initialize_dto(params)
        } else if method == "textDocument/hover" {
            // Bounded nested request DTO coverage observed on the Java server.
            object(&params["textDocument"])
                && string(&params["textDocument"]["uri"])
                && object(&params["position"])
                && integer(&params["position"]["line"])
                && integer(&params["position"]["character"])
        } else {
            true
        };
    if valid {
        Ok(())
    } else {
        Err(parse_error())
    }
}

// Read a field from an already validated object. Keep its raw value so stream
// DTO adapters can reject an earlier ill-typed duplicate before it is replaced.
fn next_field<'a>(rest: &mut &'a [u8]) -> Result<(String, Value, &'a [u8]), serde_json::Error> {
    let mut keys = serde_json::Deserializer::from_slice(rest).into_iter::<String>();
    let key = keys.next().expect("validated object key")?;
    *rest = rest[keys.byte_offset()..].trim_ascii_start();
    *rest = rest[1..].trim_ascii_start(); // colon
    let mut values = serde_json::Deserializer::from_slice(rest).into_iter::<Value>();
    let value = values.next().expect("validated field value")?;
    let raw = &rest[..values.byte_offset()];
    *rest = rest[values.byte_offset()..].trim_ascii_start();
    if rest[0] == b',' {
        *rest = rest[1..].trim_ascii_start();
    }
    Ok((key, value, raw))
}

fn initialize_stream_shape(raw: &[u8]) -> Result<Option<Error>, serde_json::Error> {
    let mut rest = raw.trim_ascii_start();
    if rest[0] == b'[' {
        rest = rest[1..].trim_ascii_start();
    }
    if rest[0] != b'{' {
        return Ok(None);
    }
    rest = rest[1..].trim_ascii_start();
    while rest[0] != b'}' {
        let (key, value, _) = next_field(&mut rest)?;
        let one_field = Value::Object([(key, value)].into_iter().collect());
        if !initialize_dto(&one_field) {
            return Ok(Some(parse_error()));
        }
    }
    Ok(None)
}

// Preserve the ordering information needed by lsp4j's stream and deferred DTO
// paths. serde_json decodes escaped keys; raw text/string searches would not.
// No dependency or general parser hot-path change is needed.
pub(crate) fn read_message(bytes: &[u8]) -> Result<(Value, Option<Error>), serde_json::Error> {
    let mut message: Value = serde_json::from_slice(bytes)?;
    if !message.is_object() {
        return Ok((message, None));
    }
    let mut rest = bytes.trim_ascii_start()[1..].trim_ascii_start();
    let mut method = String::new();
    let mut id = None;
    let mut error_id = None;
    let mut deferred = None;
    let mut error = None;
    while rest[0] != b'}' {
        let (key, value, raw) = next_field(&mut rest)?;
        match key.as_str() {
            "id" => id = Some(value),
            "method" => method = value.as_str().unwrap_or("").into(),
            "params" if !typed_request(&method) => deferred = Some(value),
            "params" => {
                let shape_error = params_shape(&method, &value).err();
                let stream_error = if matches!(method.as_str(), "initialize" | "unlaxer/configure")
                {
                    initialize_stream_shape(raw)?
                } else {
                    None
                };
                if error.is_none() {
                    error = shape_error.or(stream_error);
                    // For a deterministic parse failure Java echoes the id it
                    // had read, even if a duplicate id occurs later. When no
                    // id was recovered keep native's final-id response (#1001).
                    if error.is_some() && value.as_array().is_none_or(|a| a.len() <= 1) {
                        error_id = id.clone().filter(|id| !id.is_null());
                    }
                }
                deferred = None;
            }
            _ => {}
        }
    }
    if let Some(params) = deferred {
        // MessageTypeAdapter.parseParams(Object, method) does not unwrap an
        // array read under a missing/unknown method. Its DTO conversion rejects it.
        if typed_request(&method) && params.is_array() {
            error.get_or_insert_with(parse_error);
        } else if let Err(e) = params_shape(&method, &params) {
            error.get_or_insert(e);
        }
    }
    if let Some(id) = error_id {
        message["id"] = id;
    }
    Ok((message, error))
}

pub(crate) fn initialize_params(params: &Value) -> &Value {
    // lsp4j also accepts the single positional argument form [InitializeParams].
    match params.as_array() {
        Some(values) if values.len() == 1 => &values[0],
        _ => params,
    }
}

pub(crate) fn notified_version(document: &Value) -> Result<(), Error> {
    let valid = match &document["version"] {
        Value::Null => true,
        Value::String(text) => stream_int(text).is_some(),
        Value::Number(number) => stream_int(&number.to_string()).is_some(),
        _ => false,
    };
    if valid {
        Ok(())
    } else {
        Err((-32700, "Message could not be parsed.".into()))
    }
}

// Default request methods declared by lsp4j 0.23.1 but not implemented by
// UBNFLanguageServerExt. The implemented #901 methods stay outside this list.
pub(crate) fn unimplemented_request(method: &str) -> bool {
    matches!(
        method,
        "completionItem/resolve"
            | "textDocument/declaration"
            | "textDocument/typeDefinition"
            | "textDocument/implementation"
            | "textDocument/documentHighlight"
            | "codeAction/resolve"
            | "textDocument/codeLens"
            | "codeLens/resolve"
            | "textDocument/formatting"
            | "textDocument/rangeFormatting"
            | "textDocument/onTypeFormatting"
            | "textDocument/willSaveWaitUntil"
            | "textDocument/documentLink"
            | "documentLink/resolve"
            | "textDocument/documentColor"
            | "textDocument/colorPresentation"
            | "textDocument/prepareRename"
            | "textDocument/prepareTypeHierarchy"
            | "typeHierarchy/supertypes"
            | "typeHierarchy/subtypes"
            | "textDocument/prepareCallHierarchy"
            | "callHierarchy/incomingCalls"
            | "callHierarchy/outgoingCalls"
            | "textDocument/selectionRange"
            | "textDocument/semanticTokens/full/delta"
            | "textDocument/semanticTokens/range"
            | "textDocument/moniker"
            | "textDocument/inlayHint"
            | "inlayHint/resolve"
            | "textDocument/inlineValue"
            | "textDocument/diagnostic"
            | "workspace/executeCommand"
            | "workspace/symbol"
            | "workspaceSymbol/resolve"
            | "workspace/willCreateFiles"
            | "workspace/willRenameFiles"
            | "workspace/willDeleteFiles"
            | "workspace/diagnostic"
    )
}

// Gson JsonReader.nextInt: Integer.parseInt, then Double.parseDouble with an
// exact int round trip. This accepts numeric strings, decimal/exponent numbers,
// BMP decimal digits, Java floating suffixes and hexadecimal floating strings.
fn stream_int(value: &str) -> Option<i32> {
    if let Some(n) = string_int(value) {
        return Some(n);
    }
    let trimmed = value.trim_matches(|c| c <= '\u{20}');
    if trimmed.is_empty() {
        return None;
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
    }?;
    let integer = number as i32;
    (f64::from(integer) == number).then_some(integer)
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

fn string_int(value: &str) -> Option<i32> {
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
            return None;
        }
    }
    normalized.parse().ok()
}
