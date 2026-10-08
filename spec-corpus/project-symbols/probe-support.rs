#![allow(unused_variables, unused_mut)]
use unlaxer_runtime::semantic::*;
use unlaxer_runtime::semantic_project as p;
use unlaxer_runtime::{json_string, Span};
fn object(fields: &[(&str, String)]) -> String {
    format!(
        "{{{}}}",
        fields
            .iter()
            .map(|(k, v)| format!("{}:{}", json_string(k), v))
            .collect::<Vec<_>>()
            .join(",")
    )
}
fn array(values: impl Iterator<Item = String>) -> String {
    format!("[{}]", values.collect::<Vec<_>>().join(","))
}
fn span(s: Span) -> String {
    format!("[{},{}]", s.start, s.end)
}
fn diagnostic(d: &p::Diagnostic) -> String {
    object(&[
        ("code", json_string(d.code)),
        ("uri", json_string(&d.uri)),
        ("span", span(d.span)),
    ])
}
fn error(e: p::ProjectError) -> String {
    object(&[("error", diagnostic(&e.0))])
}
fn candidate(c: &p::Candidate) -> String {
    let d = &c.definition;
    let i = &d.identity;
    let identity = object(&[
        ("project", json_string(&i.project)),
        ("dependency", json_string(&i.dependency)),
        ("dependencyVersion", json_string(&i.dependency_version)),
        ("module", json_string(&i.module)),
        ("symbol", json_string(&i.symbol)),
    ]);
    let definition = object(&[
        ("identity", identity),
        ("uri", json_string(&d.uri)),
        ("version", d.version.to_string()),
        ("span", span(d.span)),
    ]);
    object(&[
        ("name", json_string(&c.name)),
        ("type", json_string(&c.type_id)),
        ("definition", definition),
    ])
}
fn binding(b: p::Binding) -> String {
    object(&[
        ("name", json_string(&b.name)),
        ("status", json_string(b.status())),
        ("candidates", array(b.candidates.iter().map(candidate))),
        ("diagnostics", array(b.diagnostics.iter().map(diagnostic))),
    ])
}
fn completion(c: &p::Completion) -> String {
    let e = &c.edit;
    let edit = object(&[
        ("uri", json_string(&e.uri)),
        ("version", e.version.to_string()),
        ("span", span(e.span)),
        ("text", json_string(&e.text)),
    ]);
    object(&[
        ("candidate", candidate(&c.candidate)),
        ("compatibility", json_string(c.compatibility.name())),
        ("reason", json_string(&c.reason)),
        ("edit", edit),
    ])
}
