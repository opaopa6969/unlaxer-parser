use unlaxer_runtime::{
    share_grammar, Diagnostics, Expr, Memoization, ParseContext, ParseError, ParseOptions,
    ParseResult, Rule, Span,
};

fn retry(context: &mut ParseContext<'_>) -> ParseResult {
    let enabled = *context.state::<bool>("enabled").unwrap();
    let text = *context.state::<&'static str>("word").unwrap();
    let mut retained = Vec::new();
    let mut checkpoints = Vec::new();
    let mut old_ids = Vec::new();
    // A pre-existing unrelated node forces cache edges to relocate after rollback.
    context.parse(&Expr::Empty.capture_value()).unwrap();
    for attempt in 0..3 {
        let before = context.snapshot_checkpoint_metrics().opened;
        let mut saved = None;
        let result: Result<(), ParseError> = context.transaction(|context| {
            let matched = context.parse(&Expr::Rule(1)).unwrap();
            assert_eq!(context.position(), text.chars().count());
            assert_eq!(context.matched_position(), text.chars().count());
            assert_eq!(
                context.capture_spans("text"),
                &[Span {
                    start: 0,
                    end: text.chars().count()
                }]
            );
            let root = matched.root_node().unwrap();
            let node_id = context.token_node_id(root).unwrap();
            for &old_id in &old_ids {
                assert_ne!(old_id, node_id);
                assert!(
                    context.token_info(old_id).is_none(),
                    "a new replay must never revive a rolled-back ID"
                );
            }
            assert_eq!(context.token_text(node_id), Some(text));
            assert!(context
                .token_info(node_id)
                .unwrap()
                .extra("mutated")
                .is_none());
            assert!(context.put_token_extra(node_id, "mutated", "outside memo"));
            let mut tree = context.tree(root).unwrap();
            tree.validate_structure().unwrap();
            assert!(tree.nodes[root]
                .children
                .iter()
                .all(|&child| child >= 1 && child < root));
            retained.push(tree.clone());
            tree.nodes[root].children.clear();
            tree.nodes[root].captures.clear();
            saved = Some(node_id);
            Err(ParseError {
                offset: 0,
                expected: vec![],
            })
        });
        assert!(result.is_err());
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        assert!(context.capture_spans("text").is_empty());
        assert!(context.node(1).is_none());
        assert!(context.token_info(saved.unwrap()).is_none());
        old_ids.push(saved.unwrap());
        checkpoints.push(context.snapshot_checkpoint_metrics().opened - before);
        assert_eq!(
            context.memoized_success_hits(),
            if enabled { attempt } else { 0 }
        );
    }
    assert_eq!(
        format!("{:?}", retained[0].nodes),
        format!("{:?}", retained[1].nodes)
    );
    assert_eq!(
        format!("{:?}", retained[1].nodes),
        format!("{:?}", retained[2].nodes)
    );
    if enabled {
        assert!(
            checkpoints[1] < checkpoints[0],
            "actual replay must skip child checkpoints: {checkpoints:?}"
        );
    } else {
        assert_eq!(checkpoints[0], checkpoints[1]);
    }
    Ok(unlaxer_runtime::ParseMatch::empty(Span {
        start: 0,
        end: 0,
    }))
}

#[test]
fn successes_relocate_owned_cst_and_captures_without_reusing_rolled_back_ids() {
    for row in include_str!("../../../conformance/pure-success-memo/rollback.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
    {
        let fields = row.split('\t').collect::<Vec<_>>();
        let word: &'static str = if fields[1] == "-" {
            ""
        } else {
            let bytes = (0..fields[1].len())
                .step_by(2)
                .map(|i| u8::from_str_radix(&fields[1][i..i + 2], 16).unwrap())
                .collect();
            Box::leak(String::from_utf8(bytes).unwrap().into_boxed_str())
        };
        assert_eq!(word.chars().count(), fields[2].parse::<usize>().unwrap());
        assert_eq!(
            word.encode_utf16().count(),
            fields[3].parse::<usize>().unwrap()
        );
        assert_eq!(word.len(), fields[4].parse::<usize>().unwrap());
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let mut outcomes = Vec::new();
            for memo in [Memoization::Off, Memoization::SafeFailures] {
                let input = format!("{word}?");
                let mut context = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                context.enable_checkpoint_metrics();
                context.set_state("enabled", memo == Memoization::SafeFailures);
                context.set_state("word", word);
                let grammar = share_grammar(vec![
                    Rule {
                        name: "opaque_driver",
                        expression: Expr::Custom(retry),
                    },
                    Rule {
                        name: "pure",
                        expression: Expr::choice([
                            Expr::literal(word).then(Expr::literal("!")),
                            Expr::literal(word).capture_value().capture("text"),
                        ]),
                    },
                ]);
                context.parse_shared_grammar(&grammar, 0, false).unwrap();
                outcomes.push(context.failure());
            }
            assert_eq!(outcomes[0], outcomes[1], "{word:?}/{diagnostics:?}");
        }
    }
}

fn repeated_captures(context: &mut ParseContext<'_>) -> ParseResult {
    for attempt in 0..3 {
        let _: Result<(), ParseError> = context.transaction(|context| {
            let parsed = context.parse(&Expr::Rule(1)).unwrap();
            assert_eq!(
                context.capture_spans("x"),
                &[Span { start: 0, end: 1 }, Span { start: 1, end: 2 }]
            );
            assert_eq!(context.capture_spans("y"), &[Span { start: 2, end: 3 }]);
            assert_eq!(context.capture_spans("outer"), &[Span { start: 0, end: 3 }]);
            assert_eq!(context.captured("x"), Some("b"));
            let captures = &context.node(parsed.root_node().unwrap()).unwrap().captures;
            assert_eq!(
                captures
                    .iter()
                    .map(|c| (c.name, c.span))
                    .collect::<Vec<_>>(),
                vec![
                    ("x", Span { start: 0, end: 1 }),
                    ("x", Span { start: 1, end: 2 }),
                    ("y", Span { start: 2, end: 3 }),
                    ("outer", Span { start: 0, end: 3 }),
                ]
            );
            Err(ParseError {
                offset: 0,
                expected: vec![],
            })
        });
        assert!(context.capture_spans("x").is_empty());
        assert_eq!(context.memoized_success_hits(), attempt);
    }
    Ok(unlaxer_runtime::ParseMatch::empty(Span {
        start: 0,
        end: 0,
    }))
}

#[test]
fn repeated_capture_names_replay_in_publication_order() {
    let mut context = ParseContext::with_options(
        "abc",
        ParseOptions::with_memoization(Memoization::SafeFailures),
    );
    let grammar = share_grammar(vec![
        Rule {
            name: "driver",
            expression: Expr::Custom(repeated_captures),
        },
        Rule {
            name: "pure",
            expression: Expr::Sequence(vec![
                Expr::literal("a").capture("x"),
                Expr::literal("b").capture("x"),
                Expr::literal("c").capture("y"),
            ])
            .capture("outer"),
        },
    ]);
    context.parse_shared_grammar(&grammar, 0, false).unwrap();
}
