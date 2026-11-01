//! JSON-RPC/lsp4j compatibility at the authoring transport boundary (#994/#1001).
use crate::Error;
use serde_json::Value;
use std::sync::OnceLock;

pub(crate) fn internal() -> Error {
    (-32603, "Internal error.".into())
}

fn parse_error() -> Error {
    (-32700, "Message could not be parsed.".into())
}

fn object(value: &Value) -> bool {
    value.is_null() || value.is_object()
}

// Gson's String adapter preserves numeric spelling and coerces booleans.
// Keep validation and consumption on the same conversion boundary.
pub(crate) fn gson_string(value: &Value) -> Option<String> {
    match value {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}
// Gson's primitive boolean adapter parses strings with Boolean.parseBoolean.
pub(crate) fn gson_boolean(value: &Value) -> bool {
    match value {
        Value::Bool(b) => *b,
        Value::String(s) => s.eq_ignore_ascii_case("true"),
        _ => false,
    }
}
fn string(value: &Value) -> bool {
    value.is_null() || gson_string(value).is_some()
}

pub(crate) fn gson_integer(value: &Value) -> Option<i32> {
    match value {
        Value::Number(n) => stream_int(&n.to_string()),
        Value::String(s) => stream_int(s),
        _ => None,
    }
}
fn integer(value: &Value) -> bool {
    value.is_null() || gson_integer(value).is_some()
}
fn boolean(value: &Value) -> bool {
    value.is_null() || value.is_boolean() || value.is_string()
}

fn schema() -> &'static Value {
    static SCHEMA: OnceLock<Value> = OnceLock::new();
    SCHEMA.get_or_init(|| serde_json::from_str(include_str!("lsp4j-dto-schema.json")).unwrap())
}

// lsp4j's collection adapter also accepts a single element. Either selects by
// token before coercion. Unknown fields and Object/experimental stay untyped.
fn dto(value: &Value, kind: &Value) -> bool {
    if value.is_null() {
        return true;
    }
    if let Some(parts) = kind.as_array() {
        return match parts[0].as_str().unwrap() {
            "list" => value.as_array().map_or_else(
                || dto(value, &parts[1]),
                |items| items.iter().all(|item| dto(item, &parts[1])),
            ),
            "tuple" => value.as_array().is_some_and(|items| {
                items.len() == parts.len() - 1
                    && items.iter().zip(&parts[1..]).all(|(v, t)| dto(v, t))
            }),
            "either" => parts[1..]
                .iter()
                .any(|t| token_matches(value, t) && dto(value, t)),
            _ => unreachable!("generated DTO type"),
        };
    }
    match kind.as_str().unwrap() {
        "any" => true,
        "string" => string(value),
        "integer" => integer(value),
        "boolean" => boolean(value),
        "enum" => {
            if value.is_number() {
                integer(value)
            } else {
                value.is_string()
            }
        }
        // Container/bool token failures leave Java's reader without a response.
        // Keep deterministic native dispatch, as in #1001 (permanent difference).
        "workDoneToken" => !value.is_number() || integer(value),
        name => {
            value.is_object()
                && schema()["types"][name]
                    .as_object()
                    .unwrap()
                    .iter()
                    .all(|(key, ty)| dto(&value[key], ty))
        }
    }
}
fn token_matches(value: &Value, kind: &Value) -> bool {
    if let Some(parts) = kind.as_array() {
        return match parts[0].as_str().unwrap() {
            "either" => parts[1..].iter().any(|t| token_matches(value, t)),
            _ => value.is_array(),
        };
    }
    match kind.as_str().unwrap() {
        "string" => value.is_string(),
        "integer" | "enum" => value.is_number(),
        "boolean" => value.is_boolean(),
        _ => value.is_object(),
    }
}

fn position(value: &Value) -> bool {
    object(value) && integer(&value["line"]) && integer(&value["character"])
}

fn strings(value: &Value, fields: &[&str]) -> bool {
    fields.iter().all(|key| string(&value[key]))
}

