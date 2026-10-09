use unlaxer_runtime::{call_inference as ci, Span};
fn obj(fields: &[(&str, String)]) -> String {
    format!(
        "{{{}}}",
        fields
            .iter()
            .map(|(k, v)| format!("{}:{}", json_string(k), v))
            .collect::<Vec<_>>()
            .join(",")
    )
}
fn arr(values: impl Iterator<Item = String>) -> String {
    format!("[{}]", values.collect::<Vec<_>>().join(","))
}
fn span(s: Span) -> String {
    format!("[{},{}]", s.start, s.end)
}
fn resolution(r: &ci::Resolution) -> String {
    let candidates = arr(r.candidates.iter().map(|c| {
        let constraints = arr(c.constraints.iter().map(|v| {
            obj(&[
                ("role", json_string(&v.role)),
                ("uri", json_string(&v.uri)),
                ("version", v.version.to_string()),
                ("status", json_string(v.decision.status.name())),
                ("span", span(v.span)),
            ])
        }));
        let substitution = format!(
            "{{{}}}",
            c.substitution
                .iter()
                .map(|(k, v)| format!("{}:{}", json_string(k), json_string(&render(v))))
                .collect::<Vec<_>>()
                .join(",")
        );
        obj(&[
            ("signature", json_string(&c.signature)),
            ("uri", json_string(&c.uri)),
            ("version", c.version.to_string()),
            ("status", json_string(c.status.name())),
            ("substitution", substitution),
            (
                "parameters",
                arr(c.parameters.iter().map(|t| json_string(&render(t)))),
            ),
            ("result", json_string(&render(&c.result))),
            ("varargs", c.varargs.to_string()),
            ("span", span(c.span)),
            ("constraints", constraints),
        ])
    }));
    obj(&[
        ("state", json_string(r.state.name())),
        ("uri", json_string(&r.uri)),
        ("version", r.version.to_string()),
        ("candidates", candidates),
    ])
}
fn completion(c: &ci::Completion) -> String {
    let v = &c.value;
    obj(&[
        ("id", json_string(&v.id)),
        ("name", json_string(&v.name)),
        ("type", json_string(&render(&v.type_ref))),
        ("uri", json_string(&v.uri)),
        ("version", v.version.to_string()),
        ("span", span(v.span)),
        ("status", json_string(c.decision.status.name())),
        ("rule", json_string(&c.decision.rule)),
    ])
}
