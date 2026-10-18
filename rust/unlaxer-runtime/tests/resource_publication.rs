use std::cell::{Cell, RefCell};
use unlaxer_runtime::{
    token::{NodeId, TokenKind},
    Diagnostics, Expr, Memoization, ParseContext, ParseError, ParseMatch, ParseOptions,
    ParseResult, Span,
};
thread_local! { static FRESH:Cell<Option<NodeId>>=const{Cell::new(None)};static ORIGINAL:RefCell<Option<ParseError>>=const{RefCell::new(None)}; }
fn effect(c: &mut ParseContext<'_>) -> ParseResult {
    c.set_state("value", 99u32);
    let old = *c.state::<NodeId>("old").unwrap();
    assert!(c.put_token_extra(old, "label", "during"));
    let parsed = c.parse(&Expr::Literal("a").capture_owned("word"))?;
    let fresh = c.token_node_id(parsed.nodes[0]).unwrap();
    FRESH.with(|v| v.set(Some(fresh)));
    assert!(c.put_related_token(old, "new", fresh));
    Ok(parsed)
}
fn recursive(c: &mut ParseContext<'_>) -> ParseResult {
    c.parse(&Expr::Custom(recursive))
}
fn wrapped(name: &str) -> Expr {
    let child = Expr::Custom(recursive);
    match name {
        "chain" => child,
        "input-boundary" => {
            Expr::InputCaptureBoundary(unlaxer_runtime::capture_tree::Kind::Value, Box::new(child))
        }
        "match-only" => child.match_only(),
        "peg" => child.ahead(),
        "capture" => child.capture_owned("inside"),
        "scope" => child.capture_scope(),
        "repeat" => child.repeat_java(0, None),
        _ => panic!("{name}"),
    }
}
fn options() -> impl Iterator<Item = ParseOptions> {
    [Memoization::Off, Memoization::SafeFailures]
        .into_iter()
        .flat_map(|memo| {
            [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ]
            .into_iter()
            .map(move |mode| ParseOptions::with_memoization(memo).with_diagnostics(mode))
        })
}
#[test]
fn shared_resource_abort_restores_public_entry_and_outer_manual_owner() {
    for options in options() {
        for row in include_str!("../../../conformance/resource-abort/cases.tsv")
            .lines()
            .filter(|r| !r.starts_with('#'))
        {
            let e: Vec<_> = row.split('\t').collect();
            let mut c = ParseContext::with_options("paaaa", options);
            c.set_state("value", 7u32);
            c.set_reset_matched_with_consumed(false);
            let prior = c
                .parse_with_token_mode(
                    &Expr::Literal("p").capture_owned("word"),
                    TokenKind::Consumed,
                    false,
                )
                .unwrap();
            let old = c.token_node_id(prior.nodes[0]).unwrap();
            c.parse_with_token_mode(&Expr::Literal("a"), TokenKind::MatchOnly, false)
                .unwrap();
            assert!(c.put_token_extra(old, "label", "before"));
            c.set_state("old", old);
            FRESH.with(|v| v.set(None));
            let inside = |c: &mut ParseContext<'_>| -> Result<(), ParseError> {
                if e[1] == "true" {
                    c.parse_with_token_mode(
                        &Expr::Literal("a").capture_owned("word"),
                        TokenKind::Consumed,
                        false,
                    )
                    .unwrap();
                    c.set_state("value", 11u32);
                    assert!(c.put_token_extra(old, "label", "outer"));
                }
                let expression = Expr::sequence([Expr::Custom(effect), wrapped(e[0])]);
                let error = c
                    .parse_with_token_mode(&expression, TokenKind::Consumed, false)
                    .unwrap_err();
                assert!(error.expected.iter().any(|v| v.contains("below")), "{row}");
                assert_eq!(c.position().to_string(), e[2], "{row}");
                assert_eq!(c.matched_position().to_string(), e[3], "{row}");
                assert_eq!(
                    c.tree(prior.nodes[0]).unwrap().nodes.len().to_string(),
                    e[5],
                    "{row}"
                );
                assert_eq!(c.state::<u32>("value").unwrap().to_string(), e[6], "{row}");
                assert_eq!(c.captured_text("word"), Some(e[7]), "{row}");
                assert!(c.captured_text("inside").is_none());
                assert_eq!(
                    c.token_info(old).unwrap().extra("label"),
                    Some(e[8]),
                    "{row}"
                );
                assert!(c.token_info(old).unwrap().related("new").is_none());
                assert!(c.token_info(FRESH.with(Cell::get).unwrap()).is_none());
                assert!(!c.reset_matched_with_consumed());
                assert_eq!(
                    c.parse_with_token_mode(&expression, TokenKind::Consumed, false)
                        .unwrap_err(),
                    error
                );
                assert_eq!(c.transaction(|_| Ok(())).unwrap_err(), error);
                Err(error)
            };
            let result = if e[1] == "true" {
                c.transaction(inside)
            } else {
                inside(&mut c)
            };
            assert!(result.is_err());
            assert_eq!(c.state::<u32>("value"), Some(&7));
            assert_eq!(c.captured_text("word"), Some("p"));
            assert_eq!(c.token_info(old).unwrap().extra("label"), Some("before"));
            assert_eq!((c.position(), c.matched_position()), (1, 2));
        }
    }
}
fn swallowed(c: &mut ParseContext<'_>) -> ParseResult {
    let error = c.parse(&Expr::Custom(recursive)).unwrap_err();
    ORIGINAL.with(|v| *v.borrow_mut() = Some(error));
    c.set_state("value", 99u32);
    assert!(c.advance(1));
    Ok(ParseMatch::empty(Span { start: 0, end: 0 }))
}
#[test]
fn swallowed_controlled_failure_cannot_publish_callback_success() {
    for options in options() {
        let mut c = ParseContext::with_options("a", options);
        c.set_state("value", 7u32);
        let error = c
            .parse_with_token_mode(
                &Expr::sequence([Expr::Custom(swallowed)]),
                TokenKind::Consumed,
                false,
            )
            .unwrap_err();
        assert_eq!(Some(error.clone()), ORIGINAL.with(|v| v.borrow().clone()));
        assert_eq!(c.parse(&Expr::Literal("a")).unwrap_err(), error);
        assert_eq!(c.state::<u32>("value"), Some(&7));
        assert_eq!((c.position(), c.matched_position()), (0, 0));
    }
}
