use unlaxer_runtime::{
    parse, parse_detailed_with_options, Diagnostics, Expr, Memoization, ParseContext, ParseOptions,
    Rule, Severity,
};

#[test]
fn compares_only_same_named_completed_captures_with_unicode_trimmed_positions() {
    let expression = Expr::compare_captures(
        "part",
        Expr::literal(" \t😀 ")
            .capture("part")
            .then(Expr::literal("🎯").capture("other"))
            .then(Expr::literal("\r\n😀\t").capture("part"))
            .then(Expr::literal("  x ").capture("part")),
    );
    let mut context = ParseContext::new(" \t😀 🎯\r\n😀\t  x ");
    context.parse(&expression).unwrap();
    assert_eq!(context.capture_spans("part").len(), 3);
    assert_eq!(context.capture_spans("other").len(), 1);
    let diagnostics = context.scopes().diagnostics();
    assert_eq!(diagnostics.len(), 1);
    assert_eq!(
        diagnostics[0].message,
        "back-reference mismatch: expected '😀' but got 'x'"
    );
    assert_eq!((diagnostics[0].offset, diagnostics[0].length), (11, 1));
    assert_eq!(diagnostics[0].severity, Severity::Error);
}

#[test]
fn empty_and_single_captures_are_not_dropped() {
    for expression in [
        Expr::compare_captures("part", Expr::literal("x")),
        Expr::compare_captures("part", Expr::literal("x").capture("part")),
        Expr::compare_captures(
            "part",
            Expr::literal(" ")
                .capture("part")
                .then(Expr::literal("\t").capture("part")),
        ),
    ] {
        let input = match &expression {
            Expr::CaptureEquality { child, .. } => match child.as_ref() {
                Expr::Sequence(_) => " \t",
                _ => "x",
            },
            _ => unreachable!(),
        };
        let mut context = ParseContext::new(input);
        context.parse(&expression).unwrap();
        assert!(context.scopes().diagnostics().is_empty());
    }
    let expression = Expr::compare_captures(
        "part",
        Expr::literal(" ")
            .capture("part")
            .then(Expr::literal("z").capture("part")),
    );
    let mut context = ParseContext::new(" z");
    context.parse(&expression).unwrap();
    assert_eq!(
        context.scopes().diagnostics()[0].message,
        "back-reference mismatch: expected '' but got 'z'"
    );
    assert_eq!(
        (
            context.scopes().diagnostics()[0].offset,
            context.scopes().diagnostics()[0].length
        ),
        (1, 1)
    );
}

#[test]
fn callee_captures_do_not_join_the_callers_comparison() {
    let rules = [
        Rule {
            name: "root",
            expression: Expr::compare_captures(
                "part",
                Expr::Rule(1)
                    .then(Expr::literal("a").capture("part"))
                    .then(Expr::literal("b").capture("part")),
            ),
        },
        Rule {
            name: "child",
            expression: Expr::literal("hidden").capture("part"),
        },
    ];
    let tree = parse(&rules, 0, false, "hiddenab").unwrap();
    assert_eq!(tree.scopes().diagnostics().len(), 1);
    assert_eq!(
        tree.scopes().diagnostics()[0].message,
        "back-reference mismatch: expected 'a' but got 'b'"
    );
    assert_eq!(tree.scopes().diagnostics()[0].offset, 7);
}

#[test]
fn failed_branch_and_parent_failure_roll_back_semantic_diagnostics() {
    let comparison = Expr::compare_captures(
        "part",
        Expr::literal("a")
            .capture("part")
            .then(Expr::literal("b").capture("part")),
    );
    let mut branch = ParseContext::new("ab");
    branch
        .parse(&Expr::choice([
            comparison.clone().then(Expr::literal("!")),
            Expr::literal("ab"),
        ]))
        .unwrap();
    assert!(branch.scopes().diagnostics().is_empty());
    assert!(branch.capture_spans("part").is_empty());
    let mut parent = ParseContext::new("ab");
    assert!(parent.parse(&comparison.then(Expr::literal("!"))).is_err());
    assert!(parent.scopes().diagnostics().is_empty());
    assert_eq!((parent.position(), parent.matched_position()), (0, 0));
}

#[test]
fn public_lookahead_rolls_back_comparison_diagnostics_and_captures() {
    let comparison = Expr::compare_captures(
        "part",
        Expr::literal("a")
            .capture("part")
            .then(Expr::literal("b").capture("part")),
    );
    for (parser, succeeds) in [
        (comparison.clone().ahead(), true),
        (comparison.clone().not_ahead(), false),
        (comparison.then(Expr::literal("!")).not_ahead(), true),
    ] {
        let mut context = ParseContext::new("ab");
        assert_eq!(context.parse(&parser).is_ok(), succeeds);
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        assert!(context.scopes().diagnostics().is_empty());
        assert!(context.capture_spans("part").is_empty());
    }
}

#[test]
fn semantic_diagnostics_survive_auto_detailed_deferred_and_safe_memo_modes() {
    let rules = [Rule {
        name: "root",
        expression: Expr::compare_captures(
            "part",
            Expr::literal("a")
                .capture("part")
                .then(Expr::literal("b").capture("part")),
        ),
    }];
    for memoization in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let tree = parse_detailed_with_options(
                &rules,
                0,
                false,
                "ab",
                ParseOptions::with_memoization(memoization).with_diagnostics(diagnostics),
            )
            .unwrap();
            assert_eq!(tree.scopes().diagnostics().len(), 1);
            assert_eq!(tree.scopes().diagnostics()[0].severity, Severity::Error);
        }
    }
}
