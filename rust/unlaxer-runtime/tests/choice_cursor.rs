use unlaxer_runtime::{
    token::TokenKind, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, Predictor,
};

fn decode(value: &str) -> &'static str {
    let bytes = value
        .as_bytes()
        .chunks_exact(2)
        .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
        .collect::<Vec<_>>();
    Box::leak(String::from_utf8(bytes).unwrap().into_boxed_str())
}

#[test]
fn classic_prediction_uses_the_cursor_that_candidate_begin_will_publish() {
    let rows: Vec<_> = include_str!("../../../conformance/choice-cursor/predictive.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
        .collect();
    assert_eq!(rows.len(), 4);
    for row in rows {
        let columns: Vec<_> = row.split('\t').collect();
        let input = decode(columns[0]);
        let prefix = decode(columns[1]);
        let peek = decode(columns[2]);
        let literal = decode(columns[3]);
        let reset = columns[4] == "true";
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                for kind in [TokenKind::MatchOnly, TokenKind::VirtualMatchOnly] {
                    let mut context = ParseContext::with_options(
                        input,
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    );
                    context.set_reset_matched_with_consumed(false);
                    context
                        .parse_with_token_mode(&Expr::literal(prefix), TokenKind::Consumed, false)
                        .unwrap();
                    context
                        .parse_with_token_mode(&Expr::literal(peek), TokenKind::MatchOnly, false)
                        .unwrap();
                    context.set_reset_matched_with_consumed(reset);
                    let choice = Expr::predictive_choice([
                        (
                            Predictor::Literal(literal),
                            Expr::literal(literal).capture_owned("selected"),
                        ),
                        (Predictor::Any, Expr::literal("Z").optional_java()),
                    ]);
                    context.parse_with_token_mode(&choice, kind, false).unwrap();
                    assert_eq!(
                        context.position(),
                        columns[5].parse::<usize>().unwrap(),
                        "{row}/{memo:?}/{diagnostics:?}/{kind:?}"
                    );
                    assert_eq!(
                        context.matched_position(),
                        columns[6].parse::<usize>().unwrap(),
                        "{row}/{memo:?}/{diagnostics:?}/{kind:?}"
                    );
                    assert_eq!(
                        context.captured_text("selected"),
                        Some(literal),
                        "{row}/{memo:?}/{diagnostics:?}/{kind:?}"
                    );
                }
            }
        }
    }
}
