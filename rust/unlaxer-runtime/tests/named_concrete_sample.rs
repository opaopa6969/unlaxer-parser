use unlaxer_runtime::{
    share_grammar, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Rule,
};

#[test]
fn named_concrete_sample_matches_shared_prefix_contract() {
    let grammar = share_grammar(vec![
        Rule {
            name: "SimpleExpression",
            expression: Expr::sequence(vec![Expr::Rule(1), Expr::Rule(2).repeat_java(0, None)]),
        },
        Rule {
            name: "NumberParser",
            expression: Expr::CharRange('0', '9').repeat_java(1, None),
        },
        Rule {
            name: "OperatorAndOperandParser",
            expression: Expr::sequence(vec![
                Expr::choice(vec![
                    Expr::literal("+"),
                    Expr::literal("-"),
                    Expr::literal("*"),
                    Expr::literal("/"),
                ]),
                Expr::Rule(1),
            ]),
        },
    ]);
    let mut cases = 0;
    for line in include_str!("../../../conformance/samples/named-concrete.tsv").lines() {
        if line.starts_with('#') || line.is_empty() {
            continue;
        }
        let row = line.split('\t').collect::<Vec<_>>();
        assert_eq!(row.len(), 4);
        cases += 1;
        for memoization in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let mut context = ParseContext::with_options(
                    row[0],
                    ParseOptions {
                        memoization,
                        diagnostics,
                        ..ParseOptions::default()
                    },
                );
                let parsed = context.parse_shared_grammar(&grammar, 0, false);
                assert_eq!(parsed.is_ok(), row[1] == "true", "{line}");
                assert_eq!(
                    context.position(),
                    row[2].parse::<usize>().unwrap(),
                    "{line}"
                );
                assert_eq!(
                    context.matched_position(),
                    row[3].parse::<usize>().unwrap(),
                    "{line}"
                );
                if let Ok(parsed) = parsed {
                    assert_eq!(
                        context.text(parsed.span).unwrap(),
                        &row[0][..context.position()]
                    );
                }
            }
        }
    }
    assert_eq!(cases, 8);
}
