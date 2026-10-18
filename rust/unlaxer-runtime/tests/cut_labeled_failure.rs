//! Cut and labeled failures (#700) against the shared Java/Rust oracle.
use std::cell::Cell;
use unlaxer_runtime::{
    Diagnostics, Expr, Memoization, ParseContext, ParseMatch, ParseOptions, ParseResult,
    RaisedKind, RecoveryMode, Rule, Span,
};

fn text(value: &str) -> String {
    if value == "~" {
        String::new()
    } else {
        value.replace("\\r", "\r").replace("\\n", "\n")
    }
}
fn w(value: &'static str) -> Expr {
    Expr::literal(value)
}
fn seq<const N: usize>(elements: [Expr; N]) -> Expr {
    Expr::sequence(elements)
}

fn mutate(context: &mut ParseContext<'_>) -> ParseResult {
    context.set_state("value", 99i32);
    let at = context.position();
    Ok(ParseMatch::empty(Span { start: at, end: at }))
}

/// Mirrors `fixture` in unlaxer-common CutLabeledFailureTest.java.
fn fixture(name: &str) -> Expr {
    match name {
        "plain-choice" => Expr::choice([seq([w("("), w("x"), w(")")]), w("(y)")]),
        "commit-choice" => Expr::choice([
            seq([w("("), w("x").commit_with("expected x"), w(")")]),
            w("(y)"),
        ]),
        "commit-default" => w("a").commit(),
        "commit-optional" => seq([
            seq([w("a"), w("b").commit_with("expected b")]).optional(),
            w("a"),
            w("c"),
        ]),
        "commit-repeat" => seq([
            seq([w(","), w("n").commit_with("expected n")]).zero_or_more(),
            w(";"),
        ]),
        "label-repeat" => w("a").label("item").one_or_more(),
        "lookahead-commit" => seq([w("a").commit_with("expected a").ahead(), w("a")]),
        "not-commit" => seq([w("a").commit_with("expected a").not_ahead(), w("b")]),
        "label-choice" => Expr::choice([w("a").label("L"), w("b")]),
        "catch-alternative" => {
            seq([w("("), w(")").label("close")]).catch_labels(w("(]"), ["close"])
        }
        "catch-other-label" => w("a").label("A").catch_labels(w("b"), ["B"]),
        "catch-ordinary" => w("a").catch_labels(w("b"), ["L"]),
        "catch-commit" => w("a").commit_with("expected a").catch_labels(w("b"), ["L"]),
        "label-in-commit" => w("a").label("L").commit_with("m"),
        "commit-in-label" => w("a").commit_with("m").label("L"),
        "innermost-label" => seq([w("a"), w("b").label("inner")]).label("outer"),
        "nested-catch" => w("a")
            .label("A")
            .catch_labels(w("b").label("B"), ["A"])
            .catch_labels(w("c"), ["B"]),
        "recovery-commit" => seq([
            seq([w("x"), w("y").commit_with("expected y")]).recover(
                RecoveryMode::Sync,
                [";"],
                "syntax error: skipped to sync point",
            ),
            w("!"),
        ]),
        "recovery-label" => seq([w("x"), w("y").label("L")]).recover(
            RecoveryMode::Sync,
            [";"],
            "syntax error: skipped to sync point",
        ),
        "longest-commit" => {
            Expr::longest_choice([seq([w("a"), w("b").commit_with("expected b")]), w("ac")])
        }
        "nonordered-label" => Expr::non_ordered([w("a").label("L"), w("b")]),
        "unicode-label" => seq([w("😀"), w("\r\n"), w("界").label("L")]),
        "match-only-commit" => w("a").commit_with("expected a").match_only(),
        "state-success" => seq([Expr::Custom(mutate), w("a").capture_owned("c")]),
        "state-catch" => seq([
            Expr::Custom(mutate),
            w("a").capture_owned("c"),
            w("b").label("L"),
        ])
        .catch_labels(seq([w("a"), w("c")]), ["L"]),
        "state-commit" => seq([
            Expr::Custom(mutate),
            w("a").capture_owned("c"),
            w("b").commit_with("expected b"),
        ]),
        "state-label" => seq([
            Expr::Custom(mutate),
            w("a").capture_owned("c"),
            w("b").label("L"),
        ]),
        _ => panic!("{name}"),
    }
}

/// Both evaluations of rule 0 run at the same depth, so the second is a memo hit.
fn memo_grammar() -> Vec<Rule> {
    vec![
        Rule {
            name: "R",
            expression: w("a").label("L"),
        },
        Rule {
            name: "Root",
            expression: Expr::choice([Expr::Rule(0)])
                .catch_labels(Expr::choice([Expr::Rule(0), w("b")]), ["L"]),
        },
    ]
}

