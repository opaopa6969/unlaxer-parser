use super::*;

fn grammar(token: &'static str) -> SharedGrammar {
    share_grammar(vec![
        Rule {
            name: "root",
            expression: Expr::choice([Expr::Rule(1), Expr::Rule(1), Expr::literal(token)])
                .repeat(0, None)
                .then(Expr::Eof),
        },
        Rule {
            name: "failure",
            expression: Expr::literal(token).then(Expr::literal("!")),
        },
    ])
}

#[test]
fn shared_java_rust_retention_corpus() {
    // Shared expected acceptance, consumed cursor and farthest diagnostic position.
    let corpus = include_str!("../../../unlaxer-common/src/test/resources/memo-retention.tsv");
    for row in corpus.lines().filter(|line| !line.starts_with('#')) {
        let fields: Vec<_> = row.split('\t').collect();
        let count: usize = fields[1].parse().unwrap();
        let input = fields[0].repeat(count) + if fields[2] == "-" { "" } else { fields[2] };
        let grammar = grammar(fields[0]);
        for diagnostics in [Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
            let mut observations = vec![];
            for window in [None, Some(1024)] {
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(Memoization::SafeFailures)
                        .with_diagnostics(diagnostics),
                );
                context.rules = Arc::clone(&grammar);
                context.memo_safe_rules = memo_safe_rules(&grammar);
                context.first_sets = None; // Compare memo work, not candidate exclusion.
                context.failure_memo = FailureMemoBuckets::with_window(window);
                let result = context.parse(&Expr::Rule(0));
                assert_eq!(result.is_ok(), fields[3] == "true", "{row}");
                assert_eq!(context.position(), fields[4].parse::<usize>().unwrap());
                if diagnostics == Diagnostics::Detailed {
                    assert_eq!(
                        context.failure().offset,
                        fields[5].parse::<usize>().unwrap()
                    );
                }
                assert_eq!(context.failure_memo.eviction_underruns, 0);
                if window.is_some() {
                    assert_eq!(context.failure_memo.evicted_entries > 0, count > 1024);
                }
                observations.push((
                    result.is_ok(),
                    context.position(),
                    context.matched_position(),
                    context.failure(),
                    format!("{:?}", context.nodes),
                    context.memoized_failure_hits(),
                ));
            }
            assert_eq!(observations[0], observations[1], "{row} {diagnostics:?}");
            assert_eq!(observations[0].5, count + 1);
        }
    }
}

#[test]
fn underrun_restores_caching_not_just_the_window_size() {
    let mut memo = FailureMemoBuckets::with_window(Some(1024));
    let key = FailureMemoKey::new(1, 0, 0, false, 2);
    memo.insert(0, key, FailureDiagnostic::default());
    memo.observe_cursor(4000);
    assert!(memo.first.is_empty());
    assert_eq!(memo.first.capacity(), 0);
    assert!(memo.probe(0, &key).is_none());
    assert_eq!(memo.eviction_underruns, 1);
    assert_eq!(memo.window, Some(8000));
    assert_eq!(memo.evicted_buckets, 0);
    memo.insert(0, key, FailureDiagnostic::default());
    assert!(memo.probe(0, &key).is_some());
    assert_eq!(memo.eviction_underruns, 1);
    memo.observe_cursor(20_000);
    assert!(memo.probe(0, &key).is_none());
    assert_eq!(memo.window, Some(40_000));
    memo.insert(0, key, FailureDiagnostic::default());
    assert!(memo.probe(0, &key).is_some());
}

