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

#[test]
fn exact_node_and_edge_limits_accept_then_reject_one_more() {
    use unlaxer_runtime::mapping::{MAX_EDGES, MAX_NODES};
    assert_eq!(MAX_NODES, 1_048_576);
    assert_eq!(MAX_EDGES, 8_388_608);
    let mut value = tree();
    let leaf = Node {
        rule: 0,
        span: Span { start: 0, end: 1 },
        children: vec![],
        captures: vec![],
    };
    value.nodes = vec![leaf.clone(); MAX_NODES];
    value.root = MAX_NODES - 1;
    Context::new(&value, 1).unwrap();
    value.nodes.push(leaf.clone());
    invalid(&value, "arena nodes");
    value.nodes = vec![
        leaf.clone(),
        Node {
            children: vec![0; MAX_EDGES],
            ..leaf
        },
    ];
    value.root = 1;
    Context::new(&value, 1).unwrap();
    value.nodes[1].children.push(0);
    invalid(&value, "arena edges");
    Context::new(&tree(), 1).unwrap();
}
#[test]
fn forward_capture_ranges_and_exact_utf8_budget_are_checked() {
    let good = tree();
    let mut bad = good.clone();
    bad.nodes[bad.root].captures[0].span.end = usize::MAX;
    invalid(&bad, "CST");
    let mut bad = good.clone();
    bad.nodes.push(bad.nodes[bad.root].clone());
    let forward = bad.nodes.len() - 1;
    bad.nodes[bad.root].children.push(forward);
    invalid(&bad, "CST");
    assert_eq!(MAX_TEXT_BYTES, 268_435_456);
    let guard = Context::new(&good, 1).unwrap();
    guard.charge_text_bytes(MAX_TEXT_BYTES - 4).unwrap();
    assert_eq!(guard.text(Span { start: 0, end: 1 }).unwrap(), "😀");
    assert!(guard.text(Span { start: 0, end: 1 }).is_err());
    assert_eq!(
        Context::new(&good, 1)
            .unwrap()
            .text(Span { start: 0, end: 1 })
            .unwrap(),
        "😀"
    );
}
