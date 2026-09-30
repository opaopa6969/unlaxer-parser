use unlaxer_runtime::{
    parse_detailed_with_options, Diagnostics, Expr, Memoization, ParseContext, ParseOptions,
    Predictor, Rule,
};

fn failed(
    expression: Expr,
    input: &str,
    options: ParseOptions,
) -> unlaxer_runtime::ParseDiagnostic {
    parse_detailed_with_options(
        &[Rule {
            name: "root",
            expression,
        }],
        0,
        false,
        input,
        options,
    )
    .unwrap_err()
}

#[test]
fn error_expected_never_consumes_and_blank_messages_keep_failure_position() {
    for (message, expected) in [
        ("", None),
        (" \t\n", None),
        ("\u{001c}", None),
        ("\u{2003}", None),
        ("\u{3000}", None),
        ("\u{0085}", Some("\u{0085}")),
        ("\u{00a0}", Some("\u{00a0}")),
        ("\u{2007}", Some("\u{2007}")),
        ("\u{202f}", Some("\u{202f}")),
        ("\u{feff}", Some("\u{feff}")),
        ("  required 😀  ", Some("  required 😀  ")),
        ("''", Some("''")),
    ] {
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            let detailed =
                ParseOptions::with_memoization(memo).with_diagnostics(Diagnostics::Detailed);
            let expression = Expr::literal("😀").then(Expr::Error(message));
            let baseline = failed(expression.clone(), "😀x", detailed);
            assert_eq!(baseline.farthest.offset, 1, "{message:?}");
            assert_eq!(
                baseline.farthest.expected,
                expected.into_iter().map(str::to_owned).collect::<Vec<_>>(),
                "{message:?}"
            );
            for diagnostics in [Diagnostics::Auto, Diagnostics::DetailedOnFailure] {
                let actual = failed(
                    expression.clone(),
                    "😀x",
                    detailed.with_diagnostics(diagnostics),
                );
                assert_eq!(actual, baseline, "{message:?}, {diagnostics:?}");
            }
        }
        let mut context = ParseContext::new("😀x");
        context.set_state("value", 7u32);
        assert!(context
            .parse(&Expr::literal("😀").then(Expr::Error(message)))
            .is_err());
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        assert_eq!(context.state::<u32>("value"), Some(&7));
    }
}

#[test]
fn error_expected_in_choice_retains_hint_when_all_branches_fail() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let result = failed(
                Expr::choice([Expr::literal("a"), Expr::Error("need a")]),
                "x",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            );
            assert_eq!(result.farthest.offset, 0);
            assert_eq!(result.farthest.expected, ["a", "need a"]);
        }
    }
    let result = parse_detailed_with_options(
        &[Rule {
            name: "root",
            expression: Expr::choice([Expr::Error("no"), Expr::literal("ok")]),
        }],
        0,
        false,
        "ok",
        ParseOptions::default(),
    );
    assert!(result.is_ok());
}

#[test]
fn predictive_choice_retains_error_hint_after_detailed_retry() {
    let alternatives = vec![Expr::literal("a"), Expr::Error("need a")];
    let predictive = Expr::PredictiveChoice {
        alternatives: alternatives.clone(),
        predictors: vec![Predictor::Literal("a"), Predictor::Any],
    };
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            assert_eq!(
                failed(predictive.clone(), "x", options),
                failed(Expr::Choice(alternatives.clone()), "x", options),
                "{memo:?} {diagnostics:?}"
            );
        }
    }
}

#[test]
fn error_expected_rolls_back_changed_state_capture_and_scope() {
    let parser = Expr::Custom(|context| {
        context.set_state("value", 99u32);
        context.scopes_mut().declare("temporary", 0);
        context.parse(&Expr::literal("a").capture("item"))
    })
    .then(Expr::Error("stop"));
    let mut context = ParseContext::new("a");
    context.set_state("value", 7u32);
    assert!(context.parse(&parser).is_err());
    assert_eq!(context.state::<u32>("value"), Some(&7));
    assert!(context.captured("item").is_none());
    assert!(context.scopes().all_declarations().is_empty());
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    assert_eq!(context.failure().expected, ["stop"]);
}

#[test]
fn later_blank_failure_supersedes_earlier_hint_with_memo_and_deferred_diagnostics() {
    let expression = Expr::choice([
        Expr::literal("x").then(Expr::Error("earlier")),
        Expr::Rule(1),
        Expr::Rule(1),
    ]);
    let rules = [
        Rule {
            name: "root",
            expression,
        },
        Rule {
            name: "blank",
            expression: Expr::literal("xy").then(Expr::Error("  \t")),
        },
    ];
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let error = parse_detailed_with_options(
                &rules,
                0,
                false,
                "xy",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            )
            .unwrap_err();
            assert_eq!(error.farthest.offset, 2, "{memo:?} {diagnostics:?}");
            assert!(
                error.farthest.expected.is_empty(),
                "{memo:?} {diagnostics:?}"
            );
        }
    }
}
