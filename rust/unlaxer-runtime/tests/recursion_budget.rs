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

fn reenter_shared(context: &mut ParseContext<'_>) -> ParseResult {
    let grammar = share_grammar(vec![Rule {
        name: "recursive",
        expression: Expr::Custom(reenter_shared),
    }]);
    context.parse_shared_grammar(&grammar, 0, false)
}

#[test]
fn public_grammar_reentry_is_bounded_and_restores_context() {
    on_standard_stack(|| {
        for callback in [
            reenter_owned as fn(&mut ParseContext<'_>) -> ParseResult,
            reenter_shared,
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
