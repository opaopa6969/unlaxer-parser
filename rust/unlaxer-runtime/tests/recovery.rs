use unlaxer_runtime::{
    parse_detailed_with_options, Diagnostics, Expr, Memoization, ParseContext, ParseOptions,
    ParseResult, RecoveryMode, Rule, Span, RECOVERY_ERROR_RULE,
};

fn sync(child: Expr, tokens: impl IntoIterator<Item = &'static str>) -> Expr {
    child.recover(
        RecoveryMode::Sync,
        tokens,
        "syntax error: skipped to sync point",
    )
}

#[test]
fn sync_consumes_nearest_and_longest_token_with_scalar_spans() {
    for (input, tokens, expected_span, remaining) in [
        (
            "😀x};tail",
            vec![";", "}"],
            Span { start: 0, end: 3 },
            ";tail",
        ),
        (
            "😀x--tail",
            vec!["-", "--"],
            Span { start: 0, end: 4 },
            "tail",
        ),
        (";tail", vec![";"], Span { start: 0, end: 1 }, "tail"),
        ("x\r\n;tail", vec![";"], Span { start: 0, end: 4 }, "tail"),
    ] {
        let mut context = ParseContext::new(input);
        let parsed = context.parse(&sync(Expr::literal("ok"), tokens)).unwrap();
        assert_eq!(parsed.span, expected_span);
        assert_eq!(context.remaining(), remaining);
        assert_eq!(context.matched_position(), expected_span.end);
        assert_eq!(context.recoveries().len(), 1);
        let event = &context.recoveries()[0];
        assert_eq!(event.span, expected_span);
        assert_eq!(event.message, "syntax error: skipped to sync point");
        assert_eq!(context.node(event.node).unwrap().rule, RECOVERY_ERROR_RULE);
        assert_eq!(context.node(event.node).unwrap().span, expected_span);
        let tree = context.tree(parsed.root_node().unwrap()).unwrap();
        assert_eq!(tree.recoveries(), context.recoveries());
        assert_eq!(
            tree.text(event.span),
            &input[..input.len() - remaining.len()]
        );
    }
}

#[test]
fn before_sync_and_skip_have_distinct_progress_contracts() {
    let mut context = ParseContext::new("ok");
    context.parse(&sync(Expr::literal("ok"), [])).unwrap();
    assert!(context.recoveries().is_empty());
    for (mode, input, tokens, consumed) in [
        (RecoveryMode::BeforeSync, "😀x;tail", vec![";"], 2),
        (RecoveryMode::Skip, "😀x;tail", vec![";"], 2),
        (RecoveryMode::Skip, ";tail", vec![";"], 1),
        (RecoveryMode::Skip, "😀tail", vec![], 1),
        (RecoveryMode::Skip, "😀tail", vec![";"], 5),
    ] {
        let mut context = ParseContext::new(input);
        context
            .parse(&Expr::literal("ok").recover(mode, tokens, "bad"))
            .unwrap();
        assert_eq!(context.position(), consumed, "{mode:?} {input}");
        assert_eq!(context.matched_position(), consumed);
        assert_eq!(context.recoveries()[0].span.end, consumed);
    }
    for (mode, input, tokens) in [
        (RecoveryMode::BeforeSync, ";tail", vec![";"]),
        (RecoveryMode::BeforeSync, "tail", vec![";"]),
        (RecoveryMode::Sync, "tail", vec![";"]),
        (RecoveryMode::Skip, "", vec![]),
        (RecoveryMode::Sync, "", vec![";"]),
        (RecoveryMode::Sync, "x;", vec![""]),
        (RecoveryMode::Sync, "x;", vec![";", ";"]),
        (RecoveryMode::Sync, "x;", vec![]),
        (RecoveryMode::BeforeSync, "x;", vec![]),
    ] {
        let mut context = ParseContext::new(input);
        assert!(context
            .parse(&Expr::literal("ok").recover(mode, tokens, "bad"))
            .is_err());
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        assert!(context.recoveries().is_empty());
    }
}

fn mutating_child(context: &mut ParseContext<'_>) -> ParseResult {
    context.set_state("value", 99u32);
    context.scopes_mut().declare("temporary", 0);
    context.parse(&Expr::literal("a").capture("temporary"))?;
    Err(context.error("valid statement"))
}

