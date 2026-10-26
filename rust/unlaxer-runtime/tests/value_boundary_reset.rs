#[path = "support/value_boundary_reset.rs"]
#[allow(dead_code)]
mod oracle;

use unlaxer_runtime::*;

#[test]
fn projected_values_preserve_failed_chain_policy() {
    let helper = || {
        Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("z"), Expr::Rule(3)]).value_boundary()
    };
    oracle::check(
        "value-boundary.ubnf",
        vec![
            Rule {
                name: "Root",
                expression: Expr::Rule(1),
            },
            Rule {
                name: "Helper",
                expression: helper(),
            },
            Rule {
                name: "Wrapped",
                expression: helper().trivia_scope(false),
            },
            Rule {
                name: "Mixed",
                expression: Expr::Choice(vec![Expr::Literal("l"), Expr::Literal("t")])
                    .value_boundary(),
            },
        ],
    );
    oracle::check(
        "text-value.ubnf",
        vec![Rule {
            name: "Root",
            expression: Expr::Choice(vec![
                Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("z")]).text_value(),
                Expr::Number.value_boundary(),
            ]),
        }],
    );
    oracle::check(
        "text-value-choice.ubnf",
        vec![Rule {
            name: "Root",
            expression: Expr::Choice(vec![
                Expr::Choice(vec![
                    Expr::Sequence(vec![Expr::Literal("a"), Expr::Literal("z")]),
                    Expr::Literal("q"),
                ])
                .text_value(),
                Expr::Number.value_boundary(),
            ]),
        }],
    );
}
