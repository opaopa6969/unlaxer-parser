#[path = "typed_adapter.rs"]
mod typed_adapter;
use unlaxer_runtime::{editor_cst::Options, json_string as q};
/// This adapter reports original declarations only. It never proposes edits in inserted syntax.
pub fn analyze(source: &str, cursor: usize) -> Option<String> {
    let parsed =
        typed_adapter::parse("memory:playground", 0, source, None, Options::default()).ok()?;
    let expected = parsed.result.expected_types_at(cursor, None).ok()?;
    let prefix = source.chars().take(cursor).collect::<String>();
    let prefix = prefix
        .chars()
        .rev()
        .take_while(|value| value.is_ascii_alphanumeric() || *value == '_')
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect::<String>();
    let completions = parsed.result.complete_at(cursor, None, 0, &prefix).ok()?;
    let completions = completions
        .iter()
        .map(|item| {
            format!(
                "{{\"label\":{},\"type\":{},\"span\":[{},{}]}}",
                q(&item.symbol.name),
                q(&item.symbol.type_id),
                item.symbol.declaration.start,
                item.symbol.declaration.end
            )
        })
        .collect::<Vec<_>>()
        .join(",");
    Some(format!(
        "{{\"status\":{},\"expectedTypes\":[{}],\"completions\":[{}]}}",
        q(parsed.cst.status().name()),
        expected
            .iter()
            .map(|value| q(value))
            .collect::<Vec<_>>()
            .join(","),
        completions
    ))
}
