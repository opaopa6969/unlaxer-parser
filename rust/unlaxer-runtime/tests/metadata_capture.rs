//! Preferred metadata must be invisible to runtime evaluation (#788).
use unlaxer_runtime::{
    token::TokenKind, Diagnostics, Expr, Memoization, ParseContext, ParseOptions,
};

fn annotate(child: Expr, marker: Option<&'static str>) -> Expr {
    match marker {
        Some(name) => Expr::MetadataCapture(name, Box::new(child)),
        None => child,
    }
}

fn observation(
    input: &str,
    shape: usize,
    kind: TokenKind,
    reset: bool,
    options: ParseOptions,
    marker: Option<&'static str>,
) -> String {
    let x = || Expr::literal("x");
    let repeat = || x().match_only().repeat_java(0, Some(2));
    let child = match shape {
        0 => Expr::choice([repeat()]),
        1 => repeat(),
        2 => repeat().optional_java(),
        3 => Expr::sequence([repeat()]),
        4 => repeat().capture("f"),
        5 => Expr::choice([repeat().capture("f")]),
        6 => x().capture("f").match_only(),
        7 => x().capture("f").repeat_java(0, Some(2)),
        8 => Expr::choice([Expr::literal("y").capture("f"), repeat()]),
        9 => Expr::literal("y").capture("f"),
        10 => x().capture("f"),
        11 => x().match_only(),
        _ => Expr::sequence([x(), Expr::literal("y")]),
    };
    let child = annotate(child, marker).capture_owned("value");
    let mut context = ParseContext::with_options(input, options);
    context.set_reset_matched_with_consumed(reset);
    let result = context.parse_with_token_mode(&child, kind, false);
    // Metadata is available in the returned CST but must not become a replay binding.
    if let Some(name) = marker {
        assert_eq!(context.captured(name), None);
        assert_eq!(context.captured_text(name), None);
    }
    let result = result.map(|mut parsed| {
        parsed
            .captures
            .retain(|capture| !capture.name.starts_with("#preferred-"));
        parsed
    });
    let nodes = (0..)
        .map_while(|id| context.node(id))
        .map(|node| {
            let mut node = node.clone();
            node.captures
                .retain(|capture| !capture.name.starts_with("#preferred-"));
            format!("{node:?}")
        })
        .collect::<Vec<_>>();
    let diagnostic = context.error("probe");
    let before = format!(
        "{:?}/{}/{}/{}/{:?}/{:?}/{:?}/{:?}",
        result,
        context.position(),
        context.matched_position(),
        context.reset_matched_with_consumed(),
        context.captured_source("value"),
        context.captured("f"),
        nodes,
        diagnostic
    );
    // A failed @f leaves reset=false for subsequent parsing (#787). The annotation
    // must not introduce an extra rollback that restores reset=true.
    let next = context.parse_with_token_mode(&repeat().capture_owned("next"), kind, false);
    format!(
        "{before}/{next:?}/{:?}/{}/{}",
        context.captured_source("next"),
        context.position(),
        context.matched_position()
    )
}

#[test]
fn annotations_preserve_classic_state_and_failed_field_reset() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            for input in ["", "x", "xx", "xy", "😀"] {
                for kind in [
                    TokenKind::Consumed,
                    TokenKind::MatchOnly,
                    TokenKind::VirtualConsumed,
                    TokenKind::VirtualMatchOnly,
                ] {
                    for reset in [false, true] {
                        for shape in 0..13 {
                            let ordinary = observation(input, shape, kind, reset, options, None);
                            for marker in [
                                "#preferred-schema:0",
                                "#preferred-depth:1",
                                "#preferred-extra:2",
                            ] {
                                assert_eq!(ordinary, observation(input, shape, kind, reset, options, Some(marker)),
                                    "{input:?}/{shape}/{kind:?}/{reset}/{memo:?}/{diagnostics:?}/{marker}");
                            }
                        }
                    }
                }
            }
        }
    }
}

