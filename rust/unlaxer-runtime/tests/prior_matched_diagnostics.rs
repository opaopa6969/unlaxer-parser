use unlaxer_runtime::{
    token::{TokenExpr, TokenKind},
    Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult,
};

const INPUT: &str = "p😀\r\nab!";
const PEEK: &str = "😀\r\n";
const BODY: &str = "😀\r\nab";

/// `prefix LOOKAHEAD(pattern) 'é'`: the UBNF positive LOOKAHEAD token advances only the
/// matched cursor before the literal fails (#706).
fn lookahead_then(prefix: &'static str, pattern: &'static str) -> Expr {
    Expr::sequence([
        Expr::literal(prefix),
        Expr::JavaLookahead {
            pattern,
            positive: true,
        },
        Expr::literal("é"),
    ])
}

fn lookahead_rules(prefix: &'static str, pattern: &'static str) -> Vec<unlaxer_runtime::Rule> {
    vec![unlaxer_runtime::Rule {
        name: "R0",
        expression: lookahead_then(prefix, pattern),
    }]
}

fn expression_entry_inside_a_grammar(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse(&lookahead_then("a", "x"))
}

fn grammar_entry_inside_an_expression(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse_grammar(lookahead_rules("a", "x"), 0, false)
}

#[test]
fn grammar_invocations_measure_failures_at_the_matched_reach() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = options(memo, diagnostics);
            let detailed = diagnostics != Diagnostics::DetailedOnFailure;
            // Java: syntax at the matched reach (CP2; CP4 after CRLF and a supplementary scalar).
            for (prefix, pattern, inputs, offset) in [
                ("a", "x", ["ax", "axé"], 2),
                ("a\r\n", "😀", ["a\r\n😀", "a\r\n😀é"], 4),
            ] {
                for input in inputs {
                    let label = format!("{input:?}/{memo:?}/{diagnostics:?}");
                    let failure = unlaxer_runtime::parse_detailed_with_options(
                        &lookahead_rules(prefix, pattern),
                        0,
                        false,
                        input,
                        options,
                    )
                    .unwrap_err();
                    assert_eq!(
                        (failure.kind, failure.offset),
                        ("syntax", offset),
                        "{label}"
                    );

                    let mut context = ParseContext::with_options(input, options);
                    assert!(context
                        .parse_grammar(lookahead_rules(prefix, pattern), 0, false)
                        .is_err());
                    assert_eq!(
                        context.failure().offset,
                        if detailed { offset } else { 0 },
                        "{label}"
                    );
                    assert_eq!((context.position(), context.matched_position()), (0, 0));
                }
            }

            // Expression entries keep the source frontier (CP1), also inside a grammar; the
            // innermost public entry decides.
            let mut context = ParseContext::with_options("ax", options);
            assert!(context.parse(&lookahead_then("a", "x")).is_err());
            assert_eq!(context.failure().offset, usize::from(detailed));
            let mut context = ParseContext::with_options("ax", options);
            let outer = vec![unlaxer_runtime::Rule {
                name: "outer",
                expression: Expr::Custom(expression_entry_inside_a_grammar),
            }];
            assert!(context.parse_grammar(outer, 0, false).is_err());
            assert_eq!(context.failure().offset, usize::from(detailed));
            let mut context = ParseContext::with_options("ax", options);
            assert!(context
                .parse(&Expr::Custom(grammar_entry_inside_an_expression))
                .is_err());
            assert_eq!(context.failure().offset, if detailed { 2 } else { 0 });
            assert_eq!((context.position(), context.matched_position()), (0, 0));
        }
    }
}

/// `'a' LOOKAHEAD(ahead) R1` where R1 has its own trivia policy (#753).
fn rule_after_lookahead(ahead: &'static str, r1: Expr, trivia: bool) -> Vec<unlaxer_runtime::Rule> {
    let r1 = if trivia {
        Expr::TriviaScope {
            child: Box::new(r1),
            whitespace: true,
            line_comments: false,
        }
    } else {
        r1
    };
    vec![
        unlaxer_runtime::Rule {
            name: "R0",
            expression: Expr::sequence([
                Expr::literal("a"),
                Expr::JavaLookahead {
                    pattern: ahead,
                    positive: true,
                },
                Expr::Rule(1),
            ]),
        },
        unlaxer_runtime::Rule {
            name: "R1",
            expression: r1,
        },
    ]
}

