use unlaxer_runtime::{Expr, ParseContext, ParseMatch, ParseResult, Parser, Rule, Span};

#[test]
fn trivia_scope_restores_parent_boundaries_after_success_and_failed_choice() {
    let strict = Expr::literal("a")
        .then(Expr::literal("b"))
        .trivia_scope(false);
    let fallback = Expr::literal("a").then(Expr::literal("c"));
    let parser = strict
        .or(fallback)
        .then(Expr::literal("!"))
        .trivia_scope(true);
    for input in [" ab /* outer */ ! ", " a /* inner */ c /* outer */ ! "] {
        let mut context = ParseContext::new(input);
        context.parse(&parser).unwrap();
        assert_eq!(context.remaining(), "");
    }
    assert!(ParseContext::new("a b!").parse(&parser).is_err());
    let mut context = ParseContext::new("a c");
    assert!(context.parse(&parser).is_err());
    assert!(context
        .parse(&Expr::literal("a").then(Expr::literal("c")))
        .is_err());
    context
        .with_trivia(true, |ctx| {
            ctx.parse(&Expr::literal("a").then(Expr::literal("c")))
        })
        .unwrap();
}

#[test]
fn nested_grammar_restores_scoped_policy_on_success_and_failure() {
    fn nested(context: &mut ParseContext<'_>) -> ParseResult {
        context.parse_grammar(
            vec![Rule {
                name: "nested",
                expression: Expr::literal("x")
                    .then(Expr::literal("y"))
                    .trivia_scope(true),
            }],
            0,
            false,
        )
    }
    let outer = Expr::Custom(nested)
        .or(Expr::literal("x").then(Expr::literal("z")))
        .then(Expr::literal("!"));
    let mut context = ParseContext::new("x /* nested */ y!");
    context.parse(&outer).unwrap();
    // A failed nested parse must leave the caller strict, including the alternative.
    assert!(ParseContext::new("x z!").parse(&outer).is_err());
    ParseContext::new("xz!").parse(&outer).unwrap();
    // Enabling trivia never splits an atomic quoted token.
    let mut quoted = ParseContext::new("'a /* literal */ b'");
    quoted
        .parse(&Expr::Quoted('\'').capture("raw").trivia_scope(true))
        .unwrap();
    assert_eq!(quoted.captured("raw"), Some("'a /* literal */ b'"));
}

#[test]
fn lexical_tokens_preserve_raw_unicode_text_and_transactional_cursors() {
    for input in ["'😀'", "'a\nb'", "'\\q'", "'\\😀'", "'\\\n'", "'\0'"] {
        let mut context = ParseContext::new(input);
        context.parse(&Expr::Quoted('\'').capture("raw")).unwrap();
        assert_eq!(context.captured("raw"), Some(input));
        assert_eq!(context.position(), input.chars().count());
        assert_eq!(context.matched_position(), context.position());
    }
    for parser in [
        Expr::Identifier,
        Expr::Quoted('\''),
        Expr::Quoted('"'),
        Expr::Eof,
    ] {
        let mut context = ParseContext::new("1x");
        context.parse(&Expr::JavaEmpty).unwrap();
        context.set_state("count", 7u32);
        assert!(context.parse(&parser.capture("failed")).is_err());
        assert_eq!((context.position(), context.matched_position()), (0, 1));
        assert_eq!(context.captured("failed"), None);
        assert_eq!(context.state::<u32>("count"), Some(&7));
    }
    for input in ["'", "'abc", "'abc\\", "'abc\\'"] {
        let mut context = ParseContext::new(input);
        assert!(context.parse(&Expr::Quoted('\'')).is_err());
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        assert_eq!(context.error("quoted").offset, input.chars().count());
    }
    let mut context = ParseContext::new("_A0あ");
    context.parse(&Expr::Identifier.capture("id")).unwrap();
    assert_eq!(context.captured("id"), Some("_A0"));
    assert_eq!(context.remaining(), "あ");
    assert!(ParseContext::new("`a`").parse(&Expr::Quoted('`')).is_err());
}

