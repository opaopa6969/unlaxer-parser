//! Native frontend side of the shared #1006 fixture; execution parity runs in
//! `SemanticPredicateConformanceTest -DrustConformance=true`.
use serde_json::Value;
use unlaxer_generator::{generate, validation};

fn rows(name: &str) -> Vec<Value> {
    let path = format!(
        "{}/../../unlaxer-dsl/src/test/resources/semantic-predicate/{name}",
        env!("CARGO_MANIFEST_DIR")
    );
    serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap()
}

#[test]
fn invalid_grammars_are_refused_with_the_java_code_before_generation() {
    let invalid = rows("invalid.json");
    assert_eq!(12, invalid.len(), "all invalid grammars execute");
    for row in invalid {
        let name = row["name"].as_str().unwrap();
        for separator in ["\n", "\r\n"] {
            let source = row["grammar"].as_str().unwrap().replace('\n', separator);
            let grammar = &unlaxer_ubnf::parse(&source).unwrap().grammars[0];
            let codes: Vec<_> = validation::collect(grammar)
                .into_iter()
                .map(|issue| issue.code)
                .collect();
            assert!(
                codes
                    .iter()
                    .any(|code| code == row["code"].as_str().unwrap()),
                "{name}: {codes:?}"
            );
            let error = generate(&source).unwrap_err();
            assert!(
                error.contains(row["nativeMessage"].as_str().unwrap()),
                "{name}: {error}"
            );
        }
    }
}

#[test]
fn valid_grammars_wrap_rules_and_expose_the_registry_entry() {
    let corpus = rows("corpus.json");
    assert_eq!(4, corpus.len());
    for fixture in corpus {
        let source = fixture["grammar"].as_str().unwrap();
        let grammar = &unlaxer_ubnf::parse(source).unwrap().grammars[0];
        assert!(validation::collect(grammar)
            .iter()
            .all(|issue| !issue.code.starts_with("E-PREDICATE")));
        let files = generate(source).unwrap();
        let parser = &files
            .iter()
            .find(|file| file.relative_path == "parser.rs")
            .unwrap()
            .content;
        assert!(parser.contains("Expr::SemanticPredicateScope { child: Box::new("));
        assert!(parser.contains("Expr::SemanticPredicate { child: Box::new("));
        assert!(parser.contains("pub fn parse_tree_detailed_with_semantic_predicates("));
    }
}

#[test]
fn grammars_without_the_annotation_keep_their_generated_parser() {
    let source = "grammar Plain { @package: org.example.plain @root @mapping(Result, params=[value]) Start ::= 'x' @value; }";
    let parser = generate(source)
        .unwrap()
        .into_iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap()
        .content;
    assert!(!parser.contains("SemanticPredicate"));
    assert!(!parser.contains("semantic_predicates"));
}