#[test]
fn a_failed_rule_records_its_start_at_the_matched_reach() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = options(memo, diagnostics);
            let parse = |rules: Vec<unlaxer_runtime::Rule>, input: &str| {
                unlaxer_runtime::parse_detailed_with_options(&rules, 0, false, input, options)
                    .map(|_| ())
                    .map_err(|failure| (failure.kind, failure.offset))
            };
            let label = format!("{memo:?}/{diagnostics:?}");
            let consume = || Expr::sequence([Expr::literal("x"), Expr::literal("q")]);
            // Java: R1's ParseFrame starts at max(consumed CP1, matched CP4). Consumption
            // (CP2) or trivia (CP3) inside R1 lowers the matched cursor before 'q' fails.
            assert_eq!(
                parse(rule_after_lookahead("xyz", consume(), false), "axyz"),
                Err(("syntax", 4)),
                "{label}"
            );
            assert_eq!(
                parse(rule_after_lookahead("  xyz", consume(), true), "a  xyz"),
                Err(("syntax", 6)),
                "{label}"
            );
            let z = Expr::sequence([Expr::literal("z")]);
            assert_eq!(
                parse(rule_after_lookahead("  xy", z, true), "a  xy"),
                Err(("syntax", 5)),
                "{label}"
            );
            // The trivia leaves the matched cursor at CP3 for a LOOKAHEAD in R1, as Java's
            // transaction cursor; raising it to CP5 would reject this input.
            let ahead = Expr::sequence([
                Expr::JavaLookahead {
                    pattern: "xy",
                    positive: true,
                },
                Expr::literal("x"),
                Expr::literal("y"),
            ]);
            assert_eq!(
                parse(rule_after_lookahead("  xy", ahead, true), "a  xy"),
                Ok(()),
                "{label}"
            );
            // A rule that succeeds adds nothing: 'w' fails at CP3, not at R1's start CP5.
            let mut rules = rule_after_lookahead(
                "  xy",
                Expr::sequence([Expr::literal("q").optional()]),
                true,
            );
            if let Expr::Sequence(elements) = &mut rules[0].expression {
                elements.push(Expr::literal("w"));
            }
            assert_eq!(parse(rules, "a  xy"), Err(("syntax", 3)), "{label}");
        }
    }
}

#[test]
fn failed_rule_frames_respect_assertion_speculation() {
    for row in include_str!("../../../conformance/prior-matched-diagnostics/rule-frames.tsv")
        .lines()
        .filter(|row| !row.starts_with('#') && !row.is_empty())
    {
        let c: Vec<_> = row.split('\t').collect();
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let body = if c[1] == "fallback" {
                    Expr::Rule(1)
                } else {
                    Expr::Rule(2)
                };
                let wrapped = match c[2] {
                    "plain" => body,
                    "positive" => body.ahead(),
                    "negative" => body.not_ahead(),
                    "match-only" => body.match_only(),
                    "positive-twice" => Expr::sequence([body.clone().ahead(), body.ahead()]),
                    "negative-twice" => {
                        Expr::sequence([body.clone().not_ahead(), body.not_ahead()])
                    }
                    _ => panic!("unknown wrapper"),
                };
                let child = match c[1] {
                    "not-failure" => Expr::sequence([
                        Expr::literal("x"),
                        Expr::JavaLookahead {
                            pattern: "y",
                            positive: false,
                        },
                    ]),
                    "not-success" => Expr::sequence([
                        Expr::literal("x"),
                        Expr::JavaLookahead {
                            pattern: "q",
                            positive: false,
                        },
                        Expr::literal("y"),
                    ]),
                    _ => Expr::sequence([Expr::literal("x"), Expr::literal("q")]),
                };
                let rules = vec![
                    unlaxer_runtime::Rule {
                        name: "R0",
                        expression: Expr::sequence([
                            Expr::literal("a"),
                            Expr::JavaLookahead {
                                pattern: "xyz",
                                positive: true,
                            },
                            wrapped,
                            Expr::literal("x"),
                            Expr::literal("v"),
                        ]),
                    },
                    unlaxer_runtime::Rule {
                        name: "R1",
                        expression: Expr::choice([Expr::Rule(2), Expr::literal("x")]),
                    },
                    unlaxer_runtime::Rule {
                        name: "R2",
                        expression: child,
                    },
                ];
                let mut context = ParseContext::with_options("axyz", options(memo, diagnostics));
                assert!(
                    context.parse_grammar(rules.clone(), 0, false).is_err(),
                    "{row}"
                );
                assert_eq!(
                    (context.position(), context.matched_position()),
                    (0, 0),
                    "{row}"
                );
                let offset = c[3].parse::<usize>().unwrap();
                assert_eq!(
                    context.failure().offset,
                    if diagnostics == Diagnostics::DetailedOnFailure {
                        0
                    } else {
                        offset
                    },
                    "{row}/{memo:?}/{diagnostics:?}"
                );
                if c.len() == 6 {
                    let expected = if memo == Memoization::SafeFailures {
                        (
                            c[4].parse::<usize>().unwrap(),
                            c[5].parse::<usize>().unwrap(),
                        )
                    } else {
                        (0, 0)
                    };
                    assert_eq!(
                        (
                            context.memoized_failure_hits(),
                            context.memoized_success_hits()
                        ),
                        expected,
                        "{row}"
                    );
                }
                assert_eq!(
                    unlaxer_runtime::parse_detailed_with_options(
                        &rules,
                        0,
                        false,
                        "axyz",
                        options(memo, diagnostics)
                    )
                    .unwrap_err()
                    .offset,
                    offset,
                    "{row}/{memo:?}/{diagnostics:?}"
                );
            }
        }
    }
}

