use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use unlaxer_runtime::semantic_predicates::Bindings;
use unlaxer_runtime::token::{TokenKind, TokenSource};
use unlaxer_runtime::{
    Diagnostics, Expr, Memoization, ParseContext, ParseMatch, ParseOptions, ParseResult, Span,
};

const DIAGNOSTICS: [Diagnostics; 3] = [
    Diagnostics::Auto,
    Diagnostics::Detailed,
    Diagnostics::DetailedOnFailure,
];

fn generated<const EMPTY: bool>(context: &mut ParseContext<'_>) -> ParseResult {
    let at = context.position();
    let id = context
        .add_token_node(
            at,
            TokenKind::VirtualConsumed,
            TokenSource::Generated {
                anchor: at,
                text: if EMPTY { "" } else { "g" }.into(),
            },
        )
        .ok_or_else(|| context.error("token"))?;
    Ok(ParseMatch {
        span: Span { start: at, end: at },
        nodes: vec![id.index()],
        captures: vec![],
    })
}

fn parse(context: &mut ParseContext<'_>, parser: &Expr, classic: bool) -> bool {
    if classic {
        context
            .parse_with_token_mode(parser, TokenKind::Consumed, false)
            .is_ok()
    } else {
        context.parse(parser).is_ok()
    }
}

#[test]
fn generated_text_even_when_empty_never_reaches_the_predicate_or_allows_fallback() {
    for text in [
        Expr::Custom(generated::<true>),
        Expr::Custom(generated::<false>),
    ] {
        // A Classic Choice returns the selected child's Parsed source, as Java Choice does.
        for child in [text.clone(), Expr::choice([text])] {
            for classic in [false, true] {
                for memo in [Memoization::Off, Memoization::SafeFailures] {
                    for diagnostics in DIAGNOSTICS {
                        let calls = Arc::new(AtomicUsize::new(0));
                        let observed = Arc::clone(&calls);
                        let bindings = Bindings::new()
                            .with("valid", move |_| {
                                observed.fetch_add(1, Ordering::Relaxed);
                                true
                            })
                            .unwrap();
                        let mut context = ParseContext::with_semantic_predicates(
                            "😀b",
                            &bindings,
                            ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                        );
                        let parser = Expr::SemanticPredicateScope {
                            child: Box::new(Expr::Sequence(vec![
                                Expr::Literal("😀b"),
                                Expr::choice([
                                    Expr::SemanticPredicate {
                                        child: Box::new(child.clone()),
                                        name: "valid",
                                    },
                                    Expr::Empty,
                                ]),
                            ])),
                            requirements: vec!["valid"],
                        };
                        let label = format!("{child:?} classic={classic} {memo:?}/{diagnostics:?}");
                        assert!(!parse(&mut context, &parser, classic), "{label}");
                        let failure = context.semantic_predicate_failure().unwrap();
                        assert_eq!(failure.kind, "predicate_source", "{label}");
                        assert_eq!(failure.span, Span { start: 2, end: 2 }, "{label}");
                        assert_eq!(context.position(), 0, "{label}");
                        assert_eq!(calls.load(Ordering::Relaxed), 0, "{label}");
                    }
                }
            }
        }
    }
}

/// A node-less PEG assertion has no source of its own: the candidate is "" at the cursor.
#[test]
fn source_less_zero_width_success_is_an_input_candidate_at_the_cursor() {
    let lookahead = |pattern, positive| Expr::Lookahead {
        child: Box::new(Expr::Literal(pattern)),
        positive,
    };
    let optional = |pattern| Expr::Optional(Box::new(Expr::Literal(pattern)));
    let children = [
        lookahead("c", true),
        lookahead("z", false),
        Expr::choice([lookahead("z", false)]),
        Expr::Empty,
        optional("z"),
        Expr::Sequence(vec![optional("z"), optional("y")]),
    ];
    for child in children {
        for verdict in [true, false] {
            for classic in [false, true] {
                for memo in [Memoization::Off, Memoization::SafeFailures] {
                    for diagnostics in DIAGNOSTICS {
                        let seen = Arc::new(Mutex::new(Vec::new()));
                        let observed = Arc::clone(&seen);
                        let bindings = Bindings::new()
                            .with("valid", move |candidate| {
                                let mut seen = observed.lock().unwrap();
                                let candidate = (candidate.text.to_owned(), candidate.span);
                                if !seen.contains(&candidate) {
                                    seen.push(candidate);
                                }
                                verdict
                            })
                            .unwrap();
                        let mut context = ParseContext::with_semantic_predicates(
                            "😀bc",
                            &bindings,
                            ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                        );
                        let parser = Expr::SemanticPredicateScope {
                            child: Box::new(Expr::Sequence(vec![
                                Expr::Literal("😀b"),
                                Expr::choice([
                                    Expr::SemanticPredicate {
                                        child: Box::new(child.clone()),
                                        name: "valid",
                                    },
                                    Expr::Literal("c"),
                                ]),
                            ])),
                            requirements: vec!["valid"],
                        };
                        let label = format!(
                            "{child:?} verdict={verdict} classic={classic} {memo:?}/{diagnostics:?}"
                        );
                        assert!(parse(&mut context, &parser, classic), "{label}");
                        assert_eq!(
                            *seen.lock().unwrap(),
                            vec![(String::new(), Span { start: 2, end: 2 })],
                            "{label}"
                        );
                        // true keeps the zero-width child; false rolls it back and "c" follows.
                        assert_eq!(context.position(), if verdict { 2 } else { 3 }, "{label}");
                        assert!(context.semantic_predicate_failure().is_none(), "{label}");
                    }
                }
            }
        }
    }
}
