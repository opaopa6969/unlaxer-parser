const VALID: &str = r#"
grammar Predictive {
  @root @mapping(Value, params=[value]) @predictiveChoice
  Root ::= Alpha @value | Beta @value ;
  Alpha ::= 'alpha' ;
  Beta ::= 'beta' ;
}
"#;

#[test]
fn native_frontend_emits_conservative_first_predictors() {
    let files = unlaxer_generator::generate(VALID).unwrap();
    let parser = files
        .iter()
        .find(|file| file.relative_path.ends_with("parser.rs"))
        .unwrap();
    assert!(parser.content.contains("Expr::PredictiveChoice {"));
    assert!(parser
        .content
        .contains("unlaxer_runtime::Predictor::Literal(\"alpha\")"));
    assert!(parser
        .content
        .contains("unlaxer_runtime::Predictor::Literal(\"beta\")"));
}

#[test]
fn native_frontend_emits_lexical_token_predictors() {
    let source = r#"
grammar LexicalPredictive {
  token NUMBER = NumberParser
  token ID = IdentifierParser
  token SQ = SingleQuotedParser
  token DQ = DoubleQuotedParser
  @root @mapping(Value, params=[value]) @predictiveChoice
  Root ::= NUMBER @value | ID @value | SQ @value | DQ @value ;
}
"#;
    let files = unlaxer_generator::generate(source).unwrap();
    let parser = files
        .iter()
        .find(|file| file.relative_path.ends_with("parser.rs"))
        .unwrap();
    for predictor in [
        "Predictor::Number",
        "Predictor::Identifier",
        "Predictor::Quoted('\\u{27}')",
        "Predictor::Quoted('\\u{22}')",
    ] {
        assert!(parser.content.contains(predictor), "{predictor}");
    }
}

#[test]
fn native_frontend_flattens_and_deduplicates_shared_first_sets() {
    let source = r#"
grammar CanonicalPredictive {
  @root @mapping(Value, params=[value]) @predictiveChoice
  Root ::= Shared @value | 'z' @value ;
  Shared ::= Atoms | Atoms | Atoms ;
  Atoms ::= 'a' | 'b' | 'a' | 'b' ;
}
"#;
    let files = unlaxer_generator::generate(source).unwrap();
    let parser = files
        .iter()
        .find(|file| file.relative_path.ends_with("parser.rs"))
        .unwrap();
    assert_eq!(
        parser.content.matches("Predictor::Literal(\"a\")").count(),
        1
    );
    assert_eq!(
        parser.content.matches("Predictor::Literal(\"b\")").count(),
        1
    );
    assert!(!parser
        .content
        .contains("OneOf(vec![unlaxer_runtime::Predictor::OneOf"));
}

#[test]
fn native_frontend_bounds_large_first_sets_with_any() {
    let atoms = (0..65)
        .map(|index| format!("'k{index}'"))
        .collect::<Vec<_>>()
        .join(" | ");
    let source = format!(
        "grammar BoundedPredictive {{\n\
           @root @mapping(Value, params=[value]) @predictiveChoice\n\
           Root ::= Atoms @value | 'z' @value ;\n\
           Atoms ::= {atoms} ;\n\
         }}"
    );
    let files = unlaxer_generator::generate(&source).unwrap();
    let parser = files
        .iter()
        .find(|file| file.relative_path.ends_with("parser.rs"))
        .unwrap();
    assert!(parser
        .content
        .contains("predictors: vec![unlaxer_runtime::Predictor::Any"));
    assert_eq!(parser.content.matches("Predictor::Literal(\"k").count(), 0);
}

#[test]
fn native_frontend_falls_back_for_nullable_and_recursive_first_paths() {
    let nullable = VALID.replace("Alpha ::= 'alpha' ;", "Alpha ::= [ 'alpha' ] ;");
    let files = unlaxer_generator::generate(&nullable).unwrap();
    let parser = files
        .iter()
        .find(|file| file.relative_path.ends_with("parser.rs"))
        .unwrap();
    assert!(parser
        .content
        .contains("predictors: vec![unlaxer_runtime::Predictor::Any"));
}

#[test]
fn native_frontend_rejects_invalid_predictive_choice_annotations() {
    for (source, expected) in [
        (
            VALID.replace("Alpha @value | Beta @value", "Alpha @value"),
            "requires multiple alternatives",
        ),
        (
            VALID.replace("@predictiveChoice", "@predictiveChoice @predictiveChoice"),
            "duplicate @predictiveChoice",
        ),
        (
            VALID.replace("@predictiveChoice", "@longestChoice @predictiveChoice"),
            "conflicts with associativity/@longestChoice",
        ),
    ] {
        let error = unlaxer_generator::generate(&source).unwrap_err();
        assert!(error.contains(expected), "{error}");
    }
}
