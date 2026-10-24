//! The native generator and portability check against the shared COMMIT / LABEL / CATCH corpus
//! (`spec-corpus/cut-labeled-failure`), which the Java test checks with the same rule and span.
use serde_json::Value;
use unlaxer_generator::portability::check;

fn corpus(text: &str) -> Vec<Value> {
    serde_json::from_str::<Value>(text)
        .unwrap()
        .as_array()
        .unwrap()
        .clone()
}

#[test]
fn invalid_grammars_are_rejected_at_the_shared_rule_and_span() {
    let rows = corpus(include_str!(
        "../../../spec-corpus/cut-labeled-failure/invalid.json"
    ));
    assert_eq!(15, rows.len());
    for row in &rows {
        let id = row["id"].as_str().unwrap();
        let source = row["grammar"].as_str().unwrap();
        let code = row["code"].as_str().unwrap();
        let rule = row.get("rule").and_then(Value::as_str).unwrap_or("Root");
        let start = row["startCP"].as_u64().unwrap() as usize;
        let end = row["endCP"].as_u64().unwrap() as usize;
        let report = check(source);
        let errors: Vec<_> = report
            .diagnostics
            .iter()
            .filter(|diagnostic| diagnostic.code.starts_with("E-"))
            .collect();
        assert_eq!(1, errors.len(), "{id}: {report:?}");
        assert_eq!(code, errors[0].code, "{id}");
        let span = errors[0].span.unwrap();
        assert_eq!(
            (start, end),
            (span.codepoint_start, span.codepoint_end),
            "{id}"
        );
        let error = unlaxer_generator::generate(source).err().unwrap();
        assert!(!error.contains("; "), "{id}: one problem only: {error}");
        assert!(
            error.starts_with(&format!("{code}: {rule}: "))
                && error.ends_with(&format!(" at CP {start}..{end}")),
            "{id}: {error}"
        );
    }
}

#[test]
fn accepted_grammars_generate_without_a_raising_rejection() {
    let rows = corpus(include_str!(
        "../../../spec-corpus/cut-labeled-failure/cases.json"
    ));
    assert_eq!(28, rows.len());
    for row in &rows {
        let name = row["name"].as_str().unwrap();
        let source = row["grammar"].as_str().unwrap();
        if let Err(error) = unlaxer_generator::generate(source) {
            panic!("{name}: {error}");
        }
        let report = check(source);
        assert!(
            report
                .diagnostics
                .iter()
                .all(|diagnostic| !diagnostic.code.starts_with("E-")),
            "{name}: {report:?}"
        );
    }
}

/// `''` is nullable only where it succeeds with width 0 (#749): the `@tokenStream` and
/// `@lexicalContext` grammars of the empty-literal corpus keep `{ COMMIT('') }`, and a caught
/// `{ LABEL(L, '') }` is not rejected. The ordinary-grammar rejections are in `invalid.json`.
#[test]
fn empty_literal_corpus_has_no_raising_rejection() {
    let rows = corpus(include_str!(
        "../../../spec-corpus/empty-literal/corpus.json"
    ));
    let mut checked = 0;
    for row in &rows {
        let name = row["name"].as_str().unwrap();
        let source = row["grammar"].as_str().unwrap();
        if let Err(error) = unlaxer_generator::generate(source) {
            panic!("{name}: {error}");
        }
        let report = check(source);
        assert!(
            report
                .diagnostics
                .iter()
                .all(|diagnostic| diagnostic.code != "E-RAISING-REPETITION"),
            "{name}: {report:?}"
        );
        if source.contains("{ COMMIT('') }") || source.contains("{ LABEL(L, '') }") {
            checked += 1;
        }
    }
    assert_eq!(3, checked);
}
