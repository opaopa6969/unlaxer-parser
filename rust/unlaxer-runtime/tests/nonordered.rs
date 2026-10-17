use unlaxer_runtime::{Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Rule, Span};
fn decode(hex: &str) -> String {
    if hex == "~" {
        return String::new();
    }
    hex.split_whitespace()
        .map(|cp| char::from_u32(u32::from_str_radix(cp, 16).unwrap()).unwrap())
        .collect()
}
fn atom(spec: &str) -> Expr {
    match spec {
        "N" => Expr::non_ordered([]),
        "E" => Expr::Eof,
        "Z" => Expr::JavaEmpty,
        "C" => Expr::sequence([]),
        "CZ" => Expr::sequence([atom("Z")]),
        "CN" => Expr::sequence([atom("N")]),
        "ON" => atom("N").optional_java(),
        "OC" => atom("C").optional_java(),
        "D" => Expr::Number,
        "I" => Expr::Identifier,
        "S" => Expr::Quoted('\''),
        "Uy" => Expr::JavaUntil("y"),
        _ => match spec.strip_prefix('O') {
            Some(word) => Expr::literal(Box::leak(decode(word).into_boxed_str())).optional_java(),
            None => Expr::literal(Box::leak(decode(spec).into_boxed_str())),
        },
    }
}
#[test]
fn shared_greedy_order_nullable_spans_and_diagnostics() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../spec-corpus/nonordered/cases.tsv")
                .lines()
                .filter(|row| !row.starts_with('#'))
            {
                let f: Vec<_> = row.split('\t').collect();
                let input = decode(f[1]);
                let children: Vec<_> = if f[2] == "~" {
                    vec![]
                } else {
                    f[2].split('/').map(atom).collect()
                };
                let n = Expr::non_ordered((1..=children.len()).map(Expr::Rule));
                let parser = match f[3] {
                    "CHAIN" | "PRIOR_CHAIN" => Expr::sequence([n]),
                    "FAIL" => n.then(Expr::literal("!")),
                    "RETRY" => Expr::choice([n.clone().then(Expr::literal("!")), n]),
                    "PREFIX" => Expr::Any.then(n),
                    "NESTED" => Expr::non_ordered([n, Expr::literal("!")]),
                    _ => n,
                };
                let mut rules = vec![Rule {
                    name: "root",
                    expression: parser,
                }];
                rules.extend(children.into_iter().map(|child| Rule {
                    name: "item",
                    expression: child.capture("item"),
                }));
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                if f[3].starts_with("PRIOR") {
                    context.parse(&Expr::JavaEmpty).unwrap();
                }
                let result = context.parse_grammar(rules.clone(), 0, false);
                assert_eq!(
                    result.is_ok(),
                    f[4] == "true",
                    "{} {memo:?} {diagnostics:?}",
                    f[0]
                );
                assert_eq!(context.position(), f[5].parse().unwrap(), "{}", f[0]);
                assert_eq!(
                    context.matched_position(),
                    f[6].parse().unwrap(),
                    "{}",
                    f[0]
                );
                if let Ok(matched) = result {
                    let root = context.node(matched.root_node().unwrap()).unwrap();
                    let spans: Vec<_> = root
                        .children
                        .iter()
                        .map(|&id| {
                            let node = context.node(id).unwrap();
                            assert_eq!(node.captures.len(), 1);
                            assert_eq!(node.captures[0].span, node.span);
                            assert_eq!(
                                context.text(node.span),
                                Some(
                                    input
                                        .chars()
                                        .skip(node.span.start)
                                        .take(node.span.end - node.span.start)
                                        .collect::<String>()
                                        .as_str()
                                )
                            );
                            format!("{}:{}:{}", node.rule - 1, node.span.start, node.span.end)
                        })
                        .collect();
                    assert_eq!(
                        spans.join(","),
                        if f[7] == "~" { "" } else { f[7] },
                        "{}",
                        f[0]
                    );
                    assert_eq!(context.capture_spans("item").len(), root.children.len());
                } else {
                    assert!(context.node(0).is_none());
                    assert!(context.captured("item").is_none());
                    if diagnostics != Diagnostics::DetailedOnFailure {
                        assert_eq!(context.failure().offset, f[8].parse().unwrap(), "{}", f[0]);
                    }
                    assert!(context.parse_grammar(rules, 0, false).is_err());
                    assert_eq!(context.position(), 0);
                }
            }
        }
    }
}

