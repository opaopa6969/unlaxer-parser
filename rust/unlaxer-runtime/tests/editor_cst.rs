use unlaxer_runtime::{
    editor_cst::{self, Options, Reason, Status},
    Expr, Rule, Span,
};

#[test]
fn inserted_scalar_is_metadata_and_cannot_become_original_text() {
    let grammar = vec![Rule {
        name: "Root",
        expression: Expr::Capture("value", Box::new(Expr::Literal("😀a"))),
    }]
    .into();
    let parsed = editor_cst::parse(&grammar, 0, false, "😀", &["a"], Options::default()).unwrap();
    assert_eq!(parsed.status(), Status::Partial);
    assert_eq!(parsed.reason(), Reason::Repaired);
    let capture = &parsed.nodes()[0].captures[0];
    assert_eq!(capture.span, Span { start: 0, end: 1 });
    assert!(capture.synthetic);
    assert_eq!(capture.text, "😀");
    assert_eq!(parsed.source(), "😀");
    assert_eq!(parsed.defects()[0].span, Span { start: 1, end: 1 });
    assert_eq!(parsed.defects()[0].candidate_rules, ["Root"]);
    assert!(parsed.canonical_json().contains("\"text\":\"😀\""));
}
#[test]
fn syntax_failure_and_bounded_search_have_distinct_reasons() {
    let grammar = vec![Rule {
        name: "Root",
        expression: Expr::Literal("hello"),
    }]
    .into();
    assert_eq!(
        editor_cst::parse(&grammar, 0, false, "h@", &["o"], Options::default())
            .unwrap()
            .reason(),
        Reason::Syntax
    );
    assert_eq!(
        editor_cst::parse(
            &grammar,
            0,
            false,
            "hell",
            &["o"],
            Options {
                max_fragments: 4,
                max_attempts: 0
            }
        )
        .unwrap()
        .reason(),
        Reason::Limit
    );
    assert_eq!(
        editor_cst::parse(&grammar, 0, false, "", &[], Options::default())
            .unwrap()
            .reason(),
        Reason::NoCompletion
    );
    assert_eq!(
        editor_cst::parse(&grammar, 0, false, "hello", &[], Options::default())
            .unwrap()
            .status(),
        Status::Complete
    );
    assert!(editor_cst::parse(
        &grammar,
        0,
        false,
        "hell",
        &["o"],
        Options {
            max_fragments: 9,
            max_attempts: 1
        }
    )
    .is_err());
}
#[test]
fn callbacks_without_replay_contract_are_run_once() {
    use std::sync::atomic::{AtomicUsize, Ordering};
    static INVOCATIONS: AtomicUsize = AtomicUsize::new(0);
    fn callback(context: &mut unlaxer_runtime::ParseContext<'_>) -> unlaxer_runtime::ParseResult {
        INVOCATIONS.fetch_add(1, Ordering::SeqCst);
        context.advance(4);
        Err(context.error("hello"))
    }
    let grammar = vec![Rule {
        name: "Root",
        expression: Expr::Custom(callback),
    }]
    .into();
    assert_eq!(
        editor_cst::parse(&grammar, 0, false, "hell", &["o"], Options::default())
            .unwrap()
            .reason(),
        Reason::Unsafe
    );
    assert_eq!(INVOCATIONS.load(Ordering::SeqCst), 1);
}

#[test]
fn coincident_captures_have_deterministic_name_order() {
    let grammar = vec![Rule {
        name: "Root",
        expression: Expr::Capture(
            "a",
            Box::new(Expr::Capture("z", Box::new(Expr::Literal("😀")))),
        ),
    }]
    .into();
    let parsed = editor_cst::parse(&grammar, 0, false, "😀", &[], Options::default()).unwrap();
    let captures = &parsed.nodes()[0].captures;
    assert_eq!(
        captures
            .iter()
            .map(|capture| capture.name.as_str())
            .collect::<Vec<_>>(),
        ["a", "z"]
    );
    for capture in captures {
        assert_eq!(capture.span, Span { start: 0, end: 1 });
        assert_eq!(capture.text, "😀");
        assert!(!capture.synthetic);
    }
}
