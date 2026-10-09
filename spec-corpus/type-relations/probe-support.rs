use unlaxer_runtime::{json_string, type_system::*};
fn kind_name(kind: Kind) -> &'static str {
    match kind {
        Kind::Named => "NAMED",
        Kind::Variable => "VARIABLE",
        Kind::Unknown => "UNKNOWN",
        Kind::Union => "UNION",
        Kind::Intersection => "INTERSECTION",
        Kind::Nullable => "NULLABLE",
        Kind::Function => "FUNCTION",
        Kind::Alias => "ALIAS",
        Kind::Null => "NULL",
    }
}
fn render(t: &TypeRef) -> String {
    format!(
        "{}({}{})",
        kind_name(t.kind()),
        t.name(),
        if t.arguments().is_empty() {
            String::new()
        } else {
            format!(
                ",{}",
                t.arguments()
                    .iter()
                    .map(render)
                    .collect::<Vec<_>>()
                    .join(",")
            )
        }
    )
}
fn leaves(d: &Decision, result: &mut Vec<String>) {
    if d.evidence.is_empty() {
        result.push(json_string(&d.rule));
    } else {
        for c in &d.evidence {
            leaves(c, result);
        }
    }
}
fn decision(d: Decision) -> String {
    let mut leaf = vec![];
    leaves(&d, &mut leaf);
    format!(
        "{{\"status\":{},\"rule\":{},\"leaves\":[{}]}}",
        json_string(d.status.name()),
        json_string(&d.rule),
        leaf.join(",")
    )
}
fn shape_checks() {
    assert!(TypeRef::named(String::new(), vec![]).is_err());
    assert!(TypeRef::new(Kind::Unknown, "X".into(), vec![]).is_err());
    assert!(TypeRef::new(Kind::Nullable, String::new(), vec![]).is_err());
    assert!(TypeRef::new(Kind::Function, String::new(), vec![]).is_err());
    let mut deep = TypeRef::named("Int".into(), vec![]).unwrap();
    for _ in 0..150 {
        deep = TypeRef::new(Kind::Nullable, String::new(), vec![deep]).unwrap();
    }
    assert_eq!(
        Status::Limit,
        substitute(&deep, &Default::default(), 4096)
            .unwrap_err()
            .status
    );
}
