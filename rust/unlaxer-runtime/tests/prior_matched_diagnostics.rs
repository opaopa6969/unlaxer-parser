use unlaxer_runtime::{
    token::{TokenExpr, TokenKind},
    Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult,
};

const INPUT: &str = "p😀\r\nab!";
const PEEK: &str = "😀\r\n";
const BODY: &str = "😀\r\nab";

fn kind(value: &str) -> TokenKind {
    match value {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
        _ => panic!("{value}"),
    }
}
fn decode(value: &str) -> &'static str {
    if value == "~" {
        return "";
    }
    let bytes = value
        .as_bytes()
        .chunks_exact(2)
        .map(|v| u8::from_str_radix(std::str::from_utf8(v).unwrap(), 16).unwrap())
        .collect();
    Box::leak(String::from_utf8(bytes).unwrap().into_boxed_str())
}
fn parser(scenario: &str, word: &'static str) -> Expr {
    let hint = || Expr::sequence([Expr::literal(BODY), Expr::literal("Z").optional_java()]);
    match scenario {
        "peg-miss" => Expr::literal("Z").ahead(),
        "peg-negative" => Expr::literal(BODY).not_ahead(),
        "peg-deep" => Expr::sequence([Expr::literal(BODY), Expr::literal("Z")]).ahead(),
        "peg-negative-hint" => hint().not_ahead(),
        "peg-success-hint" => hint().ahead(),
        "peg-negative-success" => Expr::literal("Z").not_ahead(),
        "peg-suffix" => Expr::sequence([Expr::literal(BODY).ahead(), Expr::literal("Z")]),
        "word" => Expr::literal(word),
        "chain" => Expr::sequence([Expr::literal(word)]),
        "consume" => Expr::literal(word).stop_consume(),
        "stop-invert" => Expr::literal(word).stop_invert(),
        _ => panic!("{scenario}"),
    }
}
fn token(scenario: &str, text: &'static str) -> TokenExpr {
    let word = TokenExpr::Word { rule: 0, text };
    match scenario {
        "word" => word,
        "chain" => TokenExpr::Sequence(vec![word]),
        "consume" => TokenExpr::StopConsume(Box::new(word)),
        "stop-invert" => TokenExpr::StopInvert(Box::new(word)),
        _ => panic!("{scenario}"),
    }
}
fn options(memo: Memoization, diagnostics: Diagnostics) -> ParseOptions {
    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics)
}

#[test]
fn matching_classic_and_transactional_entries_share_the_frontier() {
    let rows: Vec<_> = include_str!("../../../conformance/prior-matched-diagnostics/cases.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
        .collect();
    assert_eq!(rows.len(), 92);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for reset in [false, true] {
                for row in &rows {
                    let c: Vec<_> = row.split('\t').collect();
                    assert_eq!(c.len(), 13);
                    let label = format!("{}/{memo:?}/{diagnostics:?}/reset={reset}", c[0]);
                    let word = decode(c[3]);
                    let mut context = ParseContext::with_options(INPUT, options(memo, diagnostics));
                    context.set_state("registered", 7_u32);
                    let prefix = context
                        .parse_with_token_mode(
                            &Expr::literal("p").capture_owned("stable"),
                            TokenKind::Consumed,
                            false,
                        )
                        .unwrap();
                    let id = context.token_node_id(prefix.nodes[0]).unwrap();
                    assert!(context.put_token_extra(id, "label", "before"));
                    context
                        .parse_token(
                            &TokenExpr::Word {
                                rule: 0,
                                text: PEEK,
                            },
                            TokenKind::MatchOnly,
                            false,
                        )
                        .unwrap();
                    context.set_reset_matched_with_consumed(reset);
                    let retained = context.tree(id.index()).unwrap();
                    let before = format!("{:?}", retained.nodes);
                    for _ in 0..2 {
                        let rollback: ParseResult = context.transaction(|context| {
                            let result = if c[1] == "token" {
                                context.parse_token(&token(c[2], word), kind(c[4]), c[5] == "true")
                            } else {
                                context.parse_with_token_mode(
                                    &parser(c[2], word),
                                    kind(c[4]),
                                    c[5] == "true",
                                )
                            };
                            assert_eq!(result.is_ok(), c[6] == "true", "{label}");
                            assert_eq!(
                                context.position(),
                                c[7].parse::<usize>().unwrap(),
                                "{label}"
                            );
                            assert_eq!(
                                context.matched_position(),
                                c[8].parse::<usize>().unwrap(),
                                "{label}"
                            );
                            let failure = context.failure();
                            let detailed = diagnostics != Diagnostics::DetailedOnFailure;
                            assert_eq!(
                                failure.offset,
                                if detailed { c[9].parse().unwrap() } else { 0 },
                                "{label}"
                            );
                            assert_eq!(
                                !failure.expected.is_empty(),
                                detailed && c[10] == "true",
                                "{label}"
                            );
                            assert_eq!(
                                format!("{:?}", context.tree(id.index()).unwrap().nodes),
                                before,
                                "{label}"
                            );
                            assert_eq!(context.state::<u32>("registered"), Some(&7), "{label}");
                            assert_eq!(context.captured_text("stable"), Some("p"), "{label}");
                            assert_eq!(
                                context.token_info(id).unwrap().extra("label"),
                                Some("before"),
                                "{label}"
                            );
                            Err(context.failure())
                        });
                        assert!(rollback.is_err());
                        assert_eq!(
                            (context.position(), context.matched_position()),
                            (1, 4),
                            "{label}"
                        );
                        assert_eq!(context.reset_matched_with_consumed(), reset, "{label}");
                        assert_eq!(
                            format!("{:?}", context.tree(id.index()).unwrap().nodes),
                            before,
                            "{label}"
                        );
                    }
                    context
                        .parse_with_token_mode(
                            &Expr::literal("😀\r\nab!"),
                            TokenKind::Consumed,
                            false,
                        )
                        .unwrap();
                    assert_eq!(
                        (context.position(), context.matched_position()),
                        (7, 7),
                        "{label}"
                    );
                    assert_eq!(
                        retained.token_info(id).unwrap().extra("label"),
                        Some("before")
                    );
                    assert_eq!(context.captured_text("stable"), Some("p"));
                }
            }
        }
    }
}