#[test]
fn long_backtracking_rebuilds_the_memo_without_changing_the_parse() {
    let input = "😀".repeat(4000);
    let items = Expr::choice([Expr::Rule(1), Expr::Rule(1), Expr::literal("😀")]).repeat(0, None);
    let grammar = share_grammar(vec![
        Rule {
            name: "root",
            expression: Expr::choice([
                items.clone().then(Expr::literal("z")),
                items.then(Expr::Eof),
            ]),
        },
        Rule {
            name: "failure",
            expression: Expr::literal("😀").then(Expr::literal("!")),
        },
    ]);
    let mut observations = vec![];
    for window in [None, Some(1024)] {
        let mut context = ParseContext::with_options(
            &input,
            ParseOptions::with_memoization(Memoization::SafeFailures),
        );
        context.rules = Arc::clone(&grammar);
        context.memo_safe_rules = memo_safe_rules(&grammar);
        context.failure_memo = FailureMemoBuckets::with_window(window);
        let result = context.parse(&Expr::Rule(0)).unwrap();
        if window.is_some() {
            assert_eq!(context.failure_memo.eviction_underruns, 1);
            assert_eq!(context.failure_memo.window, Some(8000));
            assert!(context.failure_memo.first.len() >= 256);
        }
        assert!(context.memoized_failure_hits() >= 8002);
        observations.push((
            result.span,
            context.matched_position(),
            context.failure(),
            format!("{:?}", context.nodes),
        ));
    }
    assert_eq!(observations[0], observations[1]);
}

#[test]
fn only_complete_buckets_are_freed_and_late_insertions_are_not_retained() {
    let mut memo = FailureMemoBuckets::with_window(Some(1024));
    let key = FailureMemoKey::new(1, 255, 255, false, 2);
    memo.insert(0, key, FailureDiagnostic::default());
    memo.observe_cursor(1279);
    assert!(memo.get(0, &key).is_some());
    memo.observe_cursor(1280);
    assert!(memo.get(0, &key).is_none());
    assert_eq!(memo.first.capacity(), 0);
    memo.insert(0, key, FailureDiagnostic::default());
    assert!(memo.first.is_empty());
    memo.observe_cursor(usize::MAX);
    assert!(memo.probe(0, &key).is_none());
    assert_eq!(memo.window, Some(usize::MAX));
    memo.insert(0, key, FailureDiagnostic::default());
    assert!(memo.probe(0, &key).is_some());
}

fn budget_failure(context: &mut ParseContext<'_>) {
    let start = (context.position(), context.matched_position());
    assert!(context.parse(&Expr::Rule(0)).is_err());
    assert_eq!((context.position(), context.matched_position()), start);
    assert!(context.node(0).is_none());
    assert_eq!(
        context.scopes().diagnostics(),
        &[scope::SymbolDiagnostic {
            message: "kept".into(),
            offset: 0,
            length: 1,
            severity: Severity::Warning
        }]
    );
    assert_eq!(context.state::<i32>("user"), Some(&7));
}