// InitializeParams uses the #1001 boundary plus the generated complete
// ClientCapabilities field graph (#1005). Untyped options stay untyped.
fn initialize_dto(params: &Value) -> bool {
    let caps = &params["capabilities"];
    let folders = &params["workspaceFolders"];
    integer(&params["processId"])
        && strings(params, &["rootPath", "rootUri", "locale", "trace"])
        && object(&params["clientInfo"])
        && strings(&params["clientInfo"], &["name", "version"])
        && (folders.is_null()
            || folders.as_array().is_some_and(|folders| {
                folders.iter().all(|f| object(f) && strings(f, &["uri", "name"]))
            }))
        && dto(caps, &Value::String("ClientCapabilities".into()))
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
        && (!schema()["workDoneMethods"]
            .as_array()
            .unwrap()
            .iter()
            .any(|m| m == method)
            || !params["workDoneToken"].is_number()
            || integer(&params["workDoneToken"]))
        && if matches!(method, "initialize" | "unlaxer/configure") {
            initialize_dto(params)
        } else if method == "ubnf/packageSource" {
            string(&params["uri"])
        } else if let Some(kind) = schema()["requests"].get(method) {
            dto(params, kind)
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

// Gson String exposes the original numeric token, including exponent case/sign.
// Use the validated raw object and decoded keys, never text searches.
fn string_spellings(
    raw: &[u8],
    value: &mut Value,
    fields: &[&str],
) -> Result<(), serde_json::Error> {
    let mut rest = raw.trim_ascii_start();
    if rest[0] != b'{' || !value.is_object() {
        return Ok(());
    }
    rest = rest[1..].trim_ascii_start();
    while rest[0] != b'}' {
        let (key, token, raw) = next_field(&mut rest)?;
        if fields.contains(&key.as_str()) {
            // Last duplicate wins, including a later non-number.
            value[&key] = if token.is_number() {
                Value::String(std::str::from_utf8(raw).unwrap().into())
            } else {
                token
            };
        }
    }
    Ok(())
}
fn document_string_spellings(
    raw: &[u8],
    params: &mut Value,
    open: bool,
) -> Result<(), serde_json::Error> {
    let mut rest = raw.trim_ascii_start();
    if rest[0] != b'{' {
        return Ok(());
    }
    rest = rest[1..].trim_ascii_start();
    let mut document = None;
    while rest[0] != b'}' {
        let (key, _, raw) = next_field(&mut rest)?;
        if key == "textDocument" {
            document = Some(raw);
        }
    }
    if let Some(raw) = document {
        string_spellings(
            raw,
            &mut params["textDocument"],
            if open { &["uri", "text"] } else { &["uri"] },
        )?;
    }
    Ok(())
}
// Recover change text after JSON/DTO validation, before state mutation.
fn change_text_spelling(raw: &[u8], params: &mut Value) -> Result<(), serde_json::Error> {
    let mut rest = raw.trim_ascii_start();
    if rest[0] != b'{' {
        return Ok(());
    }
    rest = rest[1..].trim_ascii_start();
    let mut raw_changes = None;
    while rest[0] != b'}' {
        let (key, _, raw) = next_field(&mut rest)?;
        if key == "contentChanges" {
            raw_changes = Some(raw);
        }
    }
    if let Some(changes) = raw_changes.filter(|raw| raw[0] == b'[') {
        let mut changes = changes[1..].trim_ascii_start();
        let mut index = 0;
        while changes[0] != b']' {
            let mut values = serde_json::Deserializer::from_slice(changes).into_iter::<Value>();
            let value = values.next().expect("validated change")?;
            let length = values.byte_offset();
            if value.is_object() {
                if let Some(target) = params["contentChanges"].get_mut(index) {
                    string_spellings(&changes[..length], target, &["text"])?;
                }
            }
            changes = changes[length..].trim_ascii_start();
            if changes[0] == b',' {
                changes = changes[1..].trim_ascii_start();
            }
            index += 1;
        }
    }
    Ok(())
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
    let mut raw_params = None;
    while rest[0] != b'}' {
        let (key, value, raw) = next_field(&mut rest)?;
        if key == "params" {
            raw_params = Some(raw);
        }
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
    if method.starts_with("textDocument/") {
        if let Some(raw) = raw_params {
            document_string_spellings(
                raw,
                &mut message["params"],
                method == "textDocument/didOpen",
            )?;
        }
    }
    if method == "textDocument/didChange" {
        if let Some(raw) = raw_params {
            change_text_spelling(raw, &mut message["params"])?;
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

// Gson reads every TextDocumentContentChangeEvent before Java Ext ignores the
// range and applies the first text. An ill-typed range/rangeLength/text in any
// change, including a raw 1e400, drops the whole notification (#1001).
pub(crate) fn change_shape(params: &Value) -> Result<(), Error> {
    let valid = params["contentChanges"].as_array().is_none_or(|changes| {
        changes.iter().all(|change| {
            let range = &change["range"];
            object(change)
                && object(range)
                && position(&range["start"])
                && position(&range["end"])
                && integer(&change["rangeLength"])
                && string(&change["text"])
        })
    });
    if valid {
        Ok(())
    } else {
        Err(parse_error())
    }
}

pub(crate) fn notified_version(document: &Value) -> Result<(), Error> {
    let valid = integer(&document["version"]);
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

#[cfg(test)]
mod tests {
    #[test]
    fn change_text_uses_gson_scalar_spelling() {
        for (raw, expected) in [
            ("-0", "-0"),
            ("0E5", "0E5"),
            ("1e400", "1e400"),
            ("1.50", "1.50"),
        ] {
            let frame = format!(
                r#"{{"method":"textDocument/didChange","params":{{"contentChanges":[{{"text":{raw}}}]}}}}"#
            );
            let (message, error) = super::read_message(frame.as_bytes()).unwrap();
            assert!(error.is_none());
            assert_eq!(
                super::gson_string(&message["params"]["contentChanges"][0]["text"]).as_deref(),
                Some(expected)
            );
        }
    }
}
