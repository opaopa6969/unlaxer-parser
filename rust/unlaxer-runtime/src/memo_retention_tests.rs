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
