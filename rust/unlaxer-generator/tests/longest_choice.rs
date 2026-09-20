const VALID: &str = r#"
grammar Longest {
  @root @mapping(Value, params=[value]) @longestChoice
  Root ::= 'a' @value | 'abc' @value ;
}
"#;

#[test]
fn native_frontend_emits_longest_choice() {
    let files = unlaxer_generator::generate(VALID).unwrap();
    let parser = files
        .iter()
        .find(|file| file.relative_path.ends_with("parser.rs"))
        .unwrap();
    assert!(parser.content.contains("Expr::LongestChoice(vec!["));
}

#[test]
fn native_frontend_projects_mixed_values_inside_longest_choice() {
    let source = r#"
grammar MixedLongest {
  @root @mapping(Root, params=[value]) @longestChoice
  Start ::= 'a' @value | Child @value ;
  @mapping(Child, params=[text]) Child ::= 'abc' @text ;
}
"#;
    let files = unlaxer_generator::generate(source).unwrap();
    let parser = files
        .iter()
        .find(|file| file.relative_path.ends_with("parser.rs"))
        .unwrap();
    assert!(parser.content.contains("Expr::LongestChoice(vec!["));
    assert!(parser.content.contains(".text_value()"));
}

#[test]
fn native_frontend_rejects_invalid_longest_choice_annotations() {
    for (source, expected) in [
        (
            VALID.replace("'a' @value | 'abc' @value", "'a' @value"),
            "requires multiple alternatives",
        ),
        (
            VALID.replace("@longestChoice", "@longestChoice @longestChoice"),
            "duplicate @longestChoice",
        ),
        (
            VALID.replace("@longestChoice", "@leftAssoc @longestChoice"),
            "conflicts with associativity",
        ),
        (
            VALID.replace("@longestChoice", "@rightAssoc @longestChoice"),
            "conflicts with associativity",
        ),
    ] {
        let error = unlaxer_generator::generate(&source).unwrap_err();
        assert!(error.contains(expected), "{error}");
    }
}
