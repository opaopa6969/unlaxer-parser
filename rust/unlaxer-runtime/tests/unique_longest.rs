use unlaxer_runtime::{parse_detailed, Expr, Memoization, ParseContext, ParseOptions, Rule};

#[test]
fn tied_failures_replay_with_memo_without_state_or_nodes() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        let options = ParseOptions::with_memoization(memo);
        let rules = vec![Rule {
            name: "Root",
            expression: Expr::UniqueLongestChoice(vec![Expr::literal("😀"), Expr::literal("😀")]),
        }];
        let mut context = ParseContext::with_options("😀", options);
        for _ in 0..2 {
            assert!(context
                .parse_shared_grammar(&unlaxer_runtime::share_grammar(rules.clone()), 0, false)
                .is_err());
            assert_eq!((context.position(), context.matched_position()), (0, 0));
        }
    }
}

#[test]
fn pure_profile_resource_limit_rejects_before_running_candidates() {
    let mut context = ParseContext::new("a");
    assert!(context
        .parse(&Expr::UniqueLongestChoice(vec![Expr::literal("a"); 65]))
        .is_err());
    assert_eq!(context.position(), 0);
    assert!(context
        .failure()
        .expected
        .contains(&"2 to 64 unique longest alternatives".to_owned()));
}

#[test]
fn larger_candidate_resets_ties_and_empty_winner_is_rejected() {
    let mut context = ParseContext::new("😀x");
    assert!(context
        .parse(&Expr::UniqueLongestChoice(vec![
            Expr::literal("😀"),
            Expr::literal("😀"),
            Expr::literal("😀x")
        ]))
        .is_ok());
    assert_eq!(context.position(), 2);
    let rules = vec![Rule {
        name: "Root",
        expression: Expr::UniqueLongestChoice(vec![Expr::literal(""), Expr::literal("a")]),
    }];
    let error = parse_detailed(&rules, 0, false, "").unwrap_err();
    assert_eq!(error.kind, "empty_choice");
    assert_eq!(error.offset, 0);
}

fn marked(context: &mut ParseContext<'_>) -> unlaxer_runtime::ParseResult {
    let matched = context.parse(&Expr::literal("a"))?;
    context.set_state("candidate", "discard");
    context.scopes_mut().declare("discard", 0);
    Ok(matched)
}

#[test]
fn tie_discards_state_captures_scopes_and_fallback_keeps_only_selected_cst() {
    let tied =
        Expr::unique_longest_choice([Expr::Custom(marked).capture("discard"), Expr::literal("a")]);
    let mut context = ParseContext::new("ab");
    assert!(context.parse(&tied).is_err());
    assert!(context.state::<&str>("candidate").is_none());
    assert!(context.captured("discard").is_none());
    assert!(!context.scopes().is_declared("discard"));
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    let rules = vec![
        Rule {
            name: "Root",
            expression: Expr::unique_longest_choice([Expr::Rule(1), Expr::Rule(2)]),
        },
        Rule {
            name: "Short",
            expression: Expr::literal("a"),
        },
        Rule {
            name: "Long",
            expression: Expr::literal("ab"),
        },
    ];
    let tree = unlaxer_runtime::parse(&rules, 0, false, "ab").unwrap();
    assert_eq!(tree.nodes.len(), 2);
    assert_eq!(tree.nodes[tree.root].children, vec![0]);
    assert_eq!(tree.nodes[0].rule, 2);
    assert_eq!(tree.text(tree.nodes[0].span), "ab");
}
