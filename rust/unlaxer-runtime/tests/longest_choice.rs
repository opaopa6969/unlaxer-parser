use std::sync::atomic::{AtomicUsize, Ordering};
use unlaxer_runtime::{
    parse, parse_detailed_with_options, Expr, Memoization, ParseContext, ParseOptions, ParseResult,
    Rule, Severity, Span,
};

static SHORT_CALLS: AtomicUsize = AtomicUsize::new(0);
static LONG_CALLS: AtomicUsize = AtomicUsize::new(0);

fn marked(context: &mut ParseContext<'_>, text: &'static str, marker: &'static str) -> ParseResult {
    let matched = context.parse(&Expr::literal(text))?;
    context.set_state("winner", marker.to_owned());
    context.scopes_mut().declare(marker, matched.span.start);
    Ok(matched)
}

#[test]
fn winner_snapshot_keeps_only_the_winning_cst_nodes() {
    let rules = vec![
        Rule {
            name: "root",
            expression: Expr::longest_choice([Expr::Rule(1), Expr::Rule(2)]),
        },
        Rule {
            name: "short",
            expression: Expr::literal("a"),
        },
        Rule {
            name: "long",
            expression: Expr::literal("ab"),
        },
    ];
    let tree = parse(&rules, 0, false, "ab").unwrap();
    assert_eq!(tree.nodes.len(), 2);
    assert_eq!(tree.nodes[tree.root].children, vec![0]);
    assert_eq!(tree.nodes[0].rule, 2);
    assert_eq!(tree.text(tree.nodes[0].span), "ab");
}

fn short(context: &mut ParseContext<'_>) -> ParseResult {
    SHORT_CALLS.fetch_add(1, Ordering::Relaxed);
    marked(context, "a", "short")
}

fn long(context: &mut ParseContext<'_>) -> ParseResult {
    LONG_CALLS.fetch_add(1, Ordering::Relaxed);
    marked(context, "ab", "long")
}

fn first(context: &mut ParseContext<'_>) -> ParseResult {
    marked(context, "a", "first")
}

fn second(context: &mut ParseContext<'_>) -> ParseResult {
    marked(context, "a", "second")
}

#[test]
fn longest_choice_commits_only_the_longest_transactional_state() {
    SHORT_CALLS.store(0, Ordering::Relaxed);
    LONG_CALLS.store(0, Ordering::Relaxed);
    let parser = Expr::longest_choice([
        Expr::Custom(short).capture("short"),
        Expr::Custom(long).capture("long"),
    ]);
    let mut context = ParseContext::new("ab!");
    let matched = context.parse(&parser).unwrap();

    assert_eq!(matched.span, Span { start: 0, end: 2 });
    assert_eq!((context.position(), context.matched_position()), (2, 2));
    assert_eq!(context.remaining(), "!");
    assert_eq!(
        context.state::<String>("winner").map(String::as_str),
        Some("long")
    );
    assert_eq!(context.captured("short"), None);
    assert_eq!(context.captured("long"), Some("ab"));
    assert!(!context.scopes().is_declared("short"));
    assert!(context.scopes().is_declared("long"));
    assert_eq!(context.scopes().all_declarations().len(), 1);
    assert_eq!(SHORT_CALLS.load(Ordering::Relaxed), 1);
    assert_eq!(LONG_CALLS.load(Ordering::Relaxed), 1);
}

#[test]
fn longest_choice_discards_every_losing_scope_event_and_keeps_winner_order() {
    fn scoped_candidate(
        context: &mut ParseContext<'_>,
        text: &'static str,
        marker: &'static str,
    ) -> ParseResult {
        let matched = context.parse(&Expr::literal(text))?;
        let scope = context.scopes_mut();
        scope.enter();
        scope.declare(marker, matched.span.start);
        scope.add_reference(marker, matched.span.start, text.chars().count());
        scope.add_diagnostic(marker, matched.span.start, 1, Severity::Info);
        Ok(matched)
    }

    fn losing(context: &mut ParseContext<'_>) -> ParseResult {
        scoped_candidate(context, "a", "loser")
    }

    fn winning(context: &mut ParseContext<'_>) -> ParseResult {
        scoped_candidate(context, "abc", "winner")
    }

    let mut context = ParseContext::new("abc");
    context
        .parse(&Expr::longest_choice([
            Expr::Custom(losing),
            Expr::Custom(winning),
        ]))
        .unwrap();

    assert_eq!(context.scopes().current_scope_depth(), 1);
    assert!(!context.scopes().is_declared("loser"));
    assert!(context.scopes().is_declared("winner"));
    assert_eq!(context.scopes().all_declarations()[0].name, "winner");
    assert_eq!(context.scopes().all_references()[0].name, "winner");
    assert_eq!(context.scopes().diagnostics()[0].message, "winner");
}

#[test]
fn equal_length_keeps_declaration_order_and_ordered_choice_is_unchanged() {
    let alternatives = [Expr::Custom(first), Expr::Custom(second)];
    for parser in [
        Expr::choice(alternatives.clone()),
        Expr::longest_choice(alternatives),
    ] {
        let mut context = ParseContext::new("a");
        context.parse(&parser).unwrap();
        assert_eq!(
            context.state::<String>("winner").map(String::as_str),
            Some("first")
        );
        assert!(context.scopes().is_declared("first"));
        assert!(!context.scopes().is_declared("second"));
    }
}

#[test]
fn all_failures_restore_state_and_merge_farthest_diagnostics() {
    let parser = Expr::longest_choice([
        Expr::literal("ab").then(Expr::literal("x")),
        Expr::literal("ab").then(Expr::literal("y")),
    ]);
    let mut context = ParseContext::new("abz");
    let error = context.parse(&parser).unwrap_err();
    assert_eq!(error.offset, 2);
    assert_eq!(error.expected, vec!["x", "y"]);
    assert_eq!((context.position(), context.matched_position()), (0, 0));
}

#[test]
fn unicode_length_and_nested_longest_choices_use_source_order_at_each_level() {
    let nested = Expr::longest_choice([Expr::literal("😀"), Expr::literal("😀界")]);
    let parser = Expr::longest_choice([nested.then(Expr::literal("!")), Expr::literal("😀界")]);
    let mut context = ParseContext::new("😀界!?");
    let matched = context.parse(&parser).unwrap();
    assert_eq!(matched.span, Span { start: 0, end: 3 });
    assert_eq!((context.position(), context.matched_position()), (3, 3));
    assert_eq!(context.remaining(), "?");
}

#[test]
fn safe_failure_memoization_preserves_longest_choice_results() {
    let rules = vec![
        Rule {
            name: "root",
            expression: Expr::longest_choice([Expr::Rule(1), Expr::Rule(1), Expr::literal("😀ok")]),
        },
        Rule {
            name: "miss",
            expression: Expr::literal("😀no"),
        },
    ];
    let off =
        parse_detailed_with_options(&rules, 0, false, "😀ok", ParseOptions::default()).unwrap();
    let on = parse_detailed_with_options(
        &rules,
        0,
        false,
        "😀ok",
        ParseOptions::with_memoization(Memoization::SafeFailures),
    )
    .unwrap();
    assert_eq!(off.source, on.source);
    assert_eq!(off.root, on.root);
    assert_eq!(format!("{:?}", off.nodes), format!("{:?}", on.nodes));
    assert_eq!(off.scopes(), on.scopes());
}