#[test]
fn java_mapper_quote_policy_is_separate_from_raw_capture_text() {
    use unlaxer_runtime::{java_capture_text, strip_capture};
    assert_eq!(strip_capture(" 'a' "), "'a'");
    assert_eq!(java_capture_text(" 'a' "), "a");
    assert_eq!(java_capture_text("' a '"), " a ");
    assert_eq!(java_capture_text("'\\n'"), "\\n");
    assert_eq!(java_capture_text("\"\\n\""), "\"\\n\"");
    assert_eq!(java_capture_text("''"), "");
    assert_eq!(java_capture_text("'"), "'");
    assert_eq!(java_capture_text("\u{a0}'a'\u{a0}"), "\u{a0}'a'\u{a0}");
}

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
fn checkpoint_metrics_distinguish_empty_payloads_and_cow_copies() {
    let mut empty = ParseContext::new("a");
    empty.enable_checkpoint_metrics();
    empty
        .transaction(|context| {
            assert!(context.advance(1));
            Ok(())
        })
        .unwrap();
    let metrics = empty.snapshot_checkpoint_metrics();
    assert_eq!(metrics.opened, 1);
    assert_eq!(metrics.committed, 1);
    assert_eq!(metrics.rolled_back, 0);
    assert_eq!(metrics.empty_payload_checkpoints, 1);
    assert_eq!(metrics.nonempty_payload_snapshots, 0);
    assert_eq!(metrics.copy_on_write_deep_copies, 0);

    let mut populated = ParseContext::new("");
    populated.set_state("value", vec![1usize]);
    populated.enable_checkpoint_metrics();
    populated
        .transaction(|context| {
            assert_eq!(context.state::<Vec<usize>>("value"), Some(&vec![1]));
            Ok(())
        })
        .unwrap();
    let rejected: Result<(), _> = populated.transaction(|context| {
        context.state_mut::<Vec<usize>>("value").unwrap().push(2);
        Err(context.error("reject mutation"))
    });
    assert!(rejected.is_err());
    assert_eq!(populated.state::<Vec<usize>>("value"), Some(&vec![1]));
    let metrics = populated.snapshot_checkpoint_metrics();
    assert_eq!(metrics.opened, 2);
    assert_eq!(metrics.committed, 1);
    assert_eq!(metrics.rolled_back, 1);
    assert_eq!(metrics.empty_payload_checkpoints, 0);
    assert_eq!(metrics.nonempty_payload_snapshots, 2);
    assert_eq!(metrics.copy_on_write_deep_copies, 1);
    assert_eq!(metrics.opened, metrics.committed + metrics.rolled_back);
}

#[test]
fn journals_do_not_change_state_cow_accounting() {
    let mut scope_only = ParseContext::new("");
    scope_only.scopes_mut().declare("seed", 0);
    scope_only.enable_checkpoint_metrics();
    scope_only
        .transaction(|context| {
            context.scopes_mut().declare("journaled", 1);
            Ok(())
        })
        .unwrap();
    let scope_metrics = scope_only.snapshot_checkpoint_metrics();
    assert_eq!(scope_metrics.copy_on_write_deep_copies, 0);
    assert_eq!(scope_metrics.scope_journal_entries, 1);

    let mut capture_only = ParseContext::new("ab");
    capture_only
        .parse(&Expr::literal("a").capture("first"))
        .unwrap();
    capture_only.enable_checkpoint_metrics();
    capture_only
        .parse(&Expr::literal("b").capture("second"))
        .unwrap();
    assert_eq!(
        capture_only
            .snapshot_checkpoint_metrics()
            .copy_on_write_deep_copies,
        0
    );

    let mut cow_domains = ParseContext::new("ab");
    cow_domains
        .parse(&Expr::literal("a").capture("first"))
        .unwrap();
    cow_domains.set_state("values", vec![1usize]);
    cow_domains.enable_checkpoint_metrics();
    cow_domains
        .transaction(|context| {
            context
                .parse(&Expr::literal("b").capture("second"))
                .map(|_| ())?;
            context.state_mut::<Vec<usize>>("values").unwrap().push(2);
            context.scopes_mut().declare("also journaled", 1);
            Ok(())
        })
        .unwrap();
    let cow_metrics = cow_domains.snapshot_checkpoint_metrics();
    assert_eq!(cow_metrics.copy_on_write_deep_copies, 1);
    assert_eq!(cow_metrics.scope_journal_entries, 1);
}

#[test]
fn capture_added_in_failed_transaction_disappears_after_rollback() {
    let mut context = ParseContext::new("a");
    let rejected: Result<(), _> = context.transaction(|context| {
        context.parse(&Expr::literal("a").capture("rolled_back"))?;
        assert_eq!(context.captured("rolled_back"), Some("a"));
        Err(context.error("reject capture"))
    });

    assert!(rejected.is_err());
    assert_eq!(context.captured("rolled_back"), None);
    assert!(context.capture_spans("rolled_back").is_empty());
}