#[test]
fn failure_budget_shared_oracle_bounds_epochs_and_preserves_real_hits() {
    let rows = include_str!("../../../conformance/failure-memo-budget/cases.tsv");
    let modes = [
        None,
        Some(token::ParseMode {
            kind: token::TokenKind::Consumed,
            invert: false,
        }),
        Some(token::ParseMode {
            kind: token::TokenKind::MatchOnly,
            invert: false,
        }),
        Some(token::ParseMode {
            kind: token::TokenKind::VirtualConsumed,
            invert: false,
        }),
        Some(token::ParseMode {
            kind: token::TokenKind::VirtualMatchOnly,
            invert: false,
        }),
    ];
    let mut count = 0;
    for row in rows
        .lines()
        .filter(|r| !r.starts_with('#') && !r.is_empty())
    {
        let f: Vec<_> = row.split('\t').collect();
        let n = |i: usize| f[i].parse::<usize>().unwrap();
        for mode in modes {
            for reset in [false, true] {
                for diagnostics in [
                    Diagnostics::Auto,
                    Diagnostics::Detailed,
                    Diagnostics::DetailedOnFailure,
                ] {
                    for memo in [Memoization::Off, Memoization::SafeFailures] {
                        let far = matches!(f[1], "evict" | "underrun");
                        let input = if far {
                            "x".repeat(4000) + "😀\r\n?"
                        } else {
                            "😀\r\n?".to_owned()
                        };
                        let mut c = ParseContext::with_options(
                            &input,
                            ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                        );
                        c.token_mode = mode;
                        c.match_cursor_policy.reset = reset;
                        c.rules = share_grammar(vec![Rule {
                            name: "failure",
                            expression: Expr::literal("😀\r\n").then(Expr::literal("!")),
                        }]);
                        c.memo_safe_rules = memo_safe_rules(&c.rules);
                        c.failure_memo = FailureMemoBuckets::with_window(Some(64));
                        c.failure_memo.max_entries = n(3);
                        c.failure_memo.max_bytes = n(4);
                        c.failure_memo.max_entry_bytes = n(5);
                        c.scopes_mut()
                            .add_diagnostic("kept", 0, 1, Severity::Warning);
                        c.set_state("user", 7_i32);
                        match f[1] {
                            "same" => {
                                for _ in 0..n(2) {
                                    budget_failure(&mut c);
                                }
                            }
                            "epoch" => {
                                for _ in 0..n(2) {
                                    c.mark_memoization_state_changed();
                                    budget_failure(&mut c);
                                }
                            }
                            "rollback" => {
                                budget_failure(&mut c);
                                let original = c.memoization_state_version();
                                for _ in 0..n(2) {
                                    let _: Result<(), ParseError> = c.transaction(|c| {
                                        c.mark_memoization_state_changed();
                                        budget_failure(c);
                                        c.set_state("user", 99_i32);
                                        Err(ParseError {
                                            offset: 0,
                                            expected: vec![],
                                        })
                                    });
                                    assert_eq!(c.memoization_state_version(), original);
                                }
                                budget_failure(&mut c);
                            }
                            "evict" | "underrun" => {
                                budget_failure(&mut c);
                                let _: Result<(), ParseError> = c.transaction(|c| {
                                    c.position = 4000;
                                    c.matched_position = 4000;
                                    budget_failure(c);
                                    if memo == Memoization::SafeFailures {
                                        assert_eq!(
                                            c.failure_memo.entries, 1,
                                            "old position must be evicted"
                                        );
                                        assert!(c.failure_memo.evicted_entries > 0);
                                    }
                                    if f[1] == "evict" {
                                        budget_failure(c);
                                    }
                                    Err(ParseError {
                                        offset: 0,
                                        expected: vec![],
                                    })
                                });
                                if f[1] == "underrun" {
                                    budget_failure(&mut c);
                                    budget_failure(&mut c);
                                    if memo == Memoization::SafeFailures {
                                        assert!(c.failure_memo.eviction_underruns > 0);
                                    }
                                }
                            }
                            _ => panic!("unknown action"),
                        }
                        let enabled = memo == Memoization::SafeFailures;
                        assert_eq!(
                            c.failure_memo_entries(),
                            if enabled { n(6) } else { 0 },
                            "{} {mode:?}/{reset}/{diagnostics:?}",
                            f[0]
                        );
                        assert_eq!(
                            c.memoized_failure_hits(),
                            if enabled { n(7) } else { 0 },
                            "{} hits {mode:?}/{reset}/{diagnostics:?}",
                            f[0]
                        );
                        assert_eq!(
                            c.failure_memo_budget_misses(),
                            if enabled { n(8) } else { 0 },
                            "{} rejects",
                            f[0]
                        );
                        assert!(c.failure_memo_retained_bytes() <= n(4));
                        assert_eq!(
                            c.failure_memo_retained_bytes() == 0,
                            c.failure_memo_entries() == 0
                        );
                        let at = if diagnostics == Diagnostics::DetailedOnFailure {
                            0
                        } else {
                            n(9)
                        };
                        assert_eq!(c.failure().offset, at, "{} diagnostic", f[0]);
                        assert_eq!(
                            c.failure().expected,
                            if at == 0 {
                                vec![]
                            } else {
                                vec!["!".to_owned()]
                            },
                            "{} expected",
                            f[0]
                        );
                        let snap = source::Snapshot::new("budget", 1, &input).unwrap();
                        assert_eq!(
                            (snap.utf16(at).unwrap(), snap.utf8(at).unwrap()),
                            if at == 0 { (0, 0) } else { (n(10), n(11)) }
                        );
                        assert_eq!((c.position(), c.matched_position()), (0, 0));
                        assert_eq!(c.source(), input);
                        println!(
                            "BUDGET\t{}\t{mode:?}\t{reset}\t{memo:?}\t{diagnostics:?}\t{}\t{}",
                            f[0],
                            c.failure_memo_entries(),
                            c.failure_memo_retained_bytes()
                        );
                        count += 1;
                    }
                }
            }
        }
    }
    assert_eq!(count, 600);
}

