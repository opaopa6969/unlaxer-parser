use unlaxer_runtime::token::TokenKind;
use unlaxer_runtime::{
    share_grammar, Diagnostics, Expr, Memoization, ParseContext, ParseError, ParseMatch,
    ParseOptions, ParseResult, Rule, Span,
};

#[derive(Clone, Copy)]
struct Case {
    id: &'static str,
    success: bool,
    warm: usize,
    retry: usize,
    resource: bool,
    accepted: bool,
    hits: usize,
    enabled: bool,
    classic: bool,
}
fn wrappers(mut child: Expr, depth: usize) -> Expr {
    for _ in 0..depth {
        child = child.optional();
    }
    child
}
fn parse_case(context: &mut ParseContext<'_>, expression: &Expr, classic: bool) -> ParseResult {
    if classic {
        context.parse_with_token_mode(expression, TokenKind::Consumed, false)
    } else {
        context.parse(expression)
    }
}
fn driver(context: &mut ParseContext<'_>) -> ParseResult {
    let case = *context.state::<Case>("case").unwrap();
    let warm = wrappers(Expr::Rule(1), case.warm);
    let _: Result<(), ParseError> = context.transaction(|context| {
        assert_eq!(
            parse_case(context, &warm, case.classic).is_ok(),
            case.success || case.warm > 0,
            "{} warm",
            case.id
        );
        Err(ParseError {
            offset: 0,
            expected: vec![],
        })
    });
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    let retry = wrappers(Expr::Rule(1), case.retry);
    let result = parse_case(context, &retry, case.classic);
    if case.resource {
        let failure = result.as_ref().unwrap_err();
        assert!(
            failure
                .expected
                .iter()
                .any(|text| text.contains("expression nesting")),
            "{}: {failure:?}",
            case.id
        );
        assert_eq!(
            &parse_case(context, &Expr::Rule(1), case.classic).unwrap_err(),
            failure
        );
        assert_eq!(failure.offset, 0);
    } else {
        assert_eq!(result.is_ok(), case.accepted, "{} retry", case.id);
    }
    let hits = if case.success {
        context.memoized_success_hits()
    } else {
        context.memoized_failure_hits()
    };
    assert_eq!(
        hits,
        if case.enabled { case.hits } else { 0 },
        "{} hits",
        case.id
    );
    assert_eq!((context.position(), context.matched_position()), (0, 0));
    // Root success cannot swallow a resource failure; the shared entry must keep it sticky.
    Ok(ParseMatch::empty(Span { start: 0, end: 0 }))
}
#[test]
fn warm_results_respect_resource_budget_and_keep_shallower_hits() {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(|| {
            let mut observations = 0;
            for classic in [false, true] {
                for row in include_str!("../../../conformance/memo-resource-depth/cases.tsv")
                    .lines()
                    .filter(|r| !r.starts_with('#') && !r.is_empty())
                {
                    let p: Vec<_> = row.split('\t').collect();
                    for memo in [Memoization::Off, Memoization::SafeFailures] {
                        for diagnostics in [
                            Diagnostics::Detailed,
                            Diagnostics::DetailedOnFailure,
                            Diagnostics::Auto,
                        ] {
                            let case = Case {
                                id: p[0],
                                success: p[1].parse().unwrap(),
                                warm: p[2].parse().unwrap(),
                                retry: p[3].parse().unwrap(),
                                resource: p[4].parse().unwrap(),
                                accepted: p[5].parse().unwrap(),
                                hits: p[6].parse().unwrap(),
                                enabled: memo == Memoization::SafeFailures,
                                classic,
                            };
                            let body = wrappers(Expr::literal("z"), 64).then(if case.success {
                                Expr::Eof
                            } else {
                                Expr::Lookahead {
                                    child: Box::new(Expr::Eof),
                                    positive: false,
                                }
                            });
                            let grammar = share_grammar(vec![
                                Rule {
                                    name: "driver",
                                    expression: Expr::Custom(driver),
                                },
                                Rule {
                                    name: "target",
                                    expression: body,
                                },
                            ]);
                            let mut context = ParseContext::with_options(
                                "",
                                ParseOptions {
                                    memoization: memo,
                                    diagnostics,
                                    ..ParseOptions::default()
                                },
                            );
                            context.set_state("case", case);
                            assert_eq!(
                                context.parse_shared_grammar(&grammar, 0, false).is_err(),
                                case.resource,
                                "{} root",
                                case.id
                            );
                            observations += 1;
                        }
                    }
                }
            }
            assert_eq!(observations, 144);
        })
        .unwrap()
        .join()
        .unwrap();
}
