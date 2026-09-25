//! FIRST-set candidate exclusion (#300, the Rust counterpart of Java #292 / case 37).
//!
//! Exclusion only skips candidates that are proven to fail, and only in the deferred
//! diagnostics pass, so every observable result must be identical with it off, on, and
//! in audit mode (which evaluates each skipped candidate and panics if it matches).
use std::cell::Cell;

use unlaxer_runtime::{
    parse_detailed_shared_with_options, parse_detailed_with_options,
    set_candidate_exclusion_for_current_thread, share_grammar, CandidateExclusion, Diagnostics,
    Expr, Memoization, ParseContext, ParseMatch, ParseOptions, ParseResult, Predictor, Rule,
    SharedGrammar,
};

const MODES: [CandidateExclusion; 3] = [
    CandidateExclusion::Off,
    CandidateExclusion::On,
    CandidateExclusion::Audit,
];

fn expression_grammar() -> Vec<Rule> {
    vec![
        Rule {
            name: "root",
            expression: Expr::sequence([
                Expr::Rule(3).repeat_java(0, None),
                Expr::Rule(1).capture("expression"),
                Expr::Eof,
            ])
            .trivia_scope(true),
        },
        Rule {
            name: "expression",
            expression: Expr::sequence([
                Expr::Rule(2),
                Expr::sequence([
                    Expr::choice([Expr::literal("+"), Expr::literal("-"), Expr::literal("/")]),
                    Expr::Rule(2),
                ])
                .zero_or_more(),
            ])
            .trivia_scope(true),
        },
        Rule {
            name: "term",
            expression: Expr::choice([
                Expr::sequence([
                    Expr::literal("if"),
                    Expr::Rule(1),
                    Expr::literal("then"),
                    Expr::Rule(1),
                ]),
                Expr::Number.capture("number"),
                Expr::Identifier.capture("identifier"),
                Expr::sequence([Expr::literal("("), Expr::Rule(1), Expr::literal(")")]),
                Expr::Quoted('"').text_value(),
                Expr::CharRange('α', 'ω').one_or_more().value_boundary(),
                Expr::Rule(4),
            ])
            .trivia_scope(true),
        },
        Rule {
            name: "annotation",
            expression: Expr::sequence([Expr::literal("@"), Expr::Identifier]).trivia_scope(true),
        },
        // No trivia inside: `a b` is not a `tight` token even though callers skip trivia.
        Rule {
            name: "tight",
            expression: Expr::sequence([
                Expr::literal("<"),
                Expr::literal("a"),
                Expr::literal(">"),
            ])
            .trivia_scope(false),
        },
    ]
}

const EXPRESSION_INPUTS: &[&str] = &[
    "1+2",
    "@a @b x - (y+3)",
    " /* c */ 1 // x\n + 2",
    "1 / 2 /* tail */",
    "(1",
    "",
    "@",
    "\"s\" + αβγ",
    "1+",
    "if 1 then 2",
    "iffy",
    "<a>",
    "< a>",
    "😀",
    "1 + é",
];

fn outcome(grammar: &SharedGrammar, input: &str, options: ParseOptions, shared: bool) -> String {
    let result = if shared {
        parse_detailed_shared_with_options(grammar, 0, true, input, options)
    } else {
        parse_detailed_with_options(grammar, 0, true, input, options)
    };
    // `ScopeStore` counts journal records including rolled-back ones, a metric like the
    // checkpoint counters (it is excluded from `ScopeStore` equality); fewer candidates
    // evaluated means fewer rolled-back records.
    let text = format!("{result:?}");
    let mut normalized = String::with_capacity(text.len());
    let mut rest = text.as_str();
    while let Some(index) = rest.find("journal_entries_created: ") {
        normalized.push_str(&rest[..index]);
        rest = rest[index + "journal_entries_created: ".len()..]
            .trim_start_matches(|c: char| c.is_ascii_digit());
    }
    normalized.push_str(rest);
    normalized
}

fn assert_same_outcomes(grammar: &SharedGrammar, inputs: &[&str]) {
    for input in inputs {
        for memoization in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::DetailedOnFailure,
                Diagnostics::Detailed,
            ] {
                for shared in [false, true] {
                    let options =
                        ParseOptions::with_memoization(memoization).with_diagnostics(diagnostics);
                    let outcomes = MODES.map(|mode| {
                        set_candidate_exclusion_for_current_thread(Some(mode));
                        outcome(grammar, input, options, shared)
                    });
                    set_candidate_exclusion_for_current_thread(None);
                    assert_eq!(outcomes[0], outcomes[1], "{input:?} {options:?} {shared}");
                    assert_eq!(outcomes[0], outcomes[2], "{input:?} {options:?} {shared}");
                }
            }
        }
    }
}

#[test]
fn exclusion_preserves_trees_scopes_and_diagnostics() {
    let grammar = share_grammar(expression_grammar());
    assert_same_outcomes(&grammar, EXPRESSION_INPUTS);
    // Spot-check that the corpus exercises both acceptance and rejection.
    set_candidate_exclusion_for_current_thread(Some(CandidateExclusion::On));
    let deferred = ParseOptions::default().with_diagnostics(Diagnostics::DetailedOnFailure);
    assert!(
        parse_detailed_shared_with_options(&grammar, 0, true, "@a x - (y+3)", deferred).is_ok()
    );
    let error = parse_detailed_shared_with_options(&grammar, 0, true, "(1", deferred).unwrap_err();
    set_candidate_exclusion_for_current_thread(None);
    assert_eq!(error.offset, 2);
    assert!(error.expected.contains(&")".to_owned()), "{error:?}");
}