fn native_rule_after_discarded_grammar_failure(context: &mut ParseContext<'_>) -> ParseResult {
    let succeeds = context.state::<bool>("succeeds").copied().unwrap();
    let memo = context.state::<bool>("memo").copied().unwrap();
    for attempt in 0..2 {
        let rollback: Result<(), unlaxer_runtime::ParseError> = context.transaction(|context| {
            let result = context.parse(&Expr::Rule(3));
            assert_eq!(result.is_ok(), succeeds);
            // Native expression entries retain the source failure even when a
            // successful rule has a failed alternative. Grammar uses CP4 instead.
            let failure = context.failure();
            assert_eq!(failure.offset, 2);
            assert_eq!(failure.expected, ["q"]);
            Err(failure)
        });
        assert!(rollback.is_err());
        let hits = usize::from(memo) * attempt;
        assert_eq!(
            context.memoized_failure_hits(),
            if succeeds { 0 } else { hits }
        );
        assert_eq!(
            context.memoized_success_hits(),
            if succeeds { hits } else { 0 }
        );
    }
    Ok(unlaxer_runtime::ParseMatch::empty(unlaxer_runtime::Span {
        start: 1,
        end: 1,
    }))
}

#[test]
fn grammar_and_native_frontiers_do_not_share_failure_or_success_memos() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for succeeds in [false, true] {
            let rules = vec![
                unlaxer_runtime::Rule {
                    name: "R0",
                    expression: Expr::sequence([
                        Expr::literal("a"),
                        Expr::JavaLookahead {
                            pattern: "xyz",
                            positive: true,
                        },
                        if succeeds {
                            Expr::Rule(2).ahead()
                        } else {
                            Expr::Rule(2).not_ahead()
                        },
                        Expr::Custom(native_rule_after_discarded_grammar_failure),
                    ]),
                },
                unlaxer_runtime::Rule {
                    name: "R1",
                    expression: Expr::sequence([Expr::literal("x"), Expr::literal("q")]),
                },
                // Match the rule-depth key of the callback's reentrant expression
                // entry; otherwise a cache miss would hide the frontier collision.
                unlaxer_runtime::Rule {
                    name: "Alias1",
                    expression: Expr::Rule(4),
                },
                unlaxer_runtime::Rule {
                    name: "MemoTarget",
                    expression: if succeeds {
                        Expr::choice([Expr::Rule(1), Expr::literal("x")])
                    } else {
                        Expr::Rule(1)
                    },
                },
                unlaxer_runtime::Rule {
                    name: "Alias2",
                    expression: Expr::Rule(3),
                },
            ];
            let mut context =
                ParseContext::with_options("axyz", options(memo, Diagnostics::Detailed));
            context.set_state("succeeds", succeeds);
            context.set_state("memo", memo == Memoization::SafeFailures);
            context.parse_grammar(rules, 0, false).unwrap();
            assert_eq!((context.position(), context.matched_position()), (1, 4));
        }
    }
}

