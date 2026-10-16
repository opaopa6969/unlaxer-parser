fn source(pattern: &str) -> String {
    format!("grammar Regex {{ token T=REGEX('{pattern}') @root @mapping(Value,params=[text]) Root ::= T @text; }}")
}
#[test]
fn regex_supported_core_is_portable_and_predictor_remains_conservative() {
    for pattern in ["[a-z]+", "👨‍👩‍👧‍👦", "(?i:a)", "a*", "(?>a|ab)b"] {
        let grammar = source(pattern);
        let report = unlaxer_generator::portability::check(&grammar);
        assert!(report.portable, "{}", report.to_json());
        let files = unlaxer_generator::generate(&grammar).unwrap();
        assert!(
            files
                .iter()
                .any(|file| file.relative_path == "parser.rs"
                    && file.content.contains("Expr::regex("))
        );
    }
    let grammar = source("[a-z]+")
        .replace("@root", "@predictiveChoice @root")
        .replace("T @text;", "T @text | '!' @text;");
    let parser = unlaxer_generator::generate(&grammar)
        .unwrap()
        .into_iter()
        .find(|file| file.relative_path == "parser.rs")
        .unwrap()
        .content;
    assert!(parser.contains("Predictor::Any"));
}
#[test]
fn unsupported_syntax_and_budget_have_explicit_pre_generation_diagnostics_including_unused_tokens()
{
    for (pattern, kind, code) in [
        ("(?=a)a", "Unsupported", "P-REGEX-UNSUPPORTED"),
        ("[", "Syntax", "P-REGEX-SYNTAX"),
        ("(?U)a", "Unsupported", "P-REGEX-UNSUPPORTED"),
    ] {
        for used in [true, false] {
            let grammar = if used {
                source(pattern)
            } else {
                source(pattern).replace("T @text", "'ok' @text")
            };
            assert!(unlaxer_generator::generate(&grammar)
                .unwrap_err()
                .contains(kind));
            let report = unlaxer_generator::portability::check(&grammar);
            assert!(!report.portable);
            assert!(report.diagnostics.iter().any(|issue| issue.code == code));
        }
    }
}
