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

const PREFIX: &str = "😀\r\n";

fn scope(child: Expr) -> Expr {
    Expr::SemanticPredicateScope {
        child: Box::new(child),
        requirements: vec!["valid"],
    }
}
fn gate(child: Expr) -> Expr {
    Expr::SemanticPredicate {
        child: Box::new(child),
        name: "valid",
    }
}
fn all_options() -> Vec<ParseOptions> {
    [Memoization::Off, Memoization::SafeFailures]
        .into_iter()
        .flat_map(|memo| {
            DIAGNOSTICS.map(|diagnostics| {
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics)
            })
        })
        .collect()
}

/// Generated text a host returns at anchor 0 with a match at offset 0, not at the cursor.
fn generated_at_zero<const EMPTY: bool>(context: &mut ParseContext<'_>) -> ParseResult {
    let id = context
        .add_token_node(
            0,
            TokenKind::VirtualConsumed,
            TokenSource::Generated {
                anchor: 0,
                text: if EMPTY { "" } else { "g" }.into(),
            },
        )
        .ok_or_else(|| context.error("token"))?;
    Ok(ParseMatch {
        span: Span { start: 0, end: 0 },
        nodes: vec![id.index()],
        captures: vec![],
    })
}

/// A shape of conformance/semantic-predicate-source, as Java builds the same name: the expression
/// and, for `Rule[...]` / `UBNF:...`, the body of rule 1. `LA` is what both frontends emit for
/// UBNF `LOOKAHEAD('c')`; `MO` is the Classic combinator.
fn shape(name: &str) -> (Expr, Option<Expr>) {
    let Some(open) = name.find('[') else {
        let atom = match name {
            "MO" => Expr::literal("c").match_only(),
            "LA" => Expr::JavaLookahead {
                pattern: "c",
                positive: true,
            },
            "&c" | "!z" => Expr::Lookahead {
                child: Box::new(Expr::Literal(if name == "&c" { "c" } else { "z" })),
                positive: name == "&c",
            },
            "Empty" => Expr::JavaEmpty,
            "gen:g" => Expr::Custom(generated_at_zero::<false>),
            "gen:" => Expr::Custom(generated_at_zero::<true>),
            _ => Expr::literal(Box::leak(name.to_owned().into_boxed_str())),
        };
        return (atom, None);
    };
    let head = &name[..open];
    if head == "Rule" {
        return (
            Expr::Rule(1),
            Some(shape(&name[open + 1..name.len() - 1]).0),
        );
    }
    if head.starts_with("UBNF:") {
        // `@predicate R ::= ...` gates the rule body; the entry references R.
        return (Expr::Rule(1), Some(gate(shape(&name[5..]).0)));
    }
    let inner = &name[open + 1..name.len() - 1];
    let mut children = vec![];
    let (mut depth, mut start) = (0, 0);
    for (index, c) in inner.char_indices() {
        match c {
            '[' => depth += 1,
            ']' => depth -= 1,
            ',' if depth == 0 => {
                children.push(shape(&inner[start..index]).0);
                start = index + 1;
            }
            _ => {}
        }
    }
    children.push(shape(&inner[start..]).0);
    let expression = match head {
        "Choice" => Expr::choice(children),
        "Chain" => Expr::sequence(children),
        "Opt" => children.remove(0).optional_java(),
        _ => panic!("{name}"),
    };
    (expression, None)
}
fn candidate(cell: &str) -> Option<(String, Span)> {
    let (text, start) = cell.split_once('@')?;
    let text = text.replace('~', "");
    let start: usize = start.parse().unwrap();
    let end = start + text.chars().count();
    Some((text, Span { start, end }))
}
fn rows(table: &str) -> Vec<Vec<&str>> {
    table
        .lines()
        .filter(|row| !row.starts_with('#'))
        .map(|row| row.split('\t').collect())
        .collect()
}
fn kind(name: &str) -> TokenKind {
    match name {
        "consumed" => TokenKind::Consumed,
        "matchOnly" => TokenKind::MatchOnly,
        "virtualTokenConsumed" => TokenKind::VirtualConsumed,
        "virtualTokenMatchOnly" => TokenKind::VirtualMatchOnly,
        _ => panic!("{name}"),
    }
}
type Seen = Arc<Mutex<Vec<(String, Span)>>>;

fn recording(wants_c: bool) -> (Bindings, Seen) {
    let seen = Arc::new(Mutex::new(Vec::new()));
    let observed = Arc::clone(&seen);
    let bindings = Bindings::new()
        .with("valid", move |candidate| {
            let mut seen = observed.lock().unwrap();
            let verdict = (candidate.text == "c") == wants_c;
            let candidate = (candidate.text.to_owned(), candidate.span);
            if !seen.contains(&candidate) {
                seen.push(candidate);
            }
            verdict
        })
        .unwrap();
    (bindings, seen)
}

