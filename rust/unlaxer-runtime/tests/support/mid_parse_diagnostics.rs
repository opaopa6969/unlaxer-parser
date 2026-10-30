use std::cell::RefCell;
use unlaxer_runtime::{token::TokenKind, *};

const INPUTS: &str =
    include_str!("../../../../conformance/chain-reset-after-failure/mid-parse-inputs.tsv");

// Observer bookkeeping stays outside ParseContext: it must not change the state epoch/memo keys.
#[derive(Default)]
struct Observation {
    read: bool,
    classic: bool,
    initial: bool,
    middle: Vec<usize>,
    result: String,
}
thread_local! {
    static OBSERVATION: RefCell<Observation> = RefCell::new(Observation::default());
}

fn observer(context: &mut ParseContext<'_>) -> ParseResult {
    OBSERVATION.with(|observation| {
        let mut observation = observation.borrow_mut();
        if observation.read {
            let cp = context.failure().offset;
            assert_eq!(cp, context.failure().offset);
            observation.middle.push(cp);
        }
    });
    Ok(ParseMatch::empty(Span {
        start: context.position(),
        end: context.position(),
    }))
}

fn state(context: &ParseContext<'_>, success: bool) -> String {
    format!(
        "{}\t{}\t{}\t{}\t{}\t{}\t{}",
        success,
        context.position(),
        context.matched_position(),
        context.reset_matched_with_consumed(),
        context.failure().offset,
        context.memoized_failure_hits(),
        context.memoized_success_hits()
    )
}

fn driver(context: &mut ParseContext<'_>) -> ParseResult {
    let (classic, initial) = OBSERVATION.with(|o| (o.borrow().classic, o.borrow().initial));
    context.set_reset_matched_with_consumed(initial);
    let root = Expr::Rule(0);
    let result = if classic {
        context.parse_with_token_mode(&root, TokenKind::Consumed, false)
    } else {
        root.parse(context)
    };
    let mut observed = state(context, result.is_ok());
    let follower = Expr::NonOrdered(vec![Expr::JavaLookahead {
        pattern: "b",
        positive: true,
    }]);
    let result = follower.parse(context);
    observed += &format!("\t{}", state(context, result.is_ok()));
    for _ in 0..3 {
        assert!(Expr::Rule(2).parse(context).is_err());
        observed += &format!("\t{}", state(context, false));
    }
    OBSERVATION.with(|o| o.borrow_mut().result = observed);
    Ok(ParseMatch::empty(Span {
        start: context.position(),
        end: context.position(),
    }))
}

fn grammar(head: &'static str, shape: &str) -> Vec<Rule> {
    let literal = Expr::Literal(head);
    let longer = match head {
        "a" => "az",
        "a😀" => "a😀z",
        _ => panic!("unknown head"),
    };
    let head = match shape {
        "frame" => Expr::NonOrdered(vec![literal]),
        "ahead" => Expr::Sequence(vec![
            Expr::MatchOnly(Box::new(Expr::Literal(longer))),
            literal,
        ]),
        "failure-before" => Expr::Choice(vec![Expr::Literal("!"), literal]),
        "failure-at-head" => {
            Expr::Sequence(vec![literal, Expr::Optional(Box::new(Expr::Literal("!")))])
        }
        "failure-ahead" => Expr::Choice(vec![
            Expr::Sequence(vec![Expr::Literal(longer), Expr::Literal("!")]),
            literal,
        ]),
        "plain" | "suffix-failure" | "eof" => literal,
        _ => panic!("unknown shape"),
    };
    let mut root = vec![head, Expr::Rule(1)];
    if shape == "suffix-failure" {
        root.push(Expr::Literal("!"));
    }
    vec![
        Rule {
            name: "Root",
            expression: Expr::Sequence(root),
        },
        Rule {
            name: "Inner",
            expression: Expr::Sequence(vec![Expr::Custom(observer), Expr::Literal("z")]),
        },
        Rule {
            name: "Failed",
            expression: Expr::Sequence(vec![Expr::Literal("!"), Expr::Literal("?")]),
        },
        Rule {
            name: "Driver",
            expression: Expr::Custom(driver),
        },
    ]
}

pub fn observations() -> Vec<String> {
    let mut rows = vec![];
    for line in INPUTS
        .lines()
        .filter(|s| !s.is_empty() && !s.starts_with('#'))
    {
        let c: Vec<_> = line.split('\t').collect();
        let grammar = share_grammar(grammar(c[1], c[3]));
        for classic in [false, true] {
            for initial in [false, true] {
                for (memo_id, memo) in [Memoization::Off, Memoization::SafeFailures]
                    .into_iter()
                    .enumerate()
                {
                    for (diag_id, diagnostics) in [
                        Diagnostics::Auto,
                        Diagnostics::Detailed,
                        Diagnostics::DetailedOnFailure,
                    ]
                    .into_iter()
                    .enumerate()
                    {
                        let mut control = None;
                        for read in [false, true] {
                            OBSERVATION.with(|o| {
                                *o.borrow_mut() = Observation {
                                    read,
                                    classic,
                                    initial,
                                    ..Observation::default()
                                };
                            });
                            let mut context = ParseContext::with_options(
                                c[2],
                                ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                            );
                            context.parse_shared_grammar(&grammar, 3, false).unwrap();
                            OBSERVATION.with(|o| {
                                let o = o.borrow();
                                assert_eq!(o.middle.len(), usize::from(read));
                                if read {
                                    assert_eq!(
                                        control.as_ref().unwrap(),
                                        &o.result,
                                        "read transparency: {line}"
                                    );
                                    rows.push(format!(
                                        "{}\t{}\t{}\t{}\t{}\t{}\t{}",
                                        c[0],
                                        classic,
                                        initial,
                                        memo_id,
                                        diag_id,
                                        o.middle[0],
                                        o.result
                                    ));
                                } else {
                                    control = Some(o.result.clone());
                                }
                            });
                        }
                    }
                }
            }
        }
    }
    rows
}
