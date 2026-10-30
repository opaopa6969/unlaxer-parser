//! Diagnostic priority (#949 / #983 / #986) against the shared CLI corpus
//! `spec-corpus/cli-stderr.json`. Each `frontend.<ending>.native` error there is a fixed
//! expectation (message, UTF-8/code-point span, line, column) that
//! `RustUbnfFrontendConformanceTest` also checks against the Java frontend. Checking it
//! here as well keeps Rust-only `cargo test` sensitive to the order in which the native
//! frontend reports syntax, value and migration errors.
use std::path::Path;

#[path = "support/json.rs"]
mod json;

use json::Json;

fn number(value: &Json) -> usize {
    match value {
        Json::Number(n) => *n as usize,
        other => panic!("number expected: {other:?}"),
    }
}

#[test]
fn shared_frontend_errors_keep_the_first_syntax_value_or_migration_diagnostic() {
    let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../spec-corpus/cli-stderr.json");
    let corpus = json::parse(&std::fs::read_to_string(path).unwrap()).unwrap();
    let mut checked = std::collections::BTreeMap::<String, usize>::new();
    for case in corpus.get("cases").unwrap().array() {
        let Some(frontend) = case.get("frontend") else {
            continue;
        };
        let id = case.required_text("id", "case");
        let scope = case.required_text("scope", id);
        let source = case.get("files").unwrap().required_text("main.ubnf", id);
        for (ending, separator) in [("LF", "\n"), ("CRLF", "\r\n")] {
            let name = format!("{id}/{ending}");
            let expected = frontend.get(ending).unwrap().get("native").unwrap();
            if expected.get("error").is_none() {
                continue;
            }
            let error = unlaxer_ubnf::parse(&source.replace('\n', separator))
                .err()
                .unwrap_or_else(|| panic!("{name}: expected an error"));
            assert_eq!(
                error.message,
                expected.required_text("error", &name),
                "{name}"
            );
            let span: Vec<usize> = expected
                .get("span")
                .unwrap()
                .array()
                .iter()
                .map(number)
                .collect();
            assert_eq!(
                vec![
                    error.span.byte_start,
                    error.span.byte_end,
                    error.span.codepoint_start,
                    error.span.codepoint_end,
                ],
                span,
                "{name}"
            );
            assert_eq!(
                (error.line, error.column),
                (
                    number(expected.get("line").unwrap()),
                    number(expected.get("column").unwrap())
                ),
                "{name}"
            );
            *checked.entry(scope.to_owned()).or_default() += 1;
        }
    }
    // Fixtures must not silently disappear (79 value, 20 regex, 12 qualified forms × LF/CRLF).
    let expected: std::collections::BTreeMap<String, usize> =
        [("qualified", 24), ("regex", 40), ("value", 158)]
            .into_iter()
            .map(|(scope, count)| (scope.to_owned(), count))
            .collect();
    assert_eq!(checked, expected);
}
