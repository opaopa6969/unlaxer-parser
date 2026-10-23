use serde_json::json;
use unlaxer_lsp::{Backend, GrammarBackend};
use unlaxer_runtime::source::Snapshot;
use unlaxer_runtime::{parse_detailed_shared, Expr, Rule, Span};

fn backend(expression: Expr) -> GrammarBackend {
    GrammarBackend {
        name: "DiagnosticFixture".into(),
        entry: "Root".into(),
        grammar: vec![Rule {
            name: "Root",
            expression,
        }]
        .into(),
        root: 0,
        whitespace: false,
        keywords: vec![],
    }
}

#[test]
fn lsp_uses_farther_failure_without_changing_public_primary_diagnostic() {
    let mut backend = backend(Expr::Choice(vec![
        Expr::Sequence(vec![
            Expr::Literal("😀"),
            Expr::Literal("!"),
            Expr::Literal("?"),
        ]),
        Expr::Literal("😀"),
    ]));
    let snapshot = Snapshot::new("file:///fixture", 1, "😀!x").unwrap();
    let error = parse_detailed_shared(&backend.grammar, 0, false, &snapshot.text).unwrap_err();
    assert_eq!(error.kind, "trailing_input");
    assert_eq!(error.offset, 1);
    assert_eq!(error.expected, ["end of input"]);
    assert_eq!(error.farthest.offset, 2);
    assert_eq!(error.farthest.expected, ["?"]);
    let diagnostics = backend.validate(&snapshot);
    assert_eq!(diagnostics.len(), 1);
    assert_eq!(diagnostics[0].span, Span { start: 2, end: 3 });
    assert_eq!(diagnostics[0].data["offset"], 3);
    assert_eq!(diagnostics[0].data["expectedTokens"], json!(["?"]));
    assert_eq!(diagnostics[0].message, "Expected ?");
}

#[test]
fn no_farther_failure_keeps_trailing_input_and_syntax_hints() {
    let mut backend = backend(Expr::Literal("a"));
    for (input, span, expected) in [
        ("ab", Span { start: 1, end: 2 }, "end of input"),
        ("b", Span { start: 0, end: 1 }, "a"),
        ("", Span { start: 0, end: 0 }, "a"),
    ] {
        let diagnostics = backend.validate(&Snapshot::new("file:///fixture", 1, input).unwrap());
        assert_eq!(diagnostics[0].span, span);
        assert_eq!(diagnostics[0].data["expectedTokens"], json!([expected]));
    }
    assert!(backend
        .validate(&Snapshot::new("file:///fixture", 1, "a").unwrap())
        .is_empty());
}

#[test]
fn nesting_resource_failure_keeps_its_primary_offset_and_hints() {
    let mut backend = backend(Expr::Rule(0));
    let snapshot = Snapshot::new("file:///fixture", 1, "x").unwrap();
    let error = parse_detailed_shared(&backend.grammar, 0, false, &snapshot.text).unwrap_err();
    assert_eq!(error.kind, "resource_limit");
    let diagnostic = backend.validate(&snapshot).remove(0);
    assert_eq!(diagnostic.span.start, error.offset);
    assert_eq!(diagnostic.data["expectedTokens"], json!(error.expected));
}

#[test]
fn empty_expected_candidate_is_readable_in_the_message() {
    // spec-corpus/empty-literal Labeled: CATCH('a' LABEL(L, ''), 'a' 'b', L) | 'c' LABEL(M, '').
    let mut backend = backend(
        Expr::literal("a")
            .then(Expr::JavaEmptyWord.label("L"))
            .catch_labels(Expr::literal("a").then(Expr::literal("b")), ["L"])
            .or(Expr::literal("c").then(Expr::JavaEmptyWord.label("M"))),
    );
    let snapshot = Snapshot::new("file:///fixture", 1, "a😀").unwrap();
    let diagnostics = backend.validate(&snapshot);
    assert_eq!(diagnostics.len(), 1);
    // The range, kind and expectedTokens keep the contract value "" (#750 changes only the text).
    assert_eq!(diagnostics[0].span, Span { start: 1, end: 2 });
    assert_eq!(diagnostics[0].data["code"], "ULX-PARSE-001");
    assert_eq!(diagnostics[0].data["kind"], "syntax");
    assert_eq!(diagnostics[0].data["offset"], 1);
    assert_eq!(diagnostics[0].data["expectedTokens"], json!(["", "b"]));
    assert_eq!(diagnostics[0].message, "Expected '' or b");
    // A raised label keeps its declared message as is.
    let raised = backend.validate(&Snapshot::new("file:///fixture", 1, "c").unwrap());
    assert_eq!(raised[0].message, "M");
}

#[test]
fn empty_and_two_quote_candidates_share_the_message_but_not_expected_tokens() {
    // Known limit (#750): '' (WordParser("")) and the two-character literal "''" read the same.
    let mut backend = backend(Expr::JavaEmptyWord.or(Expr::literal("''")));
    let diagnostics = backend.validate(&Snapshot::new("file:///fixture", 1, "x").unwrap());
    assert_eq!(diagnostics.len(), 1);
    assert_eq!(diagnostics[0].message, "Expected '' or ''");
    // The structured value distinguishes them.
    assert_eq!(diagnostics[0].data["expectedTokens"], json!(["", "''"]));
}