#[test]
fn failure_budget_replacement_releases_payload_and_eviction_restores_charge() {
    let mut memo = FailureMemoBuckets::with_window(Some(64));
    memo.max_entries = 2;
    memo.max_bytes = 2048;
    memo.max_entry_bytes = 1024;
    let key = FailureMemoKey::new(1, 0, 0, false, 2);
    let values = Rc::new(vec![1, 2, 3]);
    let old = Rc::downgrade(&values);
    let diagnostic = FailureDiagnostic {
        expected: ExpectedIds::Multiple(values),
        ..Default::default()
    };
    let charged = diagnostic.retained_bytes();
    memo.insert(0, key, diagnostic);
    assert_eq!((memo.entries, memo.retained_bytes), (1, charged));
    memo.insert(0, key, FailureDiagnostic::default());
    assert!(
        old.upgrade().is_none(),
        "replaced payload must no longer be retained"
    );
    assert_eq!((memo.entries, memo.retained_bytes), (1, 256));
    // A rejected replacement preserves the existing result and charge.
    let huge = FailureDiagnostic {
        memo_expected_text_bytes: usize::MAX,
        ..Default::default()
    };
    assert_eq!(huge.retained_bytes(), usize::MAX);
    memo.insert(0, key, huge);
    assert_eq!(
        (memo.entries, memo.retained_bytes, memo.budget_misses),
        (1, 256, 1)
    );
    assert_eq!(memo.get(0, &key).unwrap().memo_expected_text_bytes, 0);
    memo.observe_cursor(1024);
    assert_eq!((memo.entries, memo.retained_bytes), (0, 0));
    assert!(memo.probe(0, &key).is_none());
    memo.insert(0, key, FailureDiagnostic::default());
    assert_eq!((memo.entries, memo.retained_bytes), (1, 256));
    assert!(memo.probe(0, &key).is_some());
}

#[test]
fn failure_budget_payload_capacity_total_and_distant_index_are_bounded() {
    let mut memo = FailureMemoBuckets::with_window(None);
    memo.max_entries = 100;
    memo.max_bytes = 512;
    memo.max_entry_bytes = 512;
    let key = |n| FailureMemoKey::new(n, 0, 0, false, 2);
    memo.insert(0, key(0), FailureDiagnostic::default());
    memo.insert(0, key(1), FailureDiagnostic::default());
    assert_eq!((memo.entries, memo.retained_bytes), (2, 512));
    memo.insert(0, key(2), FailureDiagnostic::default());
    assert_eq!(
        (memo.entries, memo.retained_bytes, memo.budget_misses),
        (2, 512, 1)
    );
    let mut distant = FailureMemoBuckets::with_window(None);
    distant.max_bytes = 512; // Payload capacity is available; reject only the index.
    distant.insert(usize::MAX, key(0), FailureDiagnostic::default());
    distant.insert(100, key(0), FailureDiagnostic::default());
    assert!(
        distant.remaining.is_empty(),
        "reject before allocating distant buckets"
    );
    assert_eq!(
        (
            distant.entries,
            distant.retained_bytes,
            distant.budget_misses
        ),
        (0, 0, 2)
    );
    let values = Rc::new(Vec::with_capacity(1024));
    let borrowed = Rc::clone(&values);
    memo.max_bytes = 8192;
    memo.insert(
        0,
        key(3),
        FailureDiagnostic {
            expected: ExpectedIds::Multiple(values),
            ..Default::default()
        },
    );
    assert_eq!(
        Rc::strong_count(&borrowed),
        1,
        "oversized payload is not cloned or retained"
    );
    assert!(memo.get(0, &key(3)).is_none());
    assert_eq!((memo.entries, memo.retained_bytes), (2, 512));
    let mut names = FailureMemoBuckets::with_window(None);
    names.max_entry_bytes = 512;
    names.insert(
        0,
        key(0),
        FailureDiagnostic {
            memo_expected_text_bytes: 257,
            ..Default::default()
        },
    );
    assert_eq!(
        (names.entries, names.retained_bytes, names.budget_misses),
        (0, 0, 1)
    );
}

