use unlaxer_runtime::{token::TokenKind, *};

const INPUTS: &str =
    include_str!("../../../../conformance/chain-reset-after-failure/nested-boundary-inputs.tsv");

pub fn report(rules: Vec<Rule>) {
    check_internal_candidates(&rules, false);
    report_rows(rules);
}

fn report_rows(mut rules: Vec<Rule>) {
    let driver = rules.len();
    rules.push(Rule {
        name: "observer",
        expression: Expr::Custom(observe),
    });
    let grammar = share_grammar(rules);
    for row in INPUTS
        .lines()
        .filter(|row| !row.starts_with('#') && !row.is_empty())
    {
        let c: Vec<_> = row.split('\t').map(str::to_owned).collect();
        let target = grammar.iter().position(|r| r.name == c[1]).unwrap();
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
                        let input = if c[3] == "-" { "" } else { &c[3] };
                        let mut context = ParseContext::with_options(
                            input,
                            ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                        );
                        context.set_state("case", c.clone());
                        context.set_state("target", (target, classic, initial));
                        context
                            .parse_shared_grammar(&grammar, driver, false)
                            .unwrap();
                        println!(
                            "{}\t{}\t{}\t{}\t{}\t{}",
                            c[0],
                            classic,
                            initial,
                            memo_id,
                            diag_id,
                            context.state::<String>("observed").unwrap()
                        );
                    }
                }
            }
        }
    }
}

fn observe(context: &mut ParseContext<'_>) -> ParseResult {
    let c = context.state::<Vec<String>>("case").unwrap().clone();
    let (target, classic, initial) = *context.state::<(usize, bool, bool)>("target").unwrap();
    if c[4] != "-" {
        let prefix = match c[4].as_str() {
            "a" => "a",
            "a😀" => "a😀",
            "1" => "1",
            "+" => "+",
            _ => panic!("prefix"),
        };
        Expr::JavaLookahead {
            pattern: prefix,
            positive: true,
        }
        .parse(context)
        .unwrap();
    }
    let rule = Expr::Rule(target);
    let expression = match c[2].as_str() {
        "direct" => rule,
        "nonordered" => Expr::NonOrdered(vec![rule]),
        "lookahead" => Expr::MatchOnly(Box::new(rule)),
        "choice" => Expr::Choice(vec![rule, Expr::Literal("fallback")]),
        _ => panic!("boundary"),
    };
    let mut before = None;
    for _ in 0..3 {
        context.set_reset_matched_with_consumed(initial);
        let result = if classic {
            context.parse_with_token_mode(&expression, TokenKind::Consumed, false)
        } else {
            expression.parse(context)
        };
        let observed = format!(
            "{}\t{}\t{}\t{}",
            result.is_ok(),
            context.position(),
            context.matched_position(),
            context.reset_matched_with_consumed()
        );
        if let Some(previous) = &before {
            assert_eq!(previous, &observed, "{}: retry", c[0]);
        }
        before = Some(observed);
        if result.is_ok() {
            break;
        }
    }
    let before = format!(
        "{}\t{}\t{}\t{}",
        before.unwrap(),
        context.failure().offset,
        context.memoized_failure_hits(),
        context.memoized_success_hits()
    );
    let ahead = |pattern| {
        Expr::NonOrdered(vec![Expr::JavaLookahead {
            pattern,
            positive: true,
        }])
    };
    let follower = ahead("b").parse(context).is_ok();
    let _ = ahead("x").parse(context);
    context.set_state(
        "observed",
        format!(
            "{}\t{}\t{}\t{}\t{}\t{}",
            before,
            follower,
            context.position(),
            context.matched_position(),
            context.reset_matched_with_consumed(),
            context.failure().offset
        ),
    );
    Ok(ParseMatch::empty(Span {
        start: context.position(),
        end: context.position(),
    }))
}