#[test]
fn native_source_frontier_survives_either_classic_preparation_entry() {
    let rows: Vec<_> = include_str!("../../../conformance/prior-matched-diagnostics/cases.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
        .collect();
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for reset in [false, true] {
                for classic_prepare in [false, true] {
                    for row in &rows {
                        let c: Vec<_> = row.split('\t').collect();
                        if c[11] == "~" {
                            continue;
                        }
                        let label = format!("{}/{memo:?}/{diagnostics:?}/reset={reset}/classicPrepare={classic_prepare}", c[0]);
                        let mut context =
                            ParseContext::with_options(INPUT, options(memo, diagnostics));
                        context
                            .parse_with_token_mode(
                                &Expr::literal("p").capture_owned("stable"),
                                TokenKind::Consumed,
                                false,
                            )
                            .unwrap();
                        if classic_prepare {
                            context
                                .parse_with_token_mode(
                                    &Expr::literal(PEEK),
                                    TokenKind::MatchOnly,
                                    false,
                                )
                                .unwrap();
                        } else {
                            context
                                .parse_token(
                                    &TokenExpr::Word {
                                        rule: 0,
                                        text: PEEK,
                                    },
                                    TokenKind::MatchOnly,
                                    false,
                                )
                                .unwrap();
                        }
                        context.set_reset_matched_with_consumed(reset);
                        for _ in 0..2 {
                            let result = context.parse(&parser(c[2], ""));
                            assert_eq!(result.is_ok(), c[6] == "true", "{label}");
                            assert_eq!(
                                (context.position(), context.matched_position()),
                                (1, 4),
                                "{label}"
                            );
                            let failure = context.failure();
                            let detailed = diagnostics != Diagnostics::DetailedOnFailure;
                            let column = if classic_prepare { 12 } else { 11 };
                            assert_eq!(
                                failure.offset,
                                if detailed {
                                    c[column].parse().unwrap()
                                } else {
                                    0
                                },
                                "{label}"
                            );
                            assert_eq!(
                                !failure.expected.is_empty(),
                                detailed && c[10] == "true",
                                "{label}"
                            );
                            assert_eq!(context.captured_text("stable"), Some("p"), "{label}");
                        }
                    }
                }
            }
        }
    }
}