#[test]
fn failure_budget_invalid_configuration_and_extremes_use_safe_defaults() {
    for value in [
        None,
        Some(""),
        Some("-1"),
        Some("NaN"),
        Some("-0"),
        Some("+1"),
        Some("1001"),
        Some("184467440737095516160000"),
    ] {
        assert_eq!(parse_failure_budget(value, 123, 1000), 123, "{value:?}");
    }
    assert_eq!(parse_failure_budget(Some("0"), 123, 1000), 0);
    assert_eq!(parse_failure_budget(Some("1000"), 123, 1000), 1000);
}

fn success_budget_attempt(context: &mut ParseContext<'_>) {
    let (start, matched) = (context.position(), context.matched_position());
    let at = start;
    let kind = context
        .token_mode
        .map_or(token::TokenKind::Consumed, |m| m.kind);
    let mut old_id = None;
    let _: Result<(), ParseError> = context.transaction(|c| {
        let parsed = c.parse(&Expr::Rule(0)).unwrap();
        assert_eq!(
            (c.position(), c.matched_position()),
            (start + if kind.is_consumed() { 9 } else { 0 }, start + 9)
        );
        let id = c.token_node_id(parsed.root_node().unwrap()).unwrap();
        assert_eq!(c.token_info(id).unwrap().kind, kind);
        assert_eq!(c.token_text(id), Some(SUCCESS_TEXT));
        assert_eq!(
            c.node(parsed.root_node().unwrap()).unwrap().span,
            Span {
                start: at,
                end: at + 9
            }
        );
        assert_eq!(
            c.capture_spans("field"),
            &[Span {
                start: at,
                end: at + 9
            }]
        );
        assert!(c.token_info(id).unwrap().extra("changed").is_none());
        assert!(c.put_token_extra(id, "changed", "outside snapshot"));
        c.scopes_mut().declare("outer", at);
        assert!(c.scopes().is_declared("outer"));
        old_id = Some(id);
        Err(ParseError {
            offset: 0,
            expected: vec![],
        })
    });
    assert!(
        context.token_info(old_id.unwrap()).is_none(),
        "rollback must stale the returned ID"
    );
    assert!(context.capture_spans("field").is_empty());
    assert!(!context.scopes().is_declared("outer"));
    assert_eq!(
        (context.position(), context.matched_position()),
        (start, matched)
    );
    assert!(context.node(0).is_none());
    assert_eq!(
        context.scopes().diagnostics(),
        &[scope::SymbolDiagnostic {
            message: "kept".into(),
            offset: 0,
            length: 1,
            severity: Severity::Warning
        }]
    );
    assert_eq!(context.state::<i32>("user"), Some(&7));
}
const SUCCESS_TEXT: &str = "👨‍👩‍👧‍👦\r\n";

