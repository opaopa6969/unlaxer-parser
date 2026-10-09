#[test]
fn programmatic_format_two_range_rejects_the_surrogate_interval() {
    let mut legacy =
        unlaxer_ubnf::parse("grammar G { token T = CHAR_RANGE('a','') @root @mapping(Value,params=[text]) Root ::= T @text; }")
            .unwrap()
            .grammars
            .remove(0);
    assert!(unlaxer_generator::lowering::lower(&legacy).is_ok());
    legacy.settings = unlaxer_ubnf::parse("grammar V { @ubnf: v2 @root Root ::= 'v'; }")
        .unwrap()
        .grammars
        .remove(0)
        .settings;
    let error = unlaxer_generator::lowering::lower(&legacy).unwrap_err();
    assert!(error.starts_with("E-TOKEN-RANGE-SURROGATE"));
}
