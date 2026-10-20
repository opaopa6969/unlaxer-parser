//! Rule stacks of the final error (#703) against the shared Java/Rust oracle.
use std::sync::Arc;
use unlaxer_runtime::{
    parse_detailed_shared_with_options, parse_detailed_shared_with_rule_stack, Diagnostics, Expr,
    Memoization, ParseContext, ParseOptions, RaisedKind, Rule, RuleStack, SharedGrammar,
    RULE_STACK_LIMIT,
};

fn w(value: &'static str) -> Expr {
    Expr::literal(value)
}
fn seq<const N: usize>(elements: [Expr; N]) -> Expr {
    Expr::sequence(elements)
}
fn r(index: usize) -> Expr {
    Expr::Rule(index)
}
fn rule(name: &'static str, expression: Expr) -> Rule {
    Rule { name, expression }
}
/// The UBNF positive LOOKAHEAD token (Java `MatchOnly(WordParser)`).
fn ahead(pattern: &'static str) -> Expr {
    Expr::JavaLookahead {
        pattern,
        positive: true,
    }
}

const DEEP_NAMES: [&str; 40] = [
    "D0", "D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8", "D9", "D10", "D11", "D12", "D13", "D14",
    "D15", "D16", "D17", "D18", "D19", "D20", "D21", "D22", "D23", "D24", "D25", "D26", "D27",
    "D28", "D29", "D30", "D31", "D32", "D33", "D34", "D35", "D36", "D37", "D38", "D39",
];

/// Mirrors `grammar` in unlaxer-common RuleStackTest.java; rule 0 is the root.
/// `None` is the rule-free fixture, parsed as a bare expression.
fn grammar(name: &str) -> Option<Vec<Rule>> {
    Some(match name {
        "nested" => vec![
            rule("Root", seq([w("("), r(1), w(")")])),
            rule("Item", Expr::choice([w("x"), w("y")])),
        ],
        "alternatives" => vec![
            rule("Root", Expr::choice([r(1), r(2)])),
            rule("A", seq([w("a"), w("b")])),
            rule("B", seq([w("a"), w("c"), w("d")])),
        ],
        "deeper" => vec![
            rule("Root", Expr::choice([r(1), r(2)])),
            rule("A", seq([w("a"), w("q")])),
            rule("B", seq([w("a"), r(3)])),
            rule("C", seq([w("r")])),
        ],
        "equal" => vec![
            rule("Root", Expr::choice([r(1), r(2)])),
            rule("A", seq([w("a"), w("q")])),
            rule("B", seq([w("a"), w("r")])),
        ],
        "lookahead" => vec![
            rule("Root", seq([r(1).not_ahead(), r(2)])),
            rule("Probe", seq([w("("), r(3)])),
            rule(
                "Body",
                Expr::choice([seq([w("("), r(3), w("!")]), seq([w("("), w("k")])]),
            ),
            rule("M", seq([w("a"), r(4)])),
            rule("N", seq([w("b"), w("c")])),
        ],
        "commit" => vec![
            rule("Root", seq([w("("), r(1)])),
            rule("Body", seq([r(2), w(")")])),
            rule("Inner", seq([w("x"), w("y").commit_with("expected y")])),
        ],
        "label" => vec![
            rule("Root", seq([r(1)])),
            rule("List", seq([w("["), r(2).label("item"), w("]")])),
            rule("Item", seq([w("a")])),
        ],
        "memo-label" => vec![
            rule("Root", r(1).catch_labels(r(2), ["L"])),
            rule("A", seq([w("("), r(3)])),
            rule("B", seq([w("("), r(3)])),
            rule("M", w("x").label("L")),
        ],
        "caught" => vec![
            rule("Root", r(1).catch_labels(r(2), ["L"])),
            rule("P", w("a").label("L")),
            rule("Q", seq([w("b"), w("c")])),
        ],
        "deep" | "deep-commit" => (0..40)
            .map(|index| {
                let next = if index == 39 {
                    if name == "deep" {
                        w("x")
                    } else {
                        w("x").commit_with("expected x")
                    }
                } else {
                    r(index + 1)
                };
                rule(DEEP_NAMES[index], seq([w("("), next]))
            })
            .collect(),
        "unicode" => vec![
            rule("Root", seq([w("😀"), r(1)])),
            rule("Item", seq([w("界"), w("x")])),
        ],
        // Root ::= 'y' (AHEAD Item) ; Item ::= 'x' 'a' ; AHEAD = LOOKAHEAD('x') (#706).
        "lookahead-start" => vec![
            rule("Root", seq([w("y"), seq([ahead("x"), r(1)])])),
            rule("Item", seq([w("x"), w("a")])),
        ],
        // Root ::= AHEAD (Item | 'x' 'a') ; Item ::= 'a' ; (#706).
        "lookahead-frame" => vec![
            rule(
                "Root",
                seq([ahead("x"), Expr::choice([r(1), seq([w("x"), w("a")])])]),
            ),
            rule("Item", seq([w("a")])),
        ],
        "bare" => return None,
        _ => panic!("{name}"),
    })
}