#[test]
fn existing_capture_is_retrievable_by_dynamic_name_after_rollback() {
    let mut context = ParseContext::new("ab");
    let name = String::from("part");
    context.parse(&Expr::literal("a").capture("part")).unwrap();

    let rejected: Result<(), _> = context.transaction(|outer| {
        outer.transaction(|inner| {
            inner.parse(&Expr::literal("b").capture("part"))?;
            assert_eq!(inner.captured(&name), Some("b"));
            Ok(())
        })?;
        Err(outer.error("reject replacement capture"))
    });

    assert!(rejected.is_err());
    assert_eq!(context.position(), 1);
    assert_eq!(context.captured(&name), Some("a"));
    assert_eq!(context.capture_spans(&name), &[Span { start: 0, end: 1 }]);
    context.parse(&Expr::literal("b").capture("part")).unwrap();
    assert_eq!(context.captured(&name), Some("b"));
    assert_eq!(
        context.capture_spans(&name),
        &[Span { start: 0, end: 1 }, Span { start: 1, end: 2 }]
    );
}

#[test]
fn committed_inner_capture_disappears_when_outer_transaction_rolls_back() {
    let mut context = ParseContext::new("a");
    let rejected: Result<(), _> = context.transaction(|outer| {
        outer.transaction(|inner| {
            inner.parse(&Expr::literal("a").capture("nested"))?;
            Ok(())
        })?;
        assert_eq!(outer.captured("nested"), Some("a"));
        Err(outer.error("reject outer transaction"))
    });

    assert!(rejected.is_err());
    assert_eq!(context.captured("nested"), None);
    assert!(context.capture_spans("nested").is_empty());
}

#[test]
fn repeated_capture_spans_keep_source_order() {
    let mut context = ParseContext::new("ab");
    context
        .parse(&Expr::sequence([
            Expr::literal("a").capture("part"),
            Expr::literal("b").capture("part"),
        ]))
        .unwrap();

    assert_eq!(
        context.capture_spans("part"),
        &[Span { start: 0, end: 1 }, Span { start: 1, end: 2 }]
    );
    assert_eq!(context.captured("part"), Some("b"));
}