#[test]
fn success_budget_shared_oracle_bounds_epochs_and_preserves_real_hits() {
    let rows = include_str!("../../../conformance/success-memo-budget/cases.tsv");
    let modes = [
        None,
        Some(token::ParseMode {
            kind: token::TokenKind::Consumed,
            invert: false,
        }),
        Some(token::ParseMode {
            kind: token::TokenKind::MatchOnly,
            invert: false,
        }),
        Some(token::ParseMode {
            kind: token::TokenKind::VirtualConsumed,
            invert: false,
        }),
        Some(token::ParseMode {
            kind: token::TokenKind::VirtualMatchOnly,
            invert: false,
        }),
    ];
    let mut count = 0;
    for row in rows
        .lines()
        .filter(|r| !r.starts_with('#') && !r.is_empty())
    {
        let f: Vec<_> = row.split('\t').collect();
        let n = |i: usize| f[i].parse::<usize>().unwrap();
        for mode in modes {
            for reset in [false, true] {
                for diagnostics in [
                    Diagnostics::Auto,
                    Diagnostics::Detailed,
                    Diagnostics::DetailedOnFailure,
                ] {
                    for memo in [Memoization::Off, Memoization::SafeFailures] {
                        let far = matches!(f[1], "evict" | "underrun");
                        let input = if far {
                            SUCCESS_TEXT.to_owned() + &"x".repeat(4000 - 9) + SUCCESS_TEXT + "?"
                        } else {
                            SUCCESS_TEXT.to_owned() + "?"
                        };
                        let mut c = ParseContext::with_options(
                            &input,
                            ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                        );
                        c.token_mode = mode;
                        c.match_cursor_policy.reset = reset;
                        c.rules = share_grammar(vec![Rule {
                            name: "success",
                            expression: Expr::choice([
                                Expr::literal(SUCCESS_TEXT).then(Expr::literal("!")),
                                Expr::literal(SUCCESS_TEXT).capture("field"),
                            ]),
                        }]);
                        c.memo_safe_rules = memo_safe_rules(&c.rules);
                        c.success_memo = SuccessMemoBuckets::with_window(Some(64));
                        c.success_memo.max_entries = n(3);
                        c.success_memo.max_bytes = n(4);
                        c.success_memo.max_entry_bytes = n(5);
                        c.scopes_mut()
                            .add_diagnostic("kept", 0, 1, Severity::Warning);
                        c.set_state("user", 7_i32);
                        match f[1] {
                            "same" => {
                                for _ in 0..n(2) {
                                    success_budget_attempt(&mut c);
                                }
                            }
                            "epoch" => {
                                for _ in 0..n(2) {
                                    c.mark_memoization_state_changed();
                                    success_budget_attempt(&mut c);
                                }
                            }
                            "rollback" => {
                                success_budget_attempt(&mut c);
                                let original = c.memoization_state_version();
                                for _ in 0..n(2) {
                                    let _: Result<(), ParseError> = c.transaction(|c| {
                                        c.mark_memoization_state_changed();
                                        success_budget_attempt(c);
                                        c.set_state("user", 99_i32);
                                        Err(ParseError {
                                            offset: 0,
                                            expected: vec![],
                                        })
                                    });
                                    assert_eq!(c.memoization_state_version(), original);
                                }
                                success_budget_attempt(&mut c);
                            }
                            "evict" | "underrun" => {
                                success_budget_attempt(&mut c);
                                let _: Result<(), ParseError> = c.transaction(|c| {
                                    c.position = c.byte_offsets[4000];
                                    c.matched_position = c.position;
                                    success_budget_attempt(c);
                                    if memo == Memoization::SafeFailures {
                                        assert_eq!(
                                            c.success_memo.entries, 1,
                                            "old position must be evicted"
                                        );
                                        assert!(c.success_memo.evicted_entries > 0);
                                    }
                                    if f[1] == "evict" {
                                        success_budget_attempt(c);
                                    }
                                    Err(ParseError {
                                        offset: 0,
                                        expected: vec![],
                                    })
                                });
                                if f[1] == "underrun" {
                                    success_budget_attempt(&mut c);
                                    success_budget_attempt(&mut c);
                                    if memo == Memoization::SafeFailures {
                                        assert!(c.success_memo.eviction_underruns > 0);
                                    }
                                }
                            }
                            _ => panic!("unknown action"),
                        }
                        let enabled = memo == Memoization::SafeFailures;
                        assert_eq!(
                            c.success_memo_entries(),
                            if enabled { n(6) } else { 0 },
                            "{} {mode:?}/{reset}/{diagnostics:?}",
                            f[0]
                        );
                        assert_eq!(
                            c.memoized_success_hits(),
                            if enabled { n(7) } else { 0 },
                            "{} hits {mode:?}/{reset}/{diagnostics:?}",
                            f[0]
                        );
                        assert_eq!(
                            c.success_memo_budget_misses(),
                            if enabled { n(8) } else { 0 },
                            "{} rejects",
                            f[0]
                        );
                        assert!(c.success_memo_retained_bytes() <= n(4));
                        assert_eq!(
                            c.success_memo_retained_bytes() == 0,
                            c.success_memo_entries() == 0
                        );
                        let at = if diagnostics == Diagnostics::DetailedOnFailure {
                            0
                        } else {
                            n(9)
                        };
                        assert_eq!(c.failure().offset, at, "{} diagnostic", f[0]);
                        assert_eq!(
                            c.failure().expected,
                            if at == 0 {
                                vec![]
                            } else {
                                vec!["!".to_owned()]
                            },
                            "{} expected",
                            f[0]
                        );
                        let snap = source::Snapshot::new("budget", 1, &input).unwrap();
                        assert_eq!(
                            (snap.utf16(at).unwrap(), snap.utf8(at).unwrap()),
                            if at == 0 { (0, 0) } else { (n(10), n(11)) }
                        );
                        assert_eq!((c.position(), c.matched_position()), (0, 0));
                        assert_eq!(c.source(), input);
                        println!(
                            "SUCCESS_BUDGET\t{}\t{mode:?}\t{reset}\t{memo:?}\t{diagnostics:?}\t{}\t{}",
                            f[0],
                            c.success_memo_entries(),
                            c.success_memo_retained_bytes()
                        );
                        count += 1;
                    }
                }
            }
        }
    }
    assert_eq!(count, 600);
}