thread_local! {
    static RULES: std::cell::RefCell<Option<unlaxer_runtime::SharedGrammar>> =
        const { std::cell::RefCell::new(None) };
}
/// Enters the grammar of the current row at the caller's token kind (rule 0 gates rule 1).
fn enter_rules(context: &mut ParseContext<'_>) -> ParseResult {
    let grammar = RULES.with(|rules| rules.borrow().clone()).expect("rules");
    context.parse_shared_grammar(&grammar, 0, false)
}

/// A Choice and a rule of a Choice return the selected child's Parsed and a MatchOnly its own
/// source, so the callback decides the parse from that text; collectors and generated text follow
/// the token kind. Shared oracle with Java: conformance/semantic-predicate-source/classic.tsv.
#[test]
fn returned_source_decides_the_parse_in_every_token_kind() {
    let rows = rows(include_str!(
        "../../../conformance/semantic-predicate-source/classic.tsv"
    ));
    assert_eq!(rows.len(), 108);
    for row in rows {
        let (expression, rule) = shape(row[1]);
        let parser = match rule {
            Some(body) => {
                let grammar = unlaxer_runtime::share_grammar(vec![
                    unlaxer_runtime::Rule {
                        name: "start",
                        expression: scope(gate(expression)),
                    },
                    unlaxer_runtime::Rule {
                        name: "r",
                        expression: body,
                    },
                ]);
                RULES.with(|rules| *rules.borrow_mut() = Some(grammar));
                Expr::Custom(enter_rules)
            }
            None => scope(gate(expression)),
        };
        let expected = candidate(row[2]);
        for wants_c in [true, false] {
            for options in all_options() {
                let label = format!("{row:?} wants_c={wants_c} {options:?}");
                let (bindings, seen) = recording(wants_c);
                let mut context =
                    ParseContext::with_semantic_predicates("😀\r\nc", &bindings, options);
                context.parse(&Expr::literal(PREFIX)).unwrap();
                let ok = context
                    .parse_with_token_mode(&parser, kind(row[0]), false)
                    .is_ok();
                let kept = expected
                    .as_ref()
                    .is_some_and(|(text, _)| (text == "c") == wants_c);
                match &expected {
                    None => {
                        let failure = context.semantic_predicate_failure().expect(&label);
                        assert_eq!(
                            format!("{}@{}", failure.kind, failure.span.start),
                            row[5],
                            "{label}"
                        );
                        assert!(
                            seen.lock().unwrap().is_empty(),
                            "origin-less text reached the callback: {label}"
                        );
                    }
                    Some(expected) => {
                        assert_eq!(*seen.lock().unwrap(), vec![expected.clone()], "{label}");
                        assert!(context.semantic_predicate_failure().is_none(), "{label}");
                    }
                }
                assert_eq!(ok, kept, "{label}");
                let cursor = if kept {
                    (row[3].parse().unwrap(), row[4].parse().unwrap())
                } else {
                    (3, 3)
                };
                assert_eq!(
                    (context.position(), context.matched_position()),
                    cursor,
                    "{label}"
                );
            }
        }
    }
}

/// The same contract decides a whole parse at the full-input entry, whose root is not Classic.
/// Shared oracle with Java: conformance/semantic-predicate-source/full.tsv.
#[test]
fn returned_source_decides_the_whole_parse() {
    let rows = rows(include_str!(
        "../../../conformance/semantic-predicate-source/full.tsv"
    ));
    assert_eq!(rows.len(), 21);
    for row in rows {
        let (expression, rule) = shape(row[0]);
        let middle = if row[0].starts_with("UBNF:") {
            expression
        } else {
            gate(expression)
        };
        let mut rules = vec![unlaxer_runtime::Rule {
            name: "start",
            expression: scope(Expr::sequence([
                Expr::literal(PREFIX),
                middle,
                Expr::literal("c"),
            ])),
        }];
        rules.extend(rule.map(|expression| unlaxer_runtime::Rule {
            name: "r",
            expression,
        }));
        let grammar = unlaxer_runtime::share_grammar(rules);
        let expected = candidate(row[1]);
        for wants_c in [true, false] {
            for options in all_options() {
                let label = format!("{row:?} wants_c={wants_c} {options:?}");
                let (bindings, seen) = recording(wants_c);
                let result = unlaxer_runtime::parse_detailed_shared_with_semantic_predicates(
                    &grammar,
                    0,
                    false,
                    "😀\r\nc",
                    options,
                    &bindings,
                );
                match &expected {
                    None => {
                        let error = result.expect_err(&label);
                        assert_eq!(
                            format!("{}@{}", error.kind, error.offset),
                            row[2],
                            "{label}"
                        );
                        assert!(seen.lock().unwrap().is_empty(), "{label}");
                    }
                    Some(expected) => {
                        assert_eq!(*seen.lock().unwrap(), vec![expected.clone()], "{label}");
                        assert_eq!(result.is_ok(), (expected.0 == "c") == wants_c, "{label}");
                    }
                }
            }
        }
    }
}