fn rows(file: &str, columns: usize) -> Vec<Vec<&str>> {
    let rows: Vec<Vec<&str>> = file
        .lines()
        .filter(|row| !row.starts_with('#'))
        .map(|row| row.split('\t').collect())
        .collect();
    for row in &rows {
        assert_eq!(row.len(), columns, "{row:?}");
    }
    rows
}

const SETTINGS: [(Memoization, Diagnostics); 6] = [
    (Memoization::Off, Diagnostics::Auto),
    (Memoization::Off, Diagnostics::Detailed),
    (Memoization::Off, Diagnostics::DetailedOnFailure),
    (Memoization::SafeFailures, Diagnostics::Auto),
    (Memoization::SafeFailures, Diagnostics::Detailed),
    (Memoization::SafeFailures, Diagnostics::DetailedOnFailure),
];

fn assert_outcome(
    label: &str,
    outcome: &str,
    offset: &str,
    context: &ParseContext<'_>,
    accepted: bool,
) {
    let raised = context.raised_failure();
    match outcome {
        "success" | "ordinary" => {
            assert_eq!(accepted, outcome == "success", "{label}");
            assert_eq!(raised, None, "{label}");
        }
        "commit" | "label" => {
            assert!(!accepted, "{label}");
            let raised = raised.unwrap_or_else(|| panic!("{label}"));
            let kind = if outcome == "commit" {
                RaisedKind::Commit
            } else {
                RaisedKind::Label
            };
            assert_eq!(raised.kind, kind, "{label}");
            let offset: usize = offset.parse().unwrap();
            assert_eq!(raised.offset, offset, "{label}");
            // The final error prefers the raised failure in every diagnostics policy.
            let failure = context.failure();
            assert_eq!(failure.offset, offset, "{label}");
            assert_eq!(failure.expected, vec![raised.message.to_owned()], "{label}");
        }
        _ => panic!("{label}: {outcome}"),
    }
}

#[test]
fn shared_independent_oracle() {
    let rows = rows(
        include_str!("../../../conformance/cut-labeled-failure/cases.tsv"),
        10,
    );
    assert_eq!(rows.len(), 45);
    for (memo, diagnostics) in SETTINGS {
        for c in &rows {
            let label = format!("{}/{memo:?}/{diagnostics:?}", c[0]);
            let input = text(c[2]);
            let mut context = ParseContext::with_options(
                &input,
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            );
            let accepted = if c[1] == "memo-label" {
                context.parse_grammar(memo_grammar(), 1, false).is_ok()
            } else {
                context.parse(&fixture(c[1])).is_ok()
            };
            assert_eq!(accepted, c[3] == "true", "{label}");
            assert_eq!(
                context.position(),
                c[4].parse::<usize>().unwrap(),
                "{label}"
            );
            assert_eq!(
                context.matched_position(),
                c[5].parse::<usize>().unwrap(),
                "{label}"
            );
            assert_outcome(&label, c[6], c[9], &context, accepted);
            if let Some(raised) = context.raised_failure() {
                assert_eq!(raised.label, (c[7] != "~").then_some(c[7]), "{label}");
                assert_eq!(raised.message, text(c[8]), "{label}");
            }
            if c[1] == "memo-label" && c[2] == "b" && memo == Memoization::SafeFailures {
                // The replayed entry must restore the label, or the choice would try "b".
                assert!(context.memoized_failure_hits() > 0, "{label}");
            }
        }
    }
}

#[test]
fn shared_state_oracle() {
    let rows = rows(
        include_str!("../../../conformance/cut-labeled-failure/state.tsv"),
        9,
    );
    assert_eq!(rows.len(), 4);
    for (memo, diagnostics) in SETTINGS {
        for c in &rows {
            let label = format!("{}/{memo:?}/{diagnostics:?}", c[0]);
            let input = text(c[2]);
            let mut context = ParseContext::with_options(
                &input,
                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
            );
            context.set_state("value", 7i32);
            let accepted = context.parse(&fixture(c[1])).is_ok();
            assert_eq!(accepted, c[3] == "true", "{label}");
            assert_eq!(
                context.position(),
                c[4].parse::<usize>().unwrap(),
                "{label}"
            );
            assert_outcome(&label, c[5], c[6], &context, accepted);
            assert_eq!(
                context.state::<i32>("value"),
                Some(&c[7].parse::<i32>().unwrap()),
                "{label}"
            );
            assert_eq!(context.captured_text("c").is_some(), c[8] == "1", "{label}");
        }
    }
}

