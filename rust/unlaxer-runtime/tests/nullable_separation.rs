use std::cell::Cell;
use unlaxer_runtime::{
    token::TokenKind, Diagnostics, Expr, Memoization, ParseContext, ParseOptions, ParseResult,
};
thread_local! { static ITEMS:Cell<usize>=const{Cell::new(0)};static SEPARATORS:Cell<usize>=const{Cell::new(0)}; }
fn zero() -> Expr {
    Expr::Literal("unused").repeat_java(0, Some(0))
}
fn effect_item(c: &mut ParseContext<'_>) -> ParseResult {
    c.set_state("value", c.state::<u32>("value").unwrap() + 1);
    c.parse(&zero().capture_owned("word"))
}
fn effect_separator(c: &mut ParseContext<'_>) -> ParseResult {
    c.set_state("value", c.state::<u32>("value").unwrap() + 1);
    c.parse(&zero().capture_owned("sep"))
}
fn item(c: &mut ParseContext<'_>) -> ParseResult {
    let count = ITEMS.with(|v| {
        v.set(v.get() + 1);
        v.get()
    });
    let scenario = c.state::<String>("scenario").unwrap();
    let fail = scenario == "first-item-fails" || scenario == "tail-item-fails" && count > 1;
    c.parse(&Expr::sequence([
        Expr::Custom(effect_item),
        if fail {
            Expr::Error("child failure")
        } else {
            zero()
        },
    ]))
}
fn separator(c: &mut ParseContext<'_>) -> ParseResult {
    SEPARATORS.with(|v| v.set(v.get() + 1));
    let fail = c.state::<String>("scenario").unwrap() == "separator-fails";
    c.parse(&Expr::sequence([
        Expr::Custom(effect_separator),
        if fail {
            Expr::Error("child failure")
        } else {
            zero()
        },
    ]))
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
fn classic_separated(item: Expr, separator: Expr) -> Expr {
    item.separated_by_java(separator)
}
#[test]
fn independent_nullable_tail_state_and_original_cst_oracle_matches_all_modes() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for mode in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in include_str!("../../../conformance/nullable-separation/state.tsv")
                .lines()
                .filter(|r| !r.starts_with('#'))
            {
                let e: Vec<_> = row.split('\t').collect();
                let mut c = ParseContext::with_options(
                    "p😀x",
                    ParseOptions::with_memoization(memo).with_diagnostics(mode),
                );
                c.set_state("value", 7u32);
                c.set_state("scenario", e[1].to_owned());
                c.parse_with_token_mode(
                    &Expr::Literal("p").capture_owned("word"),
                    TokenKind::Consumed,
                    false,
                )
                .unwrap();
                c.parse_with_token_mode(&Expr::Literal("😀"), TokenKind::MatchOnly, false)
                    .unwrap();
                c.set_reset_matched_with_consumed(e[2] == "true");
                ITEMS.with(|v| v.set(0));
                SEPARATORS.with(|v| v.set(0));
                let rollback: Result<(), _> = c.transaction(|c| {
                    let result = c.parse_with_token_mode(
                        &classic_separated(Expr::Custom(item), Expr::Custom(separator)),
                        kind(e[3]),
                        e[4] == "true",
                    );
                    assert_eq!(result.is_ok(), e[5] == "true", "{}", e[0]);
                    assert_eq!(c.position().to_string(), e[6], "{}", e[0]);
                    assert_eq!(c.matched_position().to_string(), e[7], "{}", e[0]);
                    assert_eq!(
                        c.state::<u32>("value").unwrap().to_string(),
                        e[8],
                        "{}",
                        e[0]
                    );
                    assert_eq!(
                        c.captured_text("word"),
                        Some(if e[9] == "empty" { "" } else { "p" }),
                        "{}",
                        e[0]
                    );
                    assert_eq!(
                        c.captured_text("sep").is_some(),
                        e[10] == "true",
                        "{}",
                        e[0]
                    );
                    assert_eq!(c.reset_matched_with_consumed(), e[11] == "true", "{}", e[0]);
                    assert_eq!(ITEMS.with(Cell::get).to_string(), e[12], "{}", e[0]);
                    assert_eq!(SEPARATORS.with(Cell::get).to_string(), e[13], "{}", e[0]);
                    assert_eq!(
                        c.failure().offset,
                        if mode == Diagnostics::DetailedOnFailure {
                            0
                        } else {
                            e[15].parse().unwrap()
                        },
                        "{}",
                        e[0]
                    );
                    assert_eq!(
                        !c.failure().expected.is_empty(),
                        mode != Diagnostics::DetailedOnFailure && e[16] == "true",
                        "{}",
                        e[0]
                    );
                    if let Ok(parsed) = result {
                        let id = c.token_node_id(parsed.nodes[0]).unwrap();
                        let info = c.token_info(id).unwrap();
                        assert_eq!(info.kind, kind(e[3]));
                        assert_eq!(c.token_text(id), Some(""));
                        let children = info.original_tokens().unwrap();
                        assert_eq!(children.len(), 2, "{}", e[0]);
                        assert_eq!(
                            c.tree(parsed.nodes[0]).unwrap().nodes[parsed.nodes[0]]
                                .children
                                .len(),
                            2,
                            "{}",
                            e[0]
                        );
                        let tail = c.token_node_id(children[1]).unwrap();
                        assert_eq!(
                            c.token_info(tail)
                                .unwrap()
                                .original_tokens()
                                .unwrap()
                                .len()
                                .to_string(),
                            e[14],
                            "{}",
                            e[0]
                        );
                    }
                    Err(c.error("force parent rollback"))
                });
                assert!(rollback.is_err());
                assert_eq!(c.state::<u32>("value"), Some(&7));
                assert_eq!(c.captured_text("word"), Some("p"));
                assert!(c.captured_text("sep").is_none());
                assert_eq!((c.position(), c.matched_position()), (1, 2));
            }
        }
    }
}
#[test]
fn pure_peg_separation_keeps_its_nullable_tail_failure() {
    let mut c = ParseContext::new("");
    assert!(c.parse(&zero().separated_by(zero())).is_err());
    assert_eq!(c.position(), 0);
}