/// Projected text a host returns: "Z" over 0..1 (away from the cursor) or "" at 0.
fn projected<const EMPTY: bool>(context: &mut ParseContext<'_>) -> ParseResult {
    let span = Span {
        start: 0,
        end: usize::from(!EMPTY),
    };
    let text = if EMPTY { "" } else { "Z" };
    let id = context
        .add_token_node(
            0,
            TokenKind::Consumed,
            TokenSource::Projected {
                span,
                text: text.into(),
            },
        )
        .ok_or_else(|| context.error("token"))?;
    Ok(ParseMatch {
        span,
        nodes: vec![id.index()],
        captures: vec![],
    })
}

/// Java cannot build a projected token, so this is Rust-only. A returned projection fails at its
/// logical start when nonempty and at the child's start when empty, on the full-input and the
/// Classic entry alike (never at an anchor the host chose for empty text).
#[test]
fn returned_projection_fails_at_the_same_offset_on_every_entry() {
    type Host = fn(&mut ParseContext<'_>) -> ParseResult;
    let customs: [(Host, usize); 2] = [(projected::<false>, 0), (projected::<true>, 3)];
    for (custom, offset) in customs {
        for child in [Expr::Custom(custom), Expr::choice([Expr::Custom(custom)])] {
            for classic in [false, true] {
                // A never-reached MatchOnly alternative switches ParseContext::parse to Classic.
                let mut alternatives = vec![gate(child.clone()), Expr::Empty];
                if classic {
                    alternatives.push(Expr::literal("q").match_only());
                }
                let expression = scope(Expr::sequence([
                    Expr::literal(PREFIX),
                    Expr::choice(alternatives),
                ]));
                let grammar = unlaxer_runtime::share_grammar(vec![unlaxer_runtime::Rule {
                    name: "start",
                    expression: expression.clone(),
                }]);
                for options in all_options() {
                    let label = format!("{child:?} classic={classic} {options:?}");
                    let (bindings, seen) = recording(true);
                    let error = unlaxer_runtime::parse_detailed_shared_with_semantic_predicates(
                        &grammar, 0, false, PREFIX, options, &bindings,
                    )
                    .unwrap_err();
                    assert_eq!(
                        (error.kind, error.offset),
                        ("predicate_source", offset),
                        "{label}"
                    );
                    let mut context =
                        ParseContext::with_semantic_predicates(PREFIX, &bindings, options);
                    assert!(context.parse(&expression).is_err(), "{label}");
                    let failure = context.semantic_predicate_failure().unwrap();
                    assert_eq!(
                        (failure.kind, failure.span),
                        (
                            "predicate_source",
                            Span {
                                start: offset,
                                end: offset
                            }
                        ),
                        "{label}"
                    );
                    assert!(seen.lock().unwrap().is_empty(), "{label}");
                }
            }
        }
    }
}

/// A Chain is a collector: what it returns for a projected child is the same value capture_owned
/// stores at that entry. The full-input entry's Sequence keeps no source of a host token (owned
/// capture records input "" at the cursor), while a Classic Chain collects the projection.
#[test]
fn chain_of_a_projection_sees_what_capture_owned_stores_at_each_entry() {
    let chain = || Expr::sequence([Expr::Custom(projected::<false>)]);
    for options in all_options() {
        // Full-input entry (its root is not Classic): an input candidate, and Replay of "".
        let (bindings, seen) = recording(false);
        let gated = unlaxer_runtime::share_grammar(vec![unlaxer_runtime::Rule {
            name: "start",
            expression: scope(Expr::sequence([Expr::literal(PREFIX), gate(chain())])),
        }]);
        assert!(
            unlaxer_runtime::parse_detailed_shared_with_semantic_predicates(
                &gated, 0, false, PREFIX, options, &bindings
            )
            .is_ok()
        );
        assert_eq!(
            *seen.lock().unwrap(),
            [(String::new(), Span { start: 3, end: 3 })]
        );
        let captured = [unlaxer_runtime::Rule {
            name: "start",
            expression: Expr::sequence([
                Expr::literal(PREFIX),
                chain().capture_owned("w"),
                Expr::Replay("w"),
            ]),
        }];
        assert!(
            unlaxer_runtime::parse_detailed_with_options(&captured, 0, false, PREFIX, options)
                .is_ok()
        );
        // Classic entry: the projected source, exactly as capture_owned stores it.
        let (bindings, seen) = recording(false);
        let mut context = ParseContext::with_semantic_predicates(PREFIX, &bindings, options);
        context.parse(&Expr::literal(PREFIX)).unwrap();
        assert!(context
            .parse_with_token_mode(&scope(gate(chain())), TokenKind::Consumed, false)
            .is_err());
        let failure = context.semantic_predicate_failure().unwrap();
        assert_eq!((failure.kind, failure.span.start), ("predicate_source", 0));
        assert!(seen.lock().unwrap().is_empty());
        let mut context = ParseContext::with_options(PREFIX, options);
        context.parse(&Expr::literal(PREFIX)).unwrap();
        context
            .parse_with_token_mode(&chain().capture_owned("w"), TokenKind::Consumed, false)
            .unwrap();
        assert_eq!(
            context.captured_source("w"),
            Some(&TokenSource::Projected {
                span: Span { start: 0, end: 1 },
                text: "Z".into()
            })
        );
    }
}
