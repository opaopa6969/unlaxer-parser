use unlaxer_runtime::{
    grammar_allows_deferred_diagnostics, parse, parse_detailed, parse_detailed_shared,
    parse_detailed_shared_with_options, parse_detailed_with_options, parse_shared,
    parse_shared_with_options, parse_with_options, set_candidate_exclusion_for_current_thread,
    share_grammar, CandidateExclusion, Declaration, Diagnostics, Expr, Memoization, ParseContext,
    ParseError, ParseOptions, ParseResult, Predictor, Rule, RuleEffects, ScopeMode, SharedGrammar,
    Tree,
};

fn expression_grammar() -> SharedGrammar {
    share_grammar(vec![
        Rule {
            name: "sum",
            expression: Expr::Rule(1).then(Expr::literal("+").then(Expr::Rule(1)).zero_or_more()),
        },
        Rule {
            name: "atom",
            expression: Expr::choice([
                Expr::Number.capture("number"),
                Expr::literal("😀").capture("emoji"),
                Expr::literal("(")
                    .then(Expr::Rule(0))
                    .then(Expr::literal(")")),
            ]),
        },
    ])
}

fn assert_same_tree(a: &Tree, b: &Tree) {
    assert_eq!(a.source, b.source);
    assert_eq!(a.root, b.root);
    assert_eq!(a.scopes(), b.scopes());
    assert_eq!(a.nodes.len(), b.nodes.len());
    for (a, b) in a.nodes.iter().zip(&b.nodes) {
        assert_eq!(a.rule, b.rule);
        assert_eq!(a.span, b.span);
        assert_eq!(a.children, b.children);
        assert_eq!(a.captures.len(), b.captures.len());
        for (a, b) in a.captures.iter().zip(&b.captures) {
            assert_eq!(a.name, b.name);
            assert_eq!(a.span, b.span);
            assert_eq!(a.nodes, b.nodes);
        }
    }
}

#[test]
fn diagnostics_default_and_builder_preserve_memoization() {
    assert_eq!(Diagnostics::default(), Diagnostics::Auto);
    assert_eq!(ParseOptions::default().diagnostics, Diagnostics::Auto);
    assert_eq!(format!("{:?}", Diagnostics::default()), "Auto");
    assert_eq!(
        ParseOptions::default(),
        ParseOptions::with_memoization(Memoization::Off)
    );
    const OPTIONS: ParseOptions = ParseOptions::with_memoization(Memoization::SafeFailures)
        .with_diagnostics(Diagnostics::DetailedOnFailure);
    assert_eq!(OPTIONS.memoization, Memoization::SafeFailures);
    assert_eq!(OPTIONS.diagnostics, Diagnostics::DetailedOnFailure);
    assert_eq!(
        OPTIONS.with_diagnostics(Diagnostics::Auto),
        ParseOptions::with_memoization(Memoization::SafeFailures)
    );
    assert_ne!(
        OPTIONS.with_diagnostics(Diagnostics::Detailed),
        ParseOptions::with_memoization(Memoization::SafeFailures)
    );
}

#[test]
fn full_input_entry_points_preserve_expression_trees_and_errors() {
    let grammar = expression_grammar();
    for memoization in [Memoization::Off, Memoization::SafeFailures] {
        let auto = ParseOptions::with_memoization(memoization);
        let detailed = auto.with_diagnostics(Diagnostics::Detailed);
        let deferred = detailed.with_diagnostics(Diagnostics::DetailedOnFailure);
        for (input, accepted) in [
            ("1 + (2 + 3)", true),
            ("😀 + (2 + 3)", true),
            ("invalid", false),
            ("1 + (2 +", false),
            ("1 + (2 + 3)@", false),
            ("1 + (2 + 3", false),
            ("1 + 2)", false),
            ("😀 + (2 +", false),
        ] {
            let baseline = parse_detailed_shared_with_options(&grammar, 0, true, input, detailed);
            assert_eq!(baseline.is_ok(), accepted, "{input}");
            for options in [auto, detailed, deferred] {
                for result in [
                    parse_detailed_shared_with_options(&grammar, 0, true, input, options),
                    parse_detailed_with_options(&grammar, 0, true, input, options),
                    parse_detailed_shared(&grammar, 0, true, input),
                    parse_detailed(&grammar, 0, true, input),
                ] {
                    match (&baseline, result) {
                        (Ok(a), Ok(b)) => assert_same_tree(a, &b),
                        (Err(a), Err(b)) => assert_eq!(a, &b, "{input}"),
                        (a, b) => panic!("acceptance differs for {input}: {a:?} / {b:?}"),
                    }
                }
                for result in [
                    parse_shared_with_options(&grammar, 0, true, input, options),
                    parse_with_options(&grammar, 0, true, input, options),
                    parse_shared(&grammar, 0, true, input),
                    parse(&grammar, 0, true, input),
                ] {
                    match (&baseline, result) {
                        (Ok(a), Ok(b)) => assert_same_tree(a, &b),
                        (Err(a), Err(b)) => assert_eq!(a.farthest, b, "{input}"),
                        (a, b) => panic!("acceptance differs for {input}: {a:?} / {b:?}"),
                    }
                }
            }
        }
    }
}

