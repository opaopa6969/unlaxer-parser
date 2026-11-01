//! #1006: virtual kind and source provenance are independent. A predicate candidate is the source
//! of the Parsed its child returns, so an input-backed empty virtual token is a candidate in every
//! caller kind, while a virtual collector that keeps nothing returns generated "" (at prefix 0 both
//! are empty at offset 0). Shared oracle with Java:
//! conformance/semantic-predicate-source/virtual-input.tsv.
use std::cell::RefCell;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use unlaxer_runtime::semantic_predicates::Bindings;
use unlaxer_runtime::token::{TokenKind, TokenSource};
use unlaxer_runtime::{
    share_grammar, Diagnostics, Expr, Memoization, ParseContext, ParseMatch, ParseOptions,
    ParseResult, Rule, SharedGrammar, Span,
};

/// One virtual token over the input-backed empty source at the cursor.
fn leaf(context: &mut ParseContext<'_>) -> ParseResult {
    let at = context.position();
    let span = Span { start: at, end: at };
    let id = context
        .add_token_node(0, TokenKind::VirtualConsumed, TokenSource::Input(span))
        .ok_or_else(|| context.error("token"))?;
    Ok(ParseMatch {
        span,
        nodes: vec![id.index()],
        captures: vec![],
    })
}

/// Generated text anchored at 0 or at the cursor; neither is an input candidate.
fn generated<const AT_CURSOR: bool, const EMPTY: bool>(
    context: &mut ParseContext<'_>,
) -> ParseResult {
    let at = if AT_CURSOR { context.position() } else { 0 };
    let id = context
        .add_token_node(
            0,
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

/// The names of conformance/semantic-predicate-source, plus `leaf`, `gen0:` and `genc:g`.
/// `Rule[X]` is rule 1 with body X: a Java LazyChain, or a LazyChoice for a Choice body.
fn shape(name: &str) -> (Expr, Option<Expr>) {
    let Some(open) = name.find('[') else {
        let atom = match name {
            "leaf" => Expr::Custom(leaf),
            "MO" => Expr::literal("c").match_only(),
            "&c" | "!z" => Expr::Lookahead {
                child: Box::new(Expr::Literal(if name == "&c" { "c" } else { "z" })),
                positive: name == "&c",
            },
            "Empty" => Expr::JavaEmpty,
            "gen0:" => Expr::Custom(generated::<false, true>),
            "genc:" => Expr::Custom(generated::<true, true>),
            "genc:g" => Expr::Custom(generated::<true, false>),
            _ => Expr::literal(Box::leak(name.to_owned().into_boxed_str())),
        };
        return (atom, None);
    };
    let inner = &name[open + 1..name.len() - 1];
    if &name[..open] == "Rule" {
        return (Expr::Rule(1), Some(shape(inner).0));
    }
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
    match &name[..open] {
        "Choice" => (Expr::choice(children), None),
        "Chain" => (Expr::sequence(children), None),
        "Opt" => (children.remove(0).optional_java(), None),
        _ => panic!("{name}"),
    }
}

thread_local! {
    static RULES: RefCell<Option<SharedGrammar>> = const { RefCell::new(None) };
}
fn enter_rules(context: &mut ParseContext<'_>) -> ParseResult {
    let grammar = RULES.with(|rules| rules.borrow().clone()).expect("rules");
    context.parse_shared_grammar(&grammar, 0, false)
}
/// `wrap` applied to the shape, inside rule 0 when the shape has a rule body.
fn entry(name: &str, wrap: fn(Expr) -> Expr) -> Expr {
    let (expression, rule) = shape(name);
    match rule {
        Some(body) => {
            let grammar = share_grammar(vec![
                Rule {
                    name: "start",
                    expression: wrap(expression),
                },
                Rule {
                    name: "r",
                    expression: body,
                },
            ]);
            RULES.with(|rules| *rules.borrow_mut() = Some(grammar));
            Expr::Custom(enter_rules)
        }
        None => wrap(expression),
    }
}
fn gated(child: Expr) -> Expr {
    Expr::SemanticPredicateScope {
        child: Box::new(Expr::SemanticPredicate {
            child: Box::new(child),
            name: "valid",
        }),
        requirements: vec!["valid"],
    }
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
fn prefix(code_points: &str) -> &'static str {
    match code_points {
        "0" => "",
        "1" => "p",
        "3" => "😀\r\n",
        _ => panic!("{code_points}"),
    }
}
fn all_options() -> Vec<ParseOptions> {
    let mut options = vec![];
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            options.push(ParseOptions::with_memoization(memo).with_diagnostics(diagnostics));
        }
    }
    options
}