#[test]
fn full_input_diagnostic_reports_the_raised_failure() {
    for (memo, diagnostics) in SETTINGS {
        let rules = vec![Rule {
            name: "Root",
            expression: fixture("commit-choice"),
        }];
        let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
        let error = unlaxer_runtime::parse_detailed_with_options(&rules, 0, false, "(y)", options)
            .unwrap_err();
        assert_eq!(error.kind, "commit");
        assert_eq!(error.offset, 1);
        assert_eq!(error.expected, ["expected x"]);
        assert_eq!(error.farthest.offset, 1);
        let rules = vec![Rule {
            name: "Root",
            expression: fixture("label-choice"),
        }];
        let error = unlaxer_runtime::parse_detailed_with_options(&rules, 0, false, "b", options)
            .unwrap_err();
        assert_eq!((error.kind, error.offset), ("label", 0));
        assert_eq!(error.expected, ["L"]);
        assert!(
            unlaxer_runtime::parse_detailed_with_options(&rules, 0, false, "a", options).is_ok()
        );
    }
}

#[test]
fn a_raised_failure_does_not_leak_into_the_next_parse() {
    let mut context = ParseContext::new("b");
    assert!(context.parse(&fixture("label-choice")).is_err());
    assert_eq!(context.raised_failure().unwrap().label, Some("L"));
    assert!(context.parse(&Expr::choice([w("a"), w("b")])).is_ok());
    assert_eq!(context.raised_failure(), None);
}

thread_local! {
    static ATTEMPTS: Cell<usize> = const { Cell::new(0) };
}
fn counted(context: &mut ParseContext<'_>) -> ParseResult {
    ATTEMPTS.with(|attempts| attempts.set(attempts.get() + 1));
    context.parse(&Expr::literal("("))
}

/// Observation for #700: a committed error stops the remaining alternatives being explored.
#[test]
fn commit_stops_further_alternatives() {
    const LETTERS: [&str; 16] = [
        "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q",
    ];
    for committed in [false, true] {
        ATTEMPTS.with(|attempts| attempts.set(0));
        let first = seq([
            w("("),
            if committed {
                w("x").commit_with("expected x")
            } else {
                w("x")
            },
        ]);
        let alternatives = std::iter::once(first).chain(
            LETTERS
                .iter()
                .map(|letter| seq([Expr::Custom(counted), w(letter)])),
        );
        let mut context = ParseContext::new("(z");
        assert!(context.parse(&Expr::choice(alternatives)).is_err());
        assert_eq!(
            ATTEMPTS.with(Cell::get),
            if committed { 0 } else { 16 },
            "{committed}"
        );
        assert_eq!(context.raised_failure().is_some(), committed);
    }
}

fn swallow(context: &mut ParseContext<'_>) -> ParseResult {
    // A caller unaware of #700: it ignores the raised failure and succeeds.
    let _ = context.parse(&w("a").commit());
    let at = context.position();
    Ok(ParseMatch::empty(Span { start: at, end: at }))
}

/// A raised failure ignored by its caller does not turn a later ordinary failure into a cut.
#[test]
fn an_ignored_raised_failure_does_not_turn_a_later_ordinary_failure_into_a_cut() {
    for (memo, diagnostics) in SETTINGS {
        let mut context = ParseContext::with_options(
            "c",
            ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
        );
        let parser = Expr::choice([seq([Expr::Custom(swallow), w("b")]), w("c")]);
        assert!(context.parse(&parser).is_ok(), "{memo:?}/{diagnostics:?}");
        assert_eq!(context.position(), 1);
        assert_eq!(context.raised_failure(), None);
    }
}

thread_local! {
    static REGISTRY: unlaxer_runtime::shared_calls::Registry = {
        let grammar = unlaxer_runtime::embedded::CstGrammar {
            name: "Inner".into(),
            grammar: unlaxer_runtime::share_grammar(vec![Rule {
                name: "Root",
                expression: seq([w("a"), w("b").commit_with("inner cut")]),
            }]),
            entries: [("Root".to_owned(), 0)].into_iter().collect(),
            bindings: Vec::new(),
            whitespace: false,
        };
        unlaxer_runtime::shared_calls::Registry::new([(language(), grammar)].into_iter().collect())
            .unwrap()
    };
}
fn language() -> unlaxer_runtime::source::Language {
    unlaxer_runtime::source::Language {
        id: "inner".into(),
        package_id: "org.example".into(),
        version: "1".into(),
        grammar: "Inner".into(),
        entry: "Root".into(),
    }
}
fn shared_call(context: &mut ParseContext<'_>) -> ParseResult {
    REGISTRY
        .with(|registry| registry.call(context, &language()))
        .map_err(|failure| failure.error)
}

/// A shared grammar call returns its failure as data: an inner cut is an ordinary failure outside.
#[test]
fn a_shared_grammar_call_does_not_leak_an_inner_cut() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        let mut context = ParseContext::with_options(
            "ax",
            ParseOptions::with_memoization(memo).with_diagnostics(Diagnostics::Detailed),
        );
        let parser = Expr::choice([Expr::Custom(shared_call), w("ax")]);
        assert!(context.parse(&parser).is_ok(), "{memo:?}");
        assert_eq!(context.position(), 2);
        assert_eq!(context.raised_failure(), None);
    }
}
