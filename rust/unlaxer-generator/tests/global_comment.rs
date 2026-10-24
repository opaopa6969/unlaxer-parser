//! `@comment` next to a global `@whitespace: javaStyle` (#712): every form that
//! `E-COMMENT-SETTING` rejects elsewhere generated the same code as javaStyle alone in 3.3.0,
//! so it stays accepted and is reported as `W-COMMENT-IGNORED`. The shared corpus
//! (`spec-corpus/global-comment/ignored.json`) is checked by Java with the same codes and spans.
use serde_json::{json, Value};
use unlaxer_generator::{generate, global_comment, portability::check};

#[test]
fn java_style_keeps_ignored_comment_settings_as_warnings() {
    let rows: Vec<Value> = serde_json::from_str(include_str!(
        "../../../spec-corpus/global-comment/ignored.json"
    ))
    .unwrap();
    assert_eq!(8, rows.len());
    for row in &rows {
        let name = row["name"].as_str().unwrap();
        let source = row["grammar"].as_str().unwrap();
        let baseline = row["baseline"].as_str().unwrap();
        let file = unlaxer_ubnf::parse(source).unwrap();
        let warnings: Vec<Value> = global_comment::warnings(&file.grammars[0])
            .iter()
            .map(|warning| {
                json!({
                    "code": warning.code,
                    "subject": warning.subject,
                    "span": [warning.span.codepoint_start, warning.span.codepoint_end],
                })
            })
            .collect();
        assert_eq!(row["warnings"], Value::from(warnings), "{name} warnings");
        assert!(
            global_comment::problems(&file.grammars[0]).is_empty(),
            "{name} no E-COMMENT-*"
        );
        let report = check(source);
        assert!(
            report.portable && report.diagnostics.is_empty(),
            "{name}: {report:?}"
        );
        assert_eq!(
            generate(baseline).unwrap(),
            generate(source).unwrap(),
            "{name}: generated like javaStyle alone"
        );
    }
}

#[test]
fn without_java_style_the_same_forms_stay_errors() {
    let rows: Vec<Value> = serde_json::from_str(include_str!(
        "../../../spec-corpus/global-comment/ignored.json"
    ))
    .unwrap();
    for row in &rows {
        if row["warnings"].as_array().unwrap().is_empty() {
            continue;
        }
        let name = row["name"].as_str().unwrap();
        let source = row["grammar"]
            .as_str()
            .unwrap()
            .replace(" @whitespace: javaStyle\n", "");
        let file = unlaxer_ubnf::parse(&source).unwrap();
        assert!(
            global_comment::warnings(&file.grammars[0]).is_empty(),
            "{name}"
        );
        let error = generate(&source).unwrap_err();
        assert!(error.starts_with("E-COMMENT-SETTING"), "{name}: {error}");
    }
}
