//! A global `@comment` (#712) gives a rule a third trivia policy besides whitespace on / off:
//! skip only `//` comments. A memoized result of a rule depends on the policy it ran under,
//! so a failure or success saved under one policy must not be replayed under the other.
//! Generated grammars fix the policy inside each rule, but the runtime API lets callers wrap
//! the same rule reference in different trivia scopes.
use unlaxer_runtime::{
    parse_detailed_with_options, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Rule,
};

/// `R0` tries `R1` without trivia, then the same `R1` under the line-comment policy.
fn failure_reuse() -> Vec<Rule> {
    vec![
        Rule {
            name: "r0",
            expression: Expr::choice([
                Expr::Rule(1).trivia_scope(false),
                Expr::Rule(1).line_comment_scope(),
            ]),
        },
        Rule {
            name: "r1",
            expression: Expr::sequence([Expr::literal("\nb")]),
        },
    ]
}

/// `R0` first runs `R1` under the line-comment policy (it succeeds, then the branch fails on
/// `X`), then the same `R1` without trivia, where it must fail: `//` is not trivia there.
fn success_reuse() -> Vec<Rule> {
    vec![
        Rule {
            name: "r0",
            expression: Expr::choice([
                Expr::sequence([Expr::Rule(1).line_comment_scope(), Expr::literal("X")]),
                Expr::sequence([Expr::Rule(1).trivia_scope(false), Expr::literal("Y")]),
            ])
            .trivia_scope(false),
        },
        Rule {
            name: "r1",
            expression: Expr::sequence([Expr::literal("\nb")]),
        },
    ]
}

fn outcome(
    rules: &[Rule],
    input: &str,
    memoization: Memoization,
    diagnostics: Diagnostics,
) -> String {
    let options = ParseOptions::with_memoization(memoization).with_diagnostics(diagnostics);
    format!(
        "{:?}",
        parse_detailed_with_options(rules, 0, false, input, options).map(|tree| tree.root)
    )
}

#[test]
fn memo_does_not_replay_results_across_line_comment_policies() {
    for (name, rules, input, accepted) in [
        ("failure", failure_reuse(), "//c\nb", true),
        ("success", success_reuse(), "//c\nbY", false),
    ] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let off = outcome(&rules, input, Memoization::Off, diagnostics);
            let on = outcome(&rules, input, Memoization::SafeFailures, diagnostics);
            assert_eq!(off, on, "{name} {diagnostics:?}");
            assert_eq!(
                accepted,
                off.starts_with("Ok"),
                "{name} {diagnostics:?}: {off}"
            );
        }
        // A context runs once, without the retry of the full-input functions.
        for memoization in [Memoization::Off, Memoization::SafeFailures] {
            let options = ParseOptions::with_memoization(memoization)
                .with_diagnostics(Diagnostics::DetailedOnFailure);
            let mut context = ParseContext::with_options(input, options);
            let matched = context.parse_grammar(rules.clone(), 0, false).is_ok();
            assert_eq!(accepted, matched, "{name} {memoization:?} context");
        }
    }
}
