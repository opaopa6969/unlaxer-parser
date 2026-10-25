//! Shared by runtime integration tests and the two generated-host probes.
use super::*;

pub fn rules(shape: &str) -> Vec<Rule> {
    let number = Expr::Rule(1);
    let body = match shape {
        "number" | "bare" => number,
        "not" => number.not_ahead().then(Expr::literal("x")),
        "optional" => number.optional_java(),
        "choice" => Expr::choice([Expr::literal("x"), number]),
        "after-optional" => Expr::literal("x").optional_java().then(number),
        "tail" => number.then(Expr::literal("e").optional_java()),
        "memo" => number
            .clone()
            .ahead()
            .then(number.clone().ahead())
            .then(number.not_ahead()),
        _ => panic!("{shape}"),
    };
    vec![
        Rule {
            name: "Root",
            expression: body,
        },
        Rule {
            name: "Num",
            expression: Expr::Number,
        },
    ]
}

pub fn check(
    grammar: impl Fn(&str) -> Vec<Rule>,
    ordered: bool,
    names: impl Fn(&ParseContext<'_>) -> Vec<String>,
) {
    let rows = include_str!("../../../../conformance/number-token/diagnostics.tsv");
    for row in rows.lines().filter(|row| !row.starts_with('#')) {
        let c: Vec<_> = row.split('\t').collect();
        assert_eq!(c.len(), 9, "{row}");
        let bytes = if c[1] == "~" {
            vec![]
        } else {
            (0..c[1].len())
                .step_by(2)
                .map(|i| u8::from_str_radix(&c[1][i..i + 2], 16).unwrap())
                .collect()
        };
        let input = String::from_utf8(bytes).unwrap();
        let memo = match c[2] {
            "OFF" => Memoization::Off,
            "SAFE_FAILURES" => Memoization::SafeFailures,
            _ => panic!("{row}"),
        };
        for mode in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(mode);
            let mut context = ParseContext::with_options(&input, options);
            let success = if c[0] == "bare" {
                context.parse(&Expr::Number).is_ok()
            } else {
                context.parse_grammar(grammar(c[0]), 0, false).is_ok()
            };
            if c[0] == "memo" && memo == Memoization::SafeFailures {
                assert!(context.memoized_success_hits() > 0, "{row}");
            }
            let failure = context.failure();
            let stack = context
                .rule_stack()
                .map(|stack| {
                    format!(
                        "{}@{}/{}",
                        stack
                            .frames
                            .iter()
                            .map(|f| format!("{}@{}", f.rule, f.start))
                            .collect::<Vec<_>>()
                            .join(">"),
                        stack.offset,
                        stack.omitted
                    )
                })
                .unwrap_or_else(|| "~".into());
            let actual = (
                success,
                context.position(),
                context.matched_position(),
                failure.offset,
                names(&context),
                stack,
            );
            let mut expected_names: Vec<String> = c[7]
                .split(',')
                // Java display hints quote literals; Rust stores their raw text.
                .map(|name| name.trim_matches('\'').to_owned())
                .collect();
            if !ordered {
                expected_names.sort_unstable();
            }
            let deferred = mode == Diagnostics::DetailedOnFailure;
            let expected = (
                c[3].parse().unwrap(),
                c[4].parse().unwrap(),
                c[5].parse().unwrap(),
                if deferred { 0 } else { c[6].parse().unwrap() },
                if deferred { vec![] } else { expected_names },
                if deferred { "~" } else { c[8] }.to_owned(),
            );
            assert_eq!(actual, expected, "{row}/{memo:?}/{mode:?}");
            if !success && c[0] != "bare" {
                // Auto/deferred full-input failures must retry in Detailed, including the stack.
                let rules = std::sync::Arc::from(grammar(c[0]));
                let failure = unlaxer_runtime::parse_detailed_shared_with_rule_stack(
                    &rules, 0, false, &input, options,
                )
                .unwrap_err();
                assert_eq!(
                    failure.diagnostic.offset,
                    c[6].parse::<usize>().unwrap(),
                    "{row}/{mode:?}"
                );
                let mut expected_names: Vec<_> = c[7]
                    .split(',')
                    .map(|name| name.trim_matches('\'').to_owned())
                    .collect();
                expected_names.sort_unstable();
                assert_eq!(
                    failure.diagnostic.farthest.expected, expected_names,
                    "{row}/{mode:?}"
                );
                assert_eq!(
                    failure.rule_stack,
                    context.rule_stack().or_else(|| {
                        let mut detailed = ParseContext::with_options(
                            &input,
                            options.with_diagnostics(Diagnostics::Detailed),
                        );
                        let _ = detailed.parse_grammar(grammar(c[0]), 0, false);
                        detailed.rule_stack()
                    }),
                    "{row}/{mode:?}"
                );
            }
        }
    }
}
