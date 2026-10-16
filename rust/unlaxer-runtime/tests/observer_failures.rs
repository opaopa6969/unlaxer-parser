//! Java observers throw; Rust's corresponding custom hooks return ParseError.
//! Both must restore owned values/publication. Panic remains a separate, discard-only boundary.
use std::cell::{Cell, RefCell};
use unlaxer_runtime::{
    token::{NodeId, TokenKind, TokenSource},
    Diagnostics, Expr, Memoization, ParseContext, ParseError, ParseOptions, ParseResult, Span,
};
thread_local! {
    static EVENTS: RefCell<Vec<&'static str>> = const { RefCell::new(Vec::new()) };
    static FRESH: Cell<Option<NodeId>> = const { Cell::new(None) };
}
fn decode(hex: &str) -> String {
    String::from_utf8(
        hex.as_bytes()
            .chunks_exact(2)
            .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
            .collect(),
    )
    .unwrap()
}
fn event(c: &mut ParseContext<'_>, event: &'static str) -> Result<(), ParseError> {
    EVENTS.with(|events| events.borrow_mut().push(event));
    c.set_state("value", 99u32);
    c.scopes_mut().enter();
    c.scopes_mut().declare("inside", 1);
    c.scopes_mut().add_reference("inside", 1, 1);
    c.scopes_mut()
        .add_diagnostic("speculative", 1, 1, unlaxer_runtime::Severity::Error);
    let existing = *c.state::<NodeId>("existing").unwrap();
    assert!(c.put_token_extra(existing, "label", "during"));
    let fresh = c
        .add_token_node(
            999,
            TokenKind::Consumed,
            TokenSource::Input(Span {
                start: c.position(),
                end: c.position(),
            }),
        )
        .unwrap();
    FRESH.with(|slot| slot.set(Some(fresh)));
    assert!(c.put_related_token(existing, "new", fresh));
    if c.state::<String>("failure_event").unwrap() == event {
        Err(ParseError {
            offset: c.position(),
            expected: vec![format!("observer:{event}")],
        })
    } else {
        Ok(())
    }
}
fn hooked_leaf(c: &mut ParseContext<'_>) -> ParseResult {
    c.transaction(|c| {
        event(c, "start")?;
        let result = c.parse(&Expr::Literal("a"))?;
        event(c, "end")?;
        Ok(result)
    })
}
fn hooked_match(c: &mut ParseContext<'_>) -> ParseResult {
    c.transaction(|c| {
        event(c, "start")?;
        event(c, "begin")?;
        let failing = c.state::<String>("scenario").unwrap() == "fail";
        let result = c.parse(&Expr::Literal(if failing { "b" } else { "a" }).match_only());
        if c.state::<String>("failure_event").unwrap() == "precommit" {
            event(c, "precommit")?;
        }
        event(c, if result.is_ok() { "commit" } else { "rollback" })?;
        if c.state::<String>("failure_event").unwrap() == "postcommit" {
            event(c, "postcommit")?;
        }
        event(c, "end")?;
        result
    })
}
#[test]
fn shared_controlled_hook_failures_restore_the_published_boundary() {
    let rows: Vec<_> = include_str!("../../../conformance/observer-failures/cases.tsv")
        .lines()
        .filter(|row| !row.starts_with('#') && !row.is_empty())
        .collect();
    assert_eq!(20, rows.len());
    for memo in [Memoization::Off, Memoization::SafeFailures] {
        for diagnostics in [
            Diagnostics::Auto,
            Diagnostics::Detailed,
            Diagnostics::DetailedOnFailure,
        ] {
            for row in &rows {
                let data: Vec<_> = row.split('\t').collect();
                let input = decode(data[3]);
                let mut c = ParseContext::with_options(
                    &input,
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                c.set_reset_matched_with_consumed(false);
                let prior = c
                    .parse_with_token_mode(
                        &Expr::Literal(if data[4] == "7a" { "z" } else { "😀\r\n" }),
                        TokenKind::Consumed,
                        false,
                    )
                    .unwrap();
                let existing = c.token_node_id(prior.nodes[0]).unwrap();
                if !data[5].is_empty() {
                    c.parse_with_token_mode(
                        &Expr::Literal(if data[5] == "78" { "x" } else { "a" }),
                        TokenKind::MatchOnly,
                        false,
                    )
                    .unwrap();
                }
                c.set_state("value", 7u32);
                c.set_state("existing", existing);
                c.set_state("scenario", data[1].to_owned());
                c.set_state("failure_event", data[2].to_owned());
                c.put_token_extra(existing, "label", "before");
                c.scopes_mut().enter();
                c.scopes_mut().declare("baseline", 0);
                c.scopes_mut().add_reference("baseline", 0, 1);
                EVENTS.with(|events| events.borrow_mut().clear());
                FRESH.with(|fresh| fresh.set(None));
                let root = match data[1] {
                    "leaf" => Expr::Custom(hooked_leaf),
                    "nested" => Expr::sequence([Expr::Custom(hooked_match), Expr::Literal("!")]),
                    "child" => Expr::Custom(hooked_leaf).match_only(),
                    "peg" => Expr::Custom(hooked_leaf).ahead(),
                    _ => Expr::Custom(hooked_match),
                };
                let result = c.parse_with_token_mode(&root, TokenKind::Consumed, false);
                assert!(result.is_err(), "{}", data[0]);
                EVENTS.with(|events| assert_eq!(data[6], events.borrow().join(","), "{}", data[0]));
                assert_eq!(
                    data[7].parse::<usize>().unwrap(),
                    c.position(),
                    "{}",
                    data[0]
                );
                assert_eq!(
                    data[8].parse::<usize>().unwrap(),
                    c.matched_position(),
                    "{}",
                    data[0]
                );
                assert_eq!(
                    Some(&data[10].parse::<u32>().unwrap()),
                    c.state::<u32>("value"),
                    "{}",
                    data[0]
                );
                assert_eq!(
                    Some(data[11]),
                    c.token_info(existing).unwrap().extra("label"),
                    "{}",
                    data[0]
                );
                assert!(c.token_info(existing).unwrap().related("new").is_none());
                FRESH.with(|fresh| {
                    assert_eq!(
                        data[12].parse::<bool>().unwrap(),
                        c.token_info(fresh.get().unwrap()).is_some()
                    )
                });
                assert!(
                    c.token_node_id(data[9].parse::<usize>().unwrap()).is_none(),
                    "{}",
                    data[0]
                );
                assert_eq!(
                    data[13].parse::<usize>().unwrap(),
                    c.scopes().current_scope_depth()
                );
                assert_eq!(
                    data[14].parse::<usize>().unwrap(),
                    c.scopes().all_declarations().len()
                );
                assert_eq!(
                    data[15].parse::<usize>().unwrap(),
                    c.scopes().all_references().len()
                );
                assert_eq!(
                    data[16].parse::<usize>().unwrap(),
                    c.scopes().diagnostics().len()
                );
                // Controlled ParseError is recoverable, unlike Java's fatal observer exception.
                c.parse_with_token_mode(
                    &Expr::Literal(if data[5] == "78" { "x" } else { "a" }),
                    TokenKind::Consumed,
                    false,
                )
                .unwrap();
                assert_eq!(data[7].parse::<usize>().unwrap() + 1, c.position());
            }
        }
    }
}
#[test]
fn a_panicking_hook_requires_discarding_its_context() {
    let input = "za";
    let mut context = ParseContext::new(input);
    let panic = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _: Result<(), ParseError> = context.transaction(|context| {
            context.advance(1);
            panic!("observer panic");
        });
    }));
    assert!(panic.is_err());
    // Do not inspect or reuse a context after panic: transaction's Result rollback
    // contract does not extend to panic unwinding. A new context is retryable.
    drop(context);
    assert!(ParseContext::new(input).parse(&Expr::Literal("z")).is_ok());
}

#[test]
fn resource_failure_from_a_hook_is_sticky_even_if_the_hook_swallows_it() {
    let mut context = ParseContext::new("a");
    context.set_state("value", 7u32);
    let result: Result<(), ParseError> = context.transaction(|context| {
        context.set_state("value", 99u32);
        let mut deep = Expr::Literal("a");
        for _ in 0..1500 {
            deep = deep.match_only();
        }
        let _ignored = context.parse(&deep);
        Ok(())
    });
    let original = result.unwrap_err();
    assert_eq!(Some(&7u32), context.state::<u32>("value"));
    assert_eq!(0, context.position());
    assert_eq!(0, context.matched_position());
    assert_eq!(original, context.parse(&Expr::Literal("a")).unwrap_err());
}
