#[path = "support/name_scope_token_mode_reset.rs"]
#[allow(dead_code)]
mod oracle;

use unlaxer_runtime::{names::Requirement, *};

fn ahead() -> Expr {
    Expr::JavaLookahead {
        pattern: "a",
        positive: true,
    }
}

fn scope(child: Expr) -> Expr {
    Expr::NameResolutionScope {
        child: Box::new(child),
        requirements: vec![Requirement::new("names", "v1").unwrap()],
    }
}

#[test]
fn name_frames_restore_owner_policy_on_success_and_failure() {
    let predicate = Expr::NamePredicate {
        child: Box::new(Expr::Sequence(vec![
            Expr::Capture("name", Box::new(Expr::Literal("a"))),
            ahead(),
        ])),
        snapshot: "names",
        version: "v1",
        capture: "name",
        kind: "type",
    };
    for (file, expression) in [
        (
            "name-scope.ubnf",
            scope(Expr::Sequence(vec![Expr::Literal("a"), ahead()])),
        ),
        ("name-predicate.ubnf", scope(predicate)),
        (
            "name-predicate-lookahead.ubnf",
            scope(Expr::NamePredicate {
                child: Box::new(Expr::Sequence(vec![
                    ahead(),
                    Expr::Capture("name", Box::new(Expr::Literal("a"))),
                ])),
                snapshot: "names",
                version: "v1",
                capture: "name",
                kind: "type",
            }),
        ),
        (
            "name-scope-lookahead.ubnf",
            scope(Expr::Sequence(vec![ahead()])),
        ),
    ] {
        oracle::check(
            file,
            vec![Rule {
                name: "Root",
                expression,
            }],
        );
    }
}

#[test]
fn classic_rule_and_transparent_wrappers_retain_failed_chain_policy() {
    let chain = Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("z")]);
    for (file, expression) in [
        ("grammar.ubnf", chain.clone()),
        ("local-trivia.ubnf", chain.clone().trivia_scope(false)),
        (
            "scope-effects.ubnf",
            chain.clone().rule_effects(RuleEffects {
                scope_mode: Some(ScopeMode::Dynamic),
                declares: None,
                backref: None,
            }),
        ),
        ("backref.ubnf", Expr::compare_captures("tag", chain)),
    ] {
        oracle::check(
            file,
            vec![Rule {
                name: "Root",
                expression,
            }],
        );
    }
}

/// Java `DoConsumePropagationStopper` / `InvertMatchPropagationStopper` parse their
/// child without beginning a transaction, so a failed Chain leaves the owner at false.
/// Java measured every combination below as reset=false, CP0/1 (three calls), then
/// follower success CP0/2; `NameScopeTokenModeResetConformanceTest` checks the same.
#[test]
fn classic_propagation_stoppers_retain_failed_chain_policy() {
    let chain = || Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("z")]);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for stopper in [chain().stop_consume(), chain().stop_invert()] {
            for kind in ["consumed", "matchOnly"] {
                for initial in [true, false] {
                    for entry in ["body", "rule"] {
                        let label = format!("{stopper:?}/{kind}/{initial}/{memo:?}/{entry}");
                        let mut context =
                            ParseContext::with_options("ab", ParseOptions::with_memoization(memo));
                        context.set_state(
                            "stopper",
                            (label.clone(), stopper.clone(), kind, initial, entry),
                        );
                        let grammar = vec![
                            Rule {
                                name: "Root",
                                expression: stopper.clone(),
                            },
                            Rule {
                                name: "observer",
                                expression: Expr::Custom(observe_stopper),
                            },
                        ];
                        context
                            .parse_shared_grammar(&share_grammar(grammar), 1, false)
                            .unwrap();
                        assert_eq!(
                            context.state::<String>("observed").unwrap(),
                            "false\tfalse\t0\t1\ttrue\t0\t2",
                            "{label}"
                        );
                    }
                }
            }
        }
    }
}

fn observe_stopper(context: &mut ParseContext<'_>) -> ParseResult {
    use unlaxer_runtime::token::TokenKind;
    let (label, stopper, kind, initial, entry) = context
        .state::<(String, Expr, &str, bool, &str)>("stopper")
        .unwrap()
        .clone();
    let kind = if kind == "consumed" {
        TokenKind::Consumed
    } else {
        TokenKind::MatchOnly
    };
    ahead().parse(context).unwrap();
    let mut before = None;
    for _ in 0..3 {
        context.set_reset_matched_with_consumed(initial);
        let result = if entry == "rule" {
            context.parse_with_token_mode(&Expr::Rule(0), kind, false)
        } else {
            context.parse_with_token_mode(&stopper, kind, false)
        };
        let observed = format!(
            "{}\t{}\t{}\t{}",
            result.is_ok(),
            context.reset_matched_with_consumed(),
            context.position(),
            context.matched_position()
        );
        if let Some(previous) = &before {
            assert_eq!(
                previous, &observed,
                "{label}: retry must preserve the result"
            );
        }
        before = Some(observed);
    }
    // The rule entry also replays the failure memo that SAFE_FAILURES stored on the miss.
    let hits = if entry == "rule" && context.options().memoization == Memoization::SafeFailures {
        2
    } else {
        0
    };
    assert_eq!(context.memoized_failure_hits(), hits, "{label}");
    let follower = Expr::NonOrdered(vec![Expr::JavaLookahead {
        pattern: "b",
        positive: true,
    }])
    .parse(context)
    .is_ok();
    context.set_state(
        "observed",
        format!(
            "{}\t{}\t{}\t{}",
            before.unwrap(),
            follower,
            context.position(),
            context.matched_position()
        ),
    );
    Ok(ParseMatch::empty(Span {
        start: context.position(),
        end: context.position(),
    }))
}
