use serde_json::Value;
use unlaxer_generator::{generate, portability::check};

fn corpus() -> Value {
    serde_json::from_str(include_str!(
        "../../../spec-corpus/multiple-grammars/corpus.json"
    ))
    .unwrap()
}

#[test]
fn independent_grammars_keep_single_grammar_bytes_and_private_names() {
    for row in corpus()["valid"].as_array().unwrap() {
        let source = row["source"].as_str().unwrap();
        assert!(check(source).portable, "{row}");
        let files = generate(source).unwrap();
        let parsed = unlaxer_ubnf::parse(source).unwrap();
        assert_eq!(1 + 5 * parsed.grammars.len(), files.len());
        assert_eq!("mod.rs", files.last().unwrap().relative_path);
        for grammar in parsed.grammars {
            let original = &source[grammar.span.byte_start..grammar.span.byte_end];
            let module = format!("grammar_{}", grammar.name.to_ascii_lowercase());
            for single in generate(original).unwrap() {
                let nested = files
                    .iter()
                    .find(|f| f.relative_path == format!("{module}/{}", single.relative_path))
                    .unwrap();
                assert_eq!(single.content, nested.content);
            }
        }
    }
}

#[test]
fn shared_rejections_have_exact_codepoint_origins() {
    for row in corpus()["invalid"].as_array().unwrap() {
        let source = row["source"].as_str().unwrap();
        let report = check(source);
        assert_eq!(row["structure"], report.structure, "{row}");
        let actual: Value = serde_json::from_str(&report.to_json()).unwrap();
        for (actual, expected) in actual["diagnostics"]
            .as_array()
            .unwrap()
            .iter()
            .zip(row["diagnostics"].as_array().unwrap())
        {
            assert_eq!(expected["code"], actual["code"], "{row}");
            assert_eq!(expected["subject"], actual["subject"], "{row}");
            assert_eq!(expected["span"], actual["span"], "{row}");
        }
        assert_eq!(
            row["diagnostics"].as_array().unwrap().len(),
            report.diagnostics.len()
        );
        assert!(generate(source).is_err(), "{row}");
    }
}

#[test]
fn invalid_programmatic_grammar_name_is_a_diagnostic() {
    let mut file = unlaxer_ubnf::parse("grammar A { @root Root ::= 'a'; }").unwrap();
    file.grammars[0].name = "文法".into();
    let report = unlaxer_generator::portability::check_ast(&file);
    assert!(!report.portable);
    assert_eq!("P-GRAMMAR-NAME", report.diagnostics[0].code);
}
