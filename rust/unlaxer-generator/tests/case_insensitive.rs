fn source(word: &str) -> String {
    format!("grammar Ci {{ token Keyword = CI('{word}') @root @mapping(Root, params=[value]) Start ::= Keyword @value; }}")
}

#[test]
fn explicit_ci_lowers_and_is_portable_without_losing_unicode() {
    for word in ["select", "İΣK", "𐐀😀"] {
        let source = source(word);
        let report = unlaxer_generator::portability::check(&source);
        assert!(report.portable, "{}", report.to_json());
        let files = unlaxer_generator::generate(&source).unwrap();
        let parser = &files
            .iter()
            .find(|file| file.relative_path.ends_with("parser.rs"))
            .unwrap()
            .content;
        assert!(parser.contains("Expr::CaseInsensitive("), "{parser}");
    }
}

#[test]
fn ci_predictor_is_conservative_instead_of_case_sensitive() {
    let source = source("if")
        .replace("@root", "@predictiveChoice @root")
        .replace("Keyword @value;", "Keyword @value | 'other' @value;");
    let files = unlaxer_generator::generate(&source).unwrap();
    let parser = &files
        .iter()
        .find(|file| file.relative_path.ends_with("parser.rs"))
        .unwrap()
        .content;
    assert!(parser.contains("Predictor::Any"), "{parser}");
}

#[test]
fn empty_ci_is_rejected_even_when_unused() {
    for source in [
        source(""),
        source("").replace("Keyword @value", "'ok' @value"),
    ] {
        assert!(unlaxer_generator::generate(&source)
            .unwrap_err()
            .contains("empty CI literal"));
        let report = unlaxer_generator::portability::check(&source);
        assert_eq!(report.structure, "failed");
        assert!(!report.portable);
    }
}