#[test]
fn enclosing_bounds_rollback_retry_and_scope_preserve_owned_bindings() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for mode in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let mut c = ParseContext::with_options(
                "p😀x",
                ParseOptions::with_memoization(memo).with_diagnostics(mode),
            );
            c.set_state("value", 7u32);
            c.set_state("scenario", "both-empty".to_owned());
            c.parse_with_token_mode(
                &Expr::Literal("p").capture_owned("word"),
                TokenKind::Consumed,
                false,
            )
            .unwrap();
            c.parse_with_token_mode(&Expr::Literal("😀"), TokenKind::MatchOnly, false)
                .unwrap();
            c.set_reset_matched_with_consumed(false);
            ITEMS.with(|v| v.set(0));
            SEPARATORS.with(|v| v.set(0));
            let list = classic_separated(Expr::Custom(item), Expr::Custom(separator));
            c.parse_with_token_mode(
                &list.clone().repeat_java(0, Some(0)),
                TokenKind::Consumed,
                false,
            )
            .unwrap();
            assert_eq!(ITEMS.with(Cell::get), 0);
            assert_eq!(SEPARATORS.with(Cell::get), 0);
            assert_eq!(c.state::<u32>("value"), Some(&7));
            assert_eq!(c.captured_text("word"), Some("p"));
            assert_eq!(c.matched_position(), 2);
            assert!(c
                .parse_with_token_mode(
                    &list.clone().repeat_java(2, Some(2)),
                    TokenKind::Consumed,
                    false
                )
                .is_err());
            assert_eq!(c.state::<u32>("value"), Some(&7));
            assert!(c.captured_text("sep").is_none());
            assert_eq!(c.captured_text("word"), Some("p"));
            assert_eq!((c.position(), c.matched_position()), (1, 2));
            let rollback: Result<(), _> = c.transaction(|c| {
                c.parse_with_token_mode(&list, TokenKind::Consumed, false)
                    .unwrap();
                assert_eq!(c.state::<u32>("value"), Some(&10));
                assert_eq!(c.captured_text("word"), Some(""));
                Err(c.error("force parent rollback"))
            });
            assert!(rollback.is_err());
            assert_eq!(c.state::<u32>("value"), Some(&7));
            assert_eq!(c.captured_text("word"), Some("p"));
            assert!(c.captured_text("sep").is_none());
            c.parse_with_token_mode(&list.clone().capture_scope(), TokenKind::Consumed, false)
                .unwrap();
            assert_eq!(c.state::<u32>("value"), Some(&10));
            assert_eq!(c.captured_text("word"), Some("p"));
            assert!(c.captured_text("sep").is_none());
            c.parse_with_token_mode(&list, TokenKind::Consumed, false)
                .unwrap();
            assert_eq!(c.state::<u32>("value"), Some(&13));
            assert_eq!(c.captured_text("word"), Some(""));
            assert_eq!(c.captured_text("sep"), Some(""));
            assert_eq!((c.position(), c.matched_position()), (1, 2));
        }
    }
}
#[test]
fn empty_nonempty_and_unbound_owned_replay_compose_as_item_and_separator() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for mode in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            let options = ParseOptions::with_memoization(memo).with_diagnostics(mode);
            let mut c = ParseContext::with_options("", options);
            c.parse_with_token_mode(&zero().capture_owned("word"), TokenKind::Consumed, false)
                .unwrap();
            let result = c
                .parse_with_token_mode(
                    &classic_separated(Expr::Replay("word"), Expr::Replay("word")),
                    TokenKind::Consumed,
                    false,
                )
                .unwrap();
            let id = c.token_node_id(result.nodes[0]).unwrap();
            let children = c.token_info(id).unwrap().original_tokens().unwrap();
            assert_eq!(children.len(), 2);
            let tail = children[1];
            let tail_id = c.token_node_id(tail).unwrap();
            assert_eq!(
                c.token_info(tail_id)
                    .unwrap()
                    .original_tokens()
                    .unwrap()
                    .len(),
                1
            );
            assert_eq!((c.position(), c.matched_position()), (0, 0));
            assert!(c
                .parse_with_token_mode(
                    &classic_separated(Expr::Replay("missing"), zero()),
                    TokenKind::Consumed,
                    false
                )
                .is_err());
            c.parse_with_token_mode(
                &classic_separated(Expr::Replay("word"), Expr::Replay("missing")),
                TokenKind::Consumed,
                false,
            )
            .unwrap();
            let mut c = ParseContext::with_options("😀😀:😀", options);
            c.parse_with_token_mode(
                &Expr::Literal("😀").capture_owned("word"),
                TokenKind::Consumed,
                false,
            )
            .unwrap();
            c.parse_with_token_mode(
                &classic_separated(Expr::Replay("word"), Expr::Literal(":")),
                TokenKind::Consumed,
                false,
            )
            .unwrap();
            assert_eq!((c.position(), c.matched_position()), (4, 4));
            assert_eq!(c.captured_text("word"), Some("😀"));
            let mut c = ParseContext::with_options("aa", options);
            c.parse_with_token_mode(&zero().capture_owned("word"), TokenKind::Consumed, false)
                .unwrap();
            c.parse_with_token_mode(
                &classic_separated(Expr::Literal("a"), Expr::Replay("word")),
                TokenKind::Consumed,
                false,
            )
            .unwrap();
            assert_eq!((c.position(), c.matched_position()), (2, 2));
        }
    }
}
fn recursive(c: &mut ParseContext<'_>) -> ParseResult {
    c.parse(&Expr::Custom(recursive))
}
fn resource(c: &mut ParseContext<'_>) -> ParseResult {
    c.set_state("value", 99u32);
    c.parse(&zero().capture_owned("effect"))?;
    c.parse(&Expr::Custom(recursive))
}
fn resource_item(c: &mut ParseContext<'_>) -> ParseResult {
    let count = ITEMS.with(|v| {
        v.set(v.get() + 1);
        v.get()
    });
    let phase = c.state::<String>("phase").unwrap();
    if phase == "first" || phase == "tail" && count > 1 {
        resource(c)
    } else {
        c.parse(&zero())
    }
}
#[test]
fn first_item_separator_and_tail_item_cannot_swallow_sticky_resource_abort() {
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for mode in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for phase in ["first", "separator", "tail"] {
                let mut c = ParseContext::with_options(
                    "p😀",
                    ParseOptions::with_memoization(memo).with_diagnostics(mode),
                );
                c.set_state("value", 7u32);
                c.set_state("phase", phase.to_owned());
                ITEMS.with(|v| v.set(0));
                c.parse_with_token_mode(
                    &Expr::Literal("p").capture_owned("word"),
                    TokenKind::Consumed,
                    false,
                )
                .unwrap();
                c.parse_with_token_mode(&Expr::Literal("😀"), TokenKind::MatchOnly, false)
                    .unwrap();
                let list = classic_separated(
                    Expr::Custom(resource_item),
                    if phase == "separator" {
                        Expr::Custom(resource)
                    } else {
                        zero()
                    },
                );
                let error = c.parse(&list.capture_owned("outer")).unwrap_err();
                assert!(error.expected.iter().any(|item| item.contains("below")));
                assert_eq!(c.parse(&zero()).unwrap_err(), error);
                assert_eq!(c.state::<u32>("value"), Some(&7));
                assert!(c.captured_text("effect").is_none());
                assert!(c.captured_text("outer").is_none());
                assert_eq!(c.captured_text("word"), Some("p"));
                assert_eq!((c.position(), c.matched_position()), (1, 2));
            }
        }
    }
}
