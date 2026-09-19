use std::path::Path;
use unlaxer_ubnf::*;

fn fixtures(dir: &str) -> impl Iterator<Item = std::path::PathBuf> {
    std::fs::read_dir(
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures")
            .join(dir),
    )
    .unwrap()
    .map(|e| e.unwrap().path())
}

#[test]
fn all_declared_syntax_and_self_hosting_grammar_parse_without_java() {
    for path in fixtures("positive").chain(fixtures("known")) {
        let source = std::fs::read_to_string(&path).unwrap();
        parse(&source).unwrap_or_else(|e| panic!("{}: {e}", path.display()));
    }
    let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    for name in [
        "unlaxer-dsl/grammar/ubnf.ubnf",
        "unlaxer-dsl/tinycalc-vscode/grammar/tinycalc.ubnf",
        "unlaxer-dsl/src/test/resources/cardinality/Cardinality.ubnf",
        "unlaxer-dsl/src/test/resources/evolution/0/Evolution.ubnf",
        "unlaxer-dsl/src/test/resources/evolution/1/Evolution.ubnf",
        "unlaxer-dsl/src/test/resources/evolution/2/Evolution.ubnf",
        "unlaxer-dsl/src/test/resources/evolution/3/Evolution.ubnf",
    ] {
        parse(&std::fs::read_to_string(repo.join(name)).unwrap())
            .unwrap_or_else(|e| panic!("{name}: {e}"));
    }
}

#[test]
fn owned_values_and_all_token_kinds_are_preserved() {
    let source = include_str!("fixtures/positive/all-tokens.ubnf").to_owned();
    let ast = parse(&source).unwrap();
    drop(source);
    assert_eq!(ast.grammars.len(), 2);
    let g = &ast.grammars[0];
    assert_eq!(g.imports[0].alias, "json");
    assert_eq!(g.imports[0].path, "json.ubnf");
    assert_eq!(g.tokens.len(), 11);
    assert!(
        matches!(&g.tokens[2].kind,TokenKind::Negation{excluded_chars} if excluded_chars=="😀\n\t")
    );
    assert!(matches!(&g.tokens[10].kind,TokenKind::Regex{pattern} if pattern==r"\d+\.\d+"));
    let e = &ast.grammars[1].rules[0].body.alternatives[0].elements[0].element;
    assert_eq!(e.kind, ElementKind::Terminal("😀\n\t\r'\\\\q".to_owned()));
}

#[test]
fn keyword_boundaries_and_known_java_mapping_errors_do_not_lose_data() {
    let ast = parse(include_str!("fixtures/known/keyword-boundary.ubnf")).unwrap();
    assert_eq!(
        ast.grammars[0].rules[0].annotations[0].kind,
        AnnotationKind::Simple {
            name: "rooted".to_owned()
        }
    );
    let ast = parse(include_str!("fixtures/known/eval-default.ubnf")).unwrap();
    let AnnotationKind::Eval {
        strategy, params, ..
    } = &ast.grammars[0].rules[0].annotations[0].kind
    else {
        panic!()
    };
    assert_eq!(strategy, "default");
    assert_eq!(params[0].key, "strip_prefix");
    assert_eq!(params[0].value, "$");
    let ast = parse(include_str!("fixtures/known/namespace.ubnf")).unwrap();
    assert_eq!(
        ast.grammars[0].rules[0].body.alternatives[0].elements[0]
            .element
            .kind,
        ElementKind::RuleRef {
            namespace: Some("a.b".to_owned()),
            name: "Value".to_owned()
        }
    );
}

#[test]
fn spans_are_utf8_and_codepoint_ranges_and_diagnostics_use_crlf_lines() {
    let source = "//😀\r\ngrammar G {\r\n Root ::= '😀' @value;\r\n}";
    let ast = parse(source).unwrap();
    let e = &ast.grammars[0].rules[0].body.alternatives[0].elements[0];
    assert_eq!(
        &source[e.element.span.byte_start..e.element.span.byte_end],
        "'😀'"
    );
    assert_eq!(
        e.element.span.codepoint_end - e.element.span.codepoint_start,
        3
    );
    assert_eq!(e.element.span.byte_end - e.element.span.byte_start, 6);
    assert_eq!(
        e.span.codepoint_start,
        source[..e.span.byte_start].chars().count()
    );
    let broken = source.replace("'😀' @value;", "'😀' @value");
    let error = parse(&broken).unwrap_err();
    assert_eq!((error.line, error.column), (4, 1));
    assert_eq!(&broken[error.span.byte_start..error.span.byte_end], "}");
    let error = parse("grammar G { Root ::= '😀'; } !").unwrap_err();
    assert_eq!(error.span.byte_start - error.span.codepoint_start, 3);
    assert_eq!(error.span.codepoint_end - error.span.codepoint_start, 1);
}

