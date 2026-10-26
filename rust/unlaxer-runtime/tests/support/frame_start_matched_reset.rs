use unlaxer_runtime::{token::TokenKind, *};

const INPUTS: &str =
    include_str!("../../../../conformance/chain-reset-after-failure/frame-start-inputs.tsv");

pub fn report(rules: Vec<Rule>) {
    for rule in &rules {
        let mut body = &rule.expression;
        while let Expr::MetadataCapture(_, child) = body {
            body = child;
        }
        let correct_frame = match rule.name {
            "Root" | "PlainTok" | "PlainGroup" => matches!(body, Expr::Choice(_)),
            "PlainSeq" => matches!(body, Expr::Sequence(_)),
            "Longest" => matches!(body, Expr::LongestChoice(_)),
            "Unique" => matches!(body, Expr::UniqueLongestChoice(_)),
            "Predictive" => matches!(body, Expr::PredictiveChoice { .. }),
            _ => true,
        };
        assert!(correct_frame, "{} generated frame", rule.name);
    }
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
        "optional" => Expr::JavaOptional(Box::new(rule)),
        "repeat" => Expr::JavaRepeat {
            child: Box::new(rule),
            min: 0,
            max: None,
        },
        "no-attempt" => rule.repeat_java(0, Some(0)),
        "invalid-bounds" => rule.repeat_java(2, Some(1)),
        "zero-check" => Expr::ZeroMatch(Box::new(rule)),
        "invert" => rule.invert_match(),
        "recovery" => rule.recover(RecoveryMode::Sync, [";"], "syntax error"),
        "predictive" => Expr::PredictiveChoice {
            alternatives: vec![rule, Expr::Literal("fallback")],
            predictors: vec![Predictor::Any, Predictor::Any],
        },
        "until-frame" => Expr::JavaUntil("b"),
        "longest" => Expr::LongestChoice(vec![rule, Expr::Literal("fallback")]),
        "unique" => Expr::UniqueLongestChoice(vec![rule, Expr::Literal("fallback")]),
        "capture-scope" => Expr::CaptureScope(Box::new(rule)),
        "capture" => Expr::OwnedCapture("value", Box::new(rule)),
        "catch" => rule.clone().label("L").catch_labels(rule, ["L"]),
        "empty" => Expr::JavaEmpty,
        "positive" => Expr::JavaLookahead {
            pattern: "b",
            positive: true,
        },
        "negative" => Expr::JavaLookahead {
            pattern: "b",
            positive: false,
        },
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
