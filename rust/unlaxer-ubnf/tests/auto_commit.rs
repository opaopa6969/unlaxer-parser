//! `@autoCommit` and the cut-position lint (#702) against `spec-corpus/auto-commit`, the same
//! fixtures the Java `AutoCommitConformanceTest` reads.
use std::path::Path;
use unlaxer_ubnf::*;

#[path = "support/canonical.rs"]
mod canonical;
#[path = "support/json.rs"]
mod json;

use json::Json;

fn corpus(file: &str) -> Vec<Json> {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../spec-corpus/auto-commit")
        .join(file);
    json::parse(&std::fs::read_to_string(path).unwrap())
        .unwrap()
        .array()
        .to_vec()
}

fn text<'a>(row: &'a Json, key: &str) -> &'a str {
    row.required_text(key, "auto-commit corpus")
}

fn slice(source: &str, start: usize, end: usize) -> String {
    source.chars().skip(start).take(end - start).collect()
}

#[test]
fn lint_reports_the_corpus_positions() {
    let rows = corpus("lint.json");
    assert_eq!(rows.len(), 33);
    let mut reported = 0;
    for row in &rows {
        let name = text(row, "name");
        let source = text(row, "grammar");
        let findings = auto_commit::lint(source).unwrap_or_else(|e| panic!("{name}: {e}"));
        let expected = row.get("findings").unwrap().array();
        assert_eq!(findings.len(), expected.len(), "{name}: {findings:?}");
        for (finding, expected) in findings.iter().zip(expected) {
            assert_eq!(finding.rule, text(expected, "rule"), "{name}");
            let alternative = slice(
                source,
                finding.span.codepoint_start,
                finding.span.codepoint_end,
            );
            assert_eq!(alternative, text(expected, "alternative"), "{name}");
            let first = slice(source, finding.span.codepoint_start, finding.cut);
            assert_eq!(first, text(expected, "first"), "{name}");
            let names: Vec<&str> = finding.commits.iter().map(|(n, _)| n.as_str()).collect();
            let wanted: Vec<&str> = expected
                .get("commits")
                .unwrap()
                .array()
                .iter()
                .map(|v| v.text().unwrap())
                .collect();
            assert_eq!(names, wanted, "{name}");
            for (commit, span) in &finding.commits {
                let written = slice(source, span.codepoint_start, span.codepoint_end);
                assert!(
                    written == *commit || written.starts_with(commit.split('_').next().unwrap()),
                    "{name}: {written} for {commit}"
                );
            }
            let inserted = expected.get("inserted") == Some(&Json::Bool(true));
            assert_eq!(finding.inserted, inserted, "{name}");
            assert_eq!(finding.severity(), "info");
            assert!(finding.message().starts_with("I-AUTO-COMMIT: "), "{name}");
            reported += 1;
        }
    }
    assert_eq!(reported, 31);
}

/// The rewrite equals the hand-written grammar, inserts only into annotated rules, removes the
/// annotation and leaves the grammar without it unchanged.
#[test]
fn rewrite_equals_the_hand_written_commits() {
    let rows = corpus("cases.json");
    assert_eq!(rows.len(), 10);
    for row in &rows {
        let name = text(row, "name");
        let auto = parse(text(row, "grammar")).unwrap_or_else(|e| panic!("{name}: {e}"));
        let manual = parse(text(row, "manual")).unwrap_or_else(|e| panic!("{name}: {e}"));
        assert_eq!(canonical::file(&auto), canonical::file(&manual), "{name}");
        let plain_source = text(row, "grammar")
            .replace("@autoCommit ", "")
            .replace("@autoCommit\n  ", "");
        let plain = parse(&plain_source).unwrap();
        let rewritten = auto_commit::apply(plain.grammars[0].clone());
        assert_eq!(
            rewritten, plain.grammars[0],
            "{name}: no annotation, no rewrite"
        );
        assert!(
            !canonical::file(&auto).contains(auto_commit::ANNOTATION),
            "{name}"
        );
    }
}

