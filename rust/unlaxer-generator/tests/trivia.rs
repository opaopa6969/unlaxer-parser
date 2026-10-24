use unlaxer_codegen::ir::Expression;

fn lower(settings: &str, root: &str, child: &str) -> unlaxer_codegen::ir::GrammarIr {
    let source = format!("grammar G {{ {settings} @root @mapping(Root, params=[value]) {root} Start ::= Child @value ; {child} Child ::= 'a' 'b' ; }}");
    let file = unlaxer_ubnf::parse(&source).unwrap();
    unlaxer_generator::lowering::lower(&file.grammars[0]).unwrap()
}

#[test]
fn each_rule_resolves_trivia_against_grammar_default() {
    for (settings, root, child, policies) in [
        ("", "@whitespace", "", [true, false]),
        (
            "@whitespace: javaStyle",
            "@whitespace(none)",
            "",
            [false, true],
        ),
        (
            "@whitespace: none",
            "",
            "@interleave(profile=commentsAndSpaces)",
            [false, true],
        ),
        (
            "",
            "@interleave(profile=javaStyle) @whitespace(none)",
            "@whitespace(JAVASTYLE)",
            [false, true],
        ),
    ] {
        let ir = lower(settings, root, child);
        for (rule, expected) in ir.rules.iter().zip(policies) {
            assert!(
                matches!(&rule.body, Expression::TriviaScope { java_whitespace, .. } if *java_whitespace == expected)
            );
        }
        unlaxer_codegen::generate(&ir).unwrap();
    }
    for settings in ["", "@whitespace: javaStyle", "@whitespace: NONE"] {
        assert!(lower(settings, "", "")
            .rules
            .iter()
            .all(|rule| !matches!(rule.body, Expression::TriviaScope { .. })));
    }
}

#[test]
fn invalid_and_duplicate_trivia_policies_are_not_silently_ignored() {
    for annotation in [
        "@whitespace(custom)",
        "@whitespace @whitespace(none)",
        "@interleave(profile=custom)",
        "@interleave(profile=JAVASTYLE)",
        "@interleave(profile=javaStyle) @interleave(profile=commentsAndSpaces)",
    ] {
        let source = format!("grammar G {{ @root @mapping(Root, params=[value]) {annotation} Start ::= 'a' @value ; }}");
        assert!(
            unlaxer_generator::generate(&source).is_err(),
            "{annotation}"
        );
    }
    assert!(unlaxer_generator::generate("grammar G { @whitespace: none @whitespace: javaStyle @root @mapping(Root, params=[value]) Start ::= 'a' @value ; }").is_err());
}

#[test]
fn scoped_rules_still_reject_nullable_repeats_and_left_recursion() {
    for body in ["{ [ 'a' ] }", "Start 'a'"] {
        let source = format!(
            "grammar G {{ @root @mapping(Root, params=[]) @whitespace Start ::= {body} ; }}"
        );
        assert!(unlaxer_generator::generate(&source).is_err(), "{body}");
    }
}

#[test]
fn global_comment_is_only_the_inherited_default() {
    let scopes = |settings: &str, root: &str| {
        lower(&format!("@comment: {{ line: '//' }} {settings}"), root, "")
            .rules
            .iter()
            .map(|rule| match &rule.body {
                Expression::TriviaScope {
                    java_whitespace,
                    line_comments,
                    ..
                } => Some((*java_whitespace, *line_comments)),
                _ => None,
            })
            .collect::<Vec<_>>()
    };
    // A rule-local setting elsewhere must not widen the inherited comment-only policy.
    for (settings, root, expected) in [
        ("", "", [Some((false, true)), Some((false, true))]),
        (
            "@whitespace: NONE",
            "",
            [Some((false, true)), Some((false, true))],
        ),
        (
            "",
            "@whitespace",
            [Some((true, false)), Some((false, true))],
        ),
        (
            "",
            "@whitespace(none)",
            [Some((false, false)), Some((false, true))],
        ),
        (
            "",
            "@interleave(profile=commentsAndSpaces)",
            [Some((true, false)), Some((false, true))],
        ),
        ("@whitespace: javaStyle", "", [None, None]),
    ] {
        assert_eq!(
            expected.to_vec(),
            scopes(settings, root),
            "{settings} {root}"
        );
    }
    assert!(
        unlaxer_codegen::generate(&lower("@comment: { line: '//' }", "", ""))
            .unwrap()
            .iter()
            .any(|file| file.content.contains(".line_comment_scope()"))
    );
    for comment in [
        "@comment: { line: '#' }",
        "@comment: { line: '//' } @comment: { line: '//' }",
        "@comment: slashes",
        "@ubnf: v2 @whitespace: GAP @comment: { line: '//' } token GAP ::= ' '+;",
    ] {
        let source = format!(
            "grammar G {{ {comment} @root @mapping(Root, params=[value]) Start ::= 'a' @value ; }}"
        );
        let error = unlaxer_generator::generate(&source).unwrap_err();
        assert!(error.contains("E-COMMENT-"), "{comment}: {error}");
    }
}
