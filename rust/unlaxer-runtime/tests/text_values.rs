use unlaxer_runtime::{
    parse, Expr, ParseContext, ParseResult, Rule, Span, TEXT_VALUE_RULE, VALUE_BOUNDARY_RULE,
};

#[test]
fn repeated_mixed_values_keep_order_original_nodes_and_both_capture_layers() {
    let rules = vec![
        Rule {
            name: "root",
            expression: Expr::Rule(1)
                .capture("inside")
                .text_value()
                .or(Expr::Rule(2))
                .capture("items")
                .one_or_more(),
        },
        Rule {
            name: "text",
            expression: Expr::literal("a"),
        },
        Rule {
            name: "mapped",
            expression: Expr::literal("b"),
        },
    ];
    let tree = parse(&rules, 0, false, "abba").unwrap();
    let root = &tree.nodes[tree.root];
    let items: Vec<_> = root.captures.iter().filter(|c| c.name == "items").collect();
    assert_eq!(items.len(), 4);
    assert_eq!(root.children.len(), 4);
    for (index, expected) in [TEXT_VALUE_RULE, 2, 2, TEXT_VALUE_RULE]
        .into_iter()
        .enumerate()
    {
        let id = items[index].nodes[0];
        assert_eq!(items[index].nodes.len(), 1);
        assert_eq!(root.children[index], id);
        let node = &tree.nodes[id];
        assert_eq!(node.rule, expected);
        assert_eq!(
            node.span,
            Span {
                start: index,
                end: index + 1
            }
        );
        assert_eq!(items[index].span, node.span);
        if expected == TEXT_VALUE_RULE {
            assert!(node.captures.is_empty());
            assert_eq!(node.children.len(), 1);
            assert_eq!(tree.nodes[node.children[0]].rule, 1);
            let inner = root
                .captures
                .iter()
                .find(|c| c.name == "inside" && c.span == node.span)
                .unwrap();
            assert_eq!(inner.nodes, node.children);
        }
    }
    assert_eq!(tree.nodes.len(), 7); // four original values, two boundaries, one root
    assert_eq!(
        root.captures.iter().map(|c| c.name).collect::<Vec<_>>(),
        ["inside", "items", "items", "items", "inside", "items"]
    );
}

#[test]
fn unicode_trivia_and_zero_width_spans_are_raw_consumed_spans() {
    let rules = [Rule {
        name: "root",
        expression: Expr::sequence([Expr::literal("😀").capture("emoji")])
            .text_value()
            .capture("whole"),
    }];
    let tree = parse(&rules, 0, true, " /*前*/ 😀 //後\n").unwrap();
    let root = &tree.nodes[tree.root];
    let text = &tree.nodes[root.children[0]];
    assert_eq!(text.rule, TEXT_VALUE_RULE);
    assert_eq!(tree.text(text.span), tree.source);
    assert_eq!(text.span.end, tree.source.chars().count());
    assert_eq!(tree.text(root.captures[0].span), "😀");
    assert_eq!(root.captures[1].nodes, root.children);

    let mut context = ParseContext::new("😀");
    let zero = context
        .parse(&Expr::JavaEmpty.text_value().capture("empty"))
        .unwrap();
    let node = context.node(zero.root_node().unwrap()).unwrap();
    assert_eq!(node.rule, TEXT_VALUE_RULE);
    assert_eq!(node.span, Span { start: 0, end: 0 });
    assert_eq!(context.text(node.span), Some(""));
    assert_eq!((context.position(), context.matched_position()), (0, 1));
    assert_eq!(context.captured("empty"), Some(""));
    context.parse(&Expr::Any).unwrap();
    let eof = context.parse(&Expr::Eof.text_value()).unwrap();
    assert_eq!(eof.span, Span { start: 1, end: 1 });
    assert_eq!(
        context.node(eof.root_node().unwrap()).unwrap().span,
        eof.span
    );
}

fn nested(context: &mut ParseContext<'_>) -> ParseResult {
    context.set_state("custom", 42usize);
    context.parse_grammar(
        vec![Rule {
            name: "custom-rule",
            expression: Expr::literal("😀").capture("inner"),
        }],
        0,
        false,
    )
}

#[test]
fn custom_parser_context_and_retained_tree_survive_projection() {
    let mut context = ParseContext::new("😀!");
    let matched = context
        .parse(&Expr::Custom(nested).text_value().capture("outer"))
        .unwrap();
    assert_eq!(context.state::<usize>("custom"), Some(&42));
    assert_eq!(context.captured("inner"), Some("😀"));
    assert_eq!(context.captured("outer"), Some("😀"));
    assert_eq!(context.remaining(), "!");
    let tree = context.tree(matched.root_node().unwrap()).unwrap();
    let text = &tree.nodes[tree.root];
    assert_eq!(text.rule, TEXT_VALUE_RULE);
    assert_eq!(tree.nodes[text.children[0]].rule, 0);
    assert_eq!(tree.nodes[text.children[0]].captures[0].name, "inner");
    context.parse(&Expr::literal("!")).unwrap();
    drop(context);
    assert_eq!(tree.text(text.span), "😀");
}