#[test]
fn child_failure_rolls_back_every_state_before_recovery() {
    let mut context = ParseContext::new("a😀;tail");
    context.set_state("value", 7u32);
    let parsed = context
        .parse(&sync(Expr::Custom(mutating_child), [";"]))
        .unwrap();
    assert_eq!(parsed.span, (Span { start: 0, end: 3 }));
    assert_eq!(context.state::<u32>("value"), Some(&7));
    assert!(context.scopes().all_declarations().is_empty());
    assert!(context.captured("temporary").is_none());
    assert_eq!(context.recoveries().len(), 1);
    assert_eq!(context.failure().expected, ["valid statement"]);
}

#[test]
fn recovered_child_retains_its_original_failure_history() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        let mut context = ParseContext::with_options(
            "a😀;",
            ParseOptions::with_memoization(memo).with_diagnostics(Diagnostics::Detailed),
        );
        let result = context
            .parse_grammar(
                vec![
                    Rule {
                        name: "root",
                        expression: sync(Expr::Rule(1), [";"]),
                    },
                    Rule {
                        name: "child",
                        expression: Expr::literal("a").then(Expr::Error("statement")),
                    },
                ],
                0,
                false,
            )
            .unwrap();
        assert_eq!(result.span, (Span { start: 0, end: 3 }));
        assert_eq!(context.failure().offset, 1);
        assert_eq!(context.failure().expected, ["statement"]);
        assert_eq!(context.recoveries()[0].span, (Span { start: 0, end: 3 }));
    }
}

#[test]
fn outer_rollback_lookahead_and_longest_loser_remove_recovery_markers() {
    let recovery = sync(Expr::literal("ok"), [";"]);
    let mut context = ParseContext::new("x;tail");
    assert!(context
        .parse(&recovery.clone().then(Expr::literal("!")))
        .is_err());
    assert!(context.recoveries().is_empty());
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    context.parse(&recovery.clone().ahead()).unwrap();
    assert!(context.recoveries().is_empty());
    assert_eq!((context.position(), context.matched_position()), (0, 0));

    let mut context = ParseContext::new("x;tail");
    context
        .parse(&Expr::LongestChoice(vec![
            recovery.clone(),
            Expr::literal("x;tail"),
        ]))
        .unwrap();
    assert!(context.recoveries().is_empty());

    let mut context = ParseContext::new("x;tail");
    let winner = context
        .parse(&Expr::LongestChoice(vec![Expr::literal("x"), recovery]))
        .unwrap();
    assert_eq!(context.recoveries().len(), 1);
    assert_eq!(
        context
            .tree(winner.root_node().unwrap())
            .unwrap()
            .recoveries()
            .len(),
        1
    );
}

#[test]
fn tree_snapshot_only_exposes_recoveries_reachable_from_its_root() {
    let mut context = ParseContext::new("bad;good");
    let first = context.parse(&sync(Expr::literal("ok"), [";"])).unwrap();
    let recovered_tree = context.tree(first.root_node().unwrap()).unwrap();
    assert_eq!(context.recoveries().len(), 1);
    let parsed = context
        .parse_grammar(
            vec![Rule {
                name: "good",
                expression: Expr::literal("good"),
            }],
            0,
            false,
        )
        .unwrap();
    let later_tree = context.tree(parsed.root_node().unwrap()).unwrap();
    assert!(later_tree.recoveries().is_empty());
    assert_eq!(context.recoveries().len(), 1);
    drop(context);
    assert_eq!(recovered_tree.recoveries().len(), 1);
    assert_eq!(
        recovered_tree.text(recovered_tree.recoveries()[0].span),
        "bad;"
    );
}

#[test]
fn recovered_success_is_identical_across_diagnostic_and_memo_policies() {
    let rules = [Rule {
        name: "root",
        expression: Expr::choice([Expr::literal("different"), sync(Expr::literal("ok"), [";"])]),
    }];
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let tree = parse_detailed_with_options(&rules, 0, false, "😀;", options).unwrap();
            assert_eq!(tree.recoveries().len(), 1);
            assert_eq!(tree.recoveries()[0].span, (Span { start: 0, end: 2 }));
            assert_eq!(
                tree.recoveries()[0].message,
                "syntax error: skipped to sync point"
            );
            assert_eq!(
                tree.nodes[tree.recoveries()[0].node].rule,
                RECOVERY_ERROR_RULE
            );
        }
    }
}
