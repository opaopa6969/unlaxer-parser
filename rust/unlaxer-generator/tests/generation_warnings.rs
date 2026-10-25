//! The shared generation warnings (#715): `spec-corpus/generation-warnings` fixes the code,
//! span, text and CLI line of every warning that leaves generation unchanged, and the strict /
//! fail-on-warning failure of the native `unlaxer generate`. Java reads the same corpus in
//! `GenerationWarningsConformanceTest`.
use serde_json::{json, Value};
use std::process::Command;
use unlaxer_generator::warnings;

fn corpus() -> Vec<Value> {
    let path = format!(
        "{}/../../spec-corpus/generation-warnings/corpus.json",
        env!("CARGO_MANIFEST_DIR")
    );
    let mut rows: Vec<Value> =
        serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
    let ignored: Vec<Value> = serde_json::from_str(include_str!(
        "../../../spec-corpus/global-comment/ignored.json"
    ))
    .unwrap();
    for mut row in ignored {
        row["name"] = format!("global-comment-{}", row["name"].as_str().unwrap()).into();
        row["warnings"] = row["generationWarnings"].clone();
        rows.push(row);
    }
    rows
}

fn lines(row: &Value) -> String {
    row["warnings"]
        .as_array()
        .unwrap()
        .iter()
        .map(|w| w["line"].as_str().unwrap())
        .collect::<Vec<_>>()
        .join("\n")
}

#[test]
fn library_matches_the_shared_corpus() {
    let mut rows = corpus();
    let ordering: Vec<Value> = serde_json::from_str(include_str!(
        "../../../spec-corpus/generation-warnings/ordering.json"
    ))
    .unwrap();
    rows.extend(ordering);
    for row in rows {
        let found: Vec<Value> = warnings::collect(row["grammar"].as_str().unwrap())
            .iter()
            .map(|w| {
                assert_eq!("warning", w.severity());
                let span = w.span.unwrap();
                json!({
                    "code": w.code,
                    "grammar": w.grammar,
                    "rule": w.rule,
                    "span": [span.codepoint_start, span.codepoint_end],
                    "message": w.message,
                    "line": w.line(),
                })
            })
            .collect();
        assert_eq!(row["warnings"], Value::from(found), "{}", row["name"]);
    }
    assert!(warnings::collect("grammar G { @custom(x) R ::= 'x'; }").is_empty());
}

#[test]
fn native_cli_prints_the_lines_and_fails_when_strict() {
    let work = std::env::temp_dir().join(format!("generation-warnings-{}", std::process::id()));
    std::fs::create_dir_all(&work).unwrap();
    for row in corpus() {
        let name = row["name"].as_str().unwrap();
        let expected = lines(&row);
        let grammar = work.join(format!("{name}.ubnf"));
        std::fs::write(&grammar, row["grammar"].as_str().unwrap()).unwrap();
        let output = work.join(name);
        let result = Command::new(env!("CARGO_BIN_EXE_unlaxer"))
            .args(["generate", "--grammar"])
            .arg(&grammar)
            .arg("--output")
            .arg(&output)
            .output()
            .unwrap();
        assert_eq!(Some(0), result.status.code(), "{name}: {result:?}");
        let stderr = String::from_utf8(result.stderr).unwrap();
        let want = if expected.is_empty() {
            String::new()
        } else {
            format!("{expected}\n")
        };
        assert_eq!(want, stderr, "{name}");
        assert!(output.is_dir(), "{name}");
        for policy in [vec!["--strict"], vec!["--fail-on", "warning"]] {
            let strict = work.join(format!("{name}-strict"));
            let failed = Command::new(env!("CARGO_BIN_EXE_unlaxer"))
                .args(["generate", "--target", "rust", "--grammar"])
                .arg(&grammar)
                .arg("--output")
                .arg(&strict)
                .args(&policy)
                .output()
                .unwrap();
            if expected.is_empty() {
                assert_eq!(Some(0), failed.status.code(), "{name}: {failed:?}");
                std::fs::remove_dir_all(&strict).unwrap();
                continue;
            }
            assert_eq!(
                Some(i32::from(warnings::STRICT_EXIT)),
                failed.status.code(),
                "{name} {policy:?}"
            );
            let count = row["warnings"].as_array().unwrap().len();
            assert_eq!(
                format!("{expected}\nstrict: {count} warning(s) fail generation\n"),
                String::from_utf8(failed.stderr).unwrap(),
                "{name}"
            );
            assert!(!strict.exists(), "{name}: nothing written");
        }
    }
    let usage = Command::new(env!("CARGO_BIN_EXE_unlaxer"))
        .args([
            "generate",
            "--grammar",
            "g.ubnf",
            "--output",
            "out",
            "--fail-on",
            "skipped",
        ])
        .output()
        .unwrap();
    assert_eq!(Some(2), usage.status.code());
    std::fs::remove_dir_all(&work).unwrap();
}