thread_local! {
    static BC_REGISTRY: unlaxer_runtime::shared_calls::Registry = {
        let grammar = unlaxer_runtime::embedded::CstGrammar {
            name: "Child".into(),
            grammar: unlaxer_runtime::share_grammar(vec![unlaxer_runtime::Rule {
                name: "Root",
                expression: Expr::sequence([Expr::literal("b"), Expr::literal("c")]),
            }]),
            entries: [("Root".to_owned(), 0)].into_iter().collect(),
            bindings: Vec::new(),
            whitespace: false,
        };
        unlaxer_runtime::shared_calls::Registry::new(
            [(child_language(), grammar)].into_iter().collect(),
        )
        .unwrap()
    };
    static BC_FAILURE: std::cell::RefCell<Option<unlaxer_runtime::ParseError>> =
        const { std::cell::RefCell::new(None) };
}

fn child_language() -> unlaxer_runtime::source::Language {
    unlaxer_runtime::source::Language {
        id: "child".into(),
        package_id: "org.example".into(),
        version: "1".into(),
        grammar: "Child".into(),
        entry: "Root".into(),
    }
}

fn call_child(context: &mut ParseContext<'_>) -> ParseResult {
    let result = BC_REGISTRY
        .with(|registry| registry.call(context, &child_language()))
        .map_err(|failure| failure.error);
    BC_FAILURE.with(|cell| *cell.borrow_mut() = result.as_ref().err().cloned());
    result
}

/// A shared grammar call starts the child at the caller's consumed position with both
/// cursors there, as Java's SharedGrammarCalls does: the matched reach of a preceding
/// positive LOOKAHEAD token is not the child's frontier (#706 review).
#[test]
fn a_shared_grammar_call_starts_both_cursors_at_the_consumed_position() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for input in ["ax", "axb"] {
            let label = format!("{input}/{memo:?}");
            let rules = vec![unlaxer_runtime::Rule {
                name: "Caller",
                expression: Expr::sequence([
                    Expr::literal("a"),
                    Expr::JavaLookahead {
                        pattern: "x",
                        positive: true,
                    },
                    Expr::Custom(call_child),
                ]),
            }];
            let mut context =
                ParseContext::with_options(input, options(memo, Diagnostics::Detailed));
            assert!(context.parse_grammar(rules, 0, false).is_err(), "{label}");
            let failure = BC_FAILURE.with(|cell| cell.borrow_mut().take()).unwrap();
            assert_eq!(failure.offset, 1, "{label}");
            assert_eq!(failure.expected, ["b"], "{label}");
            let stack = context.rule_stack().unwrap();
            let frames: Vec<_> = stack
                .frames
                .iter()
                .map(|frame| format!("{}@{}", frame.rule, frame.start))
                .collect();
            assert_eq!(frames, ["Caller@0", "Root@1"], "{label}");
            assert_eq!((stack.offset, context.failure().offset), (1, 1), "{label}");
            assert_eq!((context.position(), context.matched_position()), (0, 0));
        }
    }
}

fn nested_b(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse_grammar(
        vec![unlaxer_runtime::Rule {
            name: "Inner",
            expression: Expr::literal("b"),
        }],
        0,
        false,
    )
}

/// A grammar called from inside a token-stream session keeps the consumed frontier too,
/// although its call takes the session: Rust's EMPTY advanced the matched cursor over a
/// lexeme Java's EMPTY does not match (#706 review).
#[test]
fn a_grammar_nested_in_a_token_stream_session_keeps_the_consumed_frontier() {
    let grammar = unlaxer_runtime::share_grammar(vec![unlaxer_runtime::Rule {
        name: "Outer",
        expression: Expr::sequence([Expr::JavaEmpty, Expr::Custom(nested_b)]),
    }]);
    for mode in [
        unlaxer_runtime::lexing::Mode::Direct,
        unlaxer_runtime::lexing::Mode::TokensEager,
    ] {
        let outcome = unlaxer_runtime::lexing::parse(
            &grammar,
            0,
            false,
            "a",
            unlaxer_runtime::lexing::Options {
                mode,
                preserve_trivia: true,
            },
            std::sync::Arc::from([]),
        )
        .unwrap();
        assert!(!outcome.succeeded, "{mode:?}");
        assert_eq!(outcome.farthest, 0, "{mode:?}");
        assert_eq!((outcome.consumed, outcome.matched), (0, 0), "{mode:?}");
    }
}

