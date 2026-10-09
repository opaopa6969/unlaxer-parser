use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use unlaxer_runtime::{
    language_queries::*,
    semantic::{ModelData, Scope, SemanticModel, Symbol, Type, TypeKind},
    semantic_project::{Import, Module, ModuleRef, ProjectSymbolIndex},
    semantic_queries::ProjectQueryProvider,
    source::{
        Kind, Language, LanguageRegions, Location, Operation, Region, Segment, Snapshot, SourceMap,
        State,
    },
    Span,
};
fn span(start: usize, end: usize) -> Span {
    Span { start, end }
}
fn snapshot(uri: &str, text: &str) -> Snapshot {
    Snapshot::new(uri, 1, text).unwrap()
}
fn child() -> Snapshot {
    snapshot("virtual", "foo far foo f ")
}
fn tiny() -> Snapshot {
    snapshot("tiny", &format!("<{}>", child().text))
}
fn host() -> Snapshot {
    snapshot("host", &format!("#日😀\r\n[{}]\r\n", tiny().text))
}
fn library() -> Snapshot {
    Snapshot::new("library", 2, "bar").unwrap()
}
fn language(name: &str) -> Language {
    Language {
        id: name.into(),
        package_id: "local".into(),
        version: "1".into(),
        grammar: name.into(),
        entry: "Document".into(),
    }
}
fn copy(output: Snapshot, origin: Snapshot, start: usize) -> SourceMap {
    let length = output.len();
    SourceMap::new(
        output,
        vec![Segment {
            output: span(0, length),
            kind: Kind::Copy,
            origin: Some(Location::new(origin, span(start, start + length)).unwrap()),
        }],
    )
    .unwrap()
}
fn regions() -> LanguageRegions {
    LanguageRegions::new(
        host(),
        vec![
            Region {
                id: "formula".into(),
                parent: None,
                language: language("formula"),
                full: span(0, 25),
                body: span(0, 25),
                source_map: copy(host(), host(), 0),
                parse_state: State::Complete,
            },
            Region {
                id: "tiny".into(),
                parent: Some("formula".into()),
                language: language("tiny"),
                full: span(5, 23),
                body: span(6, 22),
                source_map: copy(tiny(), host(), 6),
                parse_state: State::Complete,
            },
            Region {
                id: "child".into(),
                parent: Some("tiny".into()),
                language: language("typed"),
                full: span(6, 22),
                body: span(7, 21),
                source_map: copy(child(), tiny(), 1)
                    .through(copy(tiny(), host(), 6))
                    .unwrap(),
                parse_state: State::Partial,
            },
        ],
    )
    .unwrap()
}
fn model(source: Snapshot, symbol: &str, name: &str, end: usize) -> SemanticModel {
    let length = source.len();
    SemanticModel::new(
        source.uri,
        source.version as i64,
        source.text,
        ModelData {
            types: vec![Type {
                id: "T".into(),
                kind: TypeKind::Builtin,
                supertypes: vec![],
                fields: vec![],
                span: span(0, 0),
            }],
            scopes: vec![Scope {
                id: "root".into(),
                parent: None,
                span: span(0, length),
            }],
            symbols: vec![Symbol {
                id: symbol.into(),
                name: name.into(),
                type_id: "T".into(),
                scope: "root".into(),
                declaration: span(0, end),
                visible_from: end,
            }],
            signatures: vec![],
            calls: vec![],
        },
    )
    .unwrap()
}
fn index() -> ProjectSymbolIndex {
    let child = Module {
        id: "child".into(),
        model: model(child(), "local", "foo", 3),
        exports: BTreeSet::new(),
        imports: vec![Import {
            name: "far".into(),
            target: ModuleRef {
                dependency: "".into(),
                module: "library".into(),
            },
            symbol: "bar".into(),
            scope: "root".into(),
            span: span(0, 0),
            visible_from: 0,
        }],
    };
    let library = Module {
        id: "library".into(),
        model: model(library(), "export", "bar", 3),
        exports: BTreeSet::from(["export".into()]),
        imports: vec![],
    };
    ProjectSymbolIndex::new("project".into(), 4, vec![child, library], vec![]).unwrap()
}
fn project() -> Project {
    Project {
        id: "project".into(),
        version: 4,
        documents: BTreeMap::from([("host".into(), host()), ("library".into(), library())]),
        configuration: BTreeMap::from([("sourceLevel".into(), "21".into())]),
    }
}
fn providers(provider: Box<dyn Provider>) -> HashMap<Language, Box<dyn Provider>> {
    HashMap::from([(language("typed"), provider)])
}
#[test]
fn shared_semantic_queries_map_only_their_owning_document() {
    let queries = LanguageQueries::new(
        regions(),
        project(),
        providers(Box::new(ProjectQueryProvider::new(index()))),
    )
    .unwrap();
    let mut views = include_str!("../../../docs/fixtures/language-queries/views.jsonl").lines();
    for line in include_str!("../../../docs/fixtures/language-queries/queries.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let fields: Vec<&str> = line.split('\t').collect();
        let operation = match fields[1] {
            "DEFINITION" => Operation::Definition,
            "HOVER" => Operation::Hover,
            "COMPLETION" => Operation::Completion,
            _ => Operation::Format,
        };
        let parameters = BTreeMap::from([
            (fields[3].into(), fields[4].into()),
            ("expectedType".into(), "T".into()),
        ]);
        let result = queries
            .query(
                &host(),
                &project(),
                fields[2].parse().unwrap(),
                operation,
                &parameters,
            )
            .unwrap();
        let view = queries
            .view(
                &host(),
                &project(),
                fields[2].parse().unwrap(),
                operation,
                &parameters,
            )
            .unwrap();
        assert_eq!(
            view.canonical_json(),
            views.next().unwrap(),
            "{}",
            fields[0]
        );
        assert_eq!(queries.host(), &host());
        assert_eq!(queries.project(), &project());
        assert_eq!(
            format!("{:?}", result.state).to_uppercase(),
            fields[5],
            "{}",
            fields[0]
        );
        let labels = result
            .items
            .iter()
            .map(|item| item.label.as_str())
            .collect::<Vec<_>>()
            .join(",");
        let locations = result
            .items
            .iter()
            .flat_map(|item| &item.locations)
            .map(|mapping| {
                format!(
                    "{}:{}:{}",
                    mapping.location.snapshot.uri,
                    mapping.location.span.start,
                    mapping.location.span.end
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        let edits = result
            .items
            .iter()
            .flat_map(|item| &item.edits)
            .map(|edit| format!("{}:{}:{}", edit.span.start, edit.span.end, edit.replacement))
            .collect::<Vec<_>>()
            .join(",");
        assert_eq!(
            labels,
            if fields[6] == "-" { "" } else { fields[6] },
            "{}",
            fields[0]
        );
        assert_eq!(
            locations,
            if fields[7] == "-" { "" } else { fields[7] },
            "{}",
            fields[0]
        );
        assert_eq!(
            edits,
            if fields[8] == "-" { "" } else { fields[8] },
            "{}",
            fields[0]
        );
        if fields[0] == "completion-alternatives" {
            assert_eq!(
                regions()
                    .apply(&host(), 2, &result.items[0].edits)
                    .unwrap()
                    .text,
                "#日😀\r\n[<foo far foo far >]\r\n"
            );
        }
    }
}
struct Malicious(&'static str);
impl Provider for Malicious {
    fn capabilities(&self) -> HashSet<Operation> {
        [Operation::Hover].into()
    }
    fn query(&self, request: &Request<'_>) -> unlaxer_runtime::source::Result<Response> {
        assert_eq!(request.cursor, 4);
        assert_eq!(request.project.configuration["sourceLevel"], "21");
        let mut source = child();
        if self.0 == "stale-response" {
            source.version = 0;
        }
        let mut foreign = library();
        if self.0 == "stale-location" {
            foreign.version = 1;
        }
        let edits = if self.0 == "foreign-edit" {
            vec![TextEdit {
                location: Location::new(foreign.clone(), span(0, 1))?,
                replacement: "x".into(),
            }]
        } else {
            vec![]
        };
        Ok(Response {
            snapshot: source,
            project: "project".into(),
            project_version: if self.0 == "stale-project" { 3 } else { 4 },
            state: State::Complete,
            items: vec![Item {
                label: "foreign".into(),
                detail: "T".into(),
                locations: vec![Location::new(foreign, span(0, 1))?],
                edits,
            }],
        })
    }
}
#[test]
fn stale_context_responses_and_foreign_edits_are_rejected() {
    for mode in [
        "stale-response",
        "stale-location",
        "stale-project",
        "foreign-edit",
    ] {
        let queries =
            LanguageQueries::new(regions(), project(), providers(Box::new(Malicious(mode))))
                .unwrap();
        assert!(
            queries
                .query(&host(), &project(), 11, Operation::Hover, &BTreeMap::new())
                .is_err(),
            "{mode}"
        );
    }
    let queries = LanguageQueries::new(
        regions(),
        project(),
        providers(Box::new(ProjectQueryProvider::new(index()))),
    )
    .unwrap();
    let mut changed = project();
    changed.version = 5;
    assert!(queries
        .query(
            &host(),
            &changed,
            11,
            Operation::Hover,
            &BTreeMap::from([("name".into(), "far".into())])
        )
        .is_err());
    let mut changed_host = host();
    changed_host.version = 2;
    assert!(queries
        .query(
            &changed_host,
            &project(),
            11,
            Operation::Hover,
            &BTreeMap::from([("name".into(), "far".into())])
        )
        .is_err());
}
#[test]
fn cursor_mapping_rejects_deleted_transformed_and_duplicate_origins() {
    let origin = snapshot("origin", "a#b");
    let output = snapshot("output", "ab");
    let deleted = SourceMap::new(
        output.clone(),
        vec![
            Segment {
                output: span(0, 1),
                kind: Kind::Copy,
                origin: Some(Location::new(origin.clone(), span(0, 1)).unwrap()),
            },
            Segment {
                output: span(1, 2),
                kind: Kind::Copy,
                origin: Some(Location::new(origin.clone(), span(2, 3)).unwrap()),
            },
        ],
    )
    .unwrap();
    assert!(deleted
        .cursor(&Location::new(origin.clone(), span(1, 1)).unwrap())
        .unwrap()
        .is_none());
    assert!(deleted
        .cursor(&Location::new(origin.clone(), span(2, 2)).unwrap())
        .unwrap()
        .is_none());
    assert_eq!(
        deleted
            .cursor(&Location::new(origin.clone(), span(0, 0)).unwrap())
            .unwrap(),
        Some(0)
    );
    let one = snapshot("one", "a");
    let duplicate = SourceMap::new(
        snapshot("two", "aa"),
        vec![
            Segment {
                output: span(0, 1),
                kind: Kind::Copy,
                origin: Some(Location::new(one.clone(), span(0, 1)).unwrap()),
            },
            Segment {
                output: span(1, 2),
                kind: Kind::Copy,
                origin: Some(Location::new(one.clone(), span(0, 1)).unwrap()),
            },
        ],
    )
    .unwrap();
    assert!(duplicate
        .cursor(&Location::new(one, span(0, 0)).unwrap())
        .unwrap()
        .is_none());
    let transformed = SourceMap::new(
        output,
        vec![Segment {
            output: span(0, 2),
            kind: Kind::Transformed,
            origin: Some(Location::new(origin.clone(), span(0, 3)).unwrap()),
        }],
    )
    .unwrap();
    assert!(transformed
        .cursor(&Location::new(origin, span(1, 1)).unwrap())
        .unwrap()
        .is_none());
}

struct DiagnosticProvider(String);
impl Provider for DiagnosticProvider {
    fn capabilities(&self) -> HashSet<Operation> {
        HashSet::from([Operation::Validate])
    }
    fn query(&self, _: &Request<'_>) -> unlaxer_runtime::source::Result<Response> {
        panic!("typed validation must use diagnostics()")
    }
    fn diagnostics(
        &self,
        request: &Request<'_>,
    ) -> unlaxer_runtime::source::Result<DiagnosticResponse> {
        use unlaxer_runtime::provider_protocol::Diagnostic;
        assert_eq!(request.operation, Operation::Validate);
        assert_eq!(request.cursor, 0);
        let mut snapshot = child();
        if self.0 == "stale-response" {
            snapshot.version = 0;
        }
        let local = Location::new(child(), span(12, 13))?;
        let foreign = Location::new(library(), span(0, 3))?;
        let locations = match self.0.as_str() {
            "foreign" => vec![foreign],
            "multiple" => vec![local, foreign],
            "stale-location" => vec![Location::new(
                Snapshot::new("library", 1, "bar")?,
                span(0, 3),
            )?],
            "unknown-document" => vec![Location::new(
                Snapshot::new("unknown", 1, "x")?,
                span(0, 1),
            )?],
            _ => vec![local],
        };
        let severity = match self.0.as_str() {
            "foreign" => "WARNING",
            "note" => "NOTE",
            "unknown-severity" => "GUESS",
            _ => "ERROR",
        };
        let state = match self.0.as_str() {
            "timeout" => State::Timeout,
            "unsupported" => State::Unsupported,
            "failed-payload" => State::Failed,
            _ => State::Partial,
        };
        let diagnostics = if matches!(self.0.as_str(), "timeout" | "unsupported") {
            vec![]
        } else {
            vec![Diagnostic {
                code: "TYPE".into(),
                message: "incompatible type".into(),
                severity: severity.into(),
                locations,
            }]
        };
        Ok(DiagnosticResponse {
            snapshot,
            project: "project".into(),
            project_version: if self.0 == "stale-project" { 3 } else { 4 },
            state,
            diagnostics,
        })
    }
}
#[test]
fn typed_diagnostics_preserve_metadata_and_owning_snapshots() {
    for line in include_str!("../../../docs/fixtures/language-queries/diagnostics.tsv")
        .lines()
        .filter(|l| !l.starts_with('#'))
    {
        let fields: Vec<_> = line.split('\t').collect();
        let queries = LanguageQueries::new(
            regions(),
            project(),
            providers(Box::new(DiagnosticProvider(fields[0].into()))),
        )
        .unwrap();
        let results = queries.diagnostics_all(&host(), &project(), &BTreeMap::new());
        if fields[1] == "REJECTED" {
            assert!(results.is_err(), "{}", fields[0]);
            continue;
        }
        let results = results.unwrap();
        assert_eq!(
            results
                .iter()
                .map(|r| r.region.as_str())
                .collect::<Vec<_>>(),
            ["child", "formula", "tiny"]
        );
        let result = &results[0];
        assert_eq!(format!("{:?}", result.state).to_uppercase(), fields[1]);
        assert_eq!(results[1].state, State::Unavailable);
        assert_eq!(results[2].state, State::Unavailable);
        if fields[2] == "-" {
            assert!(result.diagnostics.is_empty());
            continue;
        }
        assert_eq!(result.diagnostics.len(), 1);
        let diagnostic = &result.diagnostics[0];
        assert_eq!(diagnostic.code, fields[2]);
        assert_eq!(diagnostic.message, "incompatible type");
        assert_eq!(diagnostic.severity, fields[3]);
        assert_eq!(
            diagnostic
                .locations
                .iter()
                .map(|m| format!(
                    "{}:{}:{}:{}",
                    m.location.snapshot.uri, m.location.span.start, m.location.span.end, m.exact
                ))
                .collect::<Vec<_>>()
                .join(","),
            fields[4]
        );
    }
    let queries = LanguageQueries::new(
        regions(),
        project(),
        providers(Box::new(DiagnosticProvider("valid".into()))),
    )
    .unwrap();
    let mut old_host = host();
    old_host.version = 2;
    assert!(queries
        .diagnostics_all(&old_host, &project(), &BTreeMap::new())
        .is_err());
    let mut old_project = project();
    old_project.version = 5;
    assert!(queries
        .diagnostics_all(&host(), &old_project, &BTreeMap::new())
        .is_err());
}
