use unlaxer_runtime::{names::*, token::TokenKind, *};

const CORPUS: &str =
    include_str!("../../../../conformance/chain-reset-after-failure/owner-reset.tsv");

pub fn report(file: &str, rules: Vec<Rule>) {
    for (expected, observed) in observations(file, rules) {
        let _ = expected;
        println!("{observed}");
    }
}

pub fn check(file: &str, rules: Vec<Rule>) {
    for (expected, observed) in observations(file, rules) {
        assert_eq!(expected, observed, "{file}");
    }
}

fn observations(file: &str, rules: Vec<Rule>) -> Vec<(String, String)> {
    let mut results = vec![];
    for row in CORPUS
        .lines()
        .filter(|row| !row.starts_with('#') && !row.is_empty())
    {
        let c: Vec<_> = row.split('\t').collect();
        if c[1] != file {
            continue;
        }
        let expected = format!("{}\t{}", c[0], c[7..14].join("\t"));
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let snapshot = Snapshot::new(
                    "names",
                    "v1",
                    [(
                        "a".to_owned(),
                        if c[5] == "type" {
                            Kind::Type
                        } else {
                            Kind::Value
                        },
                    )]
                    .into(),
                )
                .unwrap();
                let mut context = ParseContext::with_name_snapshots(
                    c[3],
                    &[snapshot],
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                )
                .unwrap();
                let mut grammar = rules.clone();
                let mut body = grammar[0].expression.clone();
                let driver = if c[2] == "predicate" {
                    let Expr::NameResolutionScope {
                        child,
                        requirements,
                    } = body
                    else {
                        panic!("generated root must have a name scope");
                    };
                    body = *child;
                    let mut predicate = &body;
                    while let Expr::MetadataCapture(_, child) = predicate {
                        predicate = child;
                    }
                    assert!(matches!(predicate, Expr::NamePredicate { .. }));
                    // Establish the generated scope's snapshot environment, then observe
                    // the predicate *inside* it, before the outer frame can hide its policy.
                    Expr::NameResolutionScope {
                        child: Box::new(Expr::Custom(observe)),
                        requirements,
                    }
                } else {
                    Expr::Custom(observe)
                };
                context.set_state("case", c.iter().map(|s| s.to_string()).collect::<Vec<_>>());
                context.set_state("body", body);
                let root = grammar.len();
                grammar.push(Rule {
                    name: "observer",
                    expression: driver,
                });
                context
                    .parse_shared_grammar(&share_grammar(grammar), root, false)
                    .unwrap();
                results.push((
                    expected.clone(),
                    context.state::<String>("observed").unwrap().clone(),
                ));
            }
        }
    }
    results
}

fn observe(context: &mut ParseContext<'_>) -> ParseResult {
    let c = context.state::<Vec<String>>("case").unwrap().clone();
    let body = context.state::<Expr>("body").unwrap().clone();
    let ahead = |pattern| Expr::JavaLookahead {
        pattern,
        positive: true,
    };
    ahead("a").parse(context).unwrap();
    let count = if c[7] == "true" { 1 } else { 3 };
    let mut before = None;
    for _ in 0..count {
        context.set_reset_matched_with_consumed(c[6] == "true");
        let result = match c[2].as_str() {
            "classic-rule" => {
                context.parse_with_token_mode(&Expr::Rule(0), TokenKind::Consumed, false)
            }
            "classic-body" => context.parse_with_token_mode(&body, TokenKind::Consumed, false),
            "predicate" => body.parse(context),
            "scope" => Expr::Rule(0).parse(context),
            _ => panic!("unknown entry"),
        };
        let observed = format!(
            "{}\t{}\t{}\t{}\t{}",
            c[0],
            result.is_ok(),
            context.reset_matched_with_consumed(),
            context.position(),
            context.matched_position()
        );
        if let Some(previous) = &before {
            assert_eq!(previous, &observed, "retry must preserve the result");
        }
        before = Some(observed);
    }
    if c[1] == "grammar.ubnf" && c[2] == "classic-rule" {
        assert_eq!(
            context.memoized_failure_hits(),
            if context.options().memoization == Memoization::Off {
                0
            } else {
                2
            }
        );
    }
    let follower = Expr::NonOrdered(vec![ahead("b")]).parse(context).is_ok();
    context.set_state(
        "observed",
        format!(
            "{}\t{}\t{}\t{}",
            before.unwrap(),
            follower,
            context.position(),
            context.matched_position()
        ),
    );
    Ok(ParseMatch::empty(Span {
        start: context.position(),
        end: context.position(),
    }))
}
