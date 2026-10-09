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