#[test]
fn deferred_diagnostics_preserve_scopes_captures_and_checkpoint_metrics() {
    let grammar = share_grammar(vec![Rule {
        name: "bindings",
        expression: Expr::sequence([
            Expr::literal("missing").optional(),
            Expr::Identifier
                .capture("declared")
                .rule_effects(RuleEffects {
                    declares: Some(Declaration {
                        symbol_capture: "declared",
                        description: None,
                    }),
                    ..RuleEffects::default()
                }),
            Expr::literal(":"),
            Expr::Identifier
                .capture("reference")
                .separated_by(Expr::literal("+"))
                .rule_effects(RuleEffects {
                    backref: Some("reference"),
                    ..RuleEffects::default()
                }),
            Expr::Eof,
        ])
        .rule_effects(RuleEffects {
            scope_mode: Some(ScopeMode::Lexical),
            ..RuleEffects::default()
        }),
    }]);
    for (memoization, exclusion) in [
        (Memoization::Off, CandidateExclusion::Off),
        (Memoization::SafeFailures, CandidateExclusion::Off),
        (Memoization::Off, CandidateExclusion::On),
        (Memoization::SafeFailures, CandidateExclusion::On),
    ] {
        set_candidate_exclusion_for_current_thread(Some(exclusion));
        let options = ParseOptions::with_memoization(memoization);
        let mut a = ParseContext::with_options("known: known + unknown", options);
        let mut b = ParseContext::with_options(
            a.source(),
            options.with_diagnostics(Diagnostics::DetailedOnFailure),
        );
        a.enable_checkpoint_metrics();
        b.enable_checkpoint_metrics();
        let a_root = a.parse_shared_grammar(&grammar, 0, true).unwrap();
        let b_root = b.parse_shared_grammar(&grammar, 0, true).unwrap();
        assert_same_tree(
            &a.tree(a_root.root_node().unwrap()).unwrap(),
            &b.tree(b_root.root_node().unwrap()).unwrap(),
        );
        assert_eq!(a.position(), b.position());
        assert_eq!(a.matched_position(), b.matched_position());
        for name in ["declared", "reference"] {
            assert!(!a.capture_spans(name).is_empty());
            assert_eq!(a.capture_spans(name), b.capture_spans(name));
        }
        assert_eq!(a.scopes(), b.scopes());
        assert_eq!(b.scopes().all_declarations().len(), 1);
        assert_eq!(b.scopes().all_references().len(), 2);
        assert_eq!(b.scopes().diagnostics().len(), 1);
        for diagnostics in [Diagnostics::Auto, Diagnostics::Detailed] {
            let tree = parse_detailed_shared_with_options(
                &grammar,
                0,
                true,
                a.source(),
                options.with_diagnostics(diagnostics),
            )
            .unwrap();
            assert_same_tree(&a.tree(a_root.root_node().unwrap()).unwrap(), &tree);
        }
        if exclusion == CandidateExclusion::Off {
            assert_eq!(
                a.snapshot_checkpoint_metrics(),
                b.snapshot_checkpoint_metrics()
            );
        } else {
            // FIRST-set exclusion (#300) skips candidates only in the deferred pass.
            assert!(
                b.snapshot_checkpoint_metrics().opened < a.snapshot_checkpoint_metrics().opened
            );
        }
        assert!(!a.failure().expected.is_empty());
        assert!(b.failure().expected.is_empty());
    }
}

#[test]
fn low_level_context_does_not_retry_or_collect_custom_errors() {
    let options = ParseOptions::default().with_diagnostics(Diagnostics::DetailedOnFailure);
    let mut context = ParseContext::with_options("😀x", options);
    let empty = ParseError {
        offset: 0,
        expected: vec![],
    };
    context.advance(1);
    assert_eq!(context.error("expected"), empty);
    assert_eq!(context.parse(&Expr::literal("z")).unwrap_err(), empty);
    let custom = ParseError {
        offset: 1,
        expected: vec!["custom".to_owned()],
    };
    let result: Result<(), _> = context.transaction(|context| {
        context.advance(1);
        Err(custom.clone())
    });
    assert_eq!(result.unwrap_err(), custom);
    assert_eq!(context.position(), 1);
    assert_eq!(context.failure(), empty);
    assert_eq!(
        context
            .parse_grammar(expression_grammar().to_vec(), 0, false)
            .unwrap_err(),
        empty
    );
}

