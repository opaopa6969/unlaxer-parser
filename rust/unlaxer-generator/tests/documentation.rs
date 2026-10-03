use unlaxer_generator::{generate, lowering, portability};

#[test]
fn ordered_unicode_docs_are_metadata_not_recognition() {
    let source = r#"grammar Docs {
        @root @mapping(Value, params=[text])
        @doc('最初 😀') @doc('quote \' and newline\n')
        Start ::= 'hello' @text;
    }"#;
    assert!(portability::check(source).portable);
    let parsed = unlaxer_ubnf::parse(source).unwrap();
    let ir = lowering::lower(&parsed.grammars[0]).unwrap();
    assert_eq!(
        ir.rules[0].documentation,
        ["最初 😀", "quote ' and newline\n"]
    );
    let files = generate(source).unwrap();
    let parser = &files
        .iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap()
        .content;
    assert!(parser.contains("pub const RULE_DOCS"));
    assert!(parser.contains("最初 😀"));
    let plain =
        generate("grammar Docs { @root @mapping(Value, params=[text]) Start ::= 'hello' @text; }")
            .unwrap();
    for file in plain {
        let annotated = &files
            .iter()
            .find(|other| other.relative_path == file.relative_path)
            .unwrap()
            .content;
        if file.relative_path == "parser.rs" {
            assert!(annotated.starts_with(&file.content));
        } else {
            assert_eq!(&file.content, annotated);
        }
    }
}