#[test]
fn exclusion_removes_candidate_checkpoints_only_in_the_deferred_pass() {
    let grammar = share_grammar(expression_grammar());
    let source = "@a @b x - (y+3) + if 1 then \"s\" / αβ";
    let mut opened = Vec::new();
    for diagnostics in [Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
        for mode in [CandidateExclusion::Off, CandidateExclusion::On] {
            set_candidate_exclusion_for_current_thread(Some(mode));
            let mut context = ParseContext::with_options(
                source,
                ParseOptions::with_memoization(Memoization::SafeFailures)
                    .with_diagnostics(diagnostics),
            );
            context.enable_checkpoint_metrics();
            let matched: ParseMatch = context.parse_shared_grammar(&grammar, 0, true).unwrap();
            assert_eq!(matched.span.end, source.chars().count());
            opened.push(context.snapshot_checkpoint_metrics().opened);
        }
    }
    set_candidate_exclusion_for_current_thread(None);
    // Detailed is unchanged by the mode; deferred with exclusion opens fewer checkpoints.
    assert_eq!(opened[0], opened[1]);
    assert_eq!(opened[0], opened[2]);
    assert!(opened[3] * 10 < opened[2] * 8, "{opened:?}");
}

// Java AbstractTokenParser resets the matched cursor when a consuming atom fails inside
// Occurs (Java case 37, bug 2). A skipped optional body must still apply that reset, so
// `LOOKAHEAD('a') ['x'] LOOKAHEAD('b') 'ab'` keeps rejecting "ab".
#[test]
fn a_skipped_java_optional_body_keeps_the_failed_atom_cursor_reset() {
    for (body, accepted) in [
        (Expr::literal("x").optional_java(), false),
        (Expr::literal("x").repeat_java(0, None), false),
        // A sequence body rolls itself back, so the reset does not escape (Java agrees).
        (Expr::sequence([Expr::literal("x")]).optional_java(), true),
    ] {
        let grammar = share_grammar(vec![Rule {
            name: "root",
            expression: Expr::sequence([
                Expr::JavaLookahead {
                    pattern: "a",
                    positive: true,
                },
                body,
                Expr::JavaLookahead {
                    pattern: "b",
                    positive: true,
                },
                Expr::literal("ab"),
            ]),
        }]);
        assert_same_outcomes(&grammar, &["ab", "xab", "b"]);
        set_candidate_exclusion_for_current_thread(Some(CandidateExclusion::On));
        let deferred = ParseOptions::default().with_diagnostics(Diagnostics::DetailedOnFailure);
        let result = parse_detailed_shared_with_options(&grammar, 0, false, "ab", deferred);
        set_candidate_exclusion_for_current_thread(None);
        assert_eq!(result.is_ok(), accepted);
    }
}

thread_local! {
    static CUSTOM_CALLS: Cell<usize> = const { Cell::new(0) };
}

fn counted(context: &mut ParseContext<'_>) -> ParseResult {
    CUSTOM_CALLS.with(|calls| calls.set(calls.get() + 1));
    Err(context.error("custom"))
}

#[test]
fn custom_parsers_and_unmodelled_starts_are_never_skipped() {
    let custom = Expr::CustomWith {
        parser: counted,
        reads_diagnostics: false,
        replayable: true,
    };
    let grammar = share_grammar(vec![
        Rule {
            name: "root",
            expression: Expr::choice([
                Expr::Rule(1),
                custom.clone(),
                // Reached only after 'a' has been consumed.
                Expr::sequence([Expr::literal("a"), custom.clone()]),
                // A lookahead's child runs at this position even though it consumes nothing.
                Expr::sequence([custom.clone().ahead(), Expr::literal("q")]),
                Expr::Rule(2),
            ]),
        },
        Rule {
            name: "captured",
            expression: Expr::Identifier.capture("name"),
        },
        Rule {
            name: "repeat",
            expression: Expr::sequence([
                Expr::Backreference("name"),
                Expr::Until("!"),
                Expr::JavaUntil("?"),
                Expr::literal("z"),
            ]),
        },
    ]);
    let deferred = ParseOptions::default().with_diagnostics(Diagnostics::DetailedOnFailure);
    for input in ["y", "a", "b", "!z", "9"] {
        let mut calls = Vec::new();
        let mut outcomes = Vec::new();
        for mode in MODES {
            set_candidate_exclusion_for_current_thread(Some(mode));
            CUSTOM_CALLS.with(|calls| calls.set(0));
            outcomes.push(outcome(&grammar, input, deferred, true));
            calls.push(CUSTOM_CALLS.with(Cell::get));
        }
        set_candidate_exclusion_for_current_thread(None);
        assert_eq!(outcomes[0], outcomes[1], "{input:?}");
        assert_eq!(outcomes[0], outcomes[2], "{input:?}");
        assert_eq!(calls[0], calls[1], "{input:?}");
    }
}

#[test]
fn predictive_choice_uses_structural_first_sets_without_retry_when_deferred() {
    for predictors in [
        vec![Predictor::Literal("a"), Predictor::Literal("z")],
        vec![Predictor::Any, Predictor::Any],
    ] {
        let grammar = share_grammar(vec![Rule {
            name: "root",
            expression: Expr::PredictiveChoice {
                alternatives: vec![
                    Expr::sequence([Expr::literal("a"), Expr::literal("x")]),
                    Expr::literal("ab"),
                ],
                predictors,
            },
        }]);
        assert_same_outcomes(&grammar, &["ab", "ax", "b", " ab", ""]);
    }
    let longest = share_grammar(vec![Rule {
        name: "root",
        expression: Expr::longest_choice([
            Expr::literal("a"),
            Expr::literal("ab"),
            Expr::sequence([Expr::literal("b"), Expr::literal("c").not_ahead()]),
        ]),
    }]);
    assert_same_outcomes(&longest, &["a", "ab", "b", "bc", "c"]);
}
