use std::sync::Arc;
use unlaxer_runtime::{
    CheckpointMetrics, DirectFragment, DirectRuleTable, ExecutionTier, Expr, Memoization,
    ParseContext, ParseError, ParseMatch, ParseOptions, Rule, SharedGrammar,
};

fn literal_a(context: &mut ParseContext<'_>, depth: usize) -> Option<DirectFragment> {
    context.direct_literal("a", depth)
}

fn reference_leaf(context: &mut ParseContext<'_>, depth: usize) -> Option<DirectFragment> {
    context.direct_rule(2, depth)
}

fn capture_leaf(context: &mut ParseContext<'_>, depth: usize) -> Option<DirectFragment> {
    context.direct_capture("value", reference_leaf, depth)
}

fn literal_b(context: &mut ParseContext<'_>, depth: usize) -> Option<DirectFragment> {
    context.direct_literal("b", depth)
}

fn root(context: &mut ParseContext<'_>, depth: usize) -> Option<DirectFragment> {
    context.direct_sequence(&[literal_a, capture_leaf, literal_b], depth)
}

fn fallback(context: &mut ParseContext<'_>, depth: usize) -> Option<DirectFragment> {
    context.direct_fallback(
        &Expr::Sequence(vec![
            Expr::Lookahead {
                child: Box::new(Expr::Rule(2)),
                positive: true,
            },
            Expr::Rule(2),
        ]),
        depth,
    )
}

fn leaf_a(context: &mut ParseContext<'_>, depth: usize) -> Option<DirectFragment> {
    context.direct_literal("x", depth)
}

fn leaf_b(context: &mut ParseContext<'_>, depth: usize) -> Option<DirectFragment> {
    context.direct_number(depth)
}

fn leaf(context: &mut ParseContext<'_>, depth: usize) -> Option<DirectFragment> {
    context.direct_choice(&[leaf_a, leaf_b], depth)
}

static DIRECT: DirectRuleTable = DirectRuleTable::new(&[root, fallback, leaf]);

fn grammar() -> SharedGrammar {
    Arc::from([
        Rule {
            name: "Root",
            expression: Expr::Sequence(vec![
                Expr::Literal("a"),
                Expr::Capture("value", Box::new(Expr::Rule(2))),
                Expr::Literal("b"),
            ]),
        },
        Rule {
            name: "Fallback",
            expression: Expr::Sequence(vec![
                Expr::Lookahead {
                    child: Box::new(Expr::Rule(2)),
                    positive: true,
                },
                Expr::Rule(2),
            ]),
        },
        Rule {
            name: "Leaf",
            expression: Expr::Choice(vec![Expr::Literal("x"), Expr::Number]),
        },
    ])
}

fn run(
    grammar: &SharedGrammar,
    root: usize,
    input: &str,
    memoization: Memoization,
    execution_tier: ExecutionTier,
) -> (
    Result<ParseMatch, ParseError>,
    usize,
    usize,
    CheckpointMetrics,
    Option<unlaxer_runtime::Tree>,
) {
    let mut context = ParseContext::with_options(
        input,
        ParseOptions {
            memoization,
            execution_tier,
        },
    );
    context.enable_checkpoint_metrics();
    let result = context.parse_shared_grammar_with_direct(grammar, &DIRECT, root, false);
    let tree = result
        .as_ref()
        .ok()
        .and_then(ParseMatch::root_node)
        .and_then(|root| context.tree(root));
    (
        result,
        context.position(),
        context.matched_position(),
        context.snapshot_checkpoint_metrics(),
        tree,
    )
}

#[test]
fn direct_and_combinator_are_observationally_equal() {
    let grammar = grammar();
    for memoization in [Memoization::Off, Memoization::SafeFailures] {
        for input in ["axb", "a12b", "ayb"] {
            let combinator = run(&grammar, 0, input, memoization, ExecutionTier::Combinator);
            let direct = run(&grammar, 0, input, memoization, ExecutionTier::Direct);
            assert_eq!(combinator.0, direct.0, "result for {input:?}");
            assert_eq!(combinator.1, direct.1, "consumed for {input:?}");
            assert_eq!(combinator.2, direct.2, "matched for {input:?}");
            assert_eq!(combinator.3, direct.3, "checkpoints for {input:?}");
            match (combinator.4, direct.4) {
                (Some(left), Some(right)) => assert_eq!(left.nodes, right.nodes),
                (None, None) => {}
                unexpected => panic!("tree mismatch: {unexpected:?}"),
            }
        }
    }
}

#[test]
fn fallback_can_reenter_direct_rules() {
    let grammar = grammar();
    for memoization in [Memoization::Off, Memoization::SafeFailures] {
        for input in ["x", "42", "?"] {
            let combinator = run(&grammar, 1, input, memoization, ExecutionTier::Combinator);
            let direct = run(&grammar, 1, input, memoization, ExecutionTier::Direct);
            assert_eq!(combinator.0, direct.0);
            assert_eq!(combinator.1, direct.1);
            assert_eq!(combinator.2, direct.2);
            assert_eq!(combinator.3, direct.3);
            match (combinator.4, direct.4) {
                (Some(left), Some(right)) => assert_eq!(left.nodes, right.nodes),
                (None, None) => {}
                unexpected => panic!("tree mismatch: {unexpected:?}"),
            }
        }
    }
}
