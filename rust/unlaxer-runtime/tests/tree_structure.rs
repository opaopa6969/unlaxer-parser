use unlaxer_runtime::{parse_detailed_with_options, Expr, ParseOptions, Rule, Span};

#[test]
fn public_arena_validation_rejects_stale_source_cycles_and_bad_spans() {
    let rules = vec![
        Rule {
            name: "Root",
            expression: Expr::Rule(1),
        },
        Rule {
            name: "Leaf",
            expression: Expr::literal("😀"),
        },
    ];
    let tree =
        parse_detailed_with_options(&rules, 0, false, "😀", ParseOptions::default()).unwrap();
    assert!(tree.validate_structure().is_ok());
    let mut bad = tree.clone();
    bad.source = "x".into();
    assert!(bad.validate_structure().is_err());
    let mut bad = tree.clone();
    bad.root = bad.nodes.len();
    assert!(bad.validate_structure().is_err());
    let mut bad = tree.clone();
    bad.nodes[tree.root].children.push(tree.root);
    assert!(bad.validate_structure().is_err());
    let mut bad = tree.clone();
    bad.nodes[0].span = Span { start: 1, end: 0 };
    assert!(bad.validate_structure().is_err());
    let mut bad = tree;
    bad.nodes[0].span.end = 2;
    assert!(bad.validate_structure().is_err());
}