fn bare() -> Expr {
    seq([w("a"), w("b")])
}

fn rows() -> Vec<Vec<&'static str>> {
    let rows: Vec<Vec<&str>> = include_str!("../../../conformance/rule-stack/cases.tsv")
        .lines()
        .filter(|row| !row.starts_with('#'))
        .map(|row| row.split('\t').collect())
        .collect();
    for row in &rows {
        assert_eq!(row.len(), 8, "{row:?}");
    }
    assert_eq!(rows.len(), 17);
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

/// `Root@0>Item@1`, or `~` without a stack.
fn render(stack: &Option<RuleStack>) -> (String, usize) {
    match stack {
        None => ("~".into(), 0),
        Some(stack) => (
            stack
                .frames
                .iter()
                .map(|frame| format!("{}@{}", frame.rule, frame.start))
                .collect::<Vec<_>>()
                .join(">"),
            stack.omitted,
        ),
    }
}

fn assert_stack(label: &str, row: &[&str], stack: &Option<RuleStack>) {
    assert_eq!(
        render(stack),
        (row[6].to_owned(), row[7].parse().unwrap()),
        "{label}"
    );
    if let Some(stack) = stack {
        assert_eq!(stack.offset, row[5].parse::<usize>().unwrap(), "{label}");
        assert!(stack.frames.len() <= RULE_STACK_LIMIT, "{label}");
        for frame in &stack.frames {
            let span = stack.span(frame);
            assert!(
                span.start <= span.end && span.end == stack.offset,
                "{label}"
            );
        }
    }
}

#[test]
fn shared_independent_oracle() {
    for (memo, diagnostics) in SETTINGS {
        for row in rows() {
            let label = format!("{}/{memo:?}/{diagnostics:?}", row[0]);
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut context = ParseContext::with_options(row[2], options);
            let accepted = match grammar(row[1]) {
                Some(rules) => context.parse_grammar(rules, 0, false).is_ok(),
                None => context.parse(&bare()).is_ok(),
            };
            assert!(!accepted, "{label}");
            let detailed = context.options().diagnostics == Diagnostics::Detailed;
            match row[3] {
                "syntax" => {
                    assert_eq!(context.raised_failure(), None, "{label}");
                    if detailed {
                        assert_eq!(
                            context.failure().offset,
                            row[5].parse::<usize>().unwrap(),
                            "{label}"
                        );
                    }
                }
                outcome => {
                    let raised = context.raised_failure().expect(&label);
                    let kind = if outcome == "commit" {
                        RaisedKind::Commit
                    } else {
                        RaisedKind::Label
                    };
                    assert_eq!(raised.kind, kind, "{label}");
                    assert_eq!(raised.label, (row[4] != "~").then_some(row[4]), "{label}");
                    assert_eq!(raised.offset, row[5].parse::<usize>().unwrap(), "{label}");
                }
            }
            let stack = context.rule_stack();
            if detailed {
                assert_stack(&label, &row, &stack);
            } else {
                // A direct context never retries, and only a detailed pass records rules.
                assert_eq!(stack, None, "{label}");
            }
            if memo == Memoization::SafeFailures && matches!(row[1], "lookahead" | "memo-label") {
                // The expected stack is only reachable through a rebased memo replay.
                assert!(context.memoized_failure_hits() > 0, "{label}");
            }
        }
    }
}

