use std::sync::Arc;

use unlaxer_runtime::{
    parse_detailed, parse_detailed_shared, share_grammar, Expr, ParseContext, ParseResult, Rule,
    SharedGrammar,
};

fn stateful_x(context: &mut ParseContext<'_>) -> ParseResult {
    let visits = context.state::<usize>("visits").copied().unwrap_or(0);
    context.set_state("visits", visits + 1);
    context.parse(&Expr::Literal("x"))
}

fn grammar() -> SharedGrammar {
    share_grammar(vec![Rule {
        name: "root",
        expression: Expr::Capture("value", Box::new(Expr::Custom(stateful_x))),
    }])
}

#[test]
fn shared_entry_preserves_existing_ast_and_diagnostic_results() {
    let owned = vec![Rule {
        name: "root",
        expression: Expr::Sequence(vec![Expr::Literal("x"), Expr::Eof]),
    }];
    let shared = share_grammar(owned.clone());

    let old_tree = parse_detailed(&owned, 0, false, "x").unwrap();
    let shared_tree = parse_detailed_shared(&shared, 0, false, "x").unwrap();
    assert_eq!(old_tree.source, shared_tree.source);
    assert_eq!(old_tree.nodes.len(), shared_tree.nodes.len());
    assert_eq!(
        old_tree.nodes[old_tree.root].span,
        shared_tree.nodes[shared_tree.root].span
    );

    let old_error = parse_detailed(&owned, 0, false, "xy").unwrap_err();
    let shared_error = parse_detailed_shared(&shared, 0, false, "xy").unwrap_err();
    assert_eq!(old_error, shared_error);
}

#[test]
fn shared_graph_keeps_context_state_captures_and_nodes_parse_local() {
    let grammar = grammar();
    let initial_strong_count = Arc::strong_count(&grammar);

    for input in ["x", "x"] {
        let mut context = ParseContext::new(input);
        let parsed = context.parse_shared_grammar(&grammar, 0, false).unwrap();
        assert_eq!(context.state::<usize>("visits"), Some(&1));
        assert_eq!(context.captured("value"), Some("x"));
        assert_eq!(parsed.span.start, 0);
        assert_eq!(parsed.span.end, 1);
        assert!(parsed.root_node().is_some());
    }

    // The temporary grammar installation is released after each parse.
    assert_eq!(Arc::strong_count(&grammar), initial_strong_count);
}

#[test]
fn concurrent_contexts_share_only_the_immutable_graph() {
    let grammar = grammar();
    let graph_address = Arc::as_ptr(&grammar) as *const () as usize;
    let threads = (0..8)
        .map(|_| {
            let grammar = Arc::clone(&grammar);
            std::thread::spawn(move || {
                assert_eq!(Arc::as_ptr(&grammar) as *const () as usize, graph_address);
                let mut context = ParseContext::new("x");
                let parsed = context.parse_shared_grammar(&grammar, 0, false).unwrap();
                (
                    context.state::<usize>("visits").copied(),
                    context.captured("value").map(str::to_owned),
                    parsed.nodes.len(),
                )
            })
        })
        .collect::<Vec<_>>();

    for thread in threads {
        assert_eq!(thread.join().unwrap(), (Some(1), Some("x".to_owned()), 1));
    }
}
