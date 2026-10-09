#![allow(dead_code)]
mod formula;
mod java;
mod tiny;
use std::collections::HashMap;
use unlaxer_runtime::embedded::{self, Grammar};
use unlaxer_runtime::source::{Language, Snapshot};
fn language(id: &str, grammar: &str, entry: &str) -> Language {
    Language {
        id: id.into(),
        package_id: "example".into(),
        version: "1".into(),
        grammar: grammar.into(),
        entry: entry.into(),
    }
}
fn main() {
    let formula = formula::parser::embedded_grammar();
    let tiny = tiny::parser::embedded_grammar();
    let java = java::parser::embedded_grammar();
    let root = language("formula", "FormulaInfo", "Document");
    for line in std::fs::read_to_string(std::env::args().nth(1).unwrap())
        .unwrap()
        .lines()
        .filter(|l| !l.starts_with('#'))
    {
        let fields: Vec<_> = line.split('\t').collect();
        let mut providers: HashMap<Language, &dyn Grammar> = HashMap::from([
            (root.clone(), &formula as &dyn Grammar),
            (
                language("tiny", "TinyExpression", "Expression"),
                &tiny as &dyn Grammar,
            ),
        ]);
        if fields[1] != "missing" {
            providers.insert(language("java", "Java", "CompilationUnit"), &java);
        }
        let host = Snapshot::new("host", 7, fields[2]).unwrap();
        let result = embedded::parse(&host, &root, &providers, 8, 32).unwrap();
        let actual = result
            .regions
            .iter()
            .map(|r| {
                format!(
                    "{}:{}:{}:{}:{}:{}",
                    r.language.grammar,
                    format!("{:?}", r.parse_state).to_uppercase(),
                    r.full.start,
                    r.full.end,
                    r.body.start,
                    r.body.end
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        assert_eq!(actual, fields[3], "{}", fields[0]);
        let tree = result.tree().unwrap();
        for region in &result.regions {
            let output = region.source_map.output();
            assert_eq!(output.text, host.slice(region.body).unwrap());
            if !output.is_empty() {
                let mapped = region
                    .source_map
                    .edit(unlaxer_runtime::Span {
                        start: 0,
                        end: output.len(),
                    })
                    .unwrap();
                assert_eq!(mapped.snapshot, host);
                assert_eq!(mapped.span, region.body);
            }
        }
        if fields[0] == "complete" {
            for (point, grammar) in [
                (2, "FormulaInfo"),
                (4, "TinyExpression"),
                (5, "Java"),
                (36, "TinyExpression"),
                (38, "FormulaInfo"),
            ] {
                assert_eq!(tree.at(point).unwrap().unwrap().language.grammar, grammar);
            }
            assert!(tree.at(40).unwrap().is_none());
            assert!(embedded::parse(&host, &root, &providers, 2, 32).is_err());
            assert!(embedded::parse(&host, &root, &providers, 8, 2).is_err());
        }
        println!("{}\t{}", fields[0], actual);
    }
    assert_eq!(
        java.parse(
            "RecoverableUnit",
            &Snapshot::new("child", 7, "bad;").unwrap()
        )
        .unwrap()
        .state,
        unlaxer_runtime::source::State::Partial
    );
    let unknown = java
        .parse("Missing", &Snapshot::new("child", 7, "{}").unwrap())
        .unwrap();
    assert_eq!(unknown.state, unlaxer_runtime::source::State::Unsupported);
    assert_eq!(
        java.parse("Block", &Snapshot::new("child", 7, "{}").unwrap())
            .unwrap()
            .state,
        unlaxer_runtime::source::State::Complete
    );
    let mut previous: Option<embedded::Output> = None;
    for (index, line) in std::fs::read_to_string(std::env::args().nth(2).unwrap())
        .unwrap()
        .lines()
        .enumerate()
    {
        let fields: Vec<_> = line.split('\t').collect();
        let options = if fields[1] == "limit" {
            unlaxer_runtime::editor_cst::Options {
                max_fragments: 0,
                max_attempts: 0,
            }
        } else {
            unlaxer_runtime::editor_cst::Options::default()
        };
        let formula = formula::parser::embedded_editor_grammar(
            vec![if fields[1] == "synthetic" {
                "x}F".into()
            } else {
                "}F".into()
            }],
            options,
        );
        let tiny = tiny::parser::embedded_editor_grammar(vec!["]T".into()], options);
        let mut providers: HashMap<Language, &dyn Grammar> = HashMap::from([
            (root.clone(), &formula as &dyn Grammar),
            (
                language("tiny", "TinyExpression", "Expression"),
                &tiny as &dyn Grammar,
            ),
        ]);
        if fields[1] != "missing" {
            providers.insert(language("java", "Java", "CompilationUnit"), &java);
        }
        let host = Snapshot::new("host", (index + 1) as u64, fields[2]).unwrap();
        let result = embedded::parse(&host, &root, &providers, 8, 32).unwrap();
        let actual = result
            .regions
            .iter()
            .map(|r| {
                format!(
                    "{}:{}:{}:{}:{}:{}",
                    r.language.grammar,
                    format!("{:?}", r.parse_state).to_uppercase(),
                    r.full.start,
                    r.full.end,
                    r.body.start,
                    r.body.end
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        assert_eq!(actual, fields[3], "{}", fields[0]);
        for region in &result.regions {
            assert_eq!(
                host.slice(region.body).unwrap(),
                region.source_map.output().text
            );
            assert_eq!(host.version, region.source_map.output().version);
            {
                assert_eq!(
                    region
                        .source_map
                        .edit(unlaxer_runtime::Span {
                            start: 0,
                            end: region.source_map.output().len()
                        })
                        .unwrap()
                        .span,
                    region.body
                );
                assert_eq!(region.source_map.cursor(&unlaxer_runtime::source::Location::new(host.clone(), unlaxer_runtime::Span { start: region.body.start, end: region.body.start }).unwrap()).unwrap(), Some(0));
            }
        }
        if let Some(old) = previous {
            let project = unlaxer_runtime::language_queries::Project {
                id: "project".into(),
                version: host.version,
                documents: std::collections::BTreeMap::from([(host.uri.clone(), host.clone())]),
                configuration: std::collections::BTreeMap::new(),
            };
            assert!(unlaxer_runtime::language_queries::LanguageQueries::new(
                old.tree().unwrap(),
                project,
                HashMap::new()
            )
            .is_err());
        }
        previous = Some(result);
    }
}
