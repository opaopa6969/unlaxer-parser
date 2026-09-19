use unlaxer_evolution_example::{
    generated::{evaluator::evaluate, mapper, parser},
    semantics::Calculator,
};
use unlaxer_runtime::{Expr, ParseContext, Span};

#[test]
fn generated_parser_shares_context_with_handwritten_parsers() {
    let tree = {
        let mut context = ParseContext::new("😀:2+3;suffix");
        context.set_state("request", String::from("shared"));
        context
            .parse(&Expr::literal("😀:").capture("prefix"))
            .unwrap();
        let matched = context.parse(&parser::GeneratedParser).unwrap();
        assert_eq!(matched.span, Span { start: 2, end: 5 });
        assert_eq!(context.position(), 5);
        assert_eq!(context.captured("prefix"), Some("😀:"));
        assert_eq!(context.state::<String>("request").unwrap(), "shared");
        let tree = context.tree(matched.root_node().unwrap()).unwrap();
        context
            .parse(&Expr::literal(";suffix").then(Expr::Eof))
            .unwrap();
        tree
    };
    let ast = mapper::map(&tree).unwrap();
    assert_eq!(ast.span(), Span { start: 2, end: 5 });
    drop(tree);
    assert_eq!(evaluate(&ast, &mut Calculator), Ok(5.0));
}

#[test]
fn generated_parser_rolls_back_inside_a_failed_custom_branch() {
    let mut context = ParseContext::new("2+3");
    let branch = Expr::Custom(parser::parse_context).then(Expr::literal("!"));
    assert!(context.parse(&branch).is_err());
    assert_eq!(context.position(), 0);
    assert!(context.node(0).is_none());
    assert!(context.capture_spans("value").is_empty());
    assert_eq!(context.failure().offset, 3);
    let matched = context.parse(&parser::GeneratedParser).unwrap();
    let tree = context.tree(matched.root_node().unwrap()).unwrap();
    assert_eq!(
        evaluate(&mapper::map(&tree).unwrap(), &mut Calculator),
        Ok(5.0)
    );
}
