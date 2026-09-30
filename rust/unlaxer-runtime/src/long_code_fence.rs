//! Input-only port of tinyexpression's LongCodeBlockParser. Byte offsets internally;
//! ParseContext projects results and failures to code-point positions.
pub(crate) fn end(source: &str, start: usize) -> Option<usize> {
    let bytes = source.as_bytes();
    if start >= bytes.len() || (start > 0 && !eol(bytes[start - 1])) {
        return None;
    }
    let mut p = start;
    while bytes.get(p) == Some(&b'`') {
        p += 1;
    }
    let width = p - start;
    if width < 4 {
        return None;
    }
    p = identifier_end(bytes, p)?;
    if bytes.get(p) != Some(&b':') {
        return None;
    }
    p = identifier_end(bytes, p + 1)?;
    while bytes.get(p) == Some(&b'.') {
        p = identifier_end(bytes, p + 1)?;
    }
    if !bytes.get(p).is_some_and(|c| eol(*c)) {
        return None;
    }
    p = after_line(bytes, p);
    while p < bytes.len() {
        if eol(bytes[p - 1]) && bytes[p] == b'`' {
            let from = p;
            while bytes.get(p) == Some(&b'`') {
                p += 1;
            }
            if p - from == width && bytes.get(p).is_none_or(|c| eol(*c)) {
                return Some(after_line(bytes, p));
            }
        } else {
            p += 1;
        }
    }
    None
}
fn identifier_end(bytes: &[u8], from: usize) -> Option<usize> {
    if !bytes.get(from).is_some_and(|c| head(*c)) {
        return None;
    }
    let mut p = from + 1;
    while bytes.get(p).is_some_and(|c| head(*c) || c.is_ascii_digit()) {
        p += 1;
    }
    Some(p)
}
fn head(c: u8) -> bool {
    c.is_ascii_alphabetic() || c == b'_'
}
fn eol(c: u8) -> bool {
    matches!(c, b'\r' | b'\n')
}
fn after_line(bytes: &[u8], mut p: usize) -> usize {
    if bytes.get(p) == Some(&b'\r') {
        p += 1;
    }
    if bytes.get(p) == Some(&b'\n') {
        p += 1;
    }
    p
}
