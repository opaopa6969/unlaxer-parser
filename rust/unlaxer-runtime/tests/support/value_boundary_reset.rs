use unlaxer_runtime::{token::TokenKind, *};

const CORPUS: &str =
    include_str!("../../../../conformance/chain-reset-after-failure/value-reset.tsv");

pub fn report(file: &str, rules: Vec<Rule>) {
    for (_, observed) in observations(file, rules) {
        println!("{observed}");
    }
}

pub fn check(file: &str, rules: Vec<Rule>) {
    for (expected, observed) in observations(file, rules) {
        assert_eq!(expected, observed, "{file}");
    }
}

fn unscoped(mut expression: &Expr) -> &Expr {
    while let Expr::MetadataCapture(_, child) | Expr::TriviaScope { child, .. } = expression {
        expression = child;
    }
    expression
}

fn observations(file: &str, rules: Vec<Rule>) -> Vec<(String, String)> {
    let mut results = vec![];
    for row in CORPUS
        .lines()
        .filter(|row| !row.starts_with('#') && !row.is_empty())
    {
        let c: Vec<_> = row.split('\t').map(str::to_owned).collect();
        if c[1] != file {
            continue;
        }
        let mut grammar = rules.clone();
        let target = if c[2] == "text-candidate" {
            let root = grammar.iter().find(|rule| rule.name == "Root").unwrap();
            let Expr::Choice(alternatives) = unscoped(&root.expression) else {
                panic!("nested mixed root must be a generated Choice");
            };
            let candidate = alternatives[0].clone();
            assert!(matches!(unscoped(&candidate), Expr::TextValue(child)
                if matches!(child.as_ref(), Expr::Sequence(_))));
            // Observe the actual generated alternative before Choice restores its owner.
            // Java calls the corresponding generated Chain child directly. A synthetic
            // rule also exercises lookup without rebuilding/replacing the generated body.
            let id = grammar.len();
            grammar.push(Rule {
                name: "TextCandidate",
                expression: candidate,
            });
            id
        } else {
            let id = grammar.iter().position(|rule| rule.name == c[2]).unwrap();
            if c[2] != "Root" {
                assert!(
                    matches!(unscoped(&grammar[id].expression), Expr::ValueBoundary(child)
                    if matches!(child.as_ref(), Expr::Sequence(_)))
                );
            }
            id
        };
        let body = grammar[target].expression.clone();
        let driver = grammar.len();
        grammar.push(Rule {
            name: "observer",
            expression: Expr::Custom(observe),
        });
        let grammar = share_grammar(grammar);
        for memo in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let mut context = ParseContext::with_options(
                    &c[4],
                    ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                );
                context.set_state("case", c.clone());
                context.set_state("target", (target, body.clone()));
                context
                    .parse_shared_grammar(&grammar, driver, false)
                    .unwrap();
                results.push((
                    format!("{}\t{}", c[0], c[7..14].join("\t")),
                    context.state::<String>("observed").unwrap().clone(),
                ));
            }
        }
    }
    results
}

fn observe(context: &mut ParseContext<'_>) -> ParseResult {
    let c = context.state::<Vec<String>>("case").unwrap().clone();
    let (target, body) = context.state::<(usize, Expr)>("target").unwrap().clone();
    let prefix = if c[5] == "a" { "a" } else { "a😀" };
    Expr::JavaLookahead {
        pattern: prefix,
        positive: true,
    }
    .parse(context)
    .unwrap();
    let initial = c[6] == "true";
    let mut before = None;
    for attempt in 0..3 {
        let reset = if attempt == 1 && c[2] != "Root" {
            !initial
        } else {
            initial
        };
        context.set_reset_matched_with_consumed(reset);
        let expression = if c[3].ends_with("-rule") {
            Expr::Rule(target)
        } else {
            body.clone()
        };
        let result = if c[3].starts_with("classic-") {
            context.parse_with_token_mode(&expression, TokenKind::Consumed, false)
        } else {
            expression.parse(context)
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
            assert_eq!(previous, &observed, "{}: retry", c[0]);
        }
        before = Some(observed);
    }
    if c[2] != "Root" {
        let hits = if c[3].ends_with("-rule")
            && context.options().memoization == Memoization::SafeFailures
        {
            2
        } else {
            0
        };
        assert_eq!(
            context.memoized_failure_hits(),
            hits,
            "{}: normalized lookup",
            c[0]
        );
    }
    let follower = Expr::NonOrdered(vec![Expr::JavaLookahead {
        pattern: "b",
        positive: true,
    }])
    .parse(context)
    .is_ok();
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
