#![allow(dead_code, unused_imports)]
mod generated;
use std::cell::Cell;
use unlaxer_runtime::token::{NodeId, TokenKind};
use unlaxer_runtime::*;
thread_local! {static FRESH:Cell<Option<NodeId>>=const{Cell::new(None)};}
fn callback(c: &mut ParseContext<'_>, word: &'static str) -> ParseResult {
    c.declare_complete_token_trace();
    let at = c.position();
    let fails = c.state::<usize>("fail") == Some(&at);
    let expression = if fails {
        Expr::literal(word).capture_owned("word")
    } else {
        Expr::literal(word)
    };
    let matched = c.parse_with_token_mode(&expression, TokenKind::Consumed, false)?;
    if fails {
        c.set_state("registered", 99_u32);
        let old = *c.state::<NodeId>("old").unwrap();
        assert!(c.put_token_extra(old, "label", "during"));
        let fresh = c.token_node_id(matched.nodes[0]).unwrap();
        FRESH.with(|v| v.set(Some(fresh)));
        assert!(c.put_related_token(old, "temporary", fresh));
        return Err(c.error("generated callback"));
    }
    Ok(matched)
}
pub fn item(c: &mut ParseContext<'_>) -> ParseResult {
    callback(c, "😀")
}
pub fn separator(c: &mut ParseContext<'_>) -> ParseResult {
    callback(c, ";")
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let input = &args[1];
    let end: usize = args[2].parse().unwrap();
    let mut observed = 0;
    for fail in &args[3..] {
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                for manual in [false, true] {
                    let mut c = ParseContext::with_options(
                        input,
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    );
                    c.set_state("registered", 7_u32);
                    c.set_state("fail", fail.parse::<usize>().unwrap());
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
                    let retained = c.tree(old.index()).unwrap();
                    let operation = |c: &mut ParseContext<'_>| -> ParseResult {
                        FRESH.with(|v| v.set(None));
                        assert!(c
                            .parse_shared_grammar(generated::parser::grammar(), 0, false)
                            .is_err());
                        assert_eq!((c.position(), c.matched_position()), (1, 1));
                        assert_eq!(c.state::<u32>("registered"), Some(&7));
                        assert_eq!(c.captured_text("word"), Some("p"));
                        assert_eq!(c.token_info(old).unwrap().extra("label"), Some("before"));
                        let fresh = FRESH.with(Cell::get).unwrap();
                        assert!(c.token_info(fresh).is_none());
                        assert!(c.token_info(old).unwrap().related("temporary").is_none());
                        assert_eq!(
                            c.tree(old.index()).unwrap().nodes.len(),
                            retained.nodes.len()
                        );
                        c.set_state("fail", usize::MAX);
                        let parsed = c
                            .parse_shared_grammar(generated::parser::grammar(), 0, false)
                            .unwrap();
                        assert_eq!(c.position(), end);
                        assert_eq!(c.captured_text("word"), Some("p"));
                        let root = parsed.root_node().unwrap();
                        let tree = c.tree(root).unwrap();
                        assert_eq!(tree.nodes[root].span, Span { start: 1, end });
                        let ast = generated::mapper::map(&tree).unwrap();
                        let generated::ast::Ast::Value { span, items } = ast;
                        assert_eq!(span, Span { start: __ROOT_START__, end: __ROOT_END__ });
                        assert_eq!(items, __EXPECTED_ITEMS__);
                        assert_eq!(
                            retained.token_info(old).unwrap().extra("label"),
                            Some("before")
                        );
                        if manual {
                            Err(c.error("manual outer rollback"))
                        } else {
                            Ok(parsed)
                        }
                    };
                    if manual {
                        assert!(c.transaction(operation).is_err());
                        assert_eq!(c.position(), 1);
                    } else {
                        operation(&mut c).unwrap();
                    }
                    observed += 1;
                }
            }
        }
    }
    println!("{observed}");
}