#[test]
fn full_input_failures_retry_once_with_fresh_state_and_detailed_policy() {
    use std::sync::atomic::{AtomicUsize, Ordering};
    static CALLS: AtomicUsize = AtomicUsize::new(0);
    static DETAILED_CALLS: AtomicUsize = AtomicUsize::new(0);
    fn custom(context: &mut ParseContext<'_>) -> ParseResult {
        CALLS.fetch_add(1, Ordering::Relaxed);
        if context.options().diagnostics == Diagnostics::Detailed {
            DETAILED_CALLS.fetch_add(1, Ordering::Relaxed);
        }
        assert!(context.state::<bool>("visited").is_none());
        context.set_state("visited", true);
        context.parse(&Expr::literal("😀"))
    }
    let grammar = share_grammar(vec![Rule {
        name: "root",
        expression: Expr::Custom(custom),
    }]);
    for (input, accepted, calls) in [("😀", true, 1), ("x", false, 2), ("😀@", false, 2)] {
        CALLS.store(0, Ordering::Relaxed);
        DETAILED_CALLS.store(0, Ordering::Relaxed);
        let result = parse_detailed_shared_with_options(
            &grammar,
            0,
            false,
            input,
            ParseOptions::default().with_diagnostics(Diagnostics::DetailedOnFailure),
        );
        assert_eq!(result.is_ok(), accepted);
        assert_eq!(CALLS.load(Ordering::Relaxed), calls);
        assert_eq!(
            DETAILED_CALLS.load(Ordering::Relaxed),
            usize::from(!accepted)
        );
        if let Err(error) = result {
            assert!(!error.farthest.expected.is_empty());
        }
    }
}

#[test]
fn auto_low_level_context_preserves_diagnostics_and_resolves_options() {
    for memoization in [Memoization::Off, Memoization::SafeFailures] {
        let options = ParseOptions::with_memoization(memoization);
        for mut context in [
            ParseContext::new("😀x"),
            ParseContext::with_options("😀x", options),
        ] {
            assert_eq!(context.options().diagnostics, Diagnostics::Detailed);
            context.parse(&Expr::literal("😀")).unwrap();
            let error = context.parse(&Expr::literal("z")).unwrap_err();
            assert_eq!(error.offset, 1);
            assert_eq!(error.expected, ["z"]);
            assert_eq!(context.failure(), error);
            assert_eq!(context.error("other").expected, ["other", "z"]);
        }
        assert_eq!(
            ParseContext::with_options("", options).options(),
            options.with_diagnostics(Diagnostics::Detailed)
        );
        for diagnostics in [Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
            let explicit = options.with_diagnostics(diagnostics);
            assert_eq!(ParseContext::with_options("", explicit).options(), explicit);
        }
    }
}

#[test]
fn auto_preserves_undeclared_diagnostic_dependent_custom_acceptance() {
    fn diagnostic_dependent(context: &mut ParseContext<'_>) -> ParseResult {
        context.parse(&Expr::literal("missing").optional())?;
        if context.failure().expected == ["missing"] {
            context.parse(&Expr::literal("😀"))
        } else {
            Err(context.error("diagnostics required"))
        }
    }
    let grammar = share_grammar(vec![Rule {
        name: "root",
        expression: Expr::Custom(diagnostic_dependent),
    }]);
    for input in ["😀", "x", "😀@"] {
        let auto = parse_detailed_shared(&grammar, 0, false, input);
        let detailed = parse_detailed_shared_with_options(
            &grammar,
            0,
            false,
            input,
            ParseOptions::default().with_diagnostics(Diagnostics::Detailed),
        );
        match (auto, detailed) {
            (Ok(a), Ok(b)) => assert_same_tree(&a, &b),
            (Err(a), Err(b)) => assert_eq!(a, b),
            results => panic!("custom acceptance changed: {results:?}"),
        }
    }
    assert!(parse_shared(&grammar, 0, false, "😀").is_ok());
}

