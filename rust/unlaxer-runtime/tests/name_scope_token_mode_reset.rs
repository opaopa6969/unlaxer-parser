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
