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
