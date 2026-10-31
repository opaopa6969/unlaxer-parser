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
/// LSP position to byte offset. As in the LSP spec and Java `UbnfEditorIndex.offset`,
/// a line past the end is the document end and a character past the line end is the
/// line end (before `\n`). A position inside a surrogate pair is rejected.
pub fn offset(text: &str, pos: &Value) -> Option<usize> {
    if !pos.is_object() {
        return None;
    }
    let line = crate::rpc::gson_integer(&pos["line"])
        .or_else(|| pos["line"].is_null().then_some(0))?
        .max(0) as usize;
    let column = crate::rpc::gson_integer(&pos["character"])
        .or_else(|| pos["character"].is_null().then_some(0))?
        .max(0) as usize;
    let start = if line == 0 {
        0
    } else {
        match text.match_indices('\n').nth(line - 1) {
            Some((i, _)) => i + 1,
            None => return Some(text.len()),
        }
    };
    let end = text[start..].find('\n').map_or(text.len(), |i| start + i);
    let mut units = 0;
    for (index, c) in text[start..end].char_indices() {
        if units == column {
            return Some(start + index);
        }
        if units > column {
            return None;
        }
        units += c.len_utf16();
    }
    (units <= column).then_some(end)
}
pub fn word(c: char) -> bool {
    c.is_ascii_alphanumeric() || c == '_'
}
/// Java `Character.isLetterOrDigit(char)` scans UTF-16 units, so supplementary
/// characters (surrogates) never match. Unicode categories are approximated by
/// `char::is_alphanumeric` (docs/ubnf-authoring-lsp.md).
pub fn java_letter_or_digit(c: char) -> bool {
    c.len_utf16() == 1 && c.is_alphanumeric()
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
/// Java `UnixPath.toUri()` (UnixUriUtils): RFC 2396 path characters, i.e. unreserved
/// `-_.!~*'()` plus `:@&=+$,;/`, stay literal; every other byte is `%XX` (#886).
pub fn file_uri(path: &std::path::Path) -> String {
    let mut uri = String::from("file://");
    for b in path.to_string_lossy().bytes() {
        if b.is_ascii_alphanumeric() || b"/-_.!~*'():@&=+$,;".contains(&b) {
            uri.push(b as char);
        } else {
            uri.push_str(&format!("%{b:02X}"));
        }
    }
    uri
}

pub fn identifier(s: &str) -> bool {
    s.as_bytes()
        .first()
        .is_some_and(|b| b.is_ascii_alphabetic() || *b == b'_')
        && s.chars().all(word)
}
pub fn regex_space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\r' | '\u{b}' | '\u{c}')
}
/// Byte-preserving analogue of UbnfEditorIndex.mask. Only ASCII identifiers are
/// searched in this view; ranges always refer to the original UTF-8 source.
pub fn mask(source: &str) -> String {
    let mut bytes = source.as_bytes().to_vec();
    let mut i = 0;
    while i < bytes.len() {
        let start = i;
        if matches!(bytes[i], b'\'' | b'"') {
            let quote = bytes[i];
            i += 1;
            while i < bytes.len() {
                let c = bytes[i];
                i += 1;
                if c == b'\\' {
                    i = (i + 1).min(bytes.len());
                } else if c == quote {
                    break;
                }
            }
        } else if bytes[i..].starts_with(b"//") {
            i += 2;
            while i < bytes.len() && !matches!(bytes[i], b'\r' | b'\n') {
                i += 1;
            }
        } else if bytes[i..].starts_with(b"/*") {
            i += 2;
            while i < bytes.len() && !bytes[i..].starts_with(b"*/") {
                i += 1;
            }
            i = (i + 2).min(bytes.len());
        } else {
            i += 1;
            continue;
        }
        for b in &mut bytes[start..i] {
            if !matches!(*b, b'\r' | b'\n') {
                *b = b' ';
            }
        }
    }
    String::from_utf8(bytes).expect("mask retains unquoted UTF-8")
}
/// java.net.URI(scheme, null, path, null).toASCIIString() preserves legal path
/// punctuation; the package hash and file name are supplied by the verified resolver.
pub fn package_uri(hash: &str, file: &str) -> String {
    let mut uri = format!("ubnf-package:/{hash}/");
    for b in file.bytes() {
        if b.is_ascii_alphanumeric() || b"/-._~!$&'()*+,;=:@".contains(&b) {
            uri.push(b as char);
        } else {
            uri.push_str(&format!("%{b:02X}"));
        }
    }
    uri
}
/// Navigation uses ASCII binding spans. At an interior UTF-16 surrogate unit no
/// binding can occur, so mapping to that scalar's start preserves Java useAt.
pub fn binding_offset(text: &str, pos: &Value) -> Option<usize> {
    if !pos.is_object() {
        return None;
    }
    let line = crate::rpc::gson_integer(&pos["line"])
        .or_else(|| pos["line"].is_null().then_some(0))?
        .max(0) as usize;
    let column = crate::rpc::gson_integer(&pos["character"])
        .or_else(|| pos["character"].is_null().then_some(0))?
        .max(0) as usize;
    let start = if line == 0 {
        0
    } else {
        match text.match_indices('\n').nth(line - 1) {
            Some((i, _)) => i + 1,
            None => return Some(text.len()),
        }
    };
    let end = text[start..].find('\n').map_or(text.len(), |i| start + i);
    let mut units = 0;
    for (i, c) in text[start..end].char_indices() {
        if units + c.len_utf16() > column {
            return Some(start + i);
        }
        units += c.len_utf16();
    }
    Some(end)
}

/// Character.isWhitespace(char), used by Java String.strip (not regex \s).
pub fn java_whitespace(c: char) -> bool {
    matches!(c, '\u{9}'..='\u{d}' | '\u{1c}'..='\u{20}' | '\u{1680}' |
        '\u{2000}'..='\u{2006}' | '\u{2008}'..='\u{200a}' | '\u{2028}' | '\u{2029}' | '\u{205f}' | '\u{3000}')
}
