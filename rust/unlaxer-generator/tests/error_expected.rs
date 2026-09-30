use unlaxer_codegen::ir::Expression;

fn lower(source: &str) -> unlaxer_codegen::GrammarIr {
    let grammar = unlaxer_ubnf::parse(source).unwrap();
    unlaxer_generator::lowering::lower(&grammar.grammars[0]).unwrap()
}

#[test]
fn error_element_lowers_as_always_failing_expected_hint() {
    let source = "grammar G { @root @mapping(Root) Root ::= 'ok' | ERROR('need 😀'); }";
    let ir = lower(source);
    assert!(matches!(
        &ir.rules[0].body,
        Expression::Choice(alternatives)
            if matches!(&alternatives[1], Expression::ErrorExpected(message) if message == "need 😀")
    ));
    let generated = unlaxer_generator::generate(source).unwrap();
    let parser = generated
        .iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap();
    assert!(parser.content.contains("Expr::Error(\"need 😀\")"));
    assert_eq!(
        unlaxer_generator::portability::check(source)
            .diagnostics
            .len(),
        0
    );
}

#[test]
fn error_element_preserves_empty_blank_and_escaped_messages() {
    for (source, message) in [
        ("grammar G { @root @mapping(Root) Root ::= ERROR(''); }", ""),
        (
            "grammar G { @root @mapping(Root) Root ::= ERROR(' \\t'); }",
            " \t",
        ),
        (
            "grammar G { @root @mapping(Root) Root ::= ERROR('bad 😀 \" newline\\n'); }",
            "bad 😀 \" newline\n",
        ),
    ] {
        let ir = lower(source);
        assert_eq!(
            ir.rules[0].body,
            Expression::Sequence(vec![Expression::ErrorExpected(message.into())])
        );
        assert!(unlaxer_generator::generate(source).is_ok());
    }
}

#[test]
fn error_element_is_not_nullable_and_recovery_is_supported() {
    let source = "grammar G { @root @mapping(Root) Root ::= (ERROR('no'))*; }";
    assert!(unlaxer_generator::generate(source).is_ok());
    let recovery = source.replace("@root", "@root @recovery(auto)");
    assert!(unlaxer_generator::portability::check(&recovery).portable);
    assert!(unlaxer_generator::generate(&recovery).is_ok());
}