fn success_budget_sample() -> (FailureMemoKey, Rc<SuccessMemo>) {
    let mut c = ParseContext::with_options(
        "x",
        ParseOptions::with_memoization(Memoization::SafeFailures),
    );
    c.rules = share_grammar(vec![Rule {
        name: "sample",
        expression: Expr::literal("x"),
    }]);
    c.memo_safe_rules = memo_safe_rules(&c.rules);
    assert!(c.rule(0, 0).is_some());
    let (&key, value) = c.success_memo.first.iter().next().unwrap();
    (key, Rc::clone(value))
}

#[test]
fn success_budget_replacement_eviction_entry_and_index_capacity_are_accounted() {
    let (key, entry) = success_budget_sample();
    let bytes = entry.bytes;
    let old = Rc::downgrade(&entry);
    let mut memo = SuccessMemoBuckets::with_window(Some(64));
    memo.max_entries = 2;
    memo.max_entry_bytes = bytes;
    memo.max_bytes = bytes * 2;
    memo.insert(0, key, entry);
    let (_, replacement) = success_budget_sample();
    assert_eq!(replacement.bytes, bytes);
    memo.insert(0, key, replacement.clone());
    assert!(
        old.upgrade().is_none(),
        "old snapshot payload is released on replacement"
    );
    assert_eq!((memo.entries, memo.retained_bytes), (1, bytes));
    let other = FailureMemoKey::new(1, 0, 0, false, 0);
    memo.insert(0, other, replacement.clone());
    assert_eq!((memo.entries, memo.retained_bytes), (2, bytes * 2));
    assert_eq!(
        memo.snapshot_room(0, &key),
        bytes,
        "replacement can reuse its old charge"
    );
    let third = FailureMemoKey::new(2, 0, 0, false, 0);
    assert_eq!(memo.snapshot_room(0, &third), 0);
    memo.insert(0, third, replacement.clone());
    assert_eq!(
        (memo.entries, memo.retained_bytes, memo.budget_misses),
        (2, bytes * 2, 1)
    );
    let oversized = Rc::new(SuccessMemo {
        nodes: vec![],
        tokens: token::MemoTokens::capture(&token::Store::new(), 0, 0),
        root: 0,
        captures: vec![],
        consumed: 0,
        matched: 0,
        policy: MatchCursorPolicy::default(),
        expression_depth: 0,
        diagnostic: FailureDiagnostic::default(),
        bytes: usize::MAX,
    });
    memo.insert(0, key, oversized);
    assert_eq!(
        (memo.entries, memo.retained_bytes, memo.budget_misses),
        (2, bytes * 2, 2)
    );
    assert_eq!(memo.get(0, &key).unwrap().bytes, bytes);
    memo.observe_cursor(1024);
    assert_eq!((memo.entries, memo.retained_bytes), (0, 0));
    assert!(memo.probe(0, &key).is_none());
    memo.insert(0, key, replacement.clone());
    assert_eq!((memo.entries, memo.retained_bytes), (1, bytes));
    let mut distant = SuccessMemoBuckets::with_window(None);
    distant.max_bytes = bytes * 10;
    distant.max_entries = 10;
    assert_eq!(distant.snapshot_room(usize::MAX, &key), 0);
    distant.insert(usize::MAX, key, replacement.clone());
    distant.insert(1_000_000, key, replacement);
    assert!(distant.remaining.is_empty());
    assert_eq!(
        (
            distant.entries,
            distant.retained_bytes,
            distant.budget_misses
        ),
        (0, 0, 2)
    );
}