#[test]
fn full_input_parse_reports_the_stack_under_every_policy() {
    for (memo, diagnostics) in SETTINGS {
        for row in rows() {
            let Some(rules) = grammar(row[1]) else {
                continue;
            };
            let label = format!("{}/{memo:?}/{diagnostics:?}", row[0]);
            let grammar: SharedGrammar = Arc::from(rules);
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let failure =
                parse_detailed_shared_with_rule_stack(&grammar, 0, false, row[2], options)
                    .unwrap_err();
            // DetailedOnFailure retries every failure in a detailed pass for the stack.
            assert_stack(&label, &row, &failure.rule_stack);
            assert_eq!(
                failure.diagnostic.kind,
                if row[3] == "syntax" { "syntax" } else { row[3] },
                "{label}"
            );
            // The diagnostic itself is the one the existing entry returns.
            let plain = parse_detailed_shared_with_options(&grammar, 0, false, row[2], options)
                .unwrap_err();
            assert_eq!(failure.diagnostic, plain, "{label}");
        }
    }
}

#[test]
fn a_raised_stack_does_not_leak_into_a_later_parse() {
    let mut context = ParseContext::new("(xz");
    assert!(context
        .parse_grammar(grammar("commit").unwrap(), 0, false)
        .is_err());
    assert_eq!(render(&context.rule_stack()).0, "Root@0>Body@1>Inner@1");
    // The same context, now an ordinary failure: the farthest failure's stack, as
    // context.failure() reports the farthest failure of the context.
    assert!(context
        .parse_grammar(grammar("equal").unwrap(), 0, false)
        .is_err());
    assert_eq!(context.raised_failure(), None);
    let stack = context.rule_stack().unwrap();
    assert_eq!(stack.offset, context.failure().offset);
}

#[test]
fn successful_parses_report_no_stack_but_unconsumed_input_does() {
    // Root ::= 'a' [Opt] ; Opt ::= 'b' ; mirrored in RuleStackTest.java.
    let grammar = || {
        vec![
            rule("Root", seq([w("a"), r(1).optional()])),
            rule("Opt", seq([w("b")])),
        ]
    };
    for (memo, diagnostics) in SETTINGS {
        let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
        let detailed = ParseContext::with_options("", options)
            .options()
            .diagnostics
            == Diagnostics::Detailed;
        for (input, unconsumed) in [("ab", "~"), ("az", "Root@0>Opt@1"), ("a", "~")] {
            let label = format!("{input}/{memo:?}/{diagnostics:?}");
            let mut context = ParseContext::with_options(input, options);
            assert!(
                context.parse_grammar(grammar(), 0, false).is_ok(),
                "{label}"
            );
            // Opt failed at 1 in every input, but a successful parse reports no stack.
            assert_eq!(context.rule_stack(), None, "{label}");
            let expected = if detailed { unconsumed } else { "~" };
            assert_eq!(
                render(&context.unconsumed_input_rule_stack()).0,
                expected,
                "{label}"
            );
            // The full-input entry reports the same stack for the trailing input.
            let shared: SharedGrammar = Arc::from(grammar());
            let full = parse_detailed_shared_with_rule_stack(&shared, 0, false, input, options);
            assert_eq!(
                full.err()
                    .map_or("~".to_owned(), |failure| render(&failure.rule_stack).0),
                unconsumed,
                "{label}"
            );
        }
    }
    // A failure after a success on the same context reports again.
    let mut context = ParseContext::new("(z)");
    assert!(context.parse_grammar(grammar(), 0, false).is_err());
    assert_eq!(render(&context.rule_stack()).0, "Root@0");
}