fn nested_frontier_leaf(context: &mut ParseContext<'_>) -> ParseResult {
    let grammar = unlaxer_runtime::share_grammar(vec![unlaxer_runtime::Rule {
        name: "Leaf",
        expression: Expr::literal("é"),
    }]);
    context.parse_shared_grammar(&grammar, 0, false)
}

fn nested_frontier_bridge(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse_grammar(
        vec![unlaxer_runtime::Rule {
            name: "Inner",
            expression: Expr::Custom(nested_frontier_leaf),
        }],
        0,
        false,
    )
}

fn nested_frontier_rules<const DEPTH: usize>() -> Vec<unlaxer_runtime::Rule> {
    let tail = match DEPTH {
        1 => Expr::literal("é"),
        2 => Expr::Custom(nested_frontier_leaf),
        3 => Expr::Custom(nested_frontier_bridge),
        _ => panic!("未対応の入れ子の深さ: {DEPTH}"),
    };
    vec![unlaxer_runtime::Rule {
        name: "Middle",
        expression: Expr::sequence([
            Expr::literal("a"),
            Expr::JavaLookahead {
                pattern: "x",
                positive: true,
            },
            tail,
        ]),
    }]
}

fn nested_frontier<const DEPTH: usize>(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse_grammar(nested_frontier_rules::<DEPTH>(), 0, false)
}

const NESTED_FRONTIER_ENTRIES: [fn(&mut ParseContext<'_>) -> ParseResult; 3] = [
    nested_frontier::<1>,
    nested_frontier::<2>,
    nested_frontier::<3>,
];

#[test]
fn one_to_three_nested_grammars_keep_the_token_stream_consumed_frontier() {
    for (depth, entry) in NESTED_FRONTIER_ENTRIES.into_iter().enumerate() {
        let grammar = unlaxer_runtime::share_grammar(vec![unlaxer_runtime::Rule {
            name: "Outer",
            expression: Expr::Custom(entry),
        }]);
        for mode in [
            unlaxer_runtime::lexing::Mode::Direct,
            unlaxer_runtime::lexing::Mode::TriviaCache,
            unlaxer_runtime::lexing::Mode::TokensLazy,
            unlaxer_runtime::lexing::Mode::TokensEager,
        ] {
            for input in ["ax", "axé"] {
                let label = format!("深さ{}/{mode:?}/{input}", depth + 1);
                let outcome = unlaxer_runtime::lexing::parse(
                    &grammar,
                    0,
                    false,
                    input,
                    unlaxer_runtime::lexing::Options {
                        mode,
                        preserve_trivia: true,
                    },
                    std::sync::Arc::from([]),
                )
                .unwrap();
                assert!(!outcome.succeeded, "{label}");
                assert_eq!(outcome.farthest, 1, "{label}");
                assert_eq!(outcome.expected, ["é"], "{label}");
                assert_eq!((outcome.consumed, outcome.matched), (0, 0), "{label}");
            }
        }
    }
}

#[test]
fn nested_grammars_outside_a_token_stream_keep_the_matched_frontier() {
    for (depth, entry) in NESTED_FRONTIER_ENTRIES.into_iter().enumerate() {
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let label = format!("深さ{}/{memo:?}/{diagnostics:?}", depth + 1);
                let mut context = ParseContext::with_options("ax", options(memo, diagnostics));
                let result = context.parse_grammar(
                    vec![unlaxer_runtime::Rule {
                        name: "Outer",
                        expression: Expr::Custom(entry),
                    }],
                    0,
                    false,
                );
                assert!(result.is_err(), "{label}");
                let offset = if diagnostics == Diagnostics::DetailedOnFailure {
                    0
                } else {
                    2
                };
                assert_eq!(context.failure().offset, offset, "{label}");
                assert_eq!((context.position(), context.matched_position()), (0, 0));
                if diagnostics != Diagnostics::DetailedOnFailure {
                    let stack = context.rule_stack().unwrap();
                    assert_eq!(stack.offset, 2, "{label}");
                    assert_eq!(stack.frames.len(), depth + 2, "{label}");
                    for frame in &stack.frames[2..] {
                        assert_eq!(frame.start, 2, "{label}/{frame:?}");
                    }
                }
            }
        }
    }
}

