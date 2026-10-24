//! The Rust side of the shared Number token oracle (#717). Java's
//! `NumberTokenOracleTest` reads the same rows with the real `NumberParser`.
use unlaxer_runtime::{
    parse_detailed_with_options, token::TokenKind, Diagnostics, Expr, Memoization, ParseContext,
    ParseOptions, Rule,
};

const CASES: &str = include_str!("../../../conformance/number-token/cases.tsv");

struct Case {
    id: String,
    input: String,
    kind: TokenKind,
    invert: bool,
    success: bool,
    consumed: usize,
    matched: usize,
    failure: Option<usize>,
    number: (bool, usize, usize),
}

fn cases() -> Vec<Case> {
    CASES
        .lines()
        .filter(|row| !row.is_empty() && !row.starts_with('#'))
        .map(|row| {
            let c: Vec<&str> = row.split('\t').collect();
            assert_eq!(c.len(), 11, "{row}");
            let input = if c[1] == "~" {
                String::new()
            } else {
                let bytes = (0..c[1].len())
                    .step_by(2)
                    .map(|i| u8::from_str_radix(&c[1][i..i + 2], 16).unwrap())
                    .collect();
                String::from_utf8(bytes).unwrap()
            };
            let kind = match c[2] {
                "consumed" => TokenKind::Consumed,
                "matchOnly" => TokenKind::MatchOnly,
                other => panic!("{other}"),
            };
            Case {
                id: c[0].to_owned(),
                input,
                kind,
                invert: c[3].parse().unwrap(),
                success: c[4].parse().unwrap(),
                consumed: c[5].parse().unwrap(),
                matched: c[6].parse().unwrap(),
                failure: (c[7] != "~").then(|| c[7].parse().unwrap()),
                number: (
                    c[8].parse().unwrap(),
                    c[9].parse().unwrap(),
                    c[10].parse().unwrap(),
                ),
            }
        })
        .collect()
}

fn number_then_a() -> Expr {
    Expr::sequence([Expr::Number, Expr::literal("a")])
}

fn configurations() -> Vec<(ParseOptions, bool, String)> {
    let mut out = Vec::new();
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for reset in [false, true] {
                out.push((
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    reset,
                    format!("{memo:?}/{diagnostics:?}/reset={reset}"),
                ));
            }
        }
    }
    out
}

/// Classic token mode (every kind and inversion row) follows Java's graph of the token.
// The Classic path already agreed with Java; the native test below detects the #717 mutation.
#[test]
fn classic_token_mode_matches_the_shared_oracle() {
    let cases = cases();
    assert_eq!(cases.len(), 144);
    for (options, reset, config) in configurations() {
        let detailed = options.diagnostics != Diagnostics::DetailedOnFailure;
        for case in &cases {
            let label = format!("{}/{config}", case.id);
            let mut context = ParseContext::with_options(&case.input, options);
            context.set_reset_matched_with_consumed(reset);
            let parsed = context.parse_with_token_mode(&number_then_a(), case.kind, case.invert);
            assert_eq!(parsed.is_ok(), case.success, "{label}");
            assert_eq!(
                (context.position(), context.matched_position()),
                (case.consumed, case.matched),
                "{label}"
            );
            if let Some(failure) = case.failure {
                let expected = if detailed { failure } else { 0 };
                assert_eq!(context.failure().offset, expected, "{label}");
            }

            let mut context = ParseContext::with_options(&case.input, options);
            context.set_reset_matched_with_consumed(reset);
            let parsed = context.parse_with_token_mode(&Expr::Number, case.kind, case.invert);
            assert_eq!(
                (
                    parsed.is_ok(),
                    context.position(),
                    context.matched_position()
                ),
                case.number,
                "{label}"
            );
        }
    }
}

/// The native token (outside token mode) and the generated-grammar entry use the
/// `consumed`/non-inverted rows: the token accepts the mantissa only, and the failure is
/// after the incomplete exponent marker and sign, as in Java (#717).
#[test]
fn native_number_and_grammar_entry_match_the_shared_oracle() {
    let rules = vec![Rule {
        name: "R0",
        expression: number_then_a(),
    }];
    let cases: Vec<Case> = cases()
        .into_iter()
        .filter(|c| c.kind == TokenKind::Consumed && !c.invert)
        .collect();
    assert_eq!(cases.len(), 36);
    for (options, _, config) in configurations() {
        let detailed = options.diagnostics != Diagnostics::DetailedOnFailure;
        for case in &cases {
            let label = format!("{}/{config}", case.id);
            let mut context = ParseContext::with_options(&case.input, options);
            let parsed = context.parse(&number_then_a());
            assert_eq!(parsed.is_ok(), case.success, "{label}");
            assert_eq!(
                (context.position(), context.matched_position()),
                (case.consumed, case.matched),
                "{label}"
            );
            if let Some(failure) = case.failure {
                let expected = if detailed { failure } else { 0 };
                assert_eq!(context.failure().offset, expected, "{label}");
            }

            let mut context = ParseContext::with_options(&case.input, options);
            let parsed = context.parse(&Expr::Number);
            assert_eq!(
                (
                    parsed.is_ok(),
                    context.position(),
                    context.matched_position()
                ),
                case.number,
                "{label}"
            );

            let result = parse_detailed_with_options(&rules, 0, false, &case.input, options);
            match (result, case.failure) {
                (Ok(_), None) => assert!(case.success, "{label}"),
                (Err(failure), Some(offset)) => {
                    assert_eq!(
                        (failure.kind, failure.offset),
                        ("syntax", offset),
                        "{label}"
                    )
                }
                (result, _) => panic!("{label}: {:?}", result.map(|_| ())),
            }
        }
    }
}

#[path = "support/number_token_diagnostics.rs"]
mod diagnostics;

#[test]
fn internal_failure_diagnostics_match_java() {
    diagnostics::check(diagnostics::rules, false, |context| {
        context.failure().expected
    });
}