#[test]
fn empty_outer_checkpoint_discards_committed_inner_cow_domains() {
    let mut context = ParseContext::new("a");
    context.enable_checkpoint_metrics();
    let rejected: Result<(), _> = context.transaction(|outer| {
        outer.transaction(|inner| {
            inner.parse(&Expr::literal("a").capture("name"))?;
            inner.set_state("value", 7usize);
            inner.scopes_mut().declare("a", 0);
            Ok(())
        })?;
        assert_eq!(outer.captured("name"), Some("a"));
        assert_eq!(outer.state::<usize>("value"), Some(&7));
        assert!(outer.scopes().is_declared("a"));
        Err(outer.error("reject outer"))
    });
    assert!(rejected.is_err());
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    assert!(context.captured("name").is_none());
    assert!(context.state::<usize>("value").is_none());
    assert!(!context.scopes().is_declared("a"));
    assert!(context.scopes().all_declarations().is_empty());
    let metrics = context.snapshot_checkpoint_metrics();
    assert_eq!(metrics.opened, metrics.committed + metrics.rolled_back);
    assert!(metrics.empty_payload_checkpoints > 0);
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

fn java_ahead(pattern: &'static str) -> Expr {
    Expr::JavaLookahead {
        pattern,
        positive: true,
    }
}

#[test]
fn ubnf_predicates_advance_only_the_match_cursor_and_rollback_both() {
    let mut context = ParseContext::new("😀ab");
    context.parse(&java_ahead("😀")).unwrap();
    assert_eq!((context.position(), context.matched_position()), (0, 1));
    context.parse(&java_ahead("a")).unwrap();
    assert_eq!((context.position(), context.matched_position()), (0, 2));
    let failed = java_ahead("b")
        .capture("peek")
        .then(Expr::Error("rollback"));
    assert!(context.parse(&failed).is_err());
    assert_eq!((context.position(), context.matched_position()), (0, 2));
    assert!(context.captured("peek").is_none());
    // A PEG predicate probes the consumed cursor and restores the separate match cursor.
    context.parse(&Expr::Any.ahead()).unwrap();
    assert_eq!((context.position(), context.matched_position()), (0, 2));
    context.parse(&Expr::Any).unwrap();
    assert_eq!((context.position(), context.matched_position()), (1, 1));
    context.parse(&Expr::JavaEmpty).unwrap();
    assert_eq!((context.position(), context.matched_position()), (1, 2));
    context.parse(&java_ahead("b")).unwrap();
    assert_eq!((context.position(), context.matched_position()), (1, 3));
    assert!(context.parse(&java_ahead("a")).is_err());
    assert_eq!(context.failure().offset, 3);
    assert_eq!((context.position(), context.matched_position()), (1, 3));
    assert!(context
        .parse(&Expr::Custom(|context| {
            context.set_state("temporary", true);
            assert!(context.advance(2));
            Err(context.error("custom rollback"))
        }))
        .is_err());
    assert!(context.state::<bool>("temporary").is_none());
    assert_eq!((context.position(), context.matched_position()), (1, 3));
    assert!(context.advance(1));
    assert_eq!((context.position(), context.matched_position()), (2, 2));
}

#[test]
fn ubnf_occurrences_distinguish_direct_atoms_and_transactional_children() {
    for (child, accepted) in [
        (Expr::literal("x"), false),
        (Expr::literal("x").trivia_scope(true), false),
        (Expr::sequence([Expr::literal("x")]), true),
        (
            Expr::sequence([Expr::literal("x")]).trivia_scope(false),
            true,
        ),
        (Expr::literal("x").capture("absent"), true),
    ] {
        let mut context = ParseContext::new("ab");
        let parser = java_ahead("a")
            .then(child.optional_java())
            .then(java_ahead("b"))
            .then(Expr::literal("ab"));
        assert_eq!(context.parse(&parser).is_ok(), accepted);
        assert!(context.captured("absent").is_none());
        let expected = if accepted { (2, 2) } else { (0, 0) };
        assert_eq!((context.position(), context.matched_position()), expected);
    }
    assert!(ParseContext::new("ab")
        .parse(
            &java_ahead("a")
                .then(Expr::literal("x").optional())
                .then(java_ahead("b"))
        )
        .is_ok());
    assert!(ParseContext::new("ab")
        .parse(&Expr::JavaEmpty.repeat(2, Some(2)))
        .is_ok());
    for (min, max, accepted) in [(2, 2, false), (1, 2, true), (0, 0, false)] {
        let mut context = ParseContext::new("ab");
        assert_eq!(
            context
                .parse(&Expr::JavaEmpty.repeat_java(min, Some(max)))
                .is_ok(),
            accepted
        );
        assert_eq!(context.position(), 0);
        assert_eq!(context.matched_position(), usize::from(accepted));
    }
    let mut context = ParseContext::new("ab");
    context.parse(&java_ahead("a")).unwrap();
    context
        .parse(&Expr::literal("x").repeat_java(0, Some(2)))
        .unwrap();
    assert_eq!((context.position(), context.matched_position()), (0, 0));
}

#[test]
fn ubnf_until_and_empty_patterns_preserve_java_contract_without_changing_peg() {
    for (input, terminator, consumed, matched) in [
        ("abc", "#", 3, 3),
        ("", "#", 0, 0),
        ("#tail", "#", 0, 1),
        ("😀*/tail", "*/", 1, 3),
        ("😀abc", "", 4, 4),
        ("", "", 0, 0),
    ] {
        let mut context = ParseContext::new(input);
        context.parse(&Expr::JavaUntil(terminator)).unwrap();
        assert_eq!(
            (context.position(), context.matched_position()),
            (consumed, matched),
            "{input}"
        );
    }
    for input in ["", "abc"] {
        let mut context = ParseContext::new(input);
        assert!(context.parse(&java_ahead("")).is_err());
        context
            .parse(&Expr::JavaLookahead {
                pattern: "",
                positive: false,
            })
            .unwrap();
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        context.parse(&Expr::literal("").ahead()).unwrap();
    }
    assert!(ParseContext::new("abc").parse(&Expr::Until("#")).is_err());
    let mut context = ParseContext::new("a#");
    context.parse(&java_ahead("a")).unwrap();
    context.parse(&Expr::JavaUntil("#")).unwrap();
    assert_eq!((context.position(), context.matched_position()), (0, 2));
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
