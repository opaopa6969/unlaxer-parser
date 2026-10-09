use unlaxer_ubnf::{lexical::Op, parse, DiagnosticKind, TokenKind};

#[test]
fn supplementary_bounds_and_bmp_bounds_are_exact_scalars() {
    for version in ["v2", "2"] {
        let file = parse(&format!(r"grammar G {{ @ubnf: {version} token T = CHAR_RANGE('\u{{1F600}}','\u{{10FFFF}}') @root Root ::= T; }}")).unwrap();
        let TokenKind::Declarative { expression } = &file.grammars[0].tokens[0].kind else {
            panic!("supplementary range must retain scalar bounds");
        };
        assert_eq!(expression.op, Op::RANGE);
        assert_eq!((expression.min, expression.max), (0x1f600, 0x10ffff));
        let file = parse(&format!(r"grammar G {{ @ubnf: {version} token T = CHAR_RANGE('\u0000','\u0001') @root Root ::= T; }}")).unwrap();
        assert!(matches!(
            file.grammars[0].tokens[0].kind,
            TokenKind::CharRange {
                min: '\0',
                max: '\u{1}'
            }
        ));
    }
}

#[test]
fn unicode_escapes_are_limited_to_v2_character_classes() {
    for version in ["", "@ubnf: v1", "@ubnf: v2"] {
        let file = parse(&format!(
            r"grammar G {{ {version} token T = UNTIL('\u0041') @root Root ::= T; }}"
        ))
        .unwrap();
        assert!(
            matches!(&file.grammars[0].tokens[0].kind, TokenKind::Until { terminator } if terminator == r"\u0041")
        );
        let file = parse(&format!(
            r"grammar G {{ {version} token T = NEGATION('\u0041') @root Root ::= T; }}"
        ))
        .unwrap();
        let expected = if version == "@ubnf: v2" {
            "A"
        } else {
            r"\u0041"
        };
        assert!(
            matches!(&file.grammars[0].tokens[0].kind, TokenKind::Negation { excluded_chars } if excluded_chars == expected)
        );
    }
    let raw =
        parse(r"grammar G { @ubnf: v2 token T = NEGATION('\😀') @root Root ::= T; }").unwrap();
    assert!(
        matches!(&raw.grammars[0].tokens[0].kind, TokenKind::Negation { excluded_chars } if excluded_chars == r"\😀")
    );
    let file =
        parse(r"grammar G { @ubnf: v2 token T = NEGATION('\\u0041') @root Root ::= T; }").unwrap();
    assert!(
        matches!(&file.grammars[0].tokens[0].kind, TokenKind::Negation { excluded_chars } if excluded_chars == r"\u0041")
    );
}

#[test]
fn malformed_escapes_have_specific_codes_without_version_fallback() {
    for (literal, code) in [
        (r"'\u123'", "E-TOKEN-ESCAPE-LENGTH"),
        (r"'\u{}'", "E-TOKEN-ESCAPE-LENGTH"),
        (r"'\u{1234567}'", "E-TOKEN-ESCAPE-LENGTH"),
        (r"'\u00G0'", "E-TOKEN-ESCAPE-HEX"),
        (r"'\u{110000}'", "E-TOKEN-ESCAPE-RANGE"),
        (r"'\uD800'", "E-TOKEN-ESCAPE-SURROGATE"),
    ] {
        let source =
            format!("grammar G {{ @ubnf: v2 token T = NEGATION({literal}) @root Root ::= T; }}");
        let error = parse(&source).unwrap_err();
        assert_eq!(error.kind, DiagnosticKind::InvalidValue);
        assert!(error.message.starts_with(code));
        assert_eq!(&source[error.span.byte_start..error.span.byte_end], literal);
    }
}
