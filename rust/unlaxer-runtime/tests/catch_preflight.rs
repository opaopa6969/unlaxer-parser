//! Rule cycles through CATCH operands (#720) on the public entries: the pre-parse
//! Classic-mode scan terminates, and parsing ends with the resource-limit contract.
use unlaxer_runtime::*;

fn on_standard_stack(operation: impl FnOnce() + Send + 'static) {
    std::thread::Builder::new()
        .stack_size(2 * 1024 * 1024)
        .spawn(operation)
        .unwrap()
        .join()
        .unwrap();
}

const SETTINGS: [(Memoization, Diagnostics); 6] = [
    (Memoization::Off, Diagnostics::Auto),
    (Memoization::Off, Diagnostics::Detailed),
    (Memoization::Off, Diagnostics::DetailedOnFailure),
    (Memoization::SafeFailures, Diagnostics::Auto),
    (Memoization::SafeFailures, Diagnostics::Detailed),
    (Memoization::SafeFailures, Diagnostics::DetailedOnFailure),
];

/// `R0 ::= CATCH(LABEL(L, 'Z'), R0, L)`: the alternative re-enters R0 at the same position.
fn left_cycle() -> Vec<Rule> {
    vec![Rule {
        name: "R0",
        expression: Expr::literal("Z")
            .label("L")
            .catch_labels(Expr::Rule(0), ["L"]),
    }]
}

/// `R0 ::= CATCH(LABEL(L, 'Z'), 'a' R0, L)`: a consuming cycle.
fn consuming_cycle() -> Vec<Rule> {
    vec![Rule {
        name: "R0",
        expression: Expr::literal("Z")
            .label("L")
            .catch_labels(Expr::literal("a").then(Expr::Rule(0)), ["L"]),
    }]
}

#[test]
fn a_left_cycle_through_the_alternative_is_a_sticky_resource_limit() {
    on_standard_stack(|| {
        for (memo, diagnostics) in SETTINGS {
            let label = format!("{memo:?}/{diagnostics:?}");
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let mut context = ParseContext::with_options("", options);
            context.set_state("user", 7i32);
            let error = context.parse_grammar(left_cycle(), 0, false).unwrap_err();
            assert_eq!(error.expected, ["rule nesting below 256"], "{label}");
            assert_eq!(error.offset, 0, "{label}");
            assert_eq!((context.position(), context.matched_position()), (0, 0));
            assert_eq!(context.raised_failure(), None, "{label}");
            assert_eq!(context.state::<i32>("user"), Some(&7), "{label}");
            // Sticky: the context reports the same failure instead of parsing again.
            assert_eq!(context.parse(&Expr::Empty).unwrap_err(), error, "{label}");
            assert_eq!(
                context.parse_grammar(left_cycle(), 0, false).unwrap_err(),
                error
            );

            let failure =
                parse_detailed_with_options(&left_cycle(), 0, false, "", options).unwrap_err();
            assert_eq!(failure.kind, "resource_limit", "{label}");
            assert_eq!(failure.offset, 0, "{label}");
            // A fresh context is unaffected: the body's success needs no alternative.
            assert!(parse_detailed_with_options(&left_cycle(), 0, false, "Z", options).is_ok());
        }
    });
}

#[test]
fn a_consuming_cycle_parses_and_a_deep_one_is_a_resource_limit() {
    on_standard_stack(|| {
        for (memo, diagnostics) in SETTINGS {
            let label = format!("{memo:?}/{diagnostics:?}");
            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
            let tree =
                parse_detailed_with_options(&consuming_cycle(), 0, false, "aaZ", options).unwrap();
            assert_eq!(tree.nodes[tree.root].span, Span { start: 0, end: 3 });
            let failure = parse_detailed_with_options(&consuming_cycle(), 0, false, "aab", options)
                .unwrap_err();
            assert_eq!((failure.kind, failure.offset), ("syntax", 2), "{label}");
            let deep = "a".repeat(300) + "Z";
            let failure = parse_detailed_with_options(&consuming_cycle(), 0, false, &deep, options)
                .unwrap_err();
            assert_eq!(failure.kind, "resource_limit", "{label}");
            assert!(failure.offset < 300, "{label}");
        }
    });
}

/// `R0 ::= CATCH(prefix-in-body, alternative, L)` with the positive LOOKAHEAD token only in
/// one operand: the failure after it is still at the matched reach (#706).
fn lookahead_then_e() -> Expr {
    Expr::sequence([
        Expr::literal("a"),
        Expr::JavaLookahead {
            pattern: "x",
            positive: true,
        },
        Expr::literal("é"),
    ])
}

#[test]
fn a_positive_lookahead_only_in_a_catch_operand_keeps_the_matched_frontier() {
    let in_body = || lookahead_then_e().catch_labels(Expr::literal("z"), ["L"]);
    let in_alternative = || {
        Expr::literal("q")
            .label("L")
            .catch_labels(lookahead_then_e(), ["L"])
    };
    for (memo, diagnostics) in SETTINGS {
        let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
        let detailed = diagnostics != Diagnostics::DetailedOnFailure;
        for (name, expression) in [("body", in_body()), ("alternative", in_alternative())] {
            let label = format!("{name}/{memo:?}/{diagnostics:?}");
            let rules = || {
                vec![Rule {
                    name: "R0",
                    expression: expression.clone(),
                }]
            };
            for input in ["ax", "axé"] {
                let failure =
                    parse_detailed_with_options(&rules(), 0, false, input, options).unwrap_err();
                assert_eq!((failure.kind, failure.offset), ("syntax", 2), "{label}");
                let mut context = ParseContext::with_options(input, options);
                assert!(context.parse_grammar(rules(), 0, false).is_err());
                assert_eq!(
                    context.failure().offset,
                    if detailed { 2 } else { 0 },
                    "{label}"
                );
                assert_eq!(context.raised_failure(), None, "{label}");
                // The native PEG expression entry keeps its source frontier (CP1).
                let mut context = ParseContext::with_options(input, options);
                assert!(context.parse(&expression).is_err());
                assert_eq!(context.failure().offset, usize::from(detailed), "{label}");
            }
        }
    }
}
