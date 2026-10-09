#![allow(dead_code)]
mod formula;
mod tiny;
mod java;
use std::collections::HashMap;
use unlaxer_runtime::embedded::{self, Grammar};
use unlaxer_runtime::source::{Language, Snapshot};
fn language(id: &str, grammar: &str, entry: &str) -> Language {
    Language { id: id.into(), package_id: "example".into(), version: "1".into(), grammar: grammar.into(), entry: entry.into() }
}
fn main() {
    let formula = formula::parser::embedded_grammar();
    let tiny = tiny::parser::embedded_grammar();
    let java = java::parser::embedded_grammar();
    let root = language("formula", "FormulaInfo", "Document");
    for line in std::fs::read_to_string(std::env::args().nth(1).unwrap()).unwrap().lines().filter(|l| !l.starts_with('#')) {
        let fields: Vec<_> = line.split('\t').collect();
        let mut providers: HashMap<Language, &dyn Grammar> = HashMap::from([(root.clone(), &formula as &dyn Grammar), (language("tiny", "TinyExpression", "Expression"), &tiny as &dyn Grammar)]);
        if fields[1] != "missing" { providers.insert(language("java", "Java", "CompilationUnit"), &java); }
        let host = Snapshot::new("host", 7, fields[2]).unwrap();
        let result = embedded::parse(&host, &root, &providers, 8, 32).unwrap();
        let actual = result.regions.iter().map(|r| format!("{}:{}:{}:{}:{}:{}", r.language.grammar, format!("{:?}", r.parse_state).to_uppercase(), r.full.start, r.full.end, r.body.start, r.body.end)).collect::<Vec<_>>().join(",");
        assert_eq!(actual, fields[3], "{}", fields[0]);
        let tree = result.tree().unwrap();
        for region in &result.regions {
            let output = region.source_map.output();
            assert_eq!(output.text, host.slice(region.body).unwrap());
            if !output.is_empty() {
                let mapped = region.source_map.edit(unlaxer_runtime::Span { start: 0, end: output.len() }).unwrap();
                assert_eq!(mapped.snapshot, host); assert_eq!(mapped.span, region.body);
            }
        }
        if fields[0] == "complete" {
            for (point, grammar) in [(2, "FormulaInfo"), (4, "TinyExpression"), (5, "Java"), (36, "TinyExpression"), (38, "FormulaInfo")] { assert_eq!(tree.at(point).unwrap().unwrap().language.grammar, grammar); }
            assert!(tree.at(40).unwrap().is_none());
            assert!(embedded::parse(&host, &root, &providers, 2, 32).is_err());
            assert!(embedded::parse(&host, &root, &providers, 8, 2).is_err());
        }
        println!("{}\t{}", fields[0], actual);
    }
    assert_eq!(java.parse("RecoverableUnit", &Snapshot::new("child", 7, "bad;").unwrap()).unwrap().state, unlaxer_runtime::source::State::Partial);
    let unknown = java.parse("Missing", &Snapshot::new("child", 7, "{}").unwrap()).unwrap();
    assert_eq!(unknown.state, unlaxer_runtime::source::State::Unsupported);
    assert_eq!(java.parse("Block", &Snapshot::new("child", 7, "{}").unwrap()).unwrap().state, unlaxer_runtime::source::State::Complete);
}
