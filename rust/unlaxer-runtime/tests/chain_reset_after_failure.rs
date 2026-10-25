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
