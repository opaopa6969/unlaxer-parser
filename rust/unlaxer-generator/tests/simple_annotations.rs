//! Simple annotations (#715): the shared corpus `spec-corpus/simple-annotations` fixes the
//! 3.3.0 Java behaviour (accepted, generated as if absent) and the `W-ANNOTATION-IGNORED`
//! warnings with their code-point spans, suggestions and messages. Java reads the same corpus
//! in `SimpleAnnotationConformanceTest`.
use serde_json::{json, Value};
use unlaxer_generator::{generate, portability::check, simple_annotations};

fn corpus(file: &str) -> Vec<Value> {
    let path = format!(
        "{}/../../spec-corpus/simple-annotations/{file}",
        env!("CARGO_MANIFEST_DIR")
    );
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

fn warnings(file: &unlaxer_ubnf::UbnfFile) -> Value {
    simple_annotations::warnings(&file.grammars[0])
        .iter()
        .map(|warning| {
            assert_eq!("warning", warning.severity());
            json!({
                "rule": warning.rule,
                "code": warning.code(),
                "subject": warning.subject,
                "span": [warning.span.codepoint_start, warning.span.codepoint_end],
                "suggestion": warning.suggestion,
                "message": warning.message(),
            })
        })
        .collect()
}

#[test]
fn ignored_annotations_are_warned_about_and_generated_as_absent() {
    let rows = corpus("corpus.json");
    assert_eq!(15, rows.len());
    for row in &rows {
        let name = row["name"].as_str().unwrap();
        let source = row["grammar"].as_str().unwrap();
        let baseline = row["baseline"].as_str().unwrap();
        // Before and after the @autoCommit rewrite: that annotation is functional, never reported.
        let rewritten = unlaxer_ubnf::parse(source).unwrap();
        let before = unlaxer_ubnf::auto_commit::parse_before_auto_commit(source).unwrap();
        assert_eq!(row["warnings"], warnings(&rewritten), "{name}");
        assert_eq!(
            row["warnings"],
            warnings(&before),
            "{name} before @autoCommit"
        );
        assert_eq!(generate(baseline), generate(source), "{name} native output");
        assert!(generate(source).is_ok(), "{name}");
        let report = check(source);
        assert!(report.portable, "{name}: {report:?}");
        assert_eq!(check(baseline), report, "{name} portability");
    }
}

#[test]
fn rejected_forms_fail_like_java() {
    for row in corpus("rejected.json") {
        let name = row["name"].as_str().unwrap();
        let source = row["grammar"].as_str().unwrap();
        assert!(unlaxer_ubnf::parse(source).is_err(), "{name}");
        assert!(generate(source).is_err(), "{name}");
        let report = check(source);
        assert_eq!("unavailable", report.structure, "{name}");
        assert_eq!(
            vec!["P-SYNTAX"],
            report
                .diagnostics
                .iter()
                .map(|d| d.code)
                .collect::<Vec<_>>(),
            "{name}"
        );
    }
}

#[test]
fn suggestions_follow_the_documented_distance() {
    for (name, expected) in [
        ("leftAssoc", Some("leftAssoc")),
        ("LEFTASSOC", Some("leftAssoc")),
        ("evl", Some("eval")),
        ("dco", Some("doc")),
        ("foo", None),
        ("a", None),
        ("nestedCaptures", None),
        ("autocommit", Some("autoCommit")),
        ("rooted", Some("root")),
        ("custom", None),
    ] {
        assert_eq!(expected, simple_annotations::suggestion(name), "{name}");
    }
}
