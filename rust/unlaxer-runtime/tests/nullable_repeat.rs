use std::cell::Cell;
use unlaxer_runtime::{
    token::TokenKind, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult,
};
thread_local! { static CALLS: Cell<usize> = const { Cell::new(0) }; }
fn zero() -> Expr {
    Expr::Literal("unused").repeat_java(0, Some(0))
}
fn effect(c: &mut ParseContext<'_>) -> ParseResult {
    CALLS.with(|v| v.set(v.get() + 1));
    c.set_state("value", c.state::<u32>("value").unwrap() + 1);
    c.parse(&zero().capture_owned("effect"))
}
fn child(c: &mut ParseContext<'_>) -> ParseResult {
    let tail = if c.state::<String>("child").unwrap() == "empty" {
        zero()
    } else {
        Expr::Error("child failure")
    };
    c.parse(&Expr::sequence([Expr::Custom(effect), tail]))
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
#[test]
fn independent_nullable_success_and_state_oracle_matches_all_classic_modes() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for mode in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../conformance/nullable-repeat/state.tsv")
                .lines()
                .filter(|r| !r.starts_with('#'))
            {
                let e: Vec<_> = row.split('\t').collect();
                let mut c = ParseContext::with_options(
                    "p😀x",
                    ParseOptions::with_memoization(memo).with_diagnostics(mode),
                );
                c.set_state("value", 7u32);
                c.set_state("child", e[1].to_owned());
                c.parse_with_token_mode(
                    &Expr::Literal("p").capture_owned("before"),
                    TokenKind::Consumed,
                    false,
                )
                .unwrap();
                c.parse_with_token_mode(&Expr::Literal("😀"), TokenKind::MatchOnly, false)
                    .unwrap();
                c.set_reset_matched_with_consumed(e[3] == "true");
                CALLS.with(|v| v.set(0));
                let rollback: Result<(), _> = c.transaction(|c| {
                    let result = c.parse_with_token_mode(
                        &Expr::Custom(child).repeat_java(e[2].parse().unwrap(), None),
                        kind(e[4]),
                        e[5] == "true",
                    );
                    assert_eq!(result.is_ok(), e[6] == "true", "{}", e[0]);
                    assert_eq!(c.position().to_string(), e[7], "{}", e[0]);
                    assert_eq!(c.matched_position().to_string(), e[8], "{}", e[0]);
                    assert_eq!(
                        c.state::<u32>("value").unwrap().to_string(),
                        e[9],
                        "{}",
                        e[0]
                    );
                    assert_eq!(
                        c.captured_text("effect").is_some(),
                        e[10] == "true",
                        "{}",
                        e[0]
                    );
                    assert_eq!(c.reset_matched_with_consumed(), e[11] == "true", "{}", e[0]);
                    assert_eq!(CALLS.with(Cell::get).to_string(), e[12], "{}", e[0]);
                    assert_eq!(c.captured_text("before"), Some("p"));
                    if let Ok(parsed) = result {
                        let id = c.token_node_id(parsed.nodes[0]).unwrap();
                        let info = c.token_info(id).unwrap();
                        assert_eq!(info.kind, kind(e[4]));
                        assert_eq!(c.token_text(id), Some(""));
                        assert_eq!(
                            info.original_tokens().unwrap().len(),
                            usize::from(e[1] == "empty"),
                            "{}",
                            e[0]
                        );
                    }
                    Err(c.error("force parent rollback"))
                });
                assert!(rollback.is_err());
                assert_eq!(c.state::<u32>("value"), Some(&7));
                assert!(c.captured_text("effect").is_none());
                assert_eq!(c.captured_text("before"), Some("p"));
                assert_eq!((c.position(), c.matched_position()), (1, 2));
            }
        }
    }
}
#[test]
fn pure_peg_nullable_repetition_still_rejects_zero_progress() {
    let mut c = ParseContext::new("");
    assert!(c
        .parse(&Expr::Literal("x").optional().repeat(0, None))
        .is_err());
    assert_eq!(c.position(), 0);
}

fn recursive(c: &mut ParseContext<'_>) -> ParseResult {
    c.parse(&Expr::Custom(recursive))
}
fn resource_child(c: &mut ParseContext<'_>) -> ParseResult {
    c.parse(&Expr::sequence([
        Expr::Custom(effect),
        Expr::Custom(recursive),
    ]))
}
#[test]
fn nullable_outer_repeat_cannot_convert_resource_failure_into_zero_matches() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for mode in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let mut c = ParseContext::with_options(
                "p😀",
                ParseOptions::with_memoization(memo).with_diagnostics(mode),
            );
            c.set_state("value", 7u32);
            c.parse_with_token_mode(
                &Expr::Literal("p").capture_owned("before"),
                TokenKind::Consumed,
                false,
            )
            .unwrap();
            c.parse_with_token_mode(&Expr::Literal("😀"), TokenKind::MatchOnly, false)
                .unwrap();
            let error = c
                .parse(
                    &Expr::Custom(resource_child)
                        .repeat_java(0, None)
                        .capture_owned("outer"),
                )
                .unwrap_err();
            assert!(error.expected.iter().any(|item| item.contains("below")));
            assert_eq!(c.parse(&zero()).unwrap_err(), error);
            assert_eq!(c.state::<u32>("value"), Some(&7));
            assert!(c.captured_text("effect").is_none());
            assert!(c.captured_text("outer").is_none());
            assert_eq!(c.captured_text("before"), Some("p"));
            assert_eq!((c.position(), c.matched_position()), (1, 2));
        }
    }
}
