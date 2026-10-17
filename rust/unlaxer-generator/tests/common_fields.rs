use serde_json::Value;

#[test]
fn common_family_schema_and_diagnostics_match_shared_oracle() {
    let valid: Value = serde_json::from_str(include_str!(
        "../../../spec-corpus/common-fields/generated-cases.json"
    ))
    .unwrap();
    for case in valid.as_array().unwrap() {
        let source = case["grammar"].as_str().unwrap();
        let files = unlaxer_generator::generate(source).unwrap();
        let ast = &files
            .iter()
            .find(|f| f.relative_path == "ast.rs")
            .unwrap()
            .content;
        assert!(ast.contains("pub struct NodeFields<'a>"));
        assert!(ast.contains("pub fn common_node(&self)"));
        assert!(unlaxer_generator::portability::check(source).portable);
    }
    let invalid: Value = serde_json::from_str(include_str!(
        "../../../spec-corpus/common-fields/invalid-cases.json"
    ))
    .unwrap();
    for case in invalid.as_array().unwrap() {
        let source = case["grammar"].as_str().unwrap();
        assert!(unlaxer_generator::generate(source)
            .unwrap_err()
            .contains("E-COMMONFIELD"));
        let report = unlaxer_generator::portability::check(source);
        assert_eq!(
            report.diagnostics.len(),
            1,
            "{}: {:?}",
            case["name"],
            report
        );
        let diagnostic = &report.diagnostics[0];
        assert_eq!(diagnostic.code, case["error"]["code"].as_str().unwrap());
        let span = diagnostic.span.unwrap();
        assert_eq!(
            span.codepoint_start,
            case["error"]["span"][0].as_u64().unwrap() as usize,
            "{}",
            case["name"]
        );
        assert_eq!(
            span.codepoint_end,
            case["error"]["span"][1].as_u64().unwrap() as usize,
            "{}",
            case["name"]
        );
    }
}
