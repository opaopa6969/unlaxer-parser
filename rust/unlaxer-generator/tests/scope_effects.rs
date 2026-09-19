use unlaxer_codegen::ir::{Expression, ScopeMode};

const GRAMMAR: &str = r#"grammar G {
    token ID = IdentifierParser
    @root @mapping(Root) @scopeTree(mode=dynamic)
    Root ::= Decl '|' Ref ;
    @declares(symbol=name, description=documentation)
    Decl ::= ID @noise ':' ID @name ;
    @backref(name=name)
    Ref ::= ID @name ;
}"#;

#[test]
fn annotations_preserve_metadata_and_allow_unmapped_named_captures() {
    let source = unlaxer_ubnf::parse(GRAMMAR).unwrap();
    let ir = unlaxer_generator::lowering::lower(&source.grammars[0]).unwrap();
    let Expression::RuleEffects { effects, .. } = &ir.rules[0].body else {
        panic!("missing root scope")
    };
    assert_eq!(effects.scope_mode, Some(ScopeMode::Dynamic));
    let Expression::RuleEffects { effects, .. } = &ir.rules[1].body else {
        panic!("missing declaration")
    };
    assert_eq!(effects.declares.as_ref().unwrap().symbol_capture, "name");
    assert_eq!(
        effects.declares.as_ref().unwrap().description.as_deref(),
        Some("documentation")
    );
    let files = unlaxer_codegen::generate(&ir).unwrap();
    let parser = &files
        .iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap()
        .content;
    assert!(parser.contains("Some(unlaxer_runtime::ScopeMode::Dynamic)"));
    assert!(parser.contains("description: Some(\"documentation\")"));
    assert!(parser.contains("backref: Some(\"name\")"));
    for name in ["_name", "self", "span", "semantics"] {
        let source = GRAMMAR
            .replace("symbol=name", &format!("symbol={name}"))
            .replace("name=name", &format!("name={name}"))
            .replace("@name", &format!("@{name}"));
        unlaxer_generator::generate(&source).unwrap();
    }
}

#[test]
fn invalid_metadata_and_structural_errors_remain_explicit() {
    for source in [
        GRAMMAR.replace("mode=dynamic", "mode=unknown"),
        GRAMMAR.replace(
            "@scopeTree(mode=dynamic)",
            "@scopeTree(mode=dynamic) @scopeTree(mode=lexical)",
        ),
        GRAMMAR.replace(
            "@declares(symbol=name, description=documentation)",
            "@declares(symbol=name) @declares(symbol=name)",
        ),
        GRAMMAR.replace(
            "@backref(name=name)",
            "@backref(name=name) @backref(name=name)",
        ),
        GRAMMAR.replace("symbol=name", "symbol=missing"),
        GRAMMAR.replace("@backref(name=name)", "@backref(name=missing)"),
        GRAMMAR.replace("@scopeTree(mode=dynamic)", ""),
        GRAMMAR.replace(
            "Decl ::= ID @noise ':' ID @name",
            "Decl ::= { [ ID ] } @name",
        ),
        GRAMMAR.replace("Decl ::= ID @noise ':' ID @name", "Decl ::= Decl @name"),
    ] {
        let ast = unlaxer_ubnf::parse(&source).unwrap();
        assert!(
            unlaxer_generator::lowering::lower(&ast.grammars[0]).is_err(),
            "{source}"
        );
    }
}