#[test]
fn peg_then_classic_keeps_real_failures_and_discards_successful_speculation() {
    let rows: Vec<_> = include_str!("../../../conformance/prior-matched-diagnostics/reverse.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
        .collect();
    assert_eq!(rows.len(), 4);
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for reset in [false, true] {
                for row in &rows {
                    let c: Vec<_> = row.split('\t').collect();
                    assert_eq!(c.len(), 6);
                    let label = format!("{}/{memo:?}/{diagnostics:?}/reset={reset}", c[0]);
                    let mut context = ParseContext::with_options(INPUT, options(memo, diagnostics));
                    context
                        .parse_with_token_mode(
                            &Expr::literal("p").capture_owned("stable"),
                            TokenKind::Consumed,
                            false,
                        )
                        .unwrap();
                    context.set_reset_matched_with_consumed(reset);
                    assert_eq!(
                        context.parse(&parser(c[1], "")).is_ok(),
                        c[2] == "true",
                        "{label}"
                    );
                    let detailed = diagnostics != Diagnostics::DetailedOnFailure;
                    assert_eq!(
                        context.failure().offset,
                        if detailed { c[3].parse().unwrap() } else { 0 },
                        "{label}"
                    );
                    assert_eq!(
                        !context.failure().expected.is_empty(),
                        detailed && c[4] == "true",
                        "{label}"
                    );
                    assert_eq!(
                        (context.position(), context.matched_position()),
                        (1, 1),
                        "{label}"
                    );
                    let initial = context.failure();
                    for success in ["peg-success-hint", "peg-negative-success"] {
                        assert!(context.parse(&parser(success, "")).is_ok(), "{label}");
                        assert_eq!(context.failure(), initial, "{label}");
                    }
                    context
                        .parse_with_token_mode(&Expr::literal(PEEK), TokenKind::MatchOnly, false)
                        .unwrap();
                    for _ in 0..2 {
                        assert!(context
                            .parse_with_token_mode(
                                &Expr::sequence([Expr::literal("Z")]),
                                TokenKind::Consumed,
                                false
                            )
                            .is_err());
                        assert_eq!(
                            context.failure().offset,
                            if detailed { c[5].parse().unwrap() } else { 0 },
                            "{label}"
                        );
                        assert_eq!(
                            (context.position(), context.matched_position()),
                            (1, 4),
                            "{label}"
                        );
                        assert_eq!(context.captured_text("stable"), Some("p"), "{label}");
                    }
                    context
                        .parse_with_token_mode(
                            &Expr::literal("😀\r\nab!"),
                            TokenKind::Consumed,
                            false,
                        )
                        .unwrap();
                    assert_eq!(context.position(), 7, "{label}");
                }
            }
        }
    }
}

#[test]
fn source_failure_can_be_before_or_after_a_classic_watermark() {
    // No consumed prefix: exact CP0/CP2 regression, independently of display-string spelling.
    for diagnostics in [
        Diagnostics::Auto,
        Diagnostics::Detailed,
        Diagnostics::DetailedOnFailure,
    ] {
        for (expression, expected) in [
            (Expr::literal("Z").ahead(), 0),
            // A real failure need not carry an expected display hint.
            (Expr::Error(""), 0),
            (
                Expr::sequence([Expr::literal("ab"), Expr::literal("Z")]).ahead(),
                2,
            ),
        ] {
            let mut context =
                ParseContext::with_options("ab", options(Memoization::SafeFailures, diagnostics));
            context
                .parse_with_token_mode(&Expr::literal("a"), TokenKind::MatchOnly, false)
                .unwrap();
            assert!(context.parse(&expression).is_err());
            assert_eq!(
                context.failure().offset,
                if diagnostics == Diagnostics::DetailedOnFailure {
                    0
                } else {
                    expected
                }
            );
            assert_eq!((context.position(), context.matched_position()), (0, 1));
            if matches!(expression, Expr::Error("")) {
                assert!(context.failure().expected.is_empty());
            }
        }
    }
}

fn failure_memo_driver(context: &mut ParseContext<'_>) -> ParseResult {
    let expected = *context.state::<usize>("expected").unwrap();
    let detailed = context.state::<bool>("detailed") == Some(&true);
    context
        .parse_with_token_mode(&Expr::literal("a"), TokenKind::MatchOnly, false)
        .unwrap();
    for _ in 0..2 {
        assert!(context.parse(&Expr::Rule(1).ahead()).is_err());
        assert_eq!(
            context.failure().offset,
            if detailed { expected } else { 0 }
        );
        assert_eq!(!context.failure().expected.is_empty(), detailed);
        assert_eq!((context.position(), context.matched_position()), (0, 1));
    }
    // Switching back must combine real failures with the Classic failure frontier.
    assert!(context
        .parse_with_token_mode(&Expr::literal("Z"), TokenKind::Consumed, false)
        .is_err());
    assert_eq!(
        context.failure().offset,
        if detailed { expected.max(1) } else { 0 }
    );
    Ok(unlaxer_runtime::ParseMatch::empty(unlaxer_runtime::Span {
        start: 0,
        end: 0,
    }))
}

