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

#[test]
fn mapping_time_constraints_are_not_silently_ignored_by_the_teaching_subset() {
    let source = "grammar Demo { @root @mapping(Pair,params=[left,right]) Root ::= Leaf @left '=' @typeof(left) 'x' @right; @mapping(Leaf) Leaf ::= 'x'; }";
    let failure = match LessonGrammar::compile(source) {
        Ok(_) => {
            panic!("@typeof must use the generated mapper, not an unchecked teaching projection")
        }
        Err(error) => error,
    };
    assert!(failure.contains("@typeof"), "{failure}");
    assert!(failure.contains("mapper"), "{failure}");
}

#[test]
fn generated_enum_corpus_is_rejected_at_the_lesson_compile_boundary() {
    let corpus: serde_json::Value = serde_json::from_str(include_str!(
        "../../../spec-corpus/enums/generated-cases.json"
    ))
    .unwrap();
    let mut grammars = corpus
        .as_array()
        .unwrap()
        .iter()
        .map(|row| row["grammar"].as_str().unwrap().to_owned())
        .collect::<Vec<_>>();
    grammars.extend([
        include_str!("../../../spec-corpus/mixed-enums/values.ubnf").to_owned(),
        include_str!("../../../spec-corpus/mixed-enums/roots.ubnf").to_owned(),
        include_str!("../../../spec-corpus/mixed-enums/shared.ubnf").to_owned(),
    ]);
    for grammar in grammars {
        // These are the same Java/Rust real compiler and mapper fixtures, not
        // invalid grammar used to justify an unrelated lesson rejection.
        assert_eq!(unlaxer_generator::generate(&grammar).unwrap().len(), 5);
        match LessonGrammar::compile(&grammar) {
            Ok(_) => panic!("enum silently admitted by the lesson projection: {grammar}"),
            Err(error) => assert!(error.contains("@enum") && error.contains("CLI"), "{error}"),
        }
    }
}
