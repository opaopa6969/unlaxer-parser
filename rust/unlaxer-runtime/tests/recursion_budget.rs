use unlaxer_runtime::*;

fn on_standard_stack(operation: impl FnOnce() + Send + 'static) {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(operation)
        .unwrap()
        .join()
        .unwrap();
}

#[test]
fn pure_recursive_wrappers_obey_the_expression_budget() {
    on_standard_stack(|| {
        for kind in 0..2 {
            let mut expression = Expr::Literal("x");
            for _ in 0..1500 {
                expression = if kind == 0 {
                    expression.rule_effects(RuleEffects::default())
                } else {
                    Expr::CaptureEquality {
                        child: Box::new(expression),
                        name: "name",
                    }
                };
            }
            let mut context = ParseContext::new("x");
            let error = context.parse(&expression).unwrap_err();
            assert_eq!(error.expected, ["expression nesting below 1024"]);
            assert_eq!(context.position(), 0);
            // The runtime budget covers execution, not recursive destruction of
            // an arbitrarily deep hand-built AST. This small fixture dies with the process.
            std::mem::forget(expression);
        }
    });
}

#[test]
fn recursive_groups_and_wrappers_fail_with_a_diagnostic() {
    on_standard_stack(|| {
        for memoization in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
                for wrappers in [0, 8] {
                    let mut child = Expr::Rule(0).capture("value").optional();
                    for _ in 0..wrappers {
                        child = child.capture("wrapper");
                    }
                    let rules = vec![Rule {
                        name: "group",
                        expression: Expr::Sequence(vec![
                            Expr::Literal("("),
                            child,
                            Expr::Literal(")"),
                        ]),
                    }];
                    let options =
                        ParseOptions::with_memoization(memoization).with_diagnostics(diagnostics);
                    let accepted = "(".repeat(64) + &")".repeat(64);
                    let tree = parse_with_options(&rules, 0, false, &accepted, options).unwrap();
                    assert_eq!(tree.nodes[tree.root].span, Span { start: 0, end: 128 });
                    let too_deep = "(".repeat(300) + &")".repeat(300);
                    let error =
                        parse_with_options(&rules, 0, false, &too_deep, options).unwrap_err();
                    assert!(
                        error
                            .expected
                            .iter()
                            .any(|value| value.contains("nesting below")),
                        "{error:?}"
                    );
                    assert!(error.offset < 300);
                }
            }
        }
    });
}

fn reenter_owned(context: &mut ParseContext<'_>) -> ParseResult {
    context.set_state("changed", true);
    context.parse_grammar(
        vec![Rule {
            name: "recursive",
            expression: Expr::Custom(reenter_owned),
        }],
        0,
        false,
    )
}

#[test]
fn resource_failure_cannot_be_swallowed_by_combinators() {
    on_standard_stack(|| {
        let input = "(".repeat(300) + &")".repeat(300);
        let rest = || Expr::Any.repeat(0, None);
        for guarded in [
            Expr::Lookahead {
                child: Box::new(Expr::Rule(1)),
                positive: false,
            },
            Expr::Rule(1).optional(),
            Expr::choice([Expr::Rule(1), rest()]),
            Expr::Rule(1).recover(RecoveryMode::Sync, [")"], "recover"),
        ] {
            let rules = vec![
                Rule {
                    name: "root",
                    expression: Expr::Sequence(vec![guarded, rest()]),
                },
                Rule {
                    name: "group",
                    expression: Expr::Sequence(vec![
                        Expr::Literal("("),
                        Expr::Rule(1).optional(),
                        Expr::Literal(")"),
                    ]),
                },
            ];
            for memo in [Memoization::Off, Memoization::SafeFailures] {
                for diagnostics in [
                    Diagnostics::Auto,
                    Diagnostics::Detailed,
                    Diagnostics::DetailedOnFailure,
                ] {
                    let error = parse_detailed_with_options(
                        &rules,
                        0,
                        false,
                        &input,
                        ParseOptions::with_memoization(memo).with_diagnostics(diagnostics),
                    )
                    .unwrap_err();
                    assert_eq!(error.kind, "resource_limit");
                    assert!(error
                        .expected
                        .iter()
                        .any(|value| value.contains("nesting below")));
                }
            }
        }
    });
}

fn reenter_shared(context: &mut ParseContext<'_>) -> ParseResult {
    let grammar = share_grammar(vec![Rule {
        name: "recursive",
        expression: Expr::Custom(reenter_shared),
    }]);
    context.parse_shared_grammar(&grammar, 0, false)
}

fn reenter_trait(context: &mut ParseContext<'_>) -> ParseResult {
    Expr::Custom(reenter_trait).parse(context)
}

#[test]
fn public_grammar_reentry_is_bounded_and_restores_context() {
    on_standard_stack(|| {
        for callback in [
            reenter_owned as fn(&mut ParseContext<'_>) -> ParseResult,
            reenter_shared,
            reenter_trait,
        ] {
            let mut context = ParseContext::new("ok");
            context.set_state("changed", false);
            let error = context.parse(&Expr::Custom(callback)).unwrap_err();
            assert!(
                error
                    .expected
                    .iter()
                    .any(|value| value.contains("below 256")),
                "{error:?}"
            );
            assert_eq!(context.position(), 0);
            assert_eq!(context.matched_position(), 0);
            assert_eq!(context.state::<bool>("changed"), Some(&false));
            assert_eq!(context.parse(&Expr::Literal("ok")).unwrap_err(), error);
            let mut context = ParseContext::new("ok");
            assert_eq!(
                context
                    .parse_grammar(
                        vec![Rule {
                            name: "root",
                            expression: Expr::Literal("ok")
                        }],
                        0,
                        false
                    )
                    .unwrap()
                    .span,
                Span { start: 0, end: 2 }
            );
        }
    });
}
