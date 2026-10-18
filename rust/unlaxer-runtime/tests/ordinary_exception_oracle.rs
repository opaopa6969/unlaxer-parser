use std::cell::Cell;
use unlaxer_runtime::{
    token::{NodeId, TokenKind},
    Diagnostics, Expr, Memoization, ParseContext, ParseMatch, ParseOptions, ParseResult, Span,
};
thread_local! {static FRESH:Cell<Option<NodeId>>=const{Cell::new(None)};}
fn mutation(c: &mut ParseContext<'_>) -> ParseResult {
    c.set_state("value", 99_u32);
    let old = *c.state::<NodeId>("old").unwrap();
    assert!(c.put_token_extra(old, "label", "during"));
    let matched = c.parse_with_token_mode(
        &Expr::literal("😀").capture_owned("word"),
        TokenKind::Consumed,
        false,
    )?;
    FRESH.with(|slot| slot.set(c.token_node_id(matched.nodes[0])));
    Err(c.error("ordinary callback"))
}
fn swallow(c: &mut ParseContext<'_>) -> ParseResult {
    assert!(c.parse(&Expr::Custom(mutation)).is_err());
    Ok(ParseMatch::empty(Span {
        start: c.position(),
        end: c.position(),
    }))
}
fn form(name: &str) -> Expr {
    let callback = Expr::Custom(mutation);
    match name {
        "chain" => Expr::sequence([callback]),
        "optional" => callback.optional_java(),
        "repeat" => callback.repeat_java(0, None),
        "choice" => Expr::choice([callback, Expr::literal("😀")]),
        "not" => callback.not_ahead(),
        "match" => callback.match_only(),
        "owned" => callback.capture_owned("outer"),
        "scope" => callback.capture_scope(),
        "swallow" => Expr::Custom(swallow),
        _ => panic!("unknown fixture {name}"),
    }
}
#[test]
fn shared_result_control_flow_restores_registered_effects_and_allows_retry() {
    let mut count = 0;
    for line in include_str!("../../../conformance/ordinary-exceptions/state.tsv")
        .lines()
        .filter(|s| !s.is_empty() && !s.starts_with('#'))
    {
        let row: Vec<_> = line.split('\t').collect();
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                for manual in [false, true] {
                    let mut c = ParseContext::with_options(
                        row[9],
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    );
                    c.set_state("value", 7_u32);
                    let old = c
                        .parse_with_token_mode(
                            &Expr::literal("p").capture_owned("word"),
                            TokenKind::Consumed,
                            false,
                        )
                        .unwrap();
                    let old = c.token_node_id(old.nodes[0]).unwrap();
                    c.set_state("old", old);
                    assert!(c.put_token_extra(old, "label", "before"));
                    let operation = |c: &mut ParseContext<'_>| -> ParseResult {
                        FRESH.with(|slot| slot.set(None));
                        let result =
                            c.parse_with_token_mode(&form(row[0]), TokenKind::Consumed, false);
                        assert_eq!(result.is_ok(), row[2] == "ok", "{}", row[0]);
                        let position: usize = row[4].parse().unwrap();
                        assert_eq!(
                            (c.position(), c.matched_position()),
                            (position, position),
                            "{}",
                            row[0]
                        );
                        assert_eq!(c.state::<u32>("value"), Some(&7));
                        assert_eq!(c.token_info(old).unwrap().extra("label"), Some("before"));
                        assert_eq!(c.captured_text("word"), Some("p"));
                        assert!(c.captured_text("outer").is_none());
                        let fresh = FRESH.with(Cell::get).unwrap();
                        assert!(c.token_info(fresh).is_none());
                        let retry = if row[6] == "😀" {
                            Expr::literal("😀")
                        } else {
                            Expr::literal(";")
                        };
                        c.parse_with_token_mode(&retry, TokenKind::Consumed, false)
                            .unwrap();
                        assert_eq!(c.position(), row[8].parse::<usize>().unwrap());
                        if manual {
                            Err(c.error("explicit outer rollback"))
                        } else {
                            Ok(ParseMatch::empty(Span {
                                start: 1,
                                end: c.position(),
                            }))
                        }
                    };
                    if manual {
                        assert!(c.transaction(operation).is_err());
                        assert_eq!(c.position(), 1);
                    } else {
                        operation(&mut c).unwrap();
                    }
                    assert_eq!(c.state::<u32>("value"), Some(&7));
                    assert_eq!(c.captured_text("word"), Some("p"));
                    count += 1;
                }
            }
        }
    }
    assert_eq!(count, 108);
}
#[test]
fn panic_unwinding_discards_the_context_instead_of_claiming_reuse() {
    for wrapper in ["transaction", "parse", "scope", "capture-scope"] {
        let discarded = std::rc::Rc::new(Cell::new(false));
        let witness = discarded.clone();
        struct Witness(std::rc::Rc<Cell<bool>>);
        impl Drop for Witness {
            fn drop(&mut self) {
                self.0.set(true);
            }
        }
        let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(move || {
            let _witness = Witness(witness);
            let mut c = ParseContext::new("😀");
            c.set_state("value", 7_u32);
            fn panics(c: &mut ParseContext<'_>) -> ParseResult {
                c.set_state("value", 99_u32);
                c.advance(1);
                panic!("callback panic");
            }
            let _ = match wrapper {
                "transaction" => c.transaction(panics),
                "parse" => c.parse(&Expr::Custom(panics)),
                "scope" => c.with_scope(panics),
                "capture-scope" => c.with_capture_scope(panics),
                _ => unreachable!(),
            };
        }));
        assert!(outcome.is_err());
        assert!(discarded.get());
        // c was owned inside the unwinding closure and has been dropped. Only a new context is used.
        ParseContext::new("😀").parse(&Expr::literal("😀")).unwrap();
    }
}
