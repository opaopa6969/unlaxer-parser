use serde_json::Value;
use unlaxer_generator::{adapters::feature_diagnostics, generate, portability, validation};

#[test]
fn feature_and_lexical_diagnostics_match_independent_oracle() {
    let cases: Vec<Value> = serde_json::from_str(include_str!(
        "../../../spec-corpus/feature-diagnostics/cases.json"
    ))
    .unwrap();
    assert_eq!(31, cases.len(), "all authored cases execute");
    for case in cases {
        for (ending, separator) in [("LF", "\n"), ("CRLF", "\r\n")] {
            let source = case["source"].as_str().unwrap().replace('\n', separator);
            let grammar = &unlaxer_ubnf::parse(&source).unwrap().grammars[0];
            let features: Vec<_> = feature_diagnostics(grammar)
                .into_iter()
                .map(|d| format!("{}: {}", d.code, d.subject))
                .collect();
            assert_eq!(
                case["features"],
                serde_json::json!(features),
                "{}",
                case["name"]
            );
            let authoring: Vec<_> = validation::collect(grammar)
                .into_iter()
                .filter(|d| d.code.starts_with("E-FEATURE-"))
                .map(|d| d.message)
                .collect();
            assert_eq!(features, authoring, "{}", case["name"]);
            assert_eq!(
                case["expected"][ending],
                serde_json::from_str::<Value>(&portability::check(&source).to_json()).unwrap(),
                "{} {ending}",
                case["name"]
            );
            if let Some(first) = case["first"].as_str() {
                assert_eq!(first, generate(&source).unwrap_err(), "{}", case["name"]);
            } else {
                assert_eq!(5, generate(&source).unwrap().len(), "{}", case["name"]);
            }
        }
    }
}

#[test]
fn imported_lexical_diagnostics_match_the_same_java_messages() {
    let cases: Vec<Value> = serde_json::from_str(include_str!(
        "../../../spec-corpus/feature-diagnostics/modules.json"
    ))
    .unwrap();
    assert_eq!(8, cases.len());
    let directory = std::env::temp_dir().join(format!("feature-modules-{}", std::process::id()));
    std::fs::create_dir_all(&directory).unwrap();
    let root = directory.join("main.ubnf");
    std::fs::write(
        &root,
        "grammar G { @import m from 'child.ubnf' @ubnf: v2 @root @mapping(Root) Start ::= 'x'; }",
    )
    .unwrap();
    for case in cases {
        for ending in ["\n", "\r\n"] {
            std::fs::write(
                directory.join("child.ubnf"),
                case["source"].as_str().unwrap().replace('\n', ending),
            )
            .unwrap();
            assert_eq!(
                case["first"].as_str().unwrap(),
                unlaxer_generator::generate_file(&root).unwrap_err(),
                "{}",
                case["name"]
            );
            let expected = serde_json::json!({"schemaVersion":1,"target":"rust","portable":false,
                "structure":"blocked","diagnostics":[{"code":"P-MODULE","severity":"error",
                "span":null,"subject":"UBNF module resolution"}]});
            assert_eq!(
                expected,
                serde_json::from_str::<Value>(&portability::check_file(&root).to_json()).unwrap(),
                "{}",
                case["name"]
            );
        }
    }
    std::fs::remove_dir_all(directory).unwrap();
}

#[test]
fn lexical_context_preflight_keeps_the_java_lexical_prefix() {
    let cases: Vec<Value> = serde_json::from_str(include_str!(
        "../../../spec-corpus/feature-diagnostics/lexical-contexts.json"
    ))
    .unwrap();
    assert_eq!(6, cases.len(), "all lexical-context cases execute");
    for case in cases {
        for (ending, separator) in [("LF", "\n"), ("CRLF", "\r\n")] {
            let source = case["source"].as_str().unwrap().replace('\n', separator);
            assert_eq!(
                case["expected"][ending],
                serde_json::from_str::<Value>(&portability::check(&source).to_json()).unwrap(),
                "{} {ending}",
                case["name"]
            );
            assert_eq!(
                case["first"].as_str().unwrap(),
                generate(&source).unwrap_err(),
                "{}",
                case["name"]
            );
            // The authoring validator keeps its single E-LEXICAL row for the same input.
            let grammar = &unlaxer_ubnf::parse(&source).unwrap().grammars[0];
            let lexical: Vec<_> = validation::collect(grammar)
                .into_iter()
                .filter(|d| d.code == "E-LEXICAL")
                .map(|d| d.message)
                .collect();
            assert_eq!(
                vec![case["first"].as_str().unwrap().to_string()],
                lexical,
                "{}",
                case["name"]
            );
        }
    }
}
