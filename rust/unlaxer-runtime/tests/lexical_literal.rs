//! #768: Java LexicalTokenParser inversion is a zero-width assertion, including EOF.
use unlaxer_runtime::lexical::{LexicalExpression, Op};
use unlaxer_runtime::lexing::Terminal;
use unlaxer_runtime::token::{TokenKind, TokenSource};
use unlaxer_runtime::{Expr, ParseContext, Span};

#[test]
fn inverted_literal_keeps_the_cursor_and_a_zero_width_leaf() {
    for input in ["", "a", "xy", "😀x"] {
        for kind in [TokenKind::Consumed, TokenKind::MatchOnly] {
            let mut context = ParseContext::new(input);
            let parsed = context
                .parse_with_token_mode(&Expr::JavaLiteral("ab"), kind, true)
                .unwrap();
            assert_eq!((context.position(), context.matched_position()), (0, 0));
            assert_eq!(parsed.span, Span { start: 0, end: 0 });
            assert_eq!(parsed.nodes.len(), 1);
            let node = context.token_node_id(parsed.nodes[0]).unwrap();
            let token = context.token_info(node).unwrap();
            assert_eq!(token.kind, kind);
            assert_eq!(token.parser_label(), Some("LiteralParser"));
            assert_eq!(token.source, TokenSource::Input(Span { start: 0, end: 0 }));
            assert_eq!(context.token_text(node), Some(""));
        }
    }
}

#[test]
fn lexical_failure_and_zero_width_success_preserve_prior_matched_cursor() {
    for (input, invert, success) in [
        ("ab!", true, false),
        ("xy!", true, true),
        ("xy!", false, false),
    ] {
        let mut context = ParseContext::new(input);
        context
            .parse_with_token_mode(
                &Expr::Any.repeat_java(2, Some(2)),
                TokenKind::MatchOnly,
                false,
            )
            .unwrap();
        let parsed =
            context.parse_with_token_mode(&Expr::JavaLiteral("ab"), TokenKind::Consumed, invert);
        assert_eq!(parsed.is_ok(), success);
        assert_eq!((context.position(), context.matched_position()), (0, 2));
        if let Err(error) = parsed {
            assert_eq!(error.offset, 2);
        }
    }
    // WordParser's consuming mismatch is a distinct contract: consume(0) resets matched.
    let mut context = ParseContext::new("xy!");
    context
        .parse_with_token_mode(
            &Expr::Any.repeat_java(2, Some(2)),
            TokenKind::MatchOnly,
            false,
        )
        .unwrap();
    assert!(context
        .parse_with_token_mode(&Expr::literal("ab"), TokenKind::Consumed, false)
        .is_err());
    assert_eq!((context.position(), context.matched_position()), (0, 0));
}

#[test]
fn lexical_goal_winner_controls_inversion_instead_of_raw_prefix() {
    let terminals = ["ab", "abc"]
        .map(|text| Terminal {
            name: text,
            literal: true,
            expression: LexicalExpression {
                op: Op::LITERAL,
                min: 0,
                max: -1,
                text,
                children: vec![],
            },
        })
        .to_vec();
    let expression = Expr::JavaLiteral("ab").lexical_context_scope(terminals);
    for (input, invert, ok, width) in [
        ("ab", false, true, 2),
        ("abc", false, false, 0),
        ("ab", true, false, 0),
        ("abc", true, true, 0),
    ] {
        let mut context = ParseContext::new(input);
        assert_eq!(
            context
                .parse_with_token_mode(&expression, TokenKind::Consumed, invert)
                .is_ok(),
            ok
        );
        assert_eq!(
            (context.position(), context.matched_position()),
            (width, width)
        );
    }
}

#[test]
fn token_trace_records_a_lexical_literal_as_a_lexical_token_leaf() {
    // Java's Lexing.LiteralParser is a LexicalTokenParser, like LiteralParser("") (#713).
    use unlaxer_runtime::token_trace::Limits;
    use unlaxer_runtime::{parse_with_options, ParseOptions, Rule};
    for (expression, label) in [
        (Expr::JavaLiteral("ab"), "LexicalTokenParser"),
        (Expr::literal("ab"), "WordParser"),
    ] {
        let rules = vec![Rule {
            name: "Root",
            expression,
        }];
        let traced = parse_with_options(
            &rules,
            0,
            false,
            "ab",
            ParseOptions::default().with_token_trace(Limits::default()),
        )
        .unwrap();
        let leaves = traced.token_trace().unwrap().unwrap();
        assert_eq!(
            leaves.iter().map(|leaf| leaf.label).collect::<Vec<_>>(),
            [label]
        );
        assert_eq!(
            leaves[0].source,
            TokenSource::Input(Span { start: 0, end: 2 })
        );
    }
}
