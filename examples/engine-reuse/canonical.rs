pub fn hex(s: &str) -> String {
    s.as_bytes().iter().map(|b| format!("{b:02x}")).collect()
}
pub fn span(s: &str, start: usize, end: usize) -> String {
    let prefix: String = s.chars().take(start).collect();
    let text: String = s.chars().skip(start).take(end - start).collect();
    format!(
        "cp={start}:{end},utf16={}:{},text={}",
        prefix.encode_utf16().count(),
        prefix.encode_utf16().count() + text.encode_utf16().count(),
        hex(&text)
    )
}
pub fn values(items: &[String]) -> String {
    format!(
        "[{}]",
        items
            .iter()
            .map(|s| format!("s{}", hex(s)))
            .collect::<Vec<_>>()
            .join(",")
    )
}
