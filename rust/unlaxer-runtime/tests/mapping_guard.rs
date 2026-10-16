use unlaxer_runtime::{
    mapping::{Context, MAX_DEPTH, MAX_TEXT_BYTES},
    Capture, Expr, Node, Rule, Span, Tree,
};
fn tree() -> Tree {
    unlaxer_runtime::parse(
        &[Rule {
            name: "Root",
            expression: Expr::literal("😀").capture("value"),
        }],
        0,
        false,
        "😀",
    )
    .unwrap()
}
fn invalid(tree: &Tree, fragment: &str) {
    let error = Context::new(tree, 1).err().expect("invalid graph accepted");
    assert!(error.contains(fragment), "{error}");
}
#[test]
fn invalid_public_ids_source_and_spans_fail_without_panicking() {
    let good = tree();
    let mut bad = good.clone();
    bad.root = usize::MAX;
    invalid(&bad, "CST");
    let mut bad = good.clone();
    bad.source = "a".into();
    invalid(&bad, "CST");
    let mut bad = good.clone();
    bad.nodes[bad.root].span.end = 2;
    invalid(&bad, "CST");
    let mut bad = good.clone();
    bad.nodes[bad.root].rule = 1;
    invalid(&bad, "unknown rule");
    let mut bad = good.clone();
    bad.nodes[bad.root].children.push(usize::MAX);
    invalid(&bad, "CST");
    let mut bad = good.clone();
    bad.nodes[bad.root].children.push(bad.root);
    invalid(&bad, "CST");
    let mut bad = good.clone();
    bad.nodes[bad.root].captures[0].nodes.push(usize::MAX);
    invalid(&bad, "CST");
    Context::new(&good, 1)
        .unwrap()
        .ensure_mappable(good.root)
        .unwrap();
}
#[test]
fn capture_must_be_descendant_but_shortcuts_and_noncontaining_spans_are_legal() {
    let mut value = tree();
    let leaf = Node {
        rule: 0,
        span: Span { start: 0, end: 1 },
        children: vec![],
        captures: vec![],
    };
    value.nodes = vec![
        leaf.clone(),
        leaf.clone(),
        Node {
            rule: 0,
            span: Span { start: 0, end: 0 },
            children: vec![0],
            captures: vec![Capture {
                name: "value",
                span: leaf.span,
                nodes: vec![0],
            }],
        },
    ];
    value.root = 2;
    Context::new(&value, 1).unwrap();
    value.nodes[2].captures[0].nodes = vec![1];
    invalid(&value, "outside");
    value.nodes[1].children = vec![0];
    value.nodes[2].children = vec![1];
    value.nodes[2].captures[0].nodes = vec![0];
    Context::new(&value, 1).unwrap();
}
#[test]
fn depth_boundary_actual_dag_work_and_text_are_bounded_per_call() {
    let mut value = tree();
    value.nodes.clear();
    for id in 0..MAX_DEPTH {
        value.nodes.push(Node {
            rule: 0,
            span: Span { start: 0, end: 1 },
            children: if id == 0 { vec![] } else { vec![id - 1] },
            captures: vec![],
        });
    }
    value.root = MAX_DEPTH - 1;
    Context::new(&value, 1).unwrap();
    value.nodes.push(Node {
        rule: 0,
        span: Span { start: 0, end: 1 },
        children: vec![value.root],
        captures: vec![],
    });
    value.root += 1;
    invalid(&value, "depth");
    let good = tree();
    let guard = Context::with_work_limit(&good, 1, 3).unwrap();
    assert!(guard.visit(good.root).is_ok());
    assert!(guard.visit(good.root).is_err());
    let fresh = Context::new(&good, 1).unwrap();
    fresh.charge_text_bytes(MAX_TEXT_BYTES).unwrap();
    assert!(fresh.text(Span { start: 0, end: 1 }).is_err());
    assert_eq!(
        Context::new(&good, 1)
            .unwrap()
            .text(Span { start: 0, end: 1 })
            .unwrap(),
        "😀"
    );
}

fn flattened_forest(leaves: usize) -> Tree {
    let mut value = tree();
    let leaf = Node {
        rule: 0,
        span: Span { start: 0, end: 1 },
        children: vec![],
        captures: vec![],
    };
    value.nodes = vec![leaf.clone(); leaves];
    value.nodes.push(Node {
        children: (0..leaves).collect(),
        ..leaf.clone()
    });
    value.nodes.push(Node {
        children: vec![leaves],
        captures: vec![Capture {
            name: "value",
            span: leaf.span,
            nodes: (0..leaves).collect(),
        }],
        ..leaf
    });
    value.root = leaves + 1;
    value
}

#[test]
fn ordinary_flattened_tree_reachability_scales_without_spending_dag_budget() {
    // Previously 4,096 leaves exhausted the 8M search budget despite height three.
    // This larger legal forest has O(nodes + edges + capture targets) validation.
    let value = flattened_forest(65_536);
    let guard = Context::new(&value, 1).unwrap();
    guard.visit(value.root).unwrap();
    assert_eq!(guard.text(value.nodes[value.root].span).unwrap(), "😀");
    let mut bad = value.clone();
    bad.nodes[bad.root].children.clear();
    invalid(&bad, "outside");
    assert!(Context::new(&value, 1).is_ok());
}

#[test]
fn forest_intervals_handle_interleaved_ids_duplicate_edges_and_foreign_targets() {
    let mut value = flattened_forest(2);
    let leaf = value.nodes[0].clone();
    value.nodes = vec![
        leaf.clone(),
        leaf.clone(),
        Node {
            children: vec![0, 0],
            captures: vec![Capture {
                name: "value",
                span: leaf.span,
                nodes: vec![0],
            }],
            ..leaf.clone()
        },
        Node {
            children: vec![1],
            ..leaf.clone()
        },
        Node {
            children: vec![2, 3],
            captures: vec![Capture {
                name: "value",
                span: leaf.span,
                nodes: vec![0, 1],
            }],
            ..leaf
        },
    ];
    value.root = 4;
    Context::new(&value, 1).unwrap();
    // Numeric arena IDs alone cannot imply membership: 1 is outside node 2.
    value.nodes[2].captures[0].nodes = vec![1];
    invalid(&value, "outside");
    value.nodes[2].captures[0].nodes = vec![0];
    // A distinct original parent triggers bounded DAG validation, not tree intervals.
    value.nodes[3].children.push(0);
    Context::new(&value, 1).unwrap();
    value.nodes[3].children.clear();
    invalid(&value, "outside");
}

#[test]
fn genuinely_shared_original_dag_keeps_capture_reachability_budget() {
    let mut value = flattened_forest(4096);
    let mut extra = value.nodes[0].clone();
    extra.children = vec![0];
    // Another original parent of leaf zero; the old root is also retained in the arena.
    value.nodes.push(extra);
    invalid(&value, "E-MAPPING-LIMIT: capture reachability");
    assert!(Context::new(&tree(), 1).is_ok());
}