#[test]
fn success_budget_large_expected_payloads_and_foreign_edges_miss_before_copy() {
    let mut c = ParseContext::with_options(
        "x",
        ParseOptions::with_memoization(Memoization::SafeFailures),
    );
    c.rules = share_grammar(vec![Rule {
        name: "sample",
        expression: Expr::literal("x"),
    }]);
    c.memo_safe_rules = memo_safe_rules(&c.rules);
    let root = c.rule(0, 0).unwrap();
    let key = *c.success_memo.first.keys().next().unwrap();
    c.success_memo = SuccessMemoBuckets::with_window(None);
    c.success_memo.max_entry_bytes = 1024;
    let name = "q".repeat(4096);
    let id = c.expected_names.intern(&name);
    let diagnostic = FailureDiagnostic {
        expected: ExpectedIds::Single(id),
        ..Default::default()
    };
    c.memoize_success(0, key, 0, root, 0, diagnostic);
    assert_eq!(
        (
            c.success_memo.entries,
            c.success_memo.retained_bytes,
            c.success_memo.budget_misses
        ),
        (0, 0, 1)
    );
    let expected = Rc::new(Vec::with_capacity(4096));
    let held = Rc::clone(&expected);
    c.memoize_success(
        0,
        key,
        0,
        root,
        0,
        FailureDiagnostic {
            expected: ExpectedIds::Multiple(expected),
            ..Default::default()
        },
    );
    assert_eq!(Rc::strong_count(&held), 1);
    assert_eq!(
        (
            c.success_memo.entries,
            c.success_memo.retained_bytes,
            c.success_memo.budget_misses
        ),
        (0, 0, 2)
    );
    c.success_memo.max_entry_bytes = 1048576;
    c.nodes[root].children.push(usize::MAX);
    c.memoize_success(0, key, 0, root, 0, FailureDiagnostic::default());
    assert_eq!(
        (
            c.success_memo.entries,
            c.success_memo.retained_bytes,
            c.success_memo.budget_misses
        ),
        (0, 0, 3)
    );
}

#[test]
fn failure_budget_shared_configuration_in_fresh_processes() {
    let rows = include_str!("../../../conformance/failure-memo-budget/configuration.tsv");
    let mut observed = 0;
    for line in rows
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
    {
        let row: Vec<_> = line.split('\t').collect();
        assert_eq!(row.len(), 5, "{line}");
        let raw = if row[1] == "-" {
            None
        } else {
            let bytes = row[1]
                .as_bytes()
                .chunks(2)
                .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
                .collect::<Vec<_>>();
            Some(String::from_utf8(bytes).unwrap())
        };
        let mut child = std::process::Command::new(std::env::current_exe().unwrap());
        child.args([
            "--exact",
            "memo_retention_tests::failure_budget_configuration_child",
            "--nocapture",
        ]);
        child.env("UNLAXER_TEST_FAILURE_MEMO_EXPECTED", row[2..].join(","));
        for field in ["ENTRIES", "BYTES", "ENTRY_BYTES"] {
            let name = format!("UNLAXER_FAILURE_MEMO_MAX_{field}");
            if let Some(value) = &raw {
                child.env(name, value);
            } else {
                child.env_remove(name);
            }
            child.env_remove(format!("UNLAXER_SUCCESS_MEMO_MAX_{field}"));
        }
        let output = child.output().unwrap();
        assert!(
            output.status.success(),
            "{}: {}{}",
            row[0],
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(
            String::from_utf8_lossy(&output.stdout).contains("CONFIG_OK"),
            "{}",
            row[0]
        );
        observed += 1;
    }
    assert_eq!(observed, 25);
}

#[test]
fn failure_budget_configuration_child() {
    let Ok(raw) = std::env::var("UNLAXER_TEST_FAILURE_MEMO_EXPECTED") else {
        return;
    };
    let expected = raw
        .split(',')
        .map(|x| x.parse::<usize>().unwrap())
        .collect::<Vec<_>>();
    let budget = failure_memo_budget();
    assert_eq!(
        (budget.entries, budget.bytes, budget.entry_bytes),
        (expected[0], expected[1], expected[2])
    );
    let success = success_memo_budget();
    assert_eq!(
        (success.entries, success.bytes, success.entry_bytes),
        (65_536, 16 * 1024 * 1024, 2 * 1024 * 1024)
    );
    assert_eq!(ParseOptions::default().memoization, Memoization::Off);
    assert_eq!(ParseOptions::default().diagnostics, Diagnostics::Auto);
    println!("CONFIG_OK");
}
