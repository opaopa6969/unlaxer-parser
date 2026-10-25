#[path = "support/chain_reset_after_failure.rs"]
mod oracle;

#[test]
fn failed_chain_leaves_owner_policy_for_following_parsers() {
    oracle::check(vec![unlaxer_runtime::Rule {
        name: "Root",
        expression: unlaxer_runtime::Expr::Sequence(vec![
            unlaxer_runtime::Expr::Literal("a"),
            unlaxer_runtime::Expr::Literal("z"),
        ]),
    }]);
}

#[test]
fn lexical_policy_scopes_do_not_add_an_owner_transaction() {
    use unlaxer_runtime::{lexical::*, lexing::Terminal, *};
    let sequence = Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("z")]);
    let literal = |text| LexicalExpression {
        op: Op::LITERAL,
        text,
        min: 0,
        max: 0,
        children: vec![],
    };
    for expression in [
        sequence.clone().trivia_scope(false),
        sequence.clone().lexical_trivia_scope(literal(" ")),
        sequence.lexical_context_scope(vec![
            Terminal {
                name: "a",
                literal: true,
                expression: literal("a"),
            },
            Terminal {
                name: "z",
                literal: true,
                expression: literal("z"),
            },
        ]),
    ] {
        oracle::check(vec![Rule {
            name: "Root",
            expression,
        }]);
    }
}

#[test]
fn rule_effects_and_back_references_do_not_add_an_owner_transaction() {
    use unlaxer_runtime::*;
    let sequence = Expr::Sequence(vec![
        Expr::Capture("tag", Box::new(Expr::Literal("a"))),
        Expr::Literal("z"),
    ]);
    oracle::check(vec![Rule {
        name: "Root",
        expression: sequence.clone().rule_effects(RuleEffects {
            scope_mode: Some(ScopeMode::Dynamic),
            declares: Some(Declaration {
                symbol_capture: "tag",
                description: None,
            }),
            backref: None,
        }),
    }]);
    // CaptureEquality is not memo-safe, so it never produces a failure hit.
    oracle::check_with_failure_hits(
        vec![Rule {
            name: "Root",
            expression: Expr::compare_captures("tag", sequence),
        }],
        0,
    );
}

/// Java `Commit` / `Label` parse their child without beginning a transaction.
#[test]
fn raising_wrappers_keep_the_failed_chain_policy() {
    use unlaxer_runtime::*;
    let chain = || Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("z")]);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for parser in [chain().commit(), chain().label("L")] {
            let mut context =
                ParseContext::with_options("ab", ParseOptions::with_memoization(memo));
            Expr::JavaLookahead {
                pattern: "a",
                positive: true,
            }
            .parse(&mut context)
            .unwrap();
            context.set_reset_matched_with_consumed(true);
            assert!(parser.parse(&mut context).is_err(), "{parser:?}");
            assert!(!context.reset_matched_with_consumed(), "{parser:?}");
            Expr::NonOrdered(vec![Expr::JavaLookahead {
                pattern: "b",
                positive: true,
            }])
            .parse(&mut context)
            .unwrap();
            assert_eq!((context.position(), context.matched_position()), (0, 2));
        }
    }
}
