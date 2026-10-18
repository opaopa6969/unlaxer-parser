use unlaxer_generator::{lowering, portability};
use unlaxer_ubnf::{ast::TokenKind, parse};

#[test]
fn frontend_and_check_reject_retired_spelling_without_running_a_regex_engine() {
    for value in [
        "REGEX('a+')",
        "REGEX('[invalid')",
        "RegexTokenParser",
        "org.unlaxer.dsl.runtime.RegexTokenParser",
    ] {
        let source = format!("grammar G {{ // 😀\r\n token T = {value} @root Root ::= T; }}");
        let error = parse(&source).unwrap_err();
        assert!(error.message.starts_with("E-REGEX-RETIRED:"), "{error}");
        assert_eq!(source.find(value).unwrap(), error.span.byte_start);
        assert_eq!(
            source[..error.span.byte_start].chars().count(),
            error.span.codepoint_start
        );
        assert_eq!((2, 12), (error.line, error.column));
        let checked = portability::check(&source);
        assert_eq!(checked.diagnostics[0].code, "E-REGEX-RETIRED");
        assert_eq!(checked.diagnostics[0].span, Some(error.span));
        assert!(unlaxer_generator::generate(&source).is_err());
    }
}

#[test]
fn manually_constructed_ast_cannot_reintroduce_regex_execution() {
    let mut grammar = parse("grammar G { token T=ANY @root Root ::= T; }")
        .unwrap()
        .grammars
        .remove(0);
    for kind in [
        TokenKind::Regex {
            pattern: "a+".into(),
        },
        TokenKind::Simple {
            parser_class: "RegexTokenParser".into(),
        },
        TokenKind::Simple {
            parser_class: "org.unlaxer.dsl.runtime.RegexTokenParser".into(),
        },
    ] {
        grammar.tokens[0].kind = kind;
        assert!(lowering::lower(&grammar)
            .unwrap_err()
            .starts_with("E-REGEX-RETIRED:"));
    }
}

#[test]
fn user_token_named_regex_is_still_a_declarative_program() {
    let source =
        "grammar G { @ubnf: v2 // REGEX('old')\n token REGEX ::= 'REGEX'; @root @mapping(Value,params=[value]) Root ::= REGEX @value; }";
    unlaxer_generator::generate(source).unwrap();
}