fn dirty(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    if context.position() == 0 {
        context.set_state("value", 99u32);
        context.scopes_mut().declare("leaked", 0);
        context.parse(&Expr::literal("b").capture("leaked"))?;
        return Err(context.error("a"));
    }
    context.parse(&Expr::literal("a").capture("selected"))
}
#[test]
fn rejected_custom_attempts_restore_every_owned_state() {
    for diagnostics in [
        Diagnostics::Auto,
        Diagnostics::Detailed,
        Diagnostics::DetailedOnFailure,
    ] {
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            let mut context = ParseContext::with_options(
                "ba",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            );
            context.set_state("value", 7u32);
            context
                .parse(&Expr::non_ordered([
                    Expr::Custom(dirty),
                    Expr::literal("b"),
                ]))
                .unwrap();
            assert_eq!(context.position(), 2);
            assert_eq!(context.state::<u32>("value"), Some(&7));
            assert!(context.scopes().all_declarations().is_empty());
            assert!(context.captured("leaked").is_none());
            assert_eq!(context.captured("selected"), Some("a"));
        }
    }
}

#[test]
fn traces_follow_selected_input_order_and_failures_leave_none() {
    let options =
        ParseOptions::default().with_token_trace(unlaxer_runtime::token_trace::Limits::default());
    for input in ["b😀", "b"] {
        let mut context = ParseContext::with_options(input, options);
        let result = context.parse(&Expr::non_ordered([
            Expr::literal("😀"),
            Expr::literal("b"),
        ]));
        let trace = context.token_trace().unwrap().unwrap();
        if result.is_ok() {
            assert_eq!(trace.len(), 2);
            assert_eq!(
                trace[0].source,
                unlaxer_runtime::token::TokenSource::Input(Span { start: 0, end: 1 })
            );
            assert_eq!(
                trace[1].source,
                unlaxer_runtime::token::TokenSource::Input(Span { start: 1, end: 2 })
            );
        } else {
            assert!(trace.is_empty());
        }
    }
}

fn recovery() -> Expr {
    Expr::literal("ok").recover(unlaxer_runtime::RecoveryMode::Sync, [";"], "bad")
}
fn effects(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    if context.position() == 0 {
        context.set_state("value", 99u32);
        context.scopes_mut().enter();
        context.scopes_mut().declare("leaked", 0);
        context.scopes_mut().add_reference("leaked", 0, 1);
        context
            .scopes_mut()
            .add_diagnostic("leaked", 0, 1, unlaxer_runtime::Severity::Warning);
        context.parse(&recovery().capture("leaked"))?;
        return Err(context.error("a"));
    }
    context.set_state("value", 9u32);
    context.scopes_mut().declare("selected", 2);
    context.scopes_mut().add_reference("selected", 2, 1);
    context
        .scopes_mut()
        .add_diagnostic("selected", 2, 1, unlaxer_runtime::Severity::Warning);
    context.parse(&Expr::literal("a").capture("selected"))
}
#[test]
fn shared_scope_capture_state_and_recovery_rollback() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../spec-corpus/nonordered/state.tsv")
                .lines()
                .filter(|row| !row.starts_with('#'))
            {
                let f: Vec<_> = row.split('\t').collect();
                let n = if f[0].starts_with("STATE") {
                    Expr::non_ordered([Expr::Custom(effects), Expr::literal("b;")])
                } else {
                    Expr::non_ordered([recovery(), Expr::literal("a")])
                };
                let parser = if f[0].ends_with("FAIL") {
                    n.then(Expr::literal("!"))
                } else if f[0].ends_with("RETRY") {
                    Expr::choice([n.clone().then(Expr::literal("!")), n])
                } else {
                    n
                };
                let mut context = ParseContext::with_options(
                    f[1],
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                context.set_state("value", 7u32);
                let result = context.parse(&parser);
                assert_eq!(
                    result.is_ok(),
                    f[2] == "true",
                    "{} {memo:?} {diagnostics:?}",
                    f[0]
                );
                assert_eq!(context.position(), f[3].parse().unwrap());
                assert_eq!(context.matched_position(), f[3].parse().unwrap());
                assert_eq!(context.state::<u32>("value"), Some(&f[4].parse().unwrap()));
                assert_eq!(
                    context.scopes().all_declarations().len(),
                    f[5].parse().unwrap()
                );
                assert_eq!(
                    context.scopes().all_references().len(),
                    f[6].parse().unwrap()
                );
                assert_eq!(context.scopes().diagnostics().len(), f[7].parse().unwrap());
                assert_eq!(context.scopes().current_scope_depth(), 0);
                let recoveries: Vec<_> = context
                    .recoveries()
                    .iter()
                    .map(|r| {
                        assert_eq!(r.message, "bad");
                        format!("{}:{}", r.span.start, r.span.end)
                    })
                    .collect();
                assert_eq!(recoveries.join(","), if f[8] == "~" { "" } else { f[8] });
                assert!(context.captured("leaked").is_none());
                let captures: Vec<_> = context
                    .capture_spans("selected")
                    .iter()
                    .map(|s| format!("{}:{}", s.start, s.end))
                    .collect();
                assert_eq!(captures.join(","), if f[9] == "~" { "" } else { f[9] });
                if result.is_err() {
                    assert!(context.node(0).is_none());
                    assert!(context.parse(&Expr::literal(f[1])).is_ok());
                }
            }
        }
    }
}