fn shared_nested_frontier(context: &mut ParseContext<'_>) -> ParseResult {
    let grammar = unlaxer_runtime::embedded::CstGrammar {
        name: "Child".into(),
        grammar: unlaxer_runtime::share_grammar(nested_frontier_rules::<3>()),
        entries: [("Root".to_owned(), 0)].into_iter().collect(),
        bindings: Vec::new(),
        whitespace: false,
    };
    let registry = unlaxer_runtime::shared_calls::Registry::new(
        [(child_language(), grammar)].into_iter().collect(),
    )
    .unwrap();
    registry
        .call(context, &child_language())
        .map_err(|failure| failure.error)
}

fn nested_shared_frontier(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse_grammar(
        vec![unlaxer_runtime::Rule {
            name: "SessionChild",
            expression: Expr::Custom(shared_nested_frontier),
        }],
        0,
        false,
    )
}

#[test]
fn shared_calls_from_token_streams_keep_the_child_matched_frontier() {
    // session を直接外す経路と、入れ子がすでに外した経路の両方を検証する。
    for entry in [shared_nested_frontier, nested_shared_frontier] {
        let grammar = unlaxer_runtime::share_grammar(vec![unlaxer_runtime::Rule {
            name: "Outer",
            expression: Expr::Custom(entry),
        }]);
        for mode in [
            unlaxer_runtime::lexing::Mode::Direct,
            unlaxer_runtime::lexing::Mode::TriviaCache,
            unlaxer_runtime::lexing::Mode::TokensLazy,
            unlaxer_runtime::lexing::Mode::TokensEager,
        ] {
            let outcome = unlaxer_runtime::lexing::parse(
                &grammar,
                0,
                false,
                "ax",
                unlaxer_runtime::lexing::Options {
                    mode,
                    preserve_trivia: true,
                },
                std::sync::Arc::from([]),
            )
            .unwrap();
            assert!(!outcome.succeeded, "{mode:?}");
            assert_eq!(outcome.farthest, 2, "{mode:?}");
            assert_eq!(outcome.expected, ["é"], "{mode:?}");
            assert_eq!((outcome.consumed, outcome.matched), (0, 0), "{mode:?}");
        }
    }
}

fn shared_call_then_frontier(context: &mut ParseContext<'_>) -> ParseResult {
    // 子 'b' 'c' の成功・失敗にかかわらず、その後も session 内の解析を続ける。
    let _ = call_child(context);
    context.parse_grammar(nested_frontier_rules::<3>(), 0, false)
}

fn nested_shared_call_then_frontier(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse_grammar(
        vec![unlaxer_runtime::Rule {
            name: "SessionChild",
            expression: Expr::Custom(shared_call_then_frontier),
        }],
        0,
        false,
    )
}

#[test]
fn shared_calls_restore_the_session_frontier_after_success_and_failure() {
    let grammar = unlaxer_runtime::share_grammar(vec![unlaxer_runtime::Rule {
        name: "Outer",
        expression: Expr::Custom(nested_shared_call_then_frontier),
    }]);
    for mode in [
        unlaxer_runtime::lexing::Mode::Direct,
        unlaxer_runtime::lexing::Mode::TriviaCache,
        unlaxer_runtime::lexing::Mode::TokensLazy,
        unlaxer_runtime::lexing::Mode::TokensEager,
    ] {
        for (input, offset, shared_succeeded) in [("ax", 1, false), ("bcax", 3, true)] {
            let label = format!("{mode:?}/{input}");
            let outcome = unlaxer_runtime::lexing::parse(
                &grammar,
                0,
                false,
                input,
                unlaxer_runtime::lexing::Options {
                    mode,
                    preserve_trivia: true,
                },
                std::sync::Arc::from([]),
            )
            .unwrap();
            assert!(!outcome.succeeded, "{label}");
            assert_eq!(outcome.farthest, offset, "{label}");
            assert_eq!(outcome.expected, ["é"], "{label}");
            assert_eq!(
                BC_FAILURE.with(|cell| cell.borrow_mut().take()).is_none(),
                shared_succeeded,
                "{label}"
            );
            assert_eq!((outcome.consumed, outcome.matched), (0, 0), "{label}");
        }
    }
}

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
