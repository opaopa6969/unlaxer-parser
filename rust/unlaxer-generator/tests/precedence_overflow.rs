#[path = "../../unlaxer-ubnf/tests/support/json.rs"]
mod json;

fn check(prefix: &str) {
    let corpus = json::parse(include_str!(
        "../../../conformance/precedence-overflow/corpus.json"
    ))
    .unwrap();
    for row in corpus.array() {
        let name = row.required_text("name", "fixture");
        if !name.starts_with(prefix) {
            continue;
        }
        let source = row.required_text("grammar", name);
        let file =
            unlaxer_ubnf::parse(source).unwrap_or_else(|e| panic!("{name}: syntax rejected: {e}"));
        let lowered = unlaxer_generator::lowering::lower(&file.grammars[0]);
        let generated = unlaxer_generator::generate(source);
        let issues = row.get("issues").unwrap().array();
        if issues.is_empty() {
            lowered.unwrap_or_else(|e| panic!("{name}: {e}"));
            assert_eq!(generated.unwrap_or_else(|e| panic!("{name}: {e}")).len(), 5);
        } else {
            // Native APIs return the first diagnostic; Java also checks the full list.
            let expected = format!(
                "{}: {}",
                issues[0].required_text("code", name),
                issues[0].required_text("message", name)
            );
            for message in [lowered.unwrap_err(), generated.unwrap_err()] {
                assert_eq!(message, expected, "{name}");
            }
        }
    }
}

#[test]
fn precedence_levels_match_shared_code_and_original_cp_oracle() {
    check("precedence-");
}

#[test]
fn bounded_diagnostics_match_java_order_and_code() {
    check("bounded-");
}
