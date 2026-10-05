#![allow(unused_variables)]
use unlaxer_runtime::semantic::*;
use unlaxer_runtime::{json_string, Span};
fn err(e: ModelError) -> String {
    format!(
        "{{\"error\":{},\"span\":[{},{}]}}",
        json_string(e.code),
        e.span.start,
        e.span.end
    )
}
fn strings(v: Vec<String>) -> String {
    format!(
        "[{}]",
        v.iter()
            .map(|s| json_string(s))
            .collect::<Vec<_>>()
            .join(",")
    )
}
fn completions(v: Vec<Completion>) -> String {
    format!("[{}]",v.iter().map(|c|format!("{{\"id\":{},\"name\":{},\"type\":{},\"span\":[{},{}],\"compatibility\":{},\"expectedTypes\":{},\"reason\":{}}}",
        json_string(&c.symbol.id),json_string(&c.symbol.name),json_string(&c.symbol.type_id),c.symbol.declaration.start,c.symbol.declaration.end,
        json_string(c.compatibility.name()),strings(c.expected_types.clone()),json_string(&c.reason()))).collect::<Vec<_>>().join(","))
}