#[test]
fn metadata_keeps_cst_edges_without_publishing_bindings() {
    let mut context = ParseContext::new("😀");
    let result = context
        .parse(&annotate(
            Expr::literal("😀").capture("field"),
            Some("#preferred-schema:0"),
        ))
        .unwrap();
    assert_eq!(result.captures.len(), 2);
    assert_eq!(result.captures[0].name, "field");
    let metadata = &result.captures[1];
    assert_eq!(metadata.name, "#preferred-schema:0");
    assert_eq!((metadata.span.start, metadata.span.end), (0, 1));
    assert_eq!(metadata.nodes, result.nodes);
    assert_eq!(context.captured("field"), Some("😀"));
    assert_eq!(context.captured(metadata.name), None);
    assert_eq!(context.captured_text(metadata.name), None);
}

#[test]
fn classic_rule_collection_keeps_the_metadata_edge() {
    use unlaxer_runtime::Rule;
    // A Classic rule keeps only nodes of its own kind. A body that returns only
    // match-only nodes must still carry the schema marker, as the former Capture did.
    let parse = |marker: Option<&'static str>| {
        let rules = vec![Rule {
            name: "Root",
            expression: annotate(Expr::literal("x").match_only(), marker),
        }];
        let mut context = ParseContext::new("x");
        let root = context.parse_grammar(rules, 0, false).unwrap().nodes[0];
        let node = context.node(root).unwrap().clone();
        (node, context.position(), context.matched_position())
    };
    let (ordinary, position, matched) = parse(None);
    let (annotated, annotated_position, annotated_matched) = parse(Some("#preferred-schema:0"));
    assert_eq!(
        (
            annotated.children.clone(),
            annotated_position,
            annotated_matched
        ),
        (ordinary.children.clone(), position, matched)
    );
    assert!(ordinary.captures.is_empty());
    assert_eq!(annotated.captures.len(), 1);
    assert_eq!(annotated.captures[0].name, "#preferred-schema:0");
    assert!(annotated.captures[0].nodes.is_empty());
}

#[test]
fn failed_metadata_does_not_roll_back_the_childs_field_reset() {
    let mut context = ParseContext::new("x");
    context.set_reset_matched_with_consumed(true);
    let failed = annotate(Expr::literal("y").capture("f"), Some("#preferred-schema:0"));
    assert!(context
        .parse_with_token_mode(&failed, TokenKind::Consumed, false)
        .is_err());
    assert!(!context.reset_matched_with_consumed());
    let next = Expr::literal("x")
        .match_only()
        .repeat_java(0, Some(2))
        .capture_owned("value");
    context
        .parse_with_token_mode(&next, TokenKind::MatchOnly, false)
        .unwrap();
    assert_eq!(context.captured_text("value"), Some("x"));
}

#[test]
fn metadata_nesting_retains_the_resource_limit() {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(|| {
            let mut expression = Expr::literal("x");
            for _ in 0..1024 {
                expression = annotate(expression, Some("#preferred-schema:0"));
            }
            let mut context = ParseContext::new("x");
            let error = context.parse(&expression).unwrap_err();
            assert!(error
                .expected
                .iter()
                .any(|value| value.contains("expression nesting below 1024")));
            assert_eq!(context.position(), 0);
            assert!(context.parse(&Expr::literal("x")).is_err());
        })
        .unwrap()
        .join()
        .unwrap();
}

#[test]
fn grammar_analysis_sees_through_metadata() {
    use unlaxer_runtime::{
        editor_cst, grammar_allows_deferred_diagnostics, incremental, Rule, SharedGrammar,
    };
    let grammar: SharedGrammar = vec![Rule {
        name: "Root",
        expression: annotate(Expr::literal("end"), Some("#preferred-schema:0")),
    }]
    .into();
    assert_eq!(editor_cst::literal_completions(&grammar), ["end"]);
    assert!(grammar_allows_deferred_diagnostics(&grammar));
    assert!(incremental::grammar_is_snapshot_cacheable(&grammar));
    let custom = Expr::Custom(|context| Err(context.error("custom")));
    let grammar = [Rule {
        name: "Root",
        expression: annotate(custom, Some("#preferred-schema:0")),
    }];
    assert!(!grammar_allows_deferred_diagnostics(&grammar));
    assert!(!incremental::grammar_is_snapshot_cacheable(&grammar));
}
