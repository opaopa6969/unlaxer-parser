use serde_json::Value;

#[test]
fn numeric_schema_matches_shared_contract() {
    let fixtures: Value = serde_json::from_str(include_str!(
        "../../../spec-corpus/numeric-captures/cases.json"
    ))
    .unwrap();
    for fixture in fixtures.as_array().unwrap() {
        let source = fixture["grammar"].as_str().unwrap();
        let files = unlaxer_generator::generate(source).unwrap();
        let ast = &files
            .iter()
            .find(|f| f.relative_path == "ast.rs")
            .unwrap()
            .content;
        let field_type = match fixture["type"].as_str().unwrap() {
            "int" => "i32",
            "java.util.Optional" => "Option<i32>",
            "java.util.List" => "Vec<i32>",
            other => panic!("unexpected type {other}"),
        };
        assert!(
            ast.contains(&format!("r#value: {field_type}")),
            "{}: {ast}",
            fixture["name"]
        );
        assert!(unlaxer_generator::portability::check(source).portable);
    }
}
