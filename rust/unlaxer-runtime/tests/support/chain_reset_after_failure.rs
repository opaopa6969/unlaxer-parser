use unlaxer_runtime::*;

pub fn check(grammar: Vec<Rule>) {
    check_with_failure_hits(grammar, 2);
}

/// `failure_hits`: SAFE_FAILURES hits for three calls of a rule that the runtime may memoize.
pub fn check_with_failure_hits(grammar: Vec<Rule>, failure_hits: usize) {
    check_boundaries(&grammar);
    check_memo_replay(&grammar, failure_hits);
    let grammar = share_grammar(grammar);
    for row in include_str!("../../../../conformance/chain-reset-after-failure/cases.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
    {
        let c: Vec<_> = row.split('\t').collect();
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                for reset in [false, true] {
                    let mut context = ParseContext::with_options(
                        c[1],
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    );
                    let prefix = if c[2] == "a" { "a" } else { "a😀" };
                    Expr::JavaLookahead {
                        pattern: prefix,
                        positive: true,
                    }
                    .parse(&mut context)
                    .unwrap();
                    context.set_reset_matched_with_consumed(reset);
                    // This generated grammar has no Classic operators: its failure is native.
                    assert!(
                        context.parse_shared_grammar(&grammar, 0, false).is_err(),
                        "{row}"
                    );
                    let follower = match c[3] {
                        "lookahead" => Expr::JavaLookahead {
                            pattern: "b",
                            positive: true,
                        },
                        "negative" => Expr::JavaLookahead {
                            pattern: "a",
                            positive: false,
                        },
                        "until" => Expr::JavaUntil("b"),
                        "empty" => Expr::JavaEmpty,
                        "diagnostic" => Expr::NonOrdered(vec![
                            Expr::JavaLookahead {
                                pattern: "b",
                                positive: true,
                            },
                            Expr::JavaLookahead {
                                pattern: "x",
                                positive: true,
                            },
                        ]),
                        _ => panic!("{row}"),
                    };
                    // NonOrdered opens a Java transaction and reads the owner's reset flag.
                    let result = Expr::NonOrdered(vec![follower]).parse(&mut context);
                    assert_eq!(
                        (
                            result.is_ok(),
                            context.position(),
                            context.matched_position(),
                            context.failure().offset
                        ),
                        (
                            c[4].parse().unwrap(),
                            c[5].parse().unwrap(),
                            c[6].parse().unwrap(),
                            if diagnostics == Diagnostics::DetailedOnFailure {
                                0
                            } else {
                                c[7].parse().unwrap()
                            }
                        ),
                        "{row}/{memo:?}/{diagnostics:?}/reset={reset}"
                    );
                    assert!(!context.reset_matched_with_consumed(), "{row}/{memo:?}");
                    if c[3] == "diagnostic" && diagnostics != Diagnostics::DetailedOnFailure {
                        assert_eq!(context.failure().expected, vec!["x"], "{row}");
                    }
                }
            }
        }
    }
}

fn retry_generated_chain(context: &mut ParseContext<'_>) -> ParseResult {
    for reset in [true, true, false] {
        context.set_reset_matched_with_consumed(reset);
        assert!(Expr::Rule(0).parse(context).is_err());
        assert!(!context.reset_matched_with_consumed());
    }
    Expr::NonOrdered(vec![Expr::JavaLookahead {
        pattern: "b",
        positive: true,
    }])
    .parse(context)
}

fn check_memo_replay(grammar: &[Rule], failure_hits: usize) {
    let mut rules = grammar.to_vec();
    let root = rules.len();
    rules.push(Rule {
        name: "retry",
        expression: Expr::Custom(retry_generated_chain),
    });
    let grammar = share_grammar(rules);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for mode in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let mut context = ParseContext::with_options(
                "ab",
                ParseOptions::with_memoization(memo).with_diagnostics(mode),
            );
            Expr::JavaLookahead {
                pattern: "a",
                positive: true,
            }
            .parse(&mut context)
            .unwrap();
            context.parse_shared_grammar(&grammar, root, false).unwrap();
            assert_eq!((context.position(), context.matched_position()), (0, 2));
            assert_eq!(
                context.memoized_failure_hits(),
                if memo == Memoization::Off {
                    0
                } else {
                    failure_hits
                }
            );
        }
    }
}

fn check_boundaries(grammar: &[Rule]) {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for boundary in ["choice", "non-ordered", "transaction"] {
            let mut context =
                ParseContext::with_options("ab", ParseOptions::with_memoization(memo));
            let ahead = |pattern| Expr::JavaLookahead {
                pattern,
                positive: true,
            };
            if boundary != "non-ordered" {
                ahead("a").parse(&mut context).unwrap();
            }
            if boundary == "transaction" {
                let _: ParseResult = context.transaction(|context| {
                    let result = context.parse_grammar(grammar.to_vec(), 0, false);
                    assert!(result.is_err());
                    assert!(!context.reset_matched_with_consumed());
                    result
                });
            } else {
                let expression = if boundary == "choice" {
                    Expr::Choice(vec![Expr::Rule(0), Expr::NonOrdered(vec![ahead("b")])])
                } else {
                    Expr::NonOrdered(vec![ahead("a"), Expr::Rule(0), ahead("b")])
                };
                let mut rules = grammar.to_vec();
                let root = rules.len();
                rules.push(Rule {
                    name: "boundary",
                    expression,
                });
                assert!(
                    context.parse_grammar(rules, root, false).is_err(),
                    "{boundary}/{memo:?}"
                );
            }
            assert_eq!(
                (
                    context.position(),
                    context.matched_position(),
                    context.failure().offset
                ),
                (0, if boundary == "non-ordered" { 0 } else { 1 }, 1),
                "{boundary}/{memo:?}"
            );
            assert!(context.reset_matched_with_consumed(), "{boundary}/{memo:?}");
        }
    }
}