#[test]
fn virtual_input_candidates_follow_the_returned_source() {
    let rows: Vec<Vec<&str>> =
        include_str!("../../../conformance/semantic-predicate-source/virtual-input.tsv")
            .lines()
            .filter(|row| !row.starts_with('#'))
            .map(|row| row.split('\t').collect())
            .collect();
    assert_eq!(rows.len(), 348);
    let mut failures = vec![];
    for row in &rows {
        let before: usize = row[1].parse().unwrap();
        for verdict in [true, false] {
            for options in all_options() {
                let label = format!("{row:?} verdict={verdict} {options:?}");
                let calls = Arc::new(AtomicUsize::new(0));
                let seen = Arc::new(Mutex::new(Vec::<String>::new()));
                let (counter, record) = (Arc::clone(&calls), Arc::clone(&seen));
                let bindings = Bindings::new()
                    .with("valid", move |candidate| {
                        counter.fetch_add(1, Ordering::Relaxed);
                        let text = if candidate.text.is_empty() {
                            "~"
                        } else {
                            candidate.text
                        };
                        assert_eq!(
                            candidate.span.end - candidate.span.start,
                            candidate.text.chars().count()
                        );
                        let cell = format!("{text}@{}", candidate.span.start);
                        let mut record = record.lock().unwrap();
                        if !record.contains(&cell) {
                            record.push(cell);
                        }
                        verdict
                    })
                    .unwrap();
                let input = format!("{}c", prefix(row[1]));
                let mut context =
                    ParseContext::with_semantic_predicates(&input, &bindings, options);
                if before > 0 {
                    context.parse(&Expr::literal(prefix(row[1]))).unwrap();
                }
                let ok = context
                    .parse_with_token_mode(&entry(row[2], gated), kind(row[0]), false)
                    .is_ok();
                let failure = context
                    .semantic_predicate_failure()
                    .map_or("-".to_owned(), |f| format!("{}@{}", f.kind, f.span.start));
                let seen = seen.lock().unwrap().join(",");
                let kept = row[3] != "-" && verdict;
                let cursor = if kept {
                    (row[4].parse().unwrap(), row[5].parse().unwrap())
                } else {
                    (before, before)
                };
                let actual = (
                    if seen.is_empty() {
                        "-".to_owned()
                    } else {
                        seen
                    },
                    failure,
                    ok,
                    (context.position(), context.matched_position()),
                );
                let expected = (row[3].to_owned(), row[6].to_owned(), kept, cursor);
                if actual != expected {
                    failures.push(format!("{label}: actual {actual:?}, expected {expected:?}"));
                }
                // Only an origin failure reaches no callback; it is sticky for both verdicts.
                if (calls.load(Ordering::Relaxed) == 0) != (row[3] == "-") {
                    failures.push(format!("{label}: calls {}", calls.load(Ordering::Relaxed)));
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

/// The candidate agrees with the token the child actually published, not with its kind.
#[test]
fn candidate_provenance_is_the_published_token_source() {
    for name in [
        "leaf",
        "Chain[leaf]",
        "Opt[leaf]",
        "Rule[leaf]",
        "Chain[MO]",
        "Rule[MO]",
    ] {
        for caller in [
            TokenKind::MatchOnly,
            TokenKind::Consumed,
            TokenKind::VirtualMatchOnly,
            TokenKind::VirtualConsumed,
        ] {
            for code_points in ["0", "1", "3"] {
                let label = format!("{name} {caller:?} {code_points}");
                let input = format!("{}c", prefix(code_points));
                let mut body = ParseContext::new(&input);
                let mut gate = ParseContext::with_semantic_predicates(
                    &input,
                    &Bindings::new().with("valid", |_| true).unwrap(),
                    ParseOptions::default(),
                );
                if code_points != "0" {
                    body.parse(&Expr::literal(prefix(code_points))).unwrap();
                    gate.parse(&Expr::literal(prefix(code_points))).unwrap();
                }
                let matched = body
                    .parse_with_token_mode(&entry(name, |e| e), caller, false)
                    .expect(&label);
                let id = body.token_node_id(matched.nodes[0]).unwrap();
                let published = body.token_info(id).unwrap().source.clone();
                let accepted = gate
                    .parse_with_token_mode(&entry(name, gated), caller, false)
                    .is_ok();
                match published {
                    TokenSource::Input(_) => {
                        assert!(accepted, "{label}");
                        assert!(gate.semantic_predicate_failure().is_none(), "{label}");
                    }
                    TokenSource::Generated { .. } | TokenSource::Projected { .. } => {
                        assert!(!accepted, "{label}");
                        assert_eq!(
                            gate.semantic_predicate_failure().map(|f| f.kind),
                            Some("predicate_source"),
                            "{label}"
                        );
                    }
                }
            }
        }
    }
}

/// A virtual-kind Classic Choice returns its selected child's Parsed, as Java's Choice: an
/// input-backed child stays input for an input capture boundary, although the Choice collector that
/// kept nothing publishes generated "". Java's CaptureBoundaryParser.input accepts all of these
/// (virtualChoiceKeepsTheSelectedInputChildForCaptureBoundaries).
#[test]
fn virtual_choice_keeps_the_selected_input_child_for_capture_boundaries() {
    for name in [
        "Choice[MO]",
        "Choice[leaf]",
        "Choice[Choice[leaf]]",
        "Choice[z,leaf]",
        "Choice[leaf,c]",
        "Choice[&c]",
        "Choice[!z]",
    ] {
        for caller in [TokenKind::VirtualMatchOnly, TokenKind::VirtualConsumed] {
            for code_points in ["0", "1", "3"] {
                for options in all_options() {
                    let label = format!("{name} {caller:?} {code_points} {options:?}");
                    let input = format!("{}c", prefix(code_points));
                    let mut context = ParseContext::with_options(&input, options);
                    if code_points != "0" {
                        context.parse(&Expr::literal(prefix(code_points))).unwrap();
                    }
                    let boundary = Expr::InputCaptureBoundary(
                        unlaxer_runtime::capture_tree::Kind::Value,
                        Box::new(shape(name).0),
                    );
                    assert!(
                        context
                            .parse_with_token_mode(&boundary, caller, false)
                            .is_ok(),
                        "{label}"
                    );
                }
            }
        }
    }
}

/// A virtual-kind rule that collected nothing publishes generated "": capture_owned records it at
/// the capture position, as Java's NamedCapture over the LazyChain records GENERATED there
/// (namedCaptureOfAnEmptyVirtualRuleIsGeneratedAtTheCursor). Not at the rule's own anchor 0.
#[test]
fn owned_capture_of_an_empty_virtual_rule_is_generated_at_the_cursor() {
    fn owned(expression: Expr) -> Expr {
        expression.capture_owned("v")
    }
    for name in [
        "Rule[leaf]",
        "Rule[Chain[leaf]]",
        "Rule[MO]",
        "Rule[Chain[MO]]",
        "Rule[&c]",
        "Rule[gen0:]",
    ] {
        for caller in [TokenKind::VirtualMatchOnly, TokenKind::VirtualConsumed] {
            for code_points in ["1", "3"] {
                for options in all_options() {
                    let label = format!("{name} {caller:?} {code_points} {options:?}");
                    let input = format!("{}c", prefix(code_points));
                    let mut context = ParseContext::with_options(&input, options);
                    context.parse(&Expr::literal(prefix(code_points))).unwrap();
                    context
                        .parse_with_token_mode(&entry(name, owned), caller, false)
                        .expect(&label);
                    assert_eq!(
                        context.captured_source("v"),
                        Some(&TokenSource::Generated {
                            anchor: code_points.parse().unwrap(),
                            text: String::new(),
                        }),
                        "{label}"
                    );
                }
            }
        }
    }
}

/// capture_owned records the source of the Parsed its child returns, not a guess from the token
/// kind: an input-backed empty virtual token and a PEG assertion that returned no token are input at
/// the cursor in every caller kind (#1023 C), and a predicate sees the same value. Shared oracle,
/// measured with Java NamedCapture: conformance/owned-capture-virtual/classic.tsv.
#[test]
fn owned_capture_follows_the_returned_source_in_every_caller_kind() {
    fn owned(expression: Expr) -> Expr {
        expression.capture_owned("v")
    }
    let rows: Vec<Vec<&str>> =
        include_str!("../../../conformance/owned-capture-virtual/classic.tsv")
            .lines()
            .filter(|row| !row.starts_with('#'))
            .map(|row| row.split('\t').collect())
            .collect();
    assert_eq!(rows.len(), 516);
    let mut failures = vec![];
    for row in &rows {
        for options in all_options() {
            let label = format!("{row:?} {options:?}");
            let input = format!("{}c", prefix(row[1]));
            let mut context = ParseContext::with_options(&input, options);
            if row[1] != "0" {
                context.parse(&Expr::literal(prefix(row[1]))).unwrap();
            }
            if context
                .parse_with_token_mode(&entry(row[2], owned), kind(row[0]), false)
                .is_err()
            {
                failures.push(format!("{label}: failed"));
                continue;
            }
            let actual = match context.captured_source("v") {
                Some(TokenSource::Input(span)) => format!(
                    "INPUT@{}-{}'{}'",
                    span.start,
                    span.end,
                    context.text(*span).unwrap()
                ),
                Some(TokenSource::Projected { span, text }) => {
                    format!("PROJECTED@{}-{}'{text}'", span.start, span.end)
                }
                Some(TokenSource::Generated { anchor, text }) => {
                    format!("GENERATED@{anchor}-{anchor}'{text}'")
                }
                None => "-".to_owned(),
            };
            let expected = format!(
                "{}@{}-{}'{}'",
                row[3],
                row[4],
                row[5],
                row[6].replace('~', "")
            );
            if actual != expected {
                failures.push(format!("{label}: actual {actual}, expected {expected}"));
                continue;
            }
            // The predicate at the same entry reads the same returned source.
            // Candidates as a set: the callback may be retried, its call count is not contract.
            let seen = Arc::new(Mutex::new(std::collections::BTreeSet::<String>::new()));
            let record = Arc::clone(&seen);
            let bindings = Bindings::new()
                .with("valid", move |candidate| {
                    record.lock().unwrap().insert(format!(
                        "INPUT@{}-{}'{}'",
                        candidate.span.start, candidate.span.end, candidate.text
                    ));
                    true
                })
                .unwrap();
            let mut gate = ParseContext::with_semantic_predicates(&input, &bindings, options);
            if row[1] != "0" {
                gate.parse(&Expr::literal(prefix(row[1]))).unwrap();
            }
            let accepted = gate
                .parse_with_token_mode(&entry(row[2], gated), kind(row[0]), false)
                .is_ok();
            let failure = gate
                .semantic_predicate_failure()
                .map(|failure| failure.kind);
            let seen = seen.lock().unwrap().iter().cloned().collect::<Vec<_>>();
            let predicate = (accepted, seen.join(","), failure);
            let agreed = if row[3] == "INPUT" {
                (true, expected.clone(), None)
            } else {
                (false, String::new(), Some("predicate_source"))
            };
            if predicate != agreed {
                failures.push(format!(
                    "{label}: predicate {predicate:?}, expected {agreed:?}"
                ));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}
