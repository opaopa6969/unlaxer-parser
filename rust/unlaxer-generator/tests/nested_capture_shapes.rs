use unlaxer_codegen::ir::{Cardinality, Kind};

#[test]
fn capture_cardinality_counts_coexisting_sites_not_exclusive_alternatives() {
    for (body, expected) in [
        ("('a' @name 'b') @name", Cardinality::Many),
        ("'a' @name 'b' @name", Cardinality::Many),
        ("'a' @name | 'b' @name", Cardinality::One),
        ("'a' @name | 'b'", Cardinality::Optional),
        ("[ 'a' @name ]", Cardinality::Optional),
        ("[ 'a' @name ] @name", Cardinality::Many),
        ("{ 'a' @name } @name", Cardinality::Many),
        ("('a' @name)+ @name", Cardinality::Many),
        ("('a' @name){1,2} @name", Cardinality::Many),
        ("('a' @name) % ',' @name", Cardinality::Many),
    ] {
        let source =
            format!("grammar Shapes {{ @root @mapping(Item, params=[name]) Root ::= {body}; }}");
        let grammar = unlaxer_ubnf::parse(&source).unwrap();
        let ir = unlaxer_generator::lowering::lower(&grammar.grammars[0]).unwrap();
        let field = &ir.rules[0].mapping.as_ref().unwrap().fields[0];
        assert_eq!(field.kind, Kind::Text, "{body}");
        assert_eq!(field.cardinality, expected, "{body}");
        assert_eq!(unlaxer_generator::generate(&source).unwrap().len(), 5);
    }
}

#[test]
fn nested_node_and_mixed_captures_preserve_kind_and_shared_schema_join() {
    for (body, kind) in [
        ("(Atom @name) @name", Kind::Node),
        ("(Atom @name | 'x') @name", Kind::Value),
    ] {
        let source = format!(
            "grammar Shapes {{ @root @mapping(Item, params=[name]) Root ::= {body}; \
            @mapping(Atom) Atom ::= 'a'; }}"
        );
        let grammar = unlaxer_ubnf::parse(&source).unwrap();
        let ir = unlaxer_generator::lowering::lower(&grammar.grammars[0]).unwrap();
        let field = &ir.rules[0].mapping.as_ref().unwrap().fields[0];
        assert_eq!(field.kind, kind, "{body}");
        assert_eq!(field.cardinality, Cardinality::Many, "{body}");
    }
    let source = "grammar Shapes { @root Root ::= Many | One; \
        @mapping(Item, params=[name]) Many ::= ('a' @name 'b') @name; \
        @mapping(Item, params=[name]) One ::= 'x' @name 'y' @name; }";
    let grammar = unlaxer_ubnf::parse(source).unwrap();
    let ir = unlaxer_generator::lowering::lower(&grammar.grammars[0]).unwrap();
    for rule in &ir.rules[1..] {
        let field = &rule.mapping.as_ref().unwrap().fields[0];
        assert_eq!(field.kind, Kind::Text);
        assert_eq!(field.cardinality, Cardinality::Many);
    }
    let incompatible = source.replace("'x' @name 'y' @name", "'x' @name");
    let grammar = unlaxer_ubnf::parse(&incompatible).unwrap();
    assert!(unlaxer_generator::lowering::lower(&grammar.grammars[0])
        .unwrap_err()
        .contains("incompatible shared mapping schema"));
}
