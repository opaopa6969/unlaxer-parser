#[path = "../../unlaxer-ubnf/tests/support/json.rs"]
mod json;

#[test]
fn validator_and_generator_match_shared_code_and_original_cp_oracle() {
    let corpus = json::parse(include_str!(
        "../../../conformance/bounded-overflow/corpus.json"
    ))
    .unwrap();
    for row in corpus.array() {
        let name = row.required_text("name", "fixture");
        let source = row.required_text("grammar", name);
        let file =
            unlaxer_ubnf::parse(source).unwrap_or_else(|e| panic!("{name}: syntax rejected: {e}"));
        let lowered = unlaxer_generator::lowering::lower(&file.grammars[0]);
        let generated = unlaxer_generator::generate(source);
        if row.required_text("code", name) == "accept" {
            lowered.unwrap_or_else(|e| panic!("{name}: {e}"));
            assert_eq!(generated.unwrap_or_else(|e| panic!("{name}: {e}")).len(), 5);
        } else {
            // Same text as the Java generators and the Java-host Rust backend.
            let expected = format!(
                "{}: repetition bound exceeds 2147483647 at CP {}",
                row.required_text("code", name),
                row.required_text("span", name)
            );
            for message in [lowered.unwrap_err(), generated.unwrap_err()] {
                assert_eq!(message, expected, "{name}");
            }
        }
    }
}

#[test]
fn original_spec_corpus_overflow_reaches_validation() {
    let corpus = json::parse(include_str!(
        "../../../unlaxer-dsl/src/test/resources/spec-corpus/ubnf/quantifiers.json"
    ))
    .unwrap();
    let row = corpus
        .get("cases")
        .unwrap()
        .array()
        .iter()
        .find(|row| row.required_text("id", "spec") == "quantifier/huge-bound")
        .unwrap();
    let source = row.required_text("input", "huge-bound");
    let file = unlaxer_ubnf::parse(source).expect("overflow belongs to validation, not syntax");
    let code = row
        .get("expectation")
        .unwrap()
        .required_text("code", "huge-bound");
    for message in [
        unlaxer_generator::lowering::lower(&file.grammars[0]).unwrap_err(),
        unlaxer_generator::generate(source).unwrap_err(),
    ] {
        assert!(message.contains(code), "{message}");
        assert!(message.ends_with("at CP 18..33"), "{message}");
    }
}
