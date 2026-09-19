use unlaxer_runtime::{Expr, ParseContext, ParseMatch, ParseResult, Parser, Rule, Span};

struct Identifier;
impl Parser for Identifier {
    fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult {
        let start = context.position();
        let length = context
            .remaining()
            .chars()
            .take_while(|c| c.is_ascii_alphabetic())
            .count();
        if length == 0 {
            return Err(context.error("identifier"));
        }
        assert!(context.advance(length));
        let span = Span {
            start,
            end: context.position(),
        };
        context.set_state("identifier", context.text(span).unwrap().to_owned());
        Ok(ParseMatch::empty(span))
    }
}

fn identifier(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse(&Identifier)
}

#[test]
fn nested_grammar_restores_outer_rules_and_trivia_policy() {
    fn nested(context: &mut ParseContext<'_>) -> ParseResult {
        context.parse_grammar(
            vec![Rule {
                name: "nested",
                expression: Expr::literal("x"),
            }],
            0,
            false,
        )
    }
    let mut context = ParseContext::new("x /* 😀 */ y!");
    let matched = context
        .parse_grammar(
            vec![
                Rule {
                    name: "outer",
                    expression: Expr::Custom(nested).then(Expr::Rule(1)),
                },
                Rule {
                    name: "tail",
                    expression: Expr::literal("y"),
                },
            ],
            0,
            true,
        )
        .unwrap();
    assert!(matched.root_node().is_some());
    assert_eq!(context.remaining(), "!");
    // Neither the outer nor the nested grammar remains installed after returning.
    assert!(context.parse(&Expr::Rule(0)).is_err());
    context.parse(&Expr::literal("!").then(Expr::Eof)).unwrap();
}

#[test]
fn custom_trait_failure_rolls_back_and_public_recursive_calls_are_bounded() {
    struct Fails;
    impl Parser for Fails {
        fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult {
            context.set_state("temporary", true);
            assert!(context.advance(1));
            Err(context.error("custom failure"))
        }
    }
    let mut context = ParseContext::new("😀");
    assert!(context.parse(&Fails).is_err());
    assert_eq!(context.position(), 0);
    assert!(context.state::<bool>("temporary").is_none());
    assert_eq!(context.failure().offset, 1);

    struct Recursive;
    impl Parser for Recursive {
        fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult {
            context.parse(self)
        }
    }
    let mut context = ParseContext::new("");
    assert!(context
        .parse(&Recursive)
        .unwrap_err()
        .expected
        .contains(&"parser calls below 256".to_owned()));
    // The counter also unwinds after a normal Err, allowing later parser calls.
    context.parse(&Expr::Empty).unwrap();
}

#[test]
fn handwritten_parser_sees_context_and_composes_with_capture_and_replay() {
    let parser = Expr::Custom(identifier)
        .capture("tag")
        .then(Expr::literal(":"))
        .then(Expr::Backreference("tag"))
        .then(Expr::Eof);
    let mut context = ParseContext::new("hello:hello");
    let matched = context.parse(&parser).unwrap();
    assert_eq!(matched.span, Span { start: 0, end: 11 });
    assert_eq!(context.state::<String>("identifier").unwrap(), "hello");
    assert_eq!(context.captured("tag"), Some("hello"));
    assert_eq!(context.capture_spans("tag"), &[Span { start: 0, end: 5 }]);
    let mut mismatch = ParseContext::new("hello:world");
    assert!(mismatch.parse(&parser).is_err());
    assert_eq!(mismatch.position(), 0);
    assert!(mismatch.state::<String>("identifier").is_none());
    assert!(mismatch.captured("tag").is_none());
    assert_eq!(mismatch.failure().offset, 6);
}

#[test]
fn failed_alternative_restores_typed_state_and_cst_but_keeps_diagnostics() {
    fn write(context: &mut ParseContext<'_>) -> ParseResult {
        context
            .state_mut::<Vec<String>>("values")
            .unwrap()
            .push("branch".into());
        context.remove_state("retained");
        context.set_state("created", 42usize);
        context.parse_grammar(
            vec![Rule {
                name: "inner",
                expression: Expr::literal("😀").capture("emoji"),
            }],
            0,
            false,
        )
    }
    let mut context = ParseContext::new("😀?");
    context.set_state("values", vec!["original".to_owned()]);
    context.set_state("retained", true);
    let parser = Expr::Custom(write)
        .then(Expr::literal("!"))
        .or(Expr::literal("😀").then(Expr::literal("?")));
    context.parse(&parser).unwrap();
    assert_eq!(context.position(), 2);
    assert_eq!(
        context.state::<Vec<String>>("values").unwrap(),
        &vec!["original".to_owned()]
    );
    assert_eq!(context.state::<bool>("retained"), Some(&true));
    assert!(context.state::<usize>("created").is_none());
    assert!(context.captured("emoji").is_none());
    assert!(context.node(0).is_none());
    assert_eq!(context.failure().offset, 1);
    assert!(context.failure().expected.contains(&"!".to_owned()));
}

