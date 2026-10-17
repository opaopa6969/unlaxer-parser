use std::fmt::Write;
use unlaxer_runtime::token::TokenKind;
use unlaxer_runtime::{
    share_grammar, Diagnostics, Expr, Memoization, ParseContext, ParseError, ParseMatch,
    ParseOptions, ParseResult, Parser, Rule, SharedGrammar, Span,
};

struct Grammar(SharedGrammar);
impl Parser for Grammar {
    fn parse(&self, context: &mut ParseContext<'_>) -> ParseResult {
        context.parse_shared_grammar(&self.0, 0, false)
    }
}
fn decode(value: &str) -> String {
    if value == "-" {
        return String::new();
    }
    String::from_utf8(
        (0..value.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&value[i..i + 2], 16).unwrap())
            .collect(),
    )
    .unwrap()
}
fn hex(value: &str) -> String {
    if value.is_empty() {
        return "-".into();
    }
    value
        .as_bytes()
        .iter()
        .fold(String::new(), |mut text, byte| {
            write!(text, "{byte:02x}").unwrap();
            text
        })
}
fn rollback(context: &ParseContext<'_>) -> ParseError {
    ParseError {
        offset: context.position(),
        expected: vec![],
    }
}

#[test]
fn classic_shared_oracle_preserves_results_and_deliberately_has_no_memo_hits() {
    for line in include_str!("../../../conformance/memo-reset-policy/cases.tsv")
        .lines()
        .filter(|line| !line.starts_with('#') && !line.is_empty())
    {
        let p: Vec<_> = line.split('\t').collect();
        let id = p[0];
        let input = decode(p[1]);
        let word: &'static str = Box::leak(decode(p[2]).into_boxed_str());
        let number = |i: usize| p[i].parse::<usize>().unwrap();
        let flag = |i: usize| p[i].parse::<bool>().unwrap();
        let grammar = Grammar(share_grammar(vec![Rule {
            name: "choice",
            expression: Expr::Choice(vec![Expr::Literal(word)]),
        }]));
        let mut summaries = vec![];
        let mut diagnostics = vec![];
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            let mut context = ParseContext::with_options(
                &input,
                ParseOptions::with_memoization(memo).with_diagnostics(Diagnostics::Detailed),
            );
            assert!(context.advance(number(3)));
            for _ in number(3)..number(4) {
                context
                    .parse_with_token_mode(&Expr::Any, TokenKind::MatchOnly, false)
                    .unwrap();
            }
            let mut baseline_nodes = 0;
            while context.node(baseline_nodes).is_some() {
                baseline_nodes += 1;
            }
            context.set_reset_matched_with_consumed(flag(5));
            let _: Result<(), _> = context.transaction(|context| {
                let warm = context.parse_with_token_mode(&grammar, TokenKind::MatchOnly, false);
                assert_eq!(flag(7), warm.is_ok(), "{id}/warm/{memo:?}");
                Err(rollback(context))
            });
            assert_eq!(
                (number(3), number(4)),
                (context.position(), context.matched_position())
            );
            context.set_reset_matched_with_consumed(flag(6));
            let mut summary = String::new();
            let mut node_id = None;
            let _: Result<(), _> = context.transaction(|context| {
                let result = context.parse_with_token_mode(&grammar, TokenKind::MatchOnly, false);
                assert_eq!(flag(8), result.is_ok(), "{id}/second/{memo:?}");
                assert_eq!(
                    (number(9), number(10)),
                    (context.position(), context.matched_position()),
                    "{id}"
                );
                let (start, end, text) = if let Ok(parsed) = &result {
                    let root = parsed.root_node().unwrap();
                    let span = context.node(root).unwrap().span;
                    let token = context.token_node_id(root).unwrap();
                    assert_eq!(
                        TokenKind::MatchOnly,
                        context.token_info(token).unwrap().kind
                    );
                    node_id = Some(token);
                    (
                        span.start as isize,
                        span.end as isize,
                        hex(context.token_text(token).unwrap()),
                    )
                } else {
                    (-1, -1, "-".into())
                };
                assert_eq!(p[11].parse::<isize>().unwrap(), start, "{id}");
                assert_eq!(p[12].parse::<isize>().unwrap(), end, "{id}");
                assert_eq!(p[13], text, "{id}");
                summary = format!(
                    "{}\t{}\t{}\t{start}\t{end}\t{text}",
                    result.is_ok(),
                    context.position(),
                    context.matched_position()
                );
                diagnostics.push(result.err());
                Err(rollback(context))
            });
            assert_eq!(
                (number(3), number(4)),
                (context.position(), context.matched_position())
            );
            assert!(
                context.node(baseline_nodes).is_none(),
                "{id}: outer rollback must remove new nodes"
            );
            if let Some(node_id) = node_id {
                assert!(context.token_info(node_id).is_none());
            }
            // This is a no-hit contract, not proof of Classic memoization support.
            assert_eq!(0, context.memoized_failure_hits(), "{id}");
            println!(
                "\nMEMO\t{id}\t{}\t{summary}",
                if memo == Memoization::Off {
                    "OFF"
                } else {
                    "ON"
                }
            );
            summaries.push(summary);
        }
        assert_eq!(summaries[0], summaries[1], "{id}");
        assert_eq!(diagnostics[0], diagnostics[1], "{id}");
    }
}

fn inspect_keys(context: &mut ParseContext<'_>) -> ParseResult {
    let classic = *context.state::<bool>("classic").unwrap();
    for (reset, expected_hits) in [(false, 0), (true, 0), (false, 1)] {
        context.set_reset_matched_with_consumed(reset);
        let child = Expr::Rule(1);
        let result = if classic {
            context.parse_with_token_mode(&child, TokenKind::MatchOnly, false)
        } else {
            context.parse(&child)
        };
        assert!(result.is_err());
        assert_eq!(
            if classic { 0 } else { expected_hits },
            context.memoized_failure_hits()
        );
    }
    Ok(ParseMatch::empty(Span { start: 0, end: 0 }))
}

#[test]
fn normal_reset_keys_are_distinct_and_classic_gate_is_observed_within_one_grammar_session() {
    let grammar = share_grammar(vec![
        Rule {
            name: "opaque_driver",
            expression: Expr::Custom(inspect_keys),
        },
        Rule {
            name: "pure_child",
            expression: Expr::Literal("x"),
        },
    ]);
    for classic in [false, true] {
        let mut context = ParseContext::with_options(
            "?",
            ParseOptions::with_memoization(Memoization::SafeFailures)
                .with_diagnostics(Diagnostics::Detailed),
        );
        context.set_state("classic", classic);
        context.parse_shared_grammar(&grammar, 0, false).unwrap();
        assert_eq!(if classic { 0 } else { 1 }, context.memoized_failure_hits());
    }
}