#[test]
fn failed_choice_and_explicit_transactions_remove_projection_nodes_and_state() {
    let failed = Expr::Custom(nested)
        .text_value()
        .capture("outer")
        .then(Expr::literal("!"));
    let mut context = ParseContext::new("😀?");
    assert!(context.parse(&failed).is_err());
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    assert!(context.node(0).is_none());
    assert!(context.captured("inner").is_none());
    assert!(context.captured("outer").is_none());
    assert!(context.state::<usize>("custom").is_none());
    assert_eq!(context.failure().offset, 1);
    let result = context
        .parse(&failed.or(Expr::literal("😀?").text_value()))
        .unwrap();
    assert_eq!(result.nodes, [0]);
    assert!(context.node(1).is_none());
    assert!(context.node(0).unwrap().children.is_empty());
    assert!(result.captures.is_empty());

    let mut context = ParseContext::new("😀");
    let result: Result<(), _> = context.transaction(|context| {
        context.parse(&Expr::Custom(nested).text_value())?;
        Err(context.error("abort"))
    });
    assert!(result.is_err());
    assert_eq!(context.position(), 0);
    assert!(context.node(0).is_none());
    assert!(context.state::<usize>("custom").is_none());
}

#[test]
fn wrapping_preserves_optional_repeat_and_lookahead_cursor_contracts() {
    for child in [
        Expr::literal("x"),
        Expr::CharRange('x', 'z'),
        Expr::Except("a"),
        Expr::sequence([Expr::literal("x")]),
        Expr::literal("x").capture("missing"),
    ] {
        for repeat in [false, true] {
            let mut observed = vec![];
            for wrapped in 0..4 {
                let mut context = ParseContext::new("ab");
                context.parse(&Expr::JavaEmpty).unwrap();
                let child = match wrapped {
                    0 => child.clone(),
                    1 => child.clone().text_value().text_value(),
                    2 => child.clone().value_boundary().value_boundary(),
                    _ => child.clone().text_value().value_boundary().text_value(),
                };
                let parser = if repeat {
                    child.repeat_java(0, Some(2))
                } else {
                    child.optional_java()
                };
                let result = context.parse(&parser).unwrap();
                assert!(result.nodes.is_empty());
                assert!(context.node(0).is_none());
                observed.push((
                    context.position(),
                    context.matched_position(),
                    context.failure(),
                ));
            }
            for wrapped in &observed[1..] {
                assert_eq!(&observed[0], wrapped);
            }
        }
    }
    let mut context = ParseContext::new("😀");
    context
        .parse(&Expr::Custom(nested).text_value().ahead())
        .unwrap();
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    assert!(context.node(0).is_none());
    assert!(context.captured("inner").is_none());
    assert!(context.state::<usize>("custom").is_none());
    assert!(context
        .parse(&Expr::Empty.text_value().zero_or_more())
        .is_err());
    assert!(context.node(0).is_none());
    let repeated = context
        .parse(&Expr::Empty.text_value().repeat(2, Some(2)))
        .unwrap();
    assert_eq!(repeated.nodes.len(), 2);
    assert_eq!(context.position(), 0);
}

#[test]
fn value_boundary_keeps_outer_span_inner_nodes_and_captures() {
    let rules = [Rule {
        name: "root",
        expression: Expr::sequence([
            Expr::literal("("),
            Expr::literal("😀").capture("inner").text_value(),
            Expr::literal(")"),
        ])
        .value_boundary()
        .capture("outer"),
    }];
    let tree = parse(&rules, 0, false, "(😀)").unwrap();
    let root = &tree.nodes[tree.root];
    let boundary = &tree.nodes[root.children[0]];
    assert_eq!(boundary.rule, VALUE_BOUNDARY_RULE);
    assert_eq!(boundary.span, Span { start: 0, end: 3 });
    assert!(boundary.captures.is_empty());
    let text = &tree.nodes[boundary.children[0]];
    assert_eq!(text.rule, TEXT_VALUE_RULE);
    assert_eq!(text.span, Span { start: 1, end: 2 });
    assert_eq!(
        root.captures
            .iter()
            .map(|capture| capture.name)
            .collect::<Vec<_>>(),
        ["inner", "outer"]
    );
    assert_eq!(root.captures[1].nodes, root.children);
    let mut context = ParseContext::new("");
    let empty = context.parse(&Expr::Empty.value_boundary()).unwrap();
    let node = context.node(empty.root_node().unwrap()).unwrap();
    assert_eq!(node.rule, VALUE_BOUNDARY_RULE);
    assert_eq!(node.span, Span { start: 0, end: 0 });
    assert!(node.children.is_empty());
}

#[test]
fn failed_value_boundary_restores_projection_captures_and_custom_state() {
    let failed = Expr::Custom(nested)
        .text_value()
        .value_boundary()
        .capture("outer")
        .then(Expr::literal("!"));
    let mut context = ParseContext::new("😀?");
    assert!(context.parse(&failed).is_err());
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    assert!(context.node(0).is_none());
    assert!(context.captured("inner").is_none());
    assert!(context.captured("outer").is_none());
    assert!(context.state::<usize>("custom").is_none());
    assert_eq!(context.failure().offset, 1);
    context
        .parse(&Expr::Custom(nested).value_boundary().ahead())
        .unwrap();
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    assert!(context.node(0).is_none());
    assert!(context.state::<usize>("custom").is_none());
}