#[test]
fn actual_failure_memo_hits_preserve_native_and_classic_frontiers() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for reset in [false, true] {
                for (expression, expected) in [
                    (Expr::literal("Z"), 0_usize),
                    (Expr::sequence([Expr::literal("ab"), Expr::literal("Z")]), 2),
                ] {
                    let grammar = unlaxer_runtime::share_grammar(vec![
                        unlaxer_runtime::Rule {
                            name: "driver",
                            expression: Expr::Custom(failure_memo_driver),
                        },
                        unlaxer_runtime::Rule {
                            name: "failure",
                            expression,
                        },
                    ]);
                    let mut context = ParseContext::with_options("ab", options(memo, diagnostics));
                    context.set_state("expected", expected);
                    context.set_state("detailed", diagnostics != Diagnostics::DetailedOnFailure);
                    context.set_reset_matched_with_consumed(reset);
                    context.parse_shared_grammar(&grammar, 0, false).unwrap();
                    assert_eq!(
                        context.memoized_failure_hits(),
                        usize::from(memo == Memoization::SafeFailures),
                        "{memo:?}/{diagnostics:?}/reset={reset}/sourceCP{expected}"
                    );
                    assert_eq!(context.memoized_success_hits(), 0);
                    assert_eq!((context.position(), context.matched_position()), (0, 0));
                    assert_eq!(context.reset_matched_with_consumed(), reset);
                }
            }
        }
    }
}

fn success_memo_driver(context: &mut ParseContext<'_>) -> ParseResult {
    let detailed = context.state::<bool>("detailed") == Some(&true);
    context
        .parse_with_token_mode(&Expr::literal("a"), TokenKind::MatchOnly, false)
        .unwrap();
    for _ in 0..2 {
        context.parse(&Expr::Rule(1).ahead()).unwrap();
        assert_eq!(context.failure().offset, usize::from(detailed));
        assert!(context.failure().expected.is_empty());
        assert_eq!((context.position(), context.matched_position()), (0, 1));
    }
    // Successful rule replay includes a failed branch. A positive assertion
    // discards it, while a failing negative assertion deliberately retains it.
    assert!(context.parse(&Expr::Rule(1).not_ahead()).is_err());
    assert_eq!(context.failure().offset, if detailed { 2 } else { 0 });
    assert_eq!(!context.failure().expected.is_empty(), detailed);
    assert_eq!((context.position(), context.matched_position()), (0, 1));
    Ok(unlaxer_runtime::ParseMatch::empty(unlaxer_runtime::Span {
        start: 0,
        end: 0,
    }))
}

#[test]
fn actual_success_memo_hits_do_not_resurrect_discarded_assertion_hints() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for reset in [false, true] {
                let grammar = unlaxer_runtime::share_grammar(vec![
                    unlaxer_runtime::Rule {
                        name: "driver",
                        expression: Expr::Custom(success_memo_driver),
                    },
                    unlaxer_runtime::Rule {
                        name: "hint",
                        expression: Expr::choice([
                            Expr::sequence([Expr::literal("ab"), Expr::literal("Z")]),
                            Expr::literal("ab"),
                        ]),
                    },
                ]);
                let mut context = ParseContext::with_options("ab", options(memo, diagnostics));
                context.set_state("detailed", diagnostics != Diagnostics::DetailedOnFailure);
                context.set_reset_matched_with_consumed(reset);
                context.parse_shared_grammar(&grammar, 0, false).unwrap();
                assert_eq!(
                    context.memoized_success_hits(),
                    if memo == Memoization::SafeFailures {
                        2
                    } else {
                        0
                    },
                    "{memo:?}/{diagnostics:?}/reset={reset}"
                );
                assert_eq!(context.memoized_failure_hits(), 0);
                assert_eq!((context.position(), context.matched_position()), (0, 1));
                assert_eq!(context.reset_matched_with_consumed(), reset);
            }
        }
    }
}