#[test]
fn malformed_or_unknown_constructs_and_overflow_are_explicit_errors() {
    for line in include_str!("fixtures/negative.tsv").lines() {
        let (name, source) = line.split_once('\t').unwrap_or((line, ""));
        parse(source).expect_err(name);
    }
    for source in [
        "",
        "grammar G {}",
        "grammar G { R ::= ; }",
        "grammar G { R ::= 'a' | ; }",
        "grammar G { R ::= (); }",
        "grammar G { R ::= 'unterminated; }",
        "grammar G { R ::= \"x\"; }",
        "grammar G { R ::= 'x' }",
        "grammar G { R ::= 'x'; } garbage",
        "grammar G { /* no */ R ::= 'x'; }",
        "grammar G { @unknown(x) R ::= 'x'; }",
        "grammar G { token T=UNKNOWN('x') R ::= T; }",
        "grammar G { @mapping(X,params=[]) R ::= 'x'; }",
        "grammar G { @eval(kind='x',kind='y') R ::= 'x'; }",
        "grammar G { @recovery(magic) R ::= 'x'; }",
        "grammar G { @precedence(level=2147483648) R ::= 'x'; }",
        "grammar G { R ::= 'x'{2147483648}; }",
        "grammar G { R ::= 'x'+?; }",
        "grammar G { token T=CHAR_RANGE('','a') R ::= T; }",
        "grammar G { token T=CHAR_RANGE('ab','z') R ::= T; }",
        "grammar G { token T=CHAR_RANGE('😀','😇') R ::= T; }",
        "grammar G { token T=CHAR_RANGE('z','a') R ::= T; }",
        "grammar G { R ::= 'x'; token T=ANY }",
        "grammar G { R ::= 'x'; @import a from 'b' }",
    ] {
        let e = parse(source).expect_err(source);
        assert!(!e.message.is_empty());
        assert!(e.span.byte_end <= source.len());
        assert!(source.is_char_boundary(e.span.byte_start));
    }
}

#[test]
fn bare_constructor_names_remain_available_as_external_parser_classes() {
    for name in [
        "UNTIL",
        "NEGATION",
        "LOOKAHEAD",
        "NEGATIVE_LOOKAHEAD",
        "CI",
        "REGEX",
        "CHAR_RANGE",
    ] {
        let ast = parse(&format!("grammar G {{ token T={name} R ::= T; }}")).unwrap();
        assert_eq!(
            ast.grammars[0].tokens[0].kind,
            TokenKind::Simple {
                parser_class: name.to_owned()
            }
        );
    }
}

#[test]
fn boundary_trivia_and_annotations_preserve_each_independent_name() {
    for separator in [" ", "\t", "\n", "\r", "\r\n", "//comment😀\n"] {
        let ast = parse(&format!("grammar G {{ R ::= T{separator}Root; }}")).unwrap();
        let elements = &ast.grammars[0].rules[0].body.alternatives[0].elements;
        assert_eq!(elements.len(), 2);
        assert_eq!(
            elements[0].element.kind,
            ElementKind::RuleRef {
                namespace: None,
                name: "T".to_owned()
            }
        );
        assert_eq!(
            elements[1].element.kind,
            ElementKind::RuleRef {
                namespace: None,
                name: "Root".to_owned()
            }
        );
    }
    let ast = parse(include_str!("fixtures/known/common-field-space.ubnf")).unwrap();
    assert_eq!(
        ast.grammars[0].rules[0].annotations[0].kind,
        AnnotationKind::CommonField {
            fields: vec!["left".to_owned(), "right".to_owned()]
        }
    );
}

#[test]
fn error_named_reference_before_group_is_not_forced_into_an_error_hint() {
    let ast = parse("grammar G { R ::= ERROR (Item) ERROR ('a' 'b') ERROR('message'); }").unwrap();
    let elements = &ast.grammars[0].rules[0].body.alternatives[0].elements;
    assert_eq!(elements.len(), 5);
    assert!(matches!(&elements[0].element.kind,ElementKind::RuleRef{name,..} if name=="ERROR"));
    assert!(matches!(&elements[2].element.kind,ElementKind::RuleRef{name,..} if name=="ERROR"));
    assert_eq!(
        elements[4].element.kind,
        ElementKind::Error("message".to_owned())
    );
}

#[test]
fn nesting_limit_is_a_diagnostic_not_a_stack_overflow() {
    let source = format!(
        "grammar G {{ R ::= {}'x'{}; }}",
        "(".repeat(MAX_NESTING + 1),
        ")".repeat(MAX_NESTING + 1)
    );
    assert_eq!(
        parse(&source).unwrap_err().kind,
        DiagnosticKind::NestingLimit
    );
}

#[test]
fn typeof_is_not_accidentally_the_previous_elements_capture() {
    for source in [
        "grammar G { R ::= A @typeof(a) B; }",
        "grammar G { R ::= A @a @typeof(a) B @b; }",
    ] {
        let ast = parse(source).unwrap();
        let elements = &ast.grammars[0].rules[0].body.alternatives[0].elements;
        assert_eq!(elements.len(), 2);
        assert_eq!(elements[1].typeof_constraint.as_deref(), Some("a"));
    }
}
