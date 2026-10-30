//! JSON-RPC/lsp4j compatibility at the authoring transport boundary (#994).
use crate::Error;
use serde_json::Value;

pub(crate) fn internal() -> Error {
    (-32603, "Internal error.".into())
}

pub(crate) fn initialize_params(params: &Value) -> &Value {
    // lsp4j also accepts the single positional argument form [InitializeParams].
    match params.as_array() {
        Some(values) if values.len() == 1 => &values[0],
        _ => params,
    }
}

pub(crate) fn initialize_shape(params: &Value) -> Result<(), Error> {
    let params = initialize_params(params);
    // Null/missing reaches the Java method (and fails there). Other shapes fail
    // in Gson first. initializationOptions itself is deliberately untyped.
    if (!params.is_null() && !params.is_object())
        || (!params["capabilities"].is_null() && !params["capabilities"].is_object())
    {
        Err((-32700, "Message could not be parsed.".into()))
    } else {
        Ok(())
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
