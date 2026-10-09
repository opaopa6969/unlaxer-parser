use serde_json::{json, Value};
use std::collections::{BTreeMap, BTreeSet, HashMap, HashSet};
use unlaxer_lsp::{Backend, Completion, Diagnostic, Server};
use unlaxer_runtime::{
    editor::{CallSite, EditorParseResult, Node, NodeKind, Status},
    language_queries::{LanguageQueries, Project},
    semantic::{
        Argument, Call, ModelData, Scope, SemanticModel, Signature, Symbol, Type, TypeKind,
    },
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
fn model(source: &Snapshot, id: &str, name: &str, start: usize, end: usize) -> SemanticModel {
    SemanticModel::new(
        source.uri.clone(),
        source.version as i64,
        source.text.clone(),
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
                span: span(0, source.len()),
            }],
            symbols: vec![Symbol {
                id: id.into(),
                name: name.into(),
                type_id: "T".into(),
                scope: "root".into(),
                declaration: span(start, end),
                visible_from: end,
            }],
            signatures: vec![],
            calls: vec![],
        },
    )
    .unwrap()
}
fn binding(host: &Snapshot) -> LanguageQueries {
    let inner = Snapshot::new("virtual:symbols", host.version, "foo far foo f ").unwrap();
    let library = Snapshot::new("file:///library", 2, "😀bar").unwrap();
    let index = ProjectSymbolIndex::new(
        "symbols".into(),
        4,
        vec![
            Module {
                id: "inner".into(),
                model: model(&inner, "local", "foo", 0, 3),
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
            },
            Module {
                id: "library".into(),
                model: model(&library, "export", "bar", 1, 4),
                exports: BTreeSet::from(["export".into()]),
                imports: vec![],
            },
        ],
        vec![],
    )
    .unwrap();
    let language = Language {
        id: "typed".into(),
        package_id: "example/typed".into(),
        version: "1".into(),
        grammar: "Emoji".into(),
        entry: "Root".into(),
    };
    let map = SourceMap::new(
        inner.clone(),
        vec![Segment {
            output: span(0, inner.len()),
            kind: Kind::Copy,
            origin: Some(Location::new(host.clone(), span(2, 2 + inner.len())).unwrap()),
        }],
    )
    .unwrap();
    let region = Region {
        id: "symbols".into(),
        parent: None,
        language: language.clone(),
        full: span(0, host.len()),
        body: span(2, 2 + inner.len()),
        source_map: map,
        parse_state: State::Complete,
    };
    let project = Project {
        id: "symbols".into(),
        version: 4,
        documents: BTreeMap::from([
            (host.uri.clone(), host.clone()),
            (library.uri.clone(), library),
        ]),
        configuration: BTreeMap::new(),
    };
    LanguageQueries::new(
        LanguageRegions::new(host.clone(), vec![region]).unwrap(),
        project,
        HashMap::from([(
            language,
            Box::new(ProjectQueryProvider::new(index))
                as Box<dyn unlaxer_runtime::language_queries::Provider>,
        )]),
    )
    .unwrap()
}
struct Queries {
    stale: bool,
    missing: bool,
}
impl Backend for Queries {
    fn grammar(&self) -> &str {
        "Emoji"
    }
    fn entry(&self) -> &str {
        "Root"
    }
    fn keywords(&self) -> &[String] {
        &[]
    }
    fn validate(&mut self, _: &Snapshot) -> Vec<Diagnostic> {
        vec![]
    }
    fn query_capabilities(&self) -> HashSet<Operation> {
        HashSet::from([
            Operation::Completion,
            Operation::Hover,
            Operation::Definition,
            Operation::Rename,
            Operation::Format,
            Operation::CodeAction,
        ])
    }
    fn language_queries(&mut self, snapshot: &Snapshot) -> Option<LanguageQueries> {
        if self.missing {
            None
        } else {
            let mut host = snapshot.clone();
            if self.stale {
                host.version = 0;
            }
            Some(binding(&host))
        }
    }
    fn query_parameters(
        &self,
        snapshot: &Snapshot,
        cursor: usize,
        _: Operation,
    ) -> BTreeMap<String, String> {
        let chars: Vec<char> = snapshot.text.chars().collect();
        let mut start = cursor;
        let mut end = cursor;
        while start > 0 && chars[start - 1].is_ascii_alphabetic() {
            start -= 1;
        }
        while end < chars.len() && chars[end].is_ascii_alphabetic() {
            end += 1;
        }
        BTreeMap::from([
            ("prefix".into(), chars[start..cursor].iter().collect()),
            ("name".into(), chars[start..end].iter().collect()),
        ])
    }
}
fn request(method: &str, params: Value) -> Value {
    json!({"jsonrpc":"2.0","id":1,"method":method,"params":params})
}
fn position(character: usize) -> Value {
    json!({"textDocument":{"uri":"file:///symbols"},"position":{"line":0,"character":character}})
}
#[test]
fn real_project_provider_owns_utf16_definition_hover_completion_and_rejects_stale_binding() {
    let mut server = Server::new(Queries {
        stale: false,
        missing: false,
    });
    let caps = server.handle(request("initialize", json!({})));
    for operation in ["RENAME", "FORMAT", "CODE_ACTION"] {
        let value = &caps[0]["result"]["capabilities"]["experimental"]["languageQueryConsumer"]
            ["operations"][operation];
        assert_eq!(value["providerRegistered"], true);
        assert_eq!(value["profileAllowed"], true);
        assert_eq!(value["transport"], false);
        assert_eq!(value["available"], false);
    }
    for field in [
        "renameProvider",
        "documentFormattingProvider",
        "codeActionProvider",
    ] {
        assert!(caps[0]["result"]["capabilities"].get(field).is_none());
    }
    assert_eq!(
        true,
        caps[0]["result"]["capabilities"]["definitionProvider"]
    );
    server.handle(json!({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///symbols","version":1,"text":"😀{foo far foo f }H"}}}));
    for (cursor, uri, start, end) in [(12, "file:///symbols", 3, 6), (8, "file:///library", 2, 5)] {
        let result = server.handle(request("textDocument/definition", position(cursor)));
        assert_eq!(
            json!([{"uri":uri,"range":{"start":{"line":0,"character":start},"end":{"line":0,"character":end}}}]),
            result[0]["result"]
        );
    }
    assert_eq!(
        "far: T",
        server.handle(request("textDocument/hover", position(8)))[0]["result"]["contents"]["value"]
    );
    let completion = server.handle(request("textDocument/completion", position(16)));
    let items = completion[0]["result"].as_array().unwrap();
    assert_eq!(
        vec!["far", "foo"],
        items
            .iter()
            .map(|v| v["label"].as_str().unwrap())
            .collect::<Vec<_>>()
    );
    for item in items {
        assert_eq!(
            json!({"start":{"line":0,"character":15},"end":{"line":0,"character":16}}),
            item["textEdit"]["range"]
        );
        assert_eq!("1", item["data"]["version"]);
        assert_eq!("symbols", item["data"]["region"]);
    }
    assert_eq!(
        json!([]),
        server.handle(request("textDocument/completion", position(1)))[0]["result"]
    );
    assert_eq!(
        json!([]),
        server.handle(request("textDocument/completion", position(17)))[0]["result"]
    );
    let rows: Vec<_> = include_str!("../../../docs/fixtures/classic-lsp/providers.jsonl")
        .lines()
        .collect();
    assert_eq!(6, rows.len());
    for row in rows {
        let oracle: Value = serde_json::from_str(row).unwrap();
        let method = oracle["method"].as_str().unwrap();
        let cursor = oracle["character"].as_u64().unwrap() as usize;
        let response = server.handle(request(method, position(cursor)));
        let result = &response[0]["result"];
        let observed = if method.ends_with("completion") {
            json!(result
                .as_array()
                .unwrap()
                .iter()
                .map(|item| json!({"label":item["label"],"range":item["textEdit"]["range"]}))
                .collect::<Vec<_>>())
        } else if method.ends_with("hover") {
            result["contents"]["value"].clone()
        } else {
            result.clone()
        };
        assert_eq!(oracle["expected"], observed, "{method}@{cursor}");
    }
    for (stale, missing) in [(true, false), (false, true)] {
        let mut server = Server::new(Queries { stale, missing });
        server.handle(request("initialize", json!({})));
        server.handle(json!({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///symbols","version":1,"text":"😀{foo far foo f }H"}}}));
        assert_eq!(
            json!([]),
            server.handle(request("textDocument/completion", position(16)))[0]["result"]
        );
    }
}
fn partial(snapshot: &Snapshot) -> EditorParseResult<()> {
    let mut data = model(snapshot, "item", "item", 0, 0).data().clone();
    data.signatures.push(Signature {
        id: "f".into(),
        name: "f".into(),
        parameters: vec!["T".into()],
        result: "T".into(),
        span: span(0, 0),
    });
    data.calls.push(Call {
        id: "f".into(),
        signatures: vec!["f".into()],
        arguments: vec![Argument {
            type_id: "?".into(),
            span: span(3, 4),
        }],
        span: span(1, 4),
    });
    let model = SemanticModel::new(
        snapshot.uri.clone(),
        snapshot.version as i64,
        snapshot.text.clone(),
        data,
    )
    .unwrap();
    EditorParseResult::new(
        snapshot.uri.clone(),
        snapshot.version as i64,
        snapshot.text.clone(),
        Status::Partial,
        None,
        vec![Node {
            kind: NodeKind::Missing,
            span: span(4, 4),
            candidate_rules: vec!["Close".into()],
            region_id: None,
        }],
        Some(model),
        vec![CallSite {
            call_id: "f".into(),
            argument_index: 0,
            region_id: None,
        }],
    )
    .unwrap()
}
struct Typed;
impl Backend for Typed {
    fn grammar(&self) -> &str {
        "Typed"
    }
    fn entry(&self) -> &str {
        "Root"
    }
    fn keywords(&self) -> &[String] {
        &[]
    }
    fn validate(&mut self, _: &Snapshot) -> Vec<Diagnostic> {
        vec![]
    }
    fn complete(&mut self, snapshot: &Snapshot, cursor: usize) -> Vec<Completion> {
        let result = partial(snapshot);
        let mut items = unlaxer_lsp::editor_completions(snapshot, cursor, span(3, cursor), &result);
        if let Some(first) = items.first().cloned() {
            let mut stale = first.clone();
            stale.snapshot.version += 1;
            items.push(stale);
            let mut invalid = first;
            invalid.replacement = Some((span(3, 99), "invalid".into()));
            items.push(invalid);
        }
        items
    }
}
#[test]
fn partial_typed_result_keeps_edit_status_and_filters_stale_source_version_and_bad_ranges() {
    let snapshot = Snapshot::new("file:///typed", 7, "😀f(i").unwrap();
    let result = partial(&snapshot);
    assert!(result.strict_ast().is_none());
    assert_eq!(
        1,
        unlaxer_lsp::editor_completions(&snapshot, 4, span(3, 4), &result).len()
    );
    let stale = Snapshot::new(snapshot.uri.clone(), 8, snapshot.text.clone()).unwrap();
    assert!(unlaxer_lsp::editor_completions(&stale, 4, span(3, 4), &result).is_empty());
    let changed = Snapshot::new(snapshot.uri.clone(), 7, "😀f(x").unwrap();
    assert!(unlaxer_lsp::editor_completions(&changed, 4, span(3, 4), &result).is_empty());
    let mut server = Server::new(Typed);
    server.handle(request("initialize", json!({})));
    server.handle(json!({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":snapshot.uri,"version":7,"text":snapshot.text}}}));
    let response = server.handle(request(
        "textDocument/completion",
        json!({"textDocument":{"uri":"file:///typed"},"position":{"line":0,"character":5}}),
    ));
    let items = response[0]["result"].as_array().unwrap();
    assert_eq!(1, items.len());
    assert_eq!("item", items[0]["label"]);
    assert_eq!("PARTIAL", items[0]["data"]["status"]);
    assert_eq!(
        json!({"start":{"line":0,"character":4},"end":{"line":0,"character":5}}),
        items[0]["textEdit"]["range"]
    );
}