#[test]
fn memo_and_deferred_safety_walk_nonordered_children() {
    let grammar = unlaxer_runtime::share_grammar(vec![
        Rule {
            name: "root",
            expression: Expr::choice([Expr::Rule(1), Expr::Rule(1)]),
        },
        Rule {
            name: "n",
            expression: Expr::non_ordered([Expr::literal("a"), Expr::literal("b")]),
        },
    ]);
    assert!(unlaxer_runtime::grammar_allows_deferred_diagnostics(
        &grammar
    ));
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut context = ParseContext::with_options("a?", options);
            assert!(context.parse_shared_grammar(&grammar, 0, false).is_err());
            assert_eq!(context.position(), 0);
            assert_eq!(
                context.memoized_failure_hits() > 0,
                memo == Memoization::SafeFailures
            );
            let failure =
                unlaxer_runtime::parse_detailed_with_options(&grammar, 0, false, "a?", options)
                    .unwrap_err();
            assert_eq!(failure.farthest.offset, 1);
            assert_eq!(failure.farthest.expected, ["b"]);
        }
    }
    let custom = vec![Rule {
        name: "root",
        expression: Expr::non_ordered([Expr::Custom(dirty)]),
    }];
    assert!(!unlaxer_runtime::grammar_allows_deferred_diagnostics(
        &custom
    ));
}

#[test]
fn nesting_limits_abort_without_trying_fallback_or_reusing_the_context() {
    let mut nested = Expr::literal("a");
    for _ in 0..1100 {
        nested = Expr::non_ordered([nested]);
    }
    let mut context = ParseContext::new("a");
    let error = context
        .parse(&Expr::choice([nested, Expr::literal("a")]))
        .unwrap_err();
    assert!(error
        .expected
        .iter()
        .any(|message| message.contains("1024")));
    assert_eq!(context.position(), 0);
    assert_eq!(context.parse(&Expr::literal("a")).unwrap_err(), error);
    let recursive = vec![Rule {
        name: "recursive",
        expression: Expr::non_ordered([Expr::Rule(0)]),
    }];
    let failure = unlaxer_runtime::parse_detailed(&recursive, 0, false, "").unwrap_err();
    assert_eq!(failure.kind, "resource_limit");
}

#[test]
fn finite_wide_nullable_inputs_and_explicit_trivia() {
    let mut context = ParseContext::new("");
    context
        .parse(&Expr::non_ordered((0..4096).map(|_| Expr::Empty)))
        .unwrap();
    assert_eq!(context.position(), 0);
    let mut context = ParseContext::new(" a");
    assert!(context
        .parse(&Expr::non_ordered([Expr::literal("a")]).trivia_scope(true))
        .is_err());
    assert!(context
        .parse(&Expr::non_ordered([Expr::Sequence(vec![Expr::literal("a")])]).trivia_scope(true))
        .is_ok());
}

#[test]
fn failure_memo_distinguishes_the_matched_cursor_transaction_policy() {
    let grammar = vec![
        Rule {
            name: "root",
            expression: Expr::non_ordered([Expr::JavaEmpty, Expr::Rule(1), Expr::sequence([])]),
        },
        Rule {
            name: "peek",
            expression: Expr::JavaLookahead {
                pattern: "y",
                positive: true,
            },
        },
    ];
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let mut context = ParseContext::with_options(
                "xy",
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            );
            context.parse_grammar(grammar.clone(), 0, false).unwrap();
            assert_eq!((context.position(), context.matched_position()), (0, 2));
        }
    }
}
