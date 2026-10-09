use std::collections::{BTreeSet, HashMap};
use unlaxer_runtime::{
    semantic::{ModelData, Scope, SemanticModel, Symbol, Type, TypeKind},
    semantic_project::{Definition, Identity, Import, Module, ModuleRef, ProjectSymbolIndex},
    semantic_rename::{self, Inventory},
    source::{Edit, Kind as MapKind, Location, Segment, Snapshot, SourceMap},
    source_edits::*,
    Span,
};
const SOURCE: &str = "#日😀\r\nfoo  \"foo\" { foo foo } foo\r\n";
fn span(start: usize, end: usize) -> Span {
    Span { start, end }
}
fn snapshot(uri: &str, text: &str) -> Snapshot {
    Snapshot::new(uri, 1, text).unwrap()
}
fn decode(text: &str) -> String {
    text.replace("\\r", "\r")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
}
fn pieces(offset: usize) -> Vec<Piece> {
    include_str!("../../../docs/fixtures/source-edits/pieces.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
        .map(|line| {
            let fields: Vec<&str> = line.split('\t').collect();
            Piece {
                id: fields[0].into(),
                span: span(
                    fields[1].parse::<usize>().unwrap() + offset,
                    fields[2].parse::<usize>().unwrap() + offset,
                ),
                kind: match fields[3] {
                    "TOKEN" => Kind::Token,
                    "WHITESPACE" => Kind::Whitespace,
                    "COMMENT" => Kind::Comment,
                    _ => Kind::Unparsed,
                },
                owner: if fields[4] == "-" { "" } else { fields[4] }.into(),
            }
        })
        .collect()
}
#[test]
fn shared_source_preserving_operations() {
    let snapshot = snapshot("java", SOURCE);
    let source = SourceEdits::new(snapshot.clone(), pieces(0)).unwrap();
    assert_eq!(SOURCE.as_bytes(), source.round_trip().as_bytes());
    for line in include_str!("../../../docs/fixtures/source-edits/edits.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let fields: Vec<&str> = line.split('\t').collect();
        let edits = vec![Edit {
            span: span(fields[2].parse().unwrap(), fields[3].parse().unwrap()),
            replacement: decode(fields[4]),
        }];
        let operation = match fields[1] {
            "RENAME" => Operation::Rename,
            "FORMAT" => Operation::Format,
            _ => Operation::CodeAction,
        };
        let plan = source.plan(operation, span(0, 33), edits);
        if fields[5] == "-" {
            assert!(plan.is_err(), "{}", fields[0]);
        } else {
            assert_eq!(
                plan.unwrap().apply(&snapshot, 2).unwrap().text,
                decode(fields[5]),
                "{}",
                fields[0]
            );
        }
    }
}
fn copy(output: Snapshot, origin: Snapshot, start: usize) -> SourceMap {
    let length = output.len();
    SourceMap::new(
        output,
        vec![Segment {
            output: span(0, length),
            kind: MapKind::Copy,
            origin: Some(Location::new(origin, span(start, start + length)).unwrap()),
        }],
    )
    .unwrap()
}
#[test]
fn maps_child_plan_to_host_and_rejects_stale_or_conflicting_edits() {
    let child = snapshot("java", SOURCE);
    let tiny = snapshot("tiny", &format!("<{SOURCE}>"));
    let host = snapshot("formula", &format!("meta\r\n[<{SOURCE}>]\r\n"));
    let map = copy(child.clone(), tiny.clone(), 1)
        .through(copy(tiny, host.clone(), 7))
        .unwrap();
    let source = SourceEdits::new(child.clone(), pieces(0)).unwrap();
    let mut host_pieces = vec![Piece {
        id: "prefix".into(),
        span: span(0, 8),
        kind: Kind::Token,
        owner: "".into(),
    }];
    host_pieces.extend(pieces(8));
    host_pieces.push(Piece {
        id: "suffix".into(),
        span: span(41, host.len()),
        kind: Kind::Token,
        owner: "".into(),
    });
    let target = SourceEdits::new(host.clone(), host_pieces).unwrap();
    let plan = source
        .plan(
            Operation::Rename,
            span(0, 33),
            vec![Edit {
                span: span(5, 8),
                replacement: "bar".into(),
            }],
        )
        .unwrap();
    let mapped = target.map(&plan, &map, span(8, 41)).unwrap();
    assert_eq!(
        mapped.apply(&host, 2).unwrap().text,
        format!("meta\r\n[<{}>]\r\n", SOURCE.replacen("foo", "bar", 1))
    );
    assert!(target.map(&plan, &map, span(14, 41)).is_err());
    let mut stale = host.clone();
    stale.version = 2;
    assert!(mapped.apply(&stale, 3).is_err());
    assert!(source
        .plan(
            Operation::Rename,
            span(0, 33),
            vec![
                Edit {
                    span: span(5, 8),
                    replacement: "x".into()
                },
                Edit {
                    span: span(5, 8),
                    replacement: "y".into()
                }
            ]
        )
        .is_err());
    assert!(apply_all(
        &[plan.clone(), plan.clone()],
        &HashMap::from([("java".into(), child.clone())]),
        &HashMap::from([("java".into(), 2)])
    )
    .is_err());
    assert_eq!(
        apply_all(
            &[plan],
            &HashMap::from([("java".into(), child)]),
            &HashMap::from([("java".into(), 2)])
        )
        .unwrap()["java"]
            .slice(span(5, 8))
            .unwrap(),
        "bar"
    );
}
#[test]
fn invalid_ownership_and_partial_input_are_explicit() {
    let snapshot = snapshot("partial", "x ?");
    let pieces = vec![
        Piece {
            id: "x".into(),
            span: span(0, 1),
            kind: Kind::Token,
            owner: "".into(),
        },
        Piece {
            id: "space".into(),
            span: span(1, 2),
            kind: Kind::Whitespace,
            owner: "x".into(),
        },
        Piece {
            id: "unknown".into(),
            span: span(2, 3),
            kind: Kind::Unparsed,
            owner: "".into(),
        },
    ];
    let source = SourceEdits::new(snapshot.clone(), pieces.clone()).unwrap();
    assert_eq!(source.round_trip(), "x ?");
    assert!(source
        .plan(
            Operation::Rename,
            span(0, 3),
            vec![Edit {
                span: span(2, 3),
                replacement: "z".into()
            }]
        )
        .is_err());
    assert_eq!(
        source
            .plan(
                Operation::CodeAction,
                span(2, 3),
                vec![Edit {
                    span: span(2, 3),
                    replacement: "z".into()
                }]
            )
            .unwrap()
            .apply(&snapshot, 2)
            .unwrap()
            .text,
        "x z"
    );
    assert!(SourceEdits::new(snapshot.clone(), pieces[..2].to_vec()).is_err());
    let mut bad = pieces;
    bad[1].owner = "missing".into();
    assert!(SourceEdits::new(snapshot, bad).is_err());
}
fn types() -> Vec<Type> {
    vec![Type {
        id: "T".into(),
        kind: TypeKind::Builtin,
        supertypes: vec![],
        fields: vec![],
        span: span(0, 0),
    }]
}
fn first() -> Module {
    Module {
        id: "first".into(),
        model: SemanticModel::new(
            "first".into(),
            1,
            SOURCE.into(),
            ModelData {
                types: types(),
                scopes: vec![
                    Scope {
                        id: "root".into(),
                        parent: None,
                        span: span(0, 33),
                    },
                    Scope {
                        id: "inner".into(),
                        parent: Some("root".into()),
                        span: span(16, 27),
                    },
                ],
                symbols: vec![
                    Symbol {
                        id: "outer".into(),
                        name: "foo".into(),
                        type_id: "T".into(),
                        scope: "root".into(),
                        declaration: span(5, 8),
                        visible_from: 8,
                    },
                    Symbol {
                        id: "inner".into(),
                        name: "foo".into(),
                        type_id: "T".into(),
                        scope: "inner".into(),
                        declaration: span(18, 21),
                        visible_from: 21,
                    },
                ],
                signatures: vec![],
                calls: vec![],
            },
        )
        .unwrap(),
        exports: BTreeSet::from(["outer".into()]),
        imports: vec![],
    }
}
fn second() -> Module {
    Module {
        id: "second".into(),
        model: SemanticModel::new(
            "second".into(),
            1,
            "foo foo".into(),
            ModelData {
                types: types(),
                scopes: vec![Scope {
                    id: "root".into(),
                    parent: None,
                    span: span(0, 7),
                }],
                symbols: vec![Symbol {
                    id: "local".into(),
                    name: "foo".into(),
                    type_id: "T".into(),
                    scope: "root".into(),
                    declaration: span(0, 3),
                    visible_from: 3,
                }],
                signatures: vec![],
                calls: vec![],
            },
        )
        .unwrap(),
        exports: BTreeSet::new(),
        imports: vec![],
    }
}
fn inventories(complete: bool) -> Vec<Inventory> {
    vec![
        Inventory {
            module: "first".into(),
            source: SourceEdits::new(snapshot("first", SOURCE), pieces(0)).unwrap(),
            reference_tokens: vec!["innerRef".into(), "outerRef".into()],
            complete,
        },
        Inventory {
            module: "second".into(),
            source: SourceEdits::new(
                snapshot("second", "foo foo"),
                vec![
                    Piece {
                        id: "decl".into(),
                        span: span(0, 3),
                        kind: Kind::Token,
                        owner: "".into(),
                    },
                    Piece {
                        id: "space".into(),
                        span: span(3, 4),
                        kind: Kind::Whitespace,
                        owner: "decl".into(),
                    },
                    Piece {
                        id: "ref".into(),
                        span: span(4, 7),
                        kind: Kind::Token,
                        owner: "".into(),
                    },
                ],
            )
            .unwrap(),
            reference_tokens: vec!["ref".into()],
            complete: true,
        },
    ]
}
fn target(symbol: &str) -> Definition {
    Definition {
        identity: Identity {
            project: "project".into(),
            dependency: "".into(),
            dependency_version: "".into(),
            module: "first".into(),
            symbol: symbol.into(),
        },
        uri: "first".into(),
        version: 1,
        span: if symbol == "outer" {
            span(5, 8)
        } else {
            span(18, 21)
        },
    }
}
fn identifier(name: &str) -> bool {
    let mut characters = name.chars();
    characters
        .next()
        .is_some_and(|character| character.is_ascii_alphabetic() || character == '_')
        && characters.all(|character| character.is_ascii_alphanumeric() || character == '_')
}
#[test]
fn common_identity_rename_preserves_shadowing_strings_and_other_modules() {
    let index =
        ProjectSymbolIndex::new("project".into(), 1, vec![first(), second()], vec![]).unwrap();
    for line in include_str!("../../../docs/fixtures/source-edits/rename.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let fields: Vec<&str> = line.split('\t').collect();
        let plans = semantic_rename::prepare(
            &index,
            &target(fields[0]),
            fields[1],
            Some(&identifier),
            &inventories(true),
        )
        .unwrap();
        assert_eq!(plans.len(), 1);
        assert_eq!(plans[0].edits().len(), 2);
        let result = apply_all(
            &plans,
            &HashMap::from([
                ("first".into(), snapshot("first", SOURCE)),
                ("second".into(), snapshot("second", "foo foo")),
            ]),
            &HashMap::from([("first".into(), 2), ("second".into(), 2)]),
        )
        .unwrap();
        assert_eq!(result["first"].text, decode(fields[2]));
        assert!(!result.contains_key("second"));
    }
}
#[test]
fn stale_incomplete_invalid_and_imported_renames_are_rejected() {
    let index =
        ProjectSymbolIndex::new("project".into(), 1, vec![first(), second()], vec![]).unwrap();
    let inventory = inventories(true);
    assert!(semantic_rename::prepare(
        &index,
        &target("outer"),
        "x-y",
        Some(&identifier),
        &inventory
    )
    .is_err());
    assert!(semantic_rename::prepare(&index, &target("outer"), "bar", None, &inventory).is_err());
    assert!(semantic_rename::prepare(
        &index,
        &target("outer"),
        "bar",
        Some(&identifier),
        &inventories(false)
    )
    .is_err());
    let mut stale = target("outer");
    stale.version = 0;
    assert!(
        semantic_rename::prepare(&index, &stale, "bar", Some(&identifier), &inventory).is_err()
    );
    let mut imported = second();
    imported.imports.push(Import {
        name: "alias".into(),
        target: ModuleRef {
            dependency: "".into(),
            module: "first".into(),
        },
        symbol: "foo".into(),
        scope: "root".into(),
        span: span(0, 0),
        visible_from: 0,
    });
    let with_import =
        ProjectSymbolIndex::new("project".into(), 1, vec![first(), imported], vec![]).unwrap();
    assert!(semantic_rename::prepare(
        &with_import,
        &target("outer"),
        "bar",
        Some(&identifier),
        &inventory
    )
    .is_err());
}

#[test]
fn capturing_another_binding_and_changed_source_are_rejected() {
    let changed_source = SOURCE.replace("{ foo foo }", "{ bar bar }");
    let mut changed_data = first().model.data().clone();
    changed_data.symbols[1].name = "bar".into();
    let changed = Module {
        id: "first".into(),
        model: SemanticModel::new("first".into(), 1, changed_source.clone(), changed_data).unwrap(),
        exports: BTreeSet::from(["outer".into()]),
        imports: vec![],
    };
    let index =
        ProjectSymbolIndex::new("project".into(), 1, vec![changed, second()], vec![]).unwrap();
    assert!(semantic_rename::prepare(
        &index,
        &target("outer"),
        "baz",
        Some(&identifier),
        &inventories(true)
    )
    .is_err());
    let mut current = inventories(true);
    current[0].source = SourceEdits::new(snapshot("first", &changed_source), pieces(0)).unwrap();
    assert!(
        semantic_rename::prepare(&index, &target("outer"), "bar", Some(&identifier), &current)
            .is_err()
    );
}

fn lexical(uri: &str, text: &str) -> SourceEdits {
    let cp = text.chars().collect::<Vec<_>>();
    let mut pieces = vec![];
    let mut i = 0;
    while i < cp.len() {
        let start = i;
        let ws = " \t\r\n".contains(cp[i]);
        if ws {
            while i < cp.len() && " \t\r\n".contains(cp[i]) {
                i += 1;
            }
        } else if cp[i].is_alphabetic() {
            while i < cp.len() && cp[i].is_alphabetic() {
                i += 1;
            }
        } else if cp[i] == '"' {
            i += 1;
            while i < cp.len() && cp[i] != '"' {
                i += 1;
            }
            if i < cp.len() {
                i += 1;
            }
        } else {
            i += 1;
        }
        pieces.push(Piece {
            id: format!("p{start}"),
            span: span(start, i),
            kind: if ws { Kind::Whitespace } else { Kind::Token },
            owner: String::new(),
        });
    }
    SourceEdits::new(snapshot(uri, text), pieces).unwrap()
}
fn imported_fixture(
    alias: &str,
) -> (
    ProjectSymbolIndex,
    Vec<Inventory>,
    Vec<semantic_rename::ImportSite>,
    Definition,
) {
    let a = "foo foo";
    let b = format!("import foo as {alias};\r\n{alias} {{ {alias} {alias} }} {alias} \"foo\" 😀");
    let c = "import foo;\r\nfoo \"foo\"";
    let open = b.find('{').unwrap();
    let close = b.find('}').unwrap();
    let first = b.find('\n').unwrap() + 1;
    let decl = open + 2;
    let inner = decl + alias.len() + 1;
    let last = close + 2;
    let types = || {
        vec![Type {
            id: "T".into(),
            kind: TypeKind::Builtin,
            supertypes: vec![],
            fields: vec![],
            span: span(0, 0),
        }]
    };
    let model = |uri: &str, source: &str, scopes, symbols| {
        SemanticModel::new(
            uri.into(),
            1,
            source.into(),
            ModelData {
                types: types(),
                scopes,
                symbols,
                signatures: vec![],
                calls: vec![],
            },
        )
        .unwrap()
    };
    let root = |len| Scope {
        id: "root".into(),
        parent: None,
        span: span(0, len),
    };
    let am = model(
        "a",
        a,
        vec![root(7)],
        vec![Symbol {
            id: "exported".into(),
            name: "foo".into(),
            type_id: "T".into(),
            scope: "root".into(),
            declaration: span(0, 3),
            visible_from: 3,
        }],
    );
    let bm = model(
        "b",
        &b,
        vec![
            root(b.chars().count()),
            Scope {
                id: "inner".into(),
                parent: Some("root".into()),
                span: span(open, close + 1),
            },
        ],
        vec![Symbol {
            id: "shadow".into(),
            name: alias.into(),
            type_id: "T".into(),
            scope: "inner".into(),
            declaration: span(decl, decl + alias.len()),
            visible_from: decl + alias.len(),
        }],
    );
    let cm = model("c", c, vec![root(c.len())], vec![]);
    let import = |name: &str, end| Import {
        name: name.into(),
        target: ModuleRef {
            dependency: String::new(),
            module: "a".into(),
        },
        symbol: "foo".into(),
        scope: "root".into(),
        span: span(0, end),
        visible_from: end,
    };
    let modules = vec![
        Module {
            id: "a".into(),
            model: am,
            exports: BTreeSet::from(["exported".into()]),
            imports: vec![],
        },
        Module {
            id: "b".into(),
            model: bm,
            exports: BTreeSet::new(),
            imports: vec![import(alias, first)],
        },
        Module {
            id: "c".into(),
            model: cm,
            exports: BTreeSet::new(),
            imports: vec![import("foo", 13)],
        },
        second(),
    ];
    let inventory = |module: &str, source: &str, reference_tokens| Inventory {
        module: module.into(),
        source: lexical(module, source),
        reference_tokens,
        complete: true,
    };
    let inventories = vec![
        inventory("a", a, vec!["p4".into()]),
        inventory(
            "b",
            &b,
            vec![format!("p{first}"), format!("p{inner}"), format!("p{last}")],
        ),
        inventory("c", c, vec!["p13".into()]),
        inventory("second", "foo foo", vec!["p4".into()]),
    ];
    let sites = vec![
        semantic_rename::ImportSite {
            module: "b".into(),
            import_index: 0,
            source_token: "p7".into(),
            alias_token: "p14".into(),
        },
        semantic_rename::ImportSite {
            module: "c".into(),
            import_index: 0,
            source_token: "p7".into(),
            alias_token: String::new(),
        },
    ];
    let target = Definition {
        identity: Identity {
            project: "project".into(),
            dependency: String::new(),
            dependency_version: String::new(),
            module: "a".into(),
            symbol: "exported".into(),
        },
        uri: "a".into(),
        version: 1,
        span: span(0, 3),
    };
    (
        ProjectSymbolIndex::new("project".into(), 1, modules, vec![]).unwrap(),
        inventories,
        sites,
        target,
    )
}
#[test]
fn shared_import_and_alias_renames_use_different_identities() {
    for line in include_str!("../../../docs/fixtures/source-edits/import-rename.tsv")
        .lines()
        .filter(|l| !l.starts_with('#'))
    {
        let f = line.split('\t').collect::<Vec<_>>();
        let (index, inventories, sites, target) = imported_fixture(f[2]);
        let mut plans = if f[1] == "definition" {
            semantic_rename::prepare_with_imports(
                &index,
                &target,
                f[3],
                Some(&|name: &str| !name.is_empty() && name.chars().all(char::is_alphabetic)),
                &inventories,
                &sites,
            )
            .unwrap()
        } else {
            vec![semantic_rename::prepare_alias(
                &index,
                &sites[0],
                f[3],
                Some(&|name: &str| !name.is_empty() && name.chars().all(char::is_alphabetic)),
                &inventories,
                &sites,
            )
            .unwrap()]
        };
        if f[1] == "shadow" {
            let symbol = &index.modules()[1].model.data().symbols[0];
            let shadow_target = Definition {
                identity: Identity {
                    project: "project".into(),
                    dependency: String::new(),
                    dependency_version: String::new(),
                    module: "b".into(),
                    symbol: "shadow".into(),
                },
                uri: "b".into(),
                version: 1,
                span: symbol.declaration,
            };
            plans = semantic_rename::prepare_with_imports(
                &index,
                &shadow_target,
                f[3],
                Some(&identifier),
                &inventories,
                &sites,
            )
            .unwrap();
        }
        let current = inventories
            .iter()
            .map(|i| (i.source.snapshot().uri.clone(), i.source.snapshot().clone()))
            .collect::<HashMap<_, _>>();
        let versions = current.keys().map(|uri| (uri.clone(), 2)).collect();
        if plans.len() > 1 {
            assert!(single_document(plans.clone()).is_err());
        } else {
            assert_eq!(
                single_document(plans.clone()).unwrap().edits().len(),
                plans[0].edits().len()
            );
        }
        let modified = apply_all(&plans, &current, &versions).unwrap();
        for (i, uri) in ["a", "b", "c"].iter().enumerate() {
            assert_eq!(
                modified.get(*uri).unwrap_or(&current[*uri]).text,
                decode(f[4 + i]),
                "{} {uri}",
                f[0]
            );
        }
        assert!(!modified.contains_key("second"));
        assert!(
            semantic_rename::prepare(&index, &target, "bar", Some(&identifier), &inventories)
                .is_err()
        );
        assert!(semantic_rename::prepare_alias(
            &index,
            &sites[1],
            "bar",
            Some(&identifier),
            &inventories,
            &sites
        )
        .is_err());
        assert!(semantic_rename::prepare_with_imports(
            &index,
            &target,
            "bar",
            Some(&identifier),
            &inventories,
            &[sites[0].clone(), sites[0].clone()]
        )
        .is_err());
        let mut invalid = sites.clone();
        invalid[0].alias_token = "p7".into();
        assert!(semantic_rename::prepare_alias(
            &index,
            &sites[0],
            "foo",
            Some(&identifier),
            &inventories,
            &invalid
        )
        .is_err());
    }
}

fn partial_source() -> SourceEdits {
    let child = snapshot("java", "x  ?\r\n😀");
    let pieces = [
        ("x", 0, 1, Kind::Token, ""),
        ("space", 1, 3, Kind::Whitespace, "x"),
        ("unknown", 3, 4, Kind::Unparsed, ""),
        ("line", 4, 6, Kind::Whitespace, ""),
        ("emoji", 6, 7, Kind::Unparsed, ""),
    ]
    .into_iter()
    .map(|(id, start, end, kind, owner)| Piece {
        id: id.into(),
        span: span(start, end),
        kind,
        owner: owner.into(),
    })
    .collect();
    SourceEdits::new(child, pieces).unwrap()
}
#[test]
fn shared_edit_provider_maps_nested_regions_and_preserves_partial_text() {
    use std::collections::BTreeMap;
    use unlaxer_runtime::language_queries::{LanguageQueries, Project, Provider, Request};
    use unlaxer_runtime::source::{
        Language, LanguageRegions, Operation as QueryOperation, Region, State,
    };
    let child = partial_source().snapshot().clone();
    let tiny = snapshot("tiny", &format!("<{}>", child.text));
    let host = snapshot("formula", &format!("F[{}] tail", tiny.text));
    let map = copy(child.clone(), tiny.clone(), 1)
        .through(copy(tiny, host.clone(), 2))
        .unwrap();
    let language = Language {
        id: "java".into(),
        package_id: "example/java".into(),
        version: "1".into(),
        grammar: "Java".into(),
        entry: "Root".into(),
    };
    let project = Project {
        id: "p".into(),
        version: 1,
        documents: BTreeMap::from([(host.uri.clone(), host.clone())]),
        configuration: BTreeMap::new(),
    };
    let state = |name| match name {
        "COMPLETE" => State::Complete,
        "PARTIAL" => State::Partial,
        "FAILED" => State::Failed,
        _ => panic!("state"),
    };
    let region = |parse_state| Region {
        id: "java".into(),
        parent: None,
        language: language.clone(),
        full: span(2, 11),
        body: span(3, 10),
        source_map: map.clone(),
        parse_state,
    };
    for line in include_str!("../../../docs/fixtures/source-edits/provider.tsv")
        .lines()
        .filter(|l| !l.starts_with('#'))
    {
        let f = line.split('\t').collect::<Vec<_>>();
        let operation = match f[2] {
            "RENAME" => QueryOperation::Rename,
            "FORMAT" => QueryOperation::Format,
            "CODE_ACTION" => QueryOperation::CodeAction,
            _ => QueryOperation::Hover,
        };
        let mut policies: HashMap<Operation, EditPolicy> = HashMap::new();
        if operation != QueryOperation::Hover {
            let op = match operation {
                QueryOperation::Rename => Operation::Rename,
                QueryOperation::Format => Operation::Format,
                _ => Operation::CodeAction,
            };
            let edit = Edit {
                span: span(f[3].parse().unwrap(), f[4].parse().unwrap()),
                replacement: decode(f[5]),
            };
            policies.insert(
                op,
                Box::new(move |_| partial_source().plan(op, span(0, 7), vec![edit.clone()])),
            );
        }
        let count = policies.len();
        let provider = QueryProvider::new(
            partial_source(),
            language.clone(),
            project.clone(),
            policies,
        );
        assert_eq!(provider.capabilities().len(), count);
        let layer = LanguageQueries::new(
            LanguageRegions::new(host.clone(), vec![region(state(f[1]))]).unwrap(),
            project.clone(),
            HashMap::from([(language.clone(), Box::new(provider) as Box<dyn Provider>)]),
        )
        .unwrap();
        let result = layer
            .query(&host, &project, 3, operation, &BTreeMap::new())
            .unwrap();
        assert_eq!(format!("{:?}", result.state).to_uppercase(), f[6]);
        let edits = result
            .items
            .first()
            .map(|item| item.edits.clone())
            .unwrap_or_default();
        if f[7] != "-" {
            assert_eq!(
                edits[0].span,
                span(f[7].parse().unwrap(), f[8].parse().unwrap())
            );
        } else {
            assert!(edits.is_empty());
        }
        assert_eq!(
            LanguageRegions::new(host.clone(), vec![])
                .unwrap()
                .apply(&host, 2, &edits)
                .unwrap()
                .text,
            decode(f[9])
        );
    }
    let region = region(State::Partial);
    let parameters = BTreeMap::new();
    let request = Request {
        region: &region,
        operation: QueryOperation::Format,
        cursor: 1,
        project: &project,
        parameters: &parameters,
    };
    let wrong = QueryProvider::new(
        partial_source(),
        language.clone(),
        project.clone(),
        HashMap::from([(
            Operation::Format,
            Box::new(|_: &Request<'_>| {
                partial_source().plan(
                    Operation::CodeAction,
                    span(0, 7),
                    vec![Edit {
                        span: span(0, 1),
                        replacement: "z".into(),
                    }],
                )
            }) as EditPolicy,
        )]),
    );
    assert!(wrong.query(&request).is_err());
    let foreign = QueryProvider::new(
        partial_source(),
        language.clone(),
        project.clone(),
        HashMap::from([(
            Operation::Format,
            Box::new(|_: &Request<'_>| {
                let source = partial_source();
                let other = SourceEdits::new(
                    snapshot("other", &source.snapshot().text),
                    source.pieces().to_vec(),
                )?;
                other.plan(
                    Operation::Format,
                    span(0, 7),
                    vec![Edit {
                        span: span(1, 3),
                        replacement: " ".into(),
                    }],
                )
            }) as EditPolicy,
        )]),
    );
    assert!(foreign.query(&request).is_err());
    let mut stale = project.clone();
    stale.version = 0;
    assert!(wrong
        .query(&Request {
            project: &stale,
            ..request
        })
        .is_err());
    let mut other = region.clone();
    other.language.version = "2".into();
    assert!(wrong
        .query(&Request {
            region: &other,
            ..request
        })
        .is_err());
}
