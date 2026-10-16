use unlaxer_runtime::{
    token_trace, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Span,
};

fn decode(hex: &str) -> String {
    if hex == "~" {
        return String::new();
    }
    hex.split_whitespace()
        .map(|value| char::from_u32(u32::from_str_radix(value, 16).unwrap()).unwrap())
        .collect()
}

#[test]
fn shared_original_text_spans_rollback_lookahead_and_first_sets() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../spec-corpus/case-insensitive/runtime.tsv")
                .lines()
                .filter(|row| !row.starts_with('#'))
            {
                let fields: Vec<_> = row.split('\t').collect();
                let word = Box::leak(decode(fields[1]).into_boxed_str());
                let input = decode(fields[2]);
                let ci = Expr::case_insensitive(word).capture("item");
                let parser = match fields[3] {
                    "ATOM" => ci,
                    "CHAIN" => Expr::Sequence(vec![ci]),
                    "FAIL" => ci.then(Expr::literal("!")),
                    "RETRY" => Expr::choice([ci.clone().then(Expr::literal("!")), ci]),
                    "AHEAD_CI" => ci.clone().ahead().then(ci),
                    "PREFIX" => Expr::Any.then(ci),
                    _ => panic!("unknown operation"),
                };
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo)
                        .with_diagnostics(diagnostics)
                        .with_token_trace(token_trace::Limits::default()),
                );
                let result = context.parse(&parser);
                let expected = fields[5].parse().unwrap();
                assert_eq!(
                    result.is_ok(),
                    fields[4] == "true",
                    "{} {memo:?} {diagnostics:?}",
                    fields[0]
                );
                assert_eq!(context.position(), expected, "{}", fields[0]);
                assert_eq!(context.matched_position(), expected, "{}", fields[0]);
                let trace = context.token_trace().unwrap().unwrap();
                if let Ok(parsed) = result {
                    assert_eq!(
                        parsed.span,
                        Span {
                            start: 0,
                            end: expected
                        }
                    );
                    assert_eq!(
                        context.text(parsed.span),
                        Some(input.chars().take(expected).collect::<String>().as_str())
                    );
                    assert!(context.captured("item").is_some());
                    assert!(trace
                        .iter()
                        .any(|leaf| leaf.label == "UnicodeIgnoreCaseWordParser"));
                } else {
                    assert!(context.captured("item").is_none());
                    assert!(trace.is_empty());
                    if !input.is_empty() {
                        assert!(context.parse(&Expr::Any).is_ok());
                    }
                }
            }
        }
    }
}

#[test]
fn long_case_insensitive_word_and_repeated_prefixes() {
    let input = "K𐐨".repeat(10_000);
    let word = Box::leak("k𐐀".repeat(10_000).into_boxed_str());
    let mut context = ParseContext::new(&input);
    assert!(context.parse(&Expr::case_insensitive(word)).is_ok());
    assert_eq!(context.position(), 20_000);
    let mut context = ParseContext::new(&input);
    assert!(context
        .parse(&Expr::case_insensitive("k𐐀").one_or_more())
        .is_ok());
    assert_eq!(context.position(), 20_000);
}

#[test]
fn failed_case_insensitive_rules_replay_diagnostics_from_memo() {
    use unlaxer_runtime::{share_grammar, Rule};
    let grammar = share_grammar(vec![
        Rule {
            name: "root",
            expression: Expr::Sequence(vec![
                Expr::Lookahead {
                    child: Box::new(Expr::Rule(1)),
                    positive: false,
                },
                Expr::Rule(1),
            ]),
        },
        Rule {
            name: "case-word",
            expression: Expr::case_insensitive("k").then(Expr::case_insensitive("x")),
        },
    ]);
    for diagnostics in [
        Diagnostics::Auto,
        Diagnostics::Detailed,
        Diagnostics::DetailedOnFailure,
    ] {
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            let mut context = ParseContext::with_options(
                "Kz",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            );
            let error = context
                .parse_shared_grammar(&grammar, 0, false)
                .unwrap_err();
            // A low-level deferred context intentionally carries no detailed diagnostic.
            if diagnostics == Diagnostics::DetailedOnFailure {
                assert_eq!(error.offset, 0);
                assert!(error.expected.is_empty());
            } else {
                assert_eq!(error.offset, 1);
                assert_eq!(error.expected, vec!["x"]);
            }
            let diagnostic = unlaxer_runtime::parse_detailed_with_options(
                &grammar,
                0,
                false,
                "Kz",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            )
            .unwrap_err();
            assert_eq!(diagnostic.farthest.offset, 1);
            assert_eq!(diagnostic.farthest.expected, vec!["x"]);
            assert_eq!(context.position(), 0);
            if memo == Memoization::Off {
                assert_eq!(context.memoized_failure_hits(), 0);
            } else {
                assert!(context.memoized_failure_hits() > 0);
            }
        }
    }
}