// A counterfactual generated graph, not a production rule or a new runtime contract.
// Each nested candidate remains immediately below its original Choice.
pub fn report_control(mut rules: Vec<Rule>) {
    fn add_chain(expr: &mut Expr, under_choice: bool) -> usize {
        match expr {
            Expr::TextValue(child) | Expr::ValueBoundary(child) => {
                assert!(under_choice, "a nested candidate escaped its Choice");
                let original = (**child).clone();
                **child = Expr::Sequence(vec![original]);
                1
            }
            Expr::Choice(children)
            | Expr::LongestChoice(children)
            | Expr::UniqueLongestChoice(children)
            | Expr::PredictiveChoice {
                alternatives: children,
                ..
            } => children
                .iter_mut()
                .map(|child| add_chain(child, true))
                .sum(),
            Expr::Sequence(children) => children
                .iter_mut()
                .map(|child| add_chain(child, false))
                .sum(),
            Expr::MetadataCapture(_, child) => add_chain(child, under_choice),
            _ => 0,
        }
    }
    for rule in &mut rules {
        if matches!(
            rule.name,
            "Root"
                | "Integer"
                | "Single"
                | "SingleInteger"
                | "Ahead"
                | "AheadToken"
                | "Longest"
                | "Unique"
                | "Predictive"
        ) {
            assert_eq!(add_chain(&mut rule.expression, false), 2, "{}", rule.name);
        }
    }
    // Positive control: the same transformation MUST change candidate-only reset.
    check_internal_candidates(&rules, true);
    report_rows(rules);
}

fn unscoped(mut expr: &Expr) -> &Expr {
    while let Expr::MetadataCapture(_, child) = expr {
        expr = child;
    }
    expr
}

fn check_internal_candidates(rules: &[Rule], chain_control: bool) {
    for name in ["Root", "Integer"] {
        let root = rules.iter().find(|r| r.name == name).unwrap();
        let Expr::Choice(candidates) = unscoped(&root.expression) else {
            panic!("generated Choice");
        };
        let candidate = &candidates[0];
        let child = match candidate {
            Expr::TextValue(child) if name == "Root" => child,
            Expr::ValueBoundary(child) if name == "Integer" => child,
            _ => panic!("nested candidate kind"),
        };
        if chain_control {
            assert!(
                matches!(child.as_ref(), Expr::Sequence(children) if children.len() == 1 && matches!(&children[0], Expr::Choice(_)))
            );
        } else {
            assert!(matches!(child.as_ref(), Expr::Choice(_)));
        }
        for classic in [false, true] {
            for memo in [Memoization::Off, Memoization::SafeFailures] {
                for diagnostics in [
                    Diagnostics::Auto,
                    Diagnostics::Detailed,
                    Diagnostics::DetailedOnFailure,
                ] {
                    let mut context = ParseContext::with_options(
                        "ab",
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    );
                    Expr::JavaLookahead {
                        pattern: "a",
                        positive: true,
                    }
                    .parse(&mut context)
                    .unwrap();
                    context.set_reset_matched_with_consumed(true);
                    let result = if classic {
                        context.parse_with_token_mode(candidate, TokenKind::Consumed, false)
                    } else {
                        candidate.parse(&mut context)
                    };
                    assert!(result.is_err());
                    assert_eq!(context.reset_matched_with_consumed(), !chain_control);
                    assert_eq!((context.position(), context.matched_position()), (0, 1));
                    let follower = Expr::NonOrdered(vec![Expr::JavaLookahead {
                        pattern: "b",
                        positive: true,
                    }])
                    .parse(&mut context)
                    .is_ok();
                    assert_eq!(follower, chain_control);
                    assert_eq!(
                        context.matched_position(),
                        if chain_control { 2 } else { 1 }
                    );
                }
            }
        }
    }
    for name in ["Single", "SingleInteger"] {
        let rule = rules.iter().find(|r| r.name == name).unwrap();
        assert!(
            matches!(unscoped(&rule.expression), Expr::Sequence(children) if children.len() == 1 && matches!(unscoped(&children[0]), Expr::Choice(_)))
        );
    }
}
