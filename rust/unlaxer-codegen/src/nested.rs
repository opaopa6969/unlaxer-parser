//! Emission for explicit, typed container captures; legacy flat fields are unchanged.
use crate::{Cardinality, Kind};

pub(crate) fn ty(leaf: &Kind, containers: &[Cardinality]) -> String {
    let mut value = match leaf {
        Kind::Text => "String".into(),
        Kind::Integer => "i32".into(),
        _ => unreachable!("validated nested leaf"),
    };
    for container in containers.iter().rev() {
        value = match container {
            Cardinality::Optional => format!("Option<{value}>"),
            Cardinality::Many => format!("Vec<{value}>"),
            Cardinality::One => unreachable!("validated nested container"),
        };
    }
    value
}
pub(crate) fn json(leaf: &Kind, containers: &[Cardinality], value: &str, depth: usize) -> String {
    let Some((container, tail)) = containers.split_first() else {
        return if *leaf == Kind::Integer {
            format!("{value}.to_string()")
        } else {
            format!("json_string({value})")
        };
    };
    let child = format!("nested_{depth}");
    let inner = json(leaf, tail, &child, depth + 1);
    match container {
        Cardinality::Optional => format!("{value}.as_ref().map_or_else(|| \"null\".to_owned(), |{child}| {inner})"),
        Cardinality::Many => format!("format!(\"[{{}}]\", {value}.iter().map(|{child}| {inner}).collect::<Vec<_>>().join(\",\"))"),
        Cardinality::One => unreachable!("validated nested container"),
    }
}
pub(crate) fn mapped(leaf: &Kind, containers: &[Cardinality], value: &str, depth: usize) -> String {
    let Some((container, tail)) = containers.split_first() else {
        return format!(
            "nested_{}({value})",
            if *leaf == Kind::Integer {
                "integer"
            } else {
                "text"
            }
        );
    };
    let child = format!("nested_{depth}");
    let inner = mapped(leaf, tail, &child, depth + 1);
    format!(
        "nested_{}({value}, |{child}| {inner})",
        if *container == Cardinality::Optional {
            "optional"
        } else {
            "list"
        }
    )
}

pub(crate) const HELPERS: &str = r#"
fn nested_capture(tree: &Tree, capture: &unlaxer_runtime::Capture) -> Result<unlaxer_runtime::capture_tree::Element, String> {
    if capture.nodes.len() != 1 { return Err(format!("invalid nested capture {} at {}..{}", capture.name, capture.span.start, capture.span.end)); }
    unlaxer_runtime::capture_tree::snapshot(tree, capture.nodes[0]).map_err(|error| format!("invalid nested capture {} at {}..{}: {error:?}", capture.name, capture.span.start, capture.span.end))
}
fn nested_text(value: &unlaxer_runtime::capture_tree::Element) -> Result<String, String> {
    if value.kind() != unlaxer_runtime::capture_tree::Kind::Value { return Err("expected nested value boundary".into()); }
    Ok(value.text().to_owned())
}
fn nested_integer(value: &unlaxer_runtime::capture_tree::Element) -> Result<i32, String> {
    nested_text(value)?.trim_matches(|ch: char| ch <= '\u{20}').parse().map_err(|_| format!("invalid i32 nested capture at {}..{}", value.span().start, value.span().end))
}
fn nested_optional<T>(value: &unlaxer_runtime::capture_tree::Element, map: impl Fn(&unlaxer_runtime::capture_tree::Element) -> Result<T, String>) -> Result<Option<T>, String> {
    if value.kind() != unlaxer_runtime::capture_tree::Kind::Optional || value.children().len() > 1 { return Err("expected nested optional boundary".into()); }
    value.children().first().map(map).transpose()
}
fn nested_list<T>(value: &unlaxer_runtime::capture_tree::Element, map: impl Fn(&unlaxer_runtime::capture_tree::Element) -> Result<T, String>) -> Result<Vec<T>, String> {
    if value.kind() != unlaxer_runtime::capture_tree::Kind::List { return Err("expected nested list boundary".into()); }
    value.children().iter().map(map).collect()
}
"#;
