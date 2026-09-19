use unlaxer_runtime::{
    parse, Declaration, Expr, ParseContext, Rule, RuleEffects, ScopeMode, Severity,
};

fn declares(scope_mode: Option<ScopeMode>) -> RuleEffects {
    RuleEffects {
        scope_mode,
        declares: Some(Declaration {
            symbol_capture: "name",
            description: Some("documentation"),
        }),
        backref: None,
    }
}

#[test]
fn repeated_captures_trim_java_controls_adjust_unicode_positions_and_keep_quotes() {
    let values = [" \t😀 \n", "'a\\b'", "\u{a0}x\u{a0}", "\u{2003}y", "\0\t "];
    let expression = Expr::sequence(
        values
            .iter()
            .map(|text| Expr::literal(text).capture("name")),
    )
    .rule_effects(RuleEffects {
        backref: Some("name"),
        ..declares(None)
    });
    let input = values.concat();
    let mut context = ParseContext::new(&input);
    context.parse(&expression).unwrap();
    let store = context.scopes();
    assert_eq!(
        store
            .all_declarations()
            .iter()
            .map(|d| d.name.as_str())
            .collect::<Vec<_>>(),
        ["😀", "'a\\b'", "\u{a0}x\u{a0}", "\u{2003}y"]
    );
    assert_eq!(store.all_declarations()[0].source_offset, 2);
    assert_eq!(store.all_references()[0].length, 1);
    assert_eq!(store.all_references()[1].length, 5);
    assert!(store.diagnostics().is_empty());
}

#[test]
fn effects_leave_scope_before_declaring_to_parent_and_keep_child_rule_captures_separate() {
    for mode in [ScopeMode::Lexical, ScopeMode::Dynamic] {
        let rules = vec![
            Rule {
                name: "outer",
                expression: Expr::Rule(1)
                    .then(Expr::literal("b").capture("name"))
                    .rule_effects(declares(Some(mode))),
            },
            Rule {
                name: "inner",
                expression: Expr::literal("a").capture("name"),
            },
        ];
        let tree = parse(&rules, 0, false, "ab").unwrap();
        assert_eq!(tree.scopes().current_scope_depth(), 0);
        assert_eq!(tree.scopes().all_declarations().len(), 1);
        assert!(tree.scopes().is_declared("b"));
        assert!(!tree.scopes().is_declared("a"));
    }
}

#[test]
fn undefined_references_warn_in_capture_order_and_tree_is_an_owned_snapshot() {
    let rules = vec![Rule {
        name: "refs",
        expression: Expr::literal("😀")
            .capture("noise")
            .then(Expr::literal(" z ").capture("name"))
            .then(Expr::literal("😀").capture("name"))
            .rule_effects(RuleEffects {
                backref: Some("name"),
                ..RuleEffects::default()
            }),
    }];
    let mut context = ParseContext::new("😀 z 😀");
    let matched = context.parse_grammar(rules, 0, false).unwrap();
    let tree = context.tree(matched.root_node().unwrap()).unwrap();
    context.scopes_mut().clear_diagnostics();
    drop(context);
    let warnings = tree.scopes().diagnostics();
    assert_eq!(warnings.len(), 2);
    assert_eq!((warnings[0].offset, warnings[0].length), (2, 1));
    assert_eq!((warnings[1].offset, warnings[1].length), (4, 1));
    assert_eq!(warnings[1].message, "未定義のシンボル: '😀'");
    assert_eq!(warnings[0].severity, Severity::Warning);
}

#[test]
fn child_failure_parent_failure_and_lookahead_restore_rule_effects() {
    let declaration = Expr::literal("a")
        .capture("name")
        .rule_effects(declares(Some(ScopeMode::Lexical)));
    let failed_child = Expr::literal("a")
        .then(Expr::literal("!"))
        .rule_effects(declares(Some(ScopeMode::Dynamic)));
    for parser in [failed_child, declaration.clone().then(Expr::literal("!"))] {
        let mut context = ParseContext::new("a");
        assert!(context.parse(&parser).is_err());
        assert_eq!(context.scopes().current_scope_depth(), 0);
        assert!(context.scopes().all_declarations().is_empty());
        assert_eq!(context.position(), 0);
    }
    let mut context = ParseContext::new("a");
    context.parse(&declaration.ahead()).unwrap();
    assert!(context.scopes().all_declarations().is_empty());
}
