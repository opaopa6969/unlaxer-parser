use unlaxer_runtime::lexical::{LexicalExpression, Op};
use unlaxer_runtime::{Expr, ParseContext};

fn leaf(op: Op, text: &'static str) -> LexicalExpression {
    LexicalExpression {
        op,
        text,
        min: 0,
        max: 0,
        children: vec![],
    }
}

#[test]
fn pure_assertions_empty_success_and_failure_preserve_independent_matched_cursor() {
    for program in [
        LexicalExpression {
            children: vec![leaf(Op::LITERAL, "a")],
            ..leaf(Op::LOOK, "")
        },
        LexicalExpression {
            children: vec![leaf(Op::LITERAL, "z")],
            ..leaf(Op::NOT, "")
        },
        leaf(Op::LITERAL, ""),
        leaf(Op::LITERAL, "z"),
    ] {
        let mut context = ParseContext::new("abc");
        context
            .parse(&Expr::JavaLookahead {
                pattern: "ab",
                positive: true,
            })
            .unwrap();
        let expected = program.op != Op::LITERAL || program.text.is_empty();
        assert_eq!(
            context.parse(&Expr::Lexical("probe", program)).is_ok(),
            expected
        );
        assert_eq!((context.position(), context.matched_position()), (0, 2));
    }
}
