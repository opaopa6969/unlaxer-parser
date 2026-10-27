use serde_json::{json, Value};
use std::path::PathBuf;

pub fn position(text: &str, byte: usize) -> Value {
    let (mut line, mut character) = (0, 0);
    for c in text[..byte].chars() {
        if c == '\n' {
            line += 1;
            character = 0;
        } else {
            character += c.len_utf16();
        }
    }
    json!({"line":line,"character":character})
}
pub fn range(text: &str, start: usize, end: usize) -> Value {
    json!({"start":position(text,start),"end":position(text,end)})
}
pub fn offset(text: &str, pos: &Value) -> Option<usize> {
    let line = pos["line"].as_u64()? as usize;
    let column = pos["character"].as_u64()? as usize;
    let start = if line == 0 {
        0
    } else {
        text.match_indices('\n').nth(line - 1)?.0 + 1
    };
    let mut units = 0;
    for (index, c) in text[start..].char_indices() {
        if units == column {
            return Some(start + index);
        }
        if c == '\n' || units > column {
            return None;
        }
        units += c.len_utf16();
    }
    (units == column).then_some(text.len())
}
pub fn word(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_'
}
#[derive(Clone, Debug)]
pub struct Lexeme {
    pub text: String,
    pub start: usize,
    pub end: usize,
}
/// Editor recovery only. Strings/comments never become declarations or references.
pub fn scan(text: &str) -> Vec<Lexeme> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < text.len() {
        let start = i;
        let c = text[i..].chars().next().unwrap();
        if text[i..].starts_with("//") {
            i += text[i..].find(['\n', '\r']).unwrap_or(text.len() - i);
            continue;
        }
        if text[i..].starts_with("/*") {
            i += text[i + 2..].find("*/").map_or(text.len() - i, |n| n + 4);
            continue;
        }
        if c == '\'' || c == '"' {
            i += 1;
            while i < text.len() {
                let next = text[i..].chars().next().unwrap();
                i += next.len_utf8();
                if next == '\\' {
                    if let Some(next) = text[i..].chars().next() {
                        i += next.len_utf8();
                    }
                } else if next == c {
                    break;
                }
            }
            continue;
        }
        i += c.len_utf8();
        if c.is_whitespace() {
            continue;
        }
        if text[start..].starts_with("::=") {
            i = start + 3;
        } else if word(c) {
            while i < text.len() && word(text[i..].chars().next().unwrap()) {
                i += 1;
            }
        }
        out.push(Lexeme {
            text: text[start..i].into(),
            start,
            end: i,
        });
    }
    out
}
pub fn file_path(uri: &str) -> Result<PathBuf, String> {
    let path = uri.strip_prefix("file://").ok_or("file URI required")?;
    if !path.starts_with('/') || path.contains(['?', '#']) {
        return Err("local file URI required".into());
    }
    let mut decoded = Vec::new();
    let bytes = path.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' {
            let hex = path.get(i + 1..i + 3).ok_or("invalid URI escape")?;
            decoded.push(u8::from_str_radix(hex, 16).map_err(|_| "invalid URI escape")?);
            i += 3;
        } else {
            decoded.push(bytes[i]);
            i += 1;
        }
    }
    let text = String::from_utf8(decoded).map_err(|_| "URI must be UTF-8")?;
    if text.contains('\0') {
        return Err("invalid URI path".into());
    }
    Ok(PathBuf::from(text))
}
pub fn file_uri(path: &std::path::Path) -> String {
    let mut uri = String::from("file://");
    for b in path.to_string_lossy().bytes() {
        if b.is_ascii_alphanumeric() || b"/-._~".contains(&b) {
            uri.push(b as char);
        } else {
            uri.push_str(&format!("%{b:02X}"));
        }
    }
    uri
}
