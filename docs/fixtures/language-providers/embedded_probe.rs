#![allow(dead_code)]
mod formula;
mod tiny;
use std::{
    collections::{BTreeMap, HashMap, HashSet},
    path::Path,
    time::Duration,
};
use unlaxer_runtime::{
    embedded::{self, Grammar},
    language_queries::{LanguageQueries, Provider},
    provider_process::{GrammarAdapter, ProviderProcess},
    provider_protocol::{self, Request, Status},
    source::{Kind, Language, Location, Operation, Segment, Snapshot, SourceMap, State},
    Span,
};
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
    let args: Vec<_> = std::env::args().collect();
    let directory = Path::new(&args[1]);
    let formula = formula::parser::embedded_grammar();
    let tiny = tiny::parser::embedded_grammar();
    for name in ["java-type", "java-definition"] {
        let owned = provider_protocol::read_request(
            &std::fs::read_to_string(directory.join(format!("{name}.wire"))).unwrap(),
        )
        .unwrap();
        let base = owned.as_request();
        let process = ProviderProcess {
            command: vec![
                args[2].clone(),
                "-cp".into(),
                args[3].clone(),
                "org.unlaxer.dsl.provider.JavacProvider".into(),
            ],
            identity: base.provider.clone(),
            operations: HashSet::from([
                Operation::Parse,
                Operation::Validate,
                Operation::Definition,
            ]),
            timeout: Duration::from_secs(30),
        };
        let host = Snapshot::new(
            "file:///host.formula",
            7,
            format!("😀F{{T[{}]T}}F", base.snapshot.text),
        )
        .unwrap();
        let mut project = base.project.clone();
        project.documents.insert(host.uri.clone(), host.clone());
        let root = language("formula", "FormulaInfo", "Document");
        let java_language = language("java", "Java", "CompilationUnit");
        let java = GrammarAdapter {
            process: &process,
            language: java_language.clone(),
            project: project.clone(),
            parameters: BTreeMap::new(),
        };
        let providers: HashMap<Language, &dyn Grammar> = HashMap::from([
            (root.clone(), &formula as &dyn Grammar),
            (
                language("tiny", "TinyExpression", "Expression"),
                &tiny as &dyn Grammar,
            ),
            (java_language.clone(), &java as &dyn Grammar),
        ]);
        let result = embedded::parse(&host, &root, &providers, 8, 32).unwrap();
        assert_eq!(result.regions.len(), 3);
        assert!(result
            .regions
            .iter()
            .all(|r| r.parse_state == State::Complete));
        let region = &result.regions[2];
        assert_eq!(region.body.start, 5);
        if name == "java-type" {
            let parameters = BTreeMap::new();
            let request = Request {
                id: name,
                provider: base.provider,
                language: &java_language,
                region: &region.id,
                snapshot: region.source_map.output(),
                project: &project,
                operation: Operation::Validate,
                cursor: 0,
                parameters: &parameters,
                execute_user_code: false,
            };
            let response = process.invoke(&request).unwrap();
            assert_eq!(response.status, Status::Diagnostics);
            let mapped = provider_protocol::map_diagnostics(&response, &region.source_map).unwrap();
            assert_eq!(mapped.len(), 1);
            assert_eq!(mapped[0].locations.len(), 1);
            let location = &mapped[0].locations[0];
            assert!(location.exact);
            assert_eq!(location.location.snapshot, host);
            assert_eq!(location.location.span, Span { start: 35, end: 36 });
            assert_eq!(host.slice(location.location.span).unwrap(), "1");
            let anchor = Location::new(host.clone(), Span { start: 0, end: 1 }).unwrap();
            for kind in [Kind::Generated, Kind::Transformed] {
                let map = SourceMap::new(
                    request.snapshot.clone(),
                    vec![Segment {
                        output: Span {
                            start: 0,
                            end: request.snapshot.len(),
                        },
                        kind,
                        origin: Some(anchor.clone()),
                    }],
                )
                .unwrap();
                let mapped = provider_protocol::map_diagnostics(&response, &map).unwrap();
                assert!(!mapped[0].locations[0].exact);
                assert_eq!(mapped[0].locations[0].location, anchor);
                assert!(map.edit(response.diagnostics[0].locations[0].span).is_err());
            }
            let queries = LanguageQueries::new(result.tree().unwrap(), project.clone(),
                HashMap::from([(java_language, Box::new(process) as Box<dyn Provider>)]),).unwrap();
            let typed = queries.diagnostics_all(&host, &project, &BTreeMap::new()).unwrap();
            let typed = typed.iter().find(|r|r.region == region.id).unwrap();
            assert_eq!(typed.state, State::Partial); assert_eq!(typed.diagnostics.len(),1);
            assert_eq!(typed.diagnostics[0].code, response.diagnostics[0].code);
            assert_eq!(typed.diagnostics[0].message, response.diagnostics[0].message);
            assert_eq!(typed.diagnostics[0].severity, "ERROR");
            assert_eq!(typed.diagnostics[0].locations[0].location.snapshot, host);
            assert_eq!(typed.diagnostics[0].locations[0].location.span, Span{start:35,end:36});
            let generic = queries.query(&host,&project,region.body.start,Operation::Validate,&BTreeMap::new()).unwrap();
            assert_eq!(generic.items.len(),1); assert_eq!(generic.items[0].label,response.diagnostics[0].code);
            assert_eq!(generic.items[0].locations[0].location.span, Span{start:35,end:36});
        } else {
            let queries = LanguageQueries::new(
                result.tree().unwrap(),
                project.clone(),
                HashMap::from([(java_language, Box::new(process) as Box<dyn Provider>)]),
            )
            .unwrap();
            let response = queries
                .query(
                    &host,
                    &project,
                    5 + base.cursor,
                    Operation::Definition,
                    &BTreeMap::new(),
                )
                .unwrap();
            assert_eq!(response.state, State::Complete);
            assert_eq!(response.items.len(), 1);
            let definition = &response.items[0].locations[0];
            assert_eq!(definition.location.snapshot.uri, "file:///Dep.java");
            assert_eq!(definition.location.span, Span { start: 0, end: 12 });
            assert!(definition.exact);
            let stale = Snapshot::new(&host.uri, 8, &host.text).unwrap();
            assert!(queries
                .query(
                    &stale,
                    &project,
                    5 + base.cursor,
                    Operation::Definition,
                    &BTreeMap::new()
                )
                .is_err());
        }
    }
}
