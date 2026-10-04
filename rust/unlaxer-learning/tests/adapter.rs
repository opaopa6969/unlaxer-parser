use unlaxer_learning::LessonGrammar;

#[test]
fn unicode_source_ranges_and_trailing_input_are_preserved() {
    let grammar = LessonGrammar::compile(
        "grammar Demo { @root @mapping(Value, params=[value]) Root ::= '😀' @value; }",
    )
    .unwrap();
    let accepted = grammar.analyze("😀");
    assert!(accepted.contains(r#""span":[0,1]"#), "{accepted}");
    assert!(accepted.contains(r#""value":"😀""#), "{accepted}");
    let rejected = grammar.analyze("😀x");
    assert!(rejected.contains(r#""ok":false"#), "{rejected}");
    assert!(rejected.contains(r#""offset":1"#), "{rejected}");
}

#[test]
fn invalid_and_unsupported_grammars_never_fall_back_to_another_parser() {
    for source in [
        "grammar Demo { @root Root ::= Root; }",
        "grammar Demo { @root Root ::= Missing; }",
        "grammar Demo { @root Root ::= ANY; }",
        "grammar Demo { import x from 'missing.ubnf'; @root Root ::= x.Value; }",
        "grammar Demo { token T = org.unlaxer.parser.elementary.NumberParser @root Root ::= T; }",
        "grammar Demo { @root Root ::= 'x'; } grammar Other { Root ::= 'y'; }",
    ] {
        assert!(LessonGrammar::compile(source).is_err(), "{source}");
    }
    assert!(LessonGrammar::compile(&" ".repeat(16385)).is_err());
}

#[test]
fn mapping_and_runtime_failures_are_separate_from_rejection() {
    let grammar = LessonGrammar::compile("grammar Demo { @root @skip Root ::= 'x'; }").unwrap();
    let result = grammar.analyze("x");
    assert!(result.contains(r#""ok":true"#), "{result}");
    assert!(result.contains("mappingError"), "{result}");
    assert!(grammar.analyze(&"x".repeat(8193)).contains("runtimeError"));
}
