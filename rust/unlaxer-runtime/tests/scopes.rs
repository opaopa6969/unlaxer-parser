use unlaxer_runtime::{Expr, ParseContext, ParseResult, Rule, Severity};

fn record_a(context: &mut ParseContext<'_>) -> ParseResult {
    let matched = context.parse(&Expr::literal("a"))?;
    let store = context.scopes_mut();
    store.declare("a", matched.span.start);
    store.add_reference("a", matched.span.start, 1);
    store.add_diagnostic("observed a", matched.span.start, 1, Severity::Info);
    Ok(matched)
}

#[test]
fn failed_custom_choice_optional_and_repeat_restore_all_scope_events() {
    let branch = Expr::Custom(record_a).then(Expr::literal("x"));
    for parser in [
        branch.clone().or(Expr::literal("a")),
        branch.clone().optional().then(Expr::literal("a")),
    ] {
        let mut context = ParseContext::new("a");
        let initial = context.scopes().clone();
        context.parse(&parser).unwrap();
        assert_eq!(context.position(), 1);
        assert_eq!(context.scopes(), &initial);
    }
    let mut context = ParseContext::new("axay");
    context.parse(&branch.zero_or_more()).unwrap();
    assert_eq!((context.position(), context.matched_position()), (2, 2));
    assert_eq!(context.scopes().all_declarations().len(), 1);
    assert_eq!(context.scopes().all_references().len(), 1);
    assert_eq!(context.scopes().diagnostics().len(), 1);
    assert_eq!(context.scopes().resolve("a").unwrap().source_offset, 0);
}

#[test]
fn positive_and_negative_speculation_leave_no_symbols_or_semantic_diagnostics() {
    for (input, positive, accepted) in [("a", true, true), ("a", false, false), ("b", false, true)]
    {
        let mut context = ParseContext::new(input);
        context.scopes_mut().declare("before", 0);
        let initial = context.scopes().clone();
        let parser = if positive {
            Expr::Custom(record_a).ahead()
        } else {
            Expr::Custom(record_a).not_ahead()
        };
        assert_eq!(context.parse(&parser).is_ok(), accepted);
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        assert_eq!(context.scopes(), &initial);
    }
}

#[test]
fn nested_commit_is_undone_by_outer_rollback_including_lazy_initialization() {
    for preexisting in [false, true] {
        let mut context = ParseContext::new("a");
        if preexisting {
            context.scopes_mut().declare("before", 0);
            context.scopes_mut().add_reference("before", 0, 1);
            context
                .scopes_mut()
                .add_diagnostic("before", 0, 1, Severity::Warning);
        }
        let initial = context.scopes().clone();
        let result: Result<(), _> = context.transaction(|outer| {
            outer.scopes_mut().enter();
            outer.transaction(|inner| {
                inner.parse(&Expr::Custom(record_a))?;
                inner.scopes_mut().clear_diagnostics();
                inner.scopes_mut().declare("inside", 1);
                Ok(())
            })?;
            assert!(outer.scopes().is_declared("inside"));
            Err(outer.error("reject enclosing parse"))
        });
        assert!(result.is_err());
        assert_eq!(context.scopes(), &initial);
        assert_eq!((context.position(), context.matched_position()), (0, 0));
        // Syntax failure evidence intentionally survives semantic rollback.
        assert_eq!(context.failure().offset, 1);
    }
}

#[test]
fn scoped_custom_operation_hides_names_but_retains_successful_events() {
    let mut context = ParseContext::new("a");
    context.scopes_mut().declare("a", 42);
    let before = context.scopes().clone();
    context
        .with_scope(|local| {
            assert_eq!(local.scopes().current_scope_depth(), 1);
            local.parse(&Expr::Custom(record_a))?;
            assert_eq!(local.scopes().resolve("a").unwrap().source_offset, 0);
            Ok(())
        })
        .unwrap();
    assert_eq!(context.scopes().current_scope_depth(), 0);
    assert_eq!(context.scopes().resolve("a").unwrap().source_offset, 42);
    assert_eq!(context.scopes().all_declarations().len(), 2);
    assert_eq!(before.all_declarations().len(), 1);
    let committed = context.scopes().clone();
    let rejected: Result<(), _> = context.with_scope(|local| {
        local.scopes_mut().declare("discard", 1);
        local.scopes_mut().clear_diagnostics();
        Err(local.error("rejected"))
    });
    assert!(rejected.is_err());
    assert_eq!(context.scopes(), &committed);
}

#[test]
fn nested_grammar_shares_store_but_parent_failure_discards_its_commits() {
    let mut context = ParseContext::new("a");
    let rejected: Result<(), _> = context.transaction(|outer| {
        outer.parse_grammar(
            vec![Rule {
                name: "Record",
                expression: Expr::Custom(record_a),
            }],
            0,
            false,
        )?;
        assert_eq!(outer.scopes().all_declarations().len(), 1);
        Err(outer.error("parent failure"))
    });
    assert!(rejected.is_err());
    assert!(context.scopes().all_declarations().is_empty());
    assert_eq!(context.position(), 0);
}

#[test]
fn unicode_offsets_owned_snapshots_and_contexts_are_independent() {
    let mut first = ParseContext::new("😀x");
    first.parse(&Expr::literal("😀")).unwrap();
    let start = first.position();
    first.scopes_mut().declare("x", start);
    first.scopes_mut().add_reference("😀", 0, 1);
    let snapshot = first.scopes().clone();
    assert_eq!(snapshot.resolve("x").unwrap().source_offset, 1);
    assert_eq!(snapshot.all_references()[0].length, 1);
    let second = ParseContext::new("😀x");
    assert!(!second.scopes().is_declared("x"));
    first.scopes_mut().declare("x", 2);
    first.set_state("scopes", "user value");
    assert_eq!(first.scopes().resolve("x").unwrap().source_offset, 2);
    assert_eq!(first.scopes().all_declarations().len(), 2);
    drop(first);
    assert_eq!(snapshot.resolve("x").unwrap().source_offset, 1);
}
