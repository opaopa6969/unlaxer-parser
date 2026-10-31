use unlaxer_ubnf::{ast::AnnotationKind, parse};

#[test]
fn semantic_predicate_is_a_named_rule_annotation() {
    for ending in ["\n", "\r\n"] {
        let source = format!("grammar G {{{ending}@predicate('valid'){ending}R ::= 'x';{ending}}}");
        let file = parse(&source).unwrap();
        assert!(matches!(&file.grammars[0].rules[0].annotations[0].kind,
            AnnotationKind::Predicate { name } if name == "valid"));
    }
    // Names are semantic validation, so both frontends preserve invalid names here.
    for name in ["", "1bad", "a b"] {
        let file = parse(&format!("grammar G {{ @predicate('{name}') R ::= 'x'; }}")).unwrap();
        assert!(matches!(&file.grammars[0].rules[0].annotations[0].kind,
            AnnotationKind::Predicate { name: actual } if actual == name));
    }
}

#[test]
fn predicate_keyword_requires_exactly_one_quoted_argument() {
    for arguments in ["", "()", "(valid)", "('x','y')", "(name='x')"] {
        assert!(parse(&format!("grammar G {{ @predicate{arguments} R ::= 'x'; }}")).is_err());
    }
    let file = parse("grammar G { @predicateExtra R ::= 'x'; }").unwrap();
    assert!(matches!(&file.grammars[0].rules[0].annotations[0].kind,
        AnnotationKind::Simple { name } if name == "predicateExtra"));
}