#[test]
fn lookahead_restores_successful_state_and_negative_failure_is_suppressed() {
    let mut context = ParseContext::new("abc!");
    context
        .parse(&Expr::Custom(identifier).capture("name").ahead())
        .unwrap();
    assert_eq!(context.position(), 0);
    assert!(context.state::<String>("identifier").is_none());
    assert!(context.captured("name").is_none());
    context.parse(&Expr::literal("xyz").not_ahead()).unwrap();
    assert!(context.failure().expected.is_empty());
    assert!(context.parse(&Expr::literal("abc").not_ahead()).is_err());
    assert_eq!(context.position(), 0);
    assert_eq!(context.failure().expected, vec!["negative lookahead"]);
}

#[test]
fn optional_and_repetition_are_atomic_and_cardinality_is_enforced() {
    for (text, accepted) in [
        ("ab", true),
        ("abab", true),
        ("", false),
        ("aba", false),
        ("ababab", false),
    ] {
        let parser = Expr::literal("a")
            .then(Expr::literal("b"))
            .repeat(1, Some(2))
            .then(Expr::Eof);
        let mut context = ParseContext::new(text);
        assert_eq!(context.parse(&parser).is_ok(), accepted, "{text}");
        if !accepted {
            assert_eq!(context.position(), 0);
        }
    }
    let mut context = ParseContext::new("abx");
    context
        .parse(
            &Expr::literal("a")
                .capture("discard")
                .then(Expr::literal("! "))
                .optional(),
        )
        .unwrap();
    assert_eq!(context.position(), 0);
    assert!(context.captured("discard").is_none());
    assert!(context.parse(&Expr::Empty.zero_or_more()).is_err());
    assert_eq!(context.position(), 0);
    assert!(context
        .parse(&Expr::literal("a").repeat(2, Some(1)))
        .is_err());
    let mut repeated = ParseContext::new("😀😀");
    repeated
        .parse(&Expr::Any.capture("items").one_or_more())
        .unwrap();
    assert_eq!(
        repeated.capture_spans("items"),
        &[Span { start: 0, end: 1 }, Span { start: 1, end: 2 }]
    );
}

#[test]
fn unicode_tokens_separators_and_until_keep_source_boundaries() {
    let parser = Expr::CharRange('a', 'z')
        .separated_by(Expr::literal(","))
        .then(Expr::Eof);
    for (text, accepted) in [
        ("a", true),
        ("a,b", true),
        ("", false),
        ("a,", false),
        ("A", false),
    ] {
        assert_eq!(ParseContext::new(text).parse(&parser).is_ok(), accepted);
    }
    let mut context = ParseContext::new("😀text#");
    context
        .parse(&Expr::Until("#").capture("body").then(Expr::literal("#")))
        .unwrap();
    assert_eq!(context.captured("body"), Some("😀text"));
    assert_eq!(context.position(), 6);
    assert_eq!(context.text(Span { start: 0, end: 1 }), Some("😀"));
    assert!(context.text(Span { start: 2, end: 1 }).is_none());
    assert!(!context.advance(usize::MAX));
    assert!(ParseContext::new("😀").parse(&Expr::Except("x")).is_ok());
    assert!(ParseContext::new("x").parse(&Expr::Except("x")).is_err());
    assert!(ParseContext::new("text").parse(&Expr::Until("#")).is_err());
}

#[test]
fn context_grammar_entry_is_prefix_parsing_and_tree_is_an_owned_snapshot() {
    let mut context = ParseContext::new("ab!");
    let matched = context
        .parse_grammar(
            vec![Rule {
                name: "word",
                expression: Expr::literal("ab"),
            }],
            0,
            false,
        )
        .unwrap();
    let tree = context.tree(matched.root_node().unwrap()).unwrap();
    assert_eq!(context.remaining(), "!");
    context.parse(&Expr::literal("!")).unwrap();
    drop(context);
    assert_eq!(tree.text(tree.nodes[tree.root].span), "ab");
}