#[test]
fn rewritten_elements_keep_their_source_spans() {
    let source = "grammar G {\n  @root\n  Root ::= { S } ;\n  @autoCommit\n  S ::= 'k' N @n ;\n  N ::= 'n' ;\n}\n";
    let file = parse(source).unwrap();
    let sequence = &file.grammars[0].rules[1].body.alternatives[0];
    let wrapper = &sequence.elements[1];
    let ElementKind::Commit {
        body,
        message: None,
    } = &wrapper.element.kind
    else {
        panic!("{wrapper:?}")
    };
    let inner = &body.alternatives[0].elements[0];
    assert_eq!(inner.capture.as_deref(), Some("n"));
    assert_eq!(
        slice(source, inner.span.codepoint_start, inner.span.codepoint_end),
        "N @n"
    );
    for span in [
        wrapper.span,
        wrapper.element.span,
        body.span,
        body.alternatives[0].span,
    ] {
        assert_eq!(span, inner.span);
    }
    assert_eq!(wrapper.capture, None);
    assert_eq!(wrapper.capture_span, None);
    assert!(file.grammars[0].rules[1].annotations.is_empty());
}

/// `W-AUTO-COMMIT-SKIPPED` for annotations the analysis cannot use, located at the annotation.
#[test]
fn skipped_annotations_are_warned_about() {
    let rows = corpus("warnings.json");
    assert_eq!(rows.len(), 11);
    for row in &rows {
        let name = text(row, "name");
        let source = text(row, "grammar");
        let warnings = auto_commit::lint_warnings(source).unwrap();
        let expected = row.get("warnings").unwrap().array();
        assert_eq!(warnings.len(), expected.len(), "{name}: {warnings:?}");
        for (warning, expected) in warnings.iter().zip(expected) {
            assert_eq!(warning.rule, text(expected, "rule"), "{name}");
            assert_eq!(warning.reason, text(expected, "reason"), "{name}");
            assert_eq!(warning.code(), "W-AUTO-COMMIT-SKIPPED");
            assert_eq!(warning.severity(), "warning");
            let written = slice(
                source,
                warning.span.codepoint_start,
                warning.span.codepoint_end,
            );
            assert_eq!(written, "@autoCommit", "{name}");
            assert!(
                warning.message().starts_with("W-AUTO-COMMIT-SKIPPED: "),
                "{name}"
            );
        }
        // The rewritten tree no longer carries the annotation.
        let rewritten = parse(source).unwrap();
        assert!(
            rewritten
                .grammars
                .iter()
                .all(|g| auto_commit::warnings(g).is_empty()),
            "{name}"
        );
        // A skipped annotation only disappears: the tree is the unannotated one.
        if !warnings.is_empty() {
            let plain = source
                .replace("@autoCommit ", "")
                .replace("@autoCommit\n  ", "");
            assert_eq!(
                canonical::file(&rewritten),
                canonical::file(&parse(&plain).unwrap()),
                "{name}"
            );
        }
    }
}

/// `findings` describes the tree before the rewrite; the rewritten tree hides inserted commits.
#[test]
fn findings_take_the_tree_before_the_rewrite() {
    let source = &corpus("lint.json")[1];
    let source = text(source, "grammar");
    let before = auto_commit::parse_before_auto_commit(source).unwrap();
    let findings: Vec<_> = before
        .grammars
        .iter()
        .flat_map(auto_commit::findings)
        .collect();
    assert_eq!(findings, auto_commit::lint(source).unwrap());
    assert_eq!(findings.iter().filter(|f| f.inserted).count(), 2);
    let after = parse(source).unwrap();
    let rewritten: Vec<_> = after
        .grammars
        .iter()
        .flat_map(auto_commit::findings)
        .collect();
    assert_eq!(
        rewritten.iter().filter(|f| f.rule == "Statement").count(),
        0
    );
}