#[test]
fn auto_custom_contract_controls_retries_and_keeps_custom_memo_unsafe() {
    use std::cell::RefCell;
    thread_local! {
        static POLICIES: RefCell<Vec<Diagnostics>> = const { RefCell::new(Vec::new()) };
    }
    fn custom(context: &mut ParseContext<'_>) -> ParseResult {
        // Observe policy only for this probe, without influencing the parse result.
        POLICIES.with(|policies| policies.borrow_mut().push(context.options().diagnostics));
        assert!(context.state::<bool>("visited").is_none());
        context.set_state("visited", true);
        context.parse(&Expr::literal("😀").capture("emoji"))
    }
    for expression in [
        Expr::Custom(custom),
        Expr::CustomWith {
            parser: custom,
            reads_diagnostics: true,
            replayable: false,
        },
        Expr::CustomWith {
            parser: custom,
            reads_diagnostics: false,
            replayable: false,
        },
        Expr::CustomWith {
            parser: custom,
            reads_diagnostics: true,
            replayable: true,
        },
        Expr::CustomWith {
            parser: custom,
            reads_diagnostics: false,
            replayable: true,
        },
    ] {
        let grammar = share_grammar(vec![
            Rule {
                name: "root",
                expression: Expr::choice([Expr::Rule(1), Expr::Rule(1)]),
            },
            Rule {
                name: "custom",
                expression,
            },
        ]);
        let allows_deferred = matches!(
            grammar[1].expression,
            Expr::CustomWith {
                reads_diagnostics: false,
                replayable: true,
                ..
            }
        );
        for memoization in [Memoization::Off, Memoization::SafeFailures] {
            for diagnostics in [
                Diagnostics::Auto,
                Diagnostics::Detailed,
                Diagnostics::DetailedOnFailure,
            ] {
                let deferred = diagnostics == Diagnostics::DetailedOnFailure
                    || (diagnostics == Diagnostics::Auto && allows_deferred);
                for (input, accepted, first_calls) in
                    [("😀", true, 1), ("x", false, 2), ("😀@", false, 1)]
                {
                    let baseline = parse_detailed_shared_with_options(
                        &grammar,
                        0,
                        false,
                        input,
                        ParseOptions::with_memoization(memoization)
                            .with_diagnostics(Diagnostics::Detailed),
                    );
                    POLICIES.with(|policies| policies.borrow_mut().clear());
                    let result = parse_detailed_shared_with_options(
                        &grammar,
                        0,
                        false,
                        input,
                        ParseOptions::with_memoization(memoization).with_diagnostics(diagnostics),
                    );
                    assert_eq!(result.is_ok(), accepted);
                    let first_policy = if deferred {
                        Diagnostics::DetailedOnFailure
                    } else {
                        Diagnostics::Detailed
                    };
                    let mut expected = vec![first_policy; first_calls];
                    if deferred && !accepted {
                        expected.extend(vec![Diagnostics::Detailed; first_calls]);
                    }
                    POLICIES.with(|policies| assert_eq!(*policies.borrow(), expected));
                    match (baseline, result) {
                        (Ok(a), Ok(b)) => assert_same_tree(&a, &b),
                        (Err(a), Err(b)) => {
                            assert!(!b.farthest.expected.is_empty());
                            assert_eq!(a, b);
                        }
                        results => panic!("custom acceptance changed: {results:?}"),
                    }
                }
            }
        }
    }
}

#[test]
fn grammar_diagnostic_contract_checks_all_rules_and_nested_combinators() {
    fn custom(context: &mut ParseContext<'_>) -> ParseResult {
        context.parse(&Expr::Empty)
    }
    let wrappers: &[fn(Expr) -> Expr] = &[
        |child| Expr::sequence([Expr::Empty, child]),
        |child| Expr::choice([Expr::Empty, child]),
        |child| Expr::longest_choice([Expr::Empty, child]),
        |child| Expr::predictive_choice([(Predictor::Any, Expr::Empty), (Predictor::Any, child)]),
        |child| child.capture("nested"),
        Expr::text_value,
        Expr::value_boundary,
        Expr::optional,
        Expr::optional_java,
        |child| child.repeat(0, Some(2)),
        |child| child.repeat_java(0, Some(2)),
        Expr::ahead,
        Expr::not_ahead,
        |child| child.rule_effects(RuleEffects::default()),
        |child| child.trivia_scope(true),
    ];
    for wrap in wrappers {
        for (expression, allowed) in [
            (Expr::literal("generated"), true),
            (Expr::Custom(custom), false),
            (
                Expr::CustomWith {
                    parser: custom,
                    reads_diagnostics: false,
                    replayable: true,
                },
                true,
            ),
        ] {
            let rules = [
                Rule {
                    name: "recursive",
                    expression: Expr::Rule(0),
                },
                Rule {
                    name: "unreachable",
                    expression: wrap(expression),
                },
            ];
            assert_eq!(grammar_allows_deferred_diagnostics(&rules), allowed);
        }
    }
    assert!(grammar_allows_deferred_diagnostics(&expression_grammar()));
    assert!(grammar_allows_deferred_diagnostics(&[]));
}
