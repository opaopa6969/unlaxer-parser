use std::collections::{HashMap, HashSet};
use unlaxer_runtime::{source::*, Span};
fn span(start: usize, end: usize) -> Span {
    Span { start, end }
}
fn snapshot(uri: &str, text: &str) -> Snapshot {
    Snapshot::new(uri, 1, text).unwrap()
}
fn decode(text: &str) -> String {
    text.replace("\\r", "\r")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
}
fn copy(output: Snapshot, origin: Snapshot, start: usize) -> SourceMap {
    let end = output.len();
    SourceMap::new(
        output,
        vec![Segment {
            output: span(0, end),
            kind: Kind::Copy,
            origin: Some(Location::new(origin, span(start, start + end)).unwrap()),
        }],
    )
    .unwrap()
}
#[test]
fn shared_maps() {
    for line in include_str!("../../../docs/fixtures/source-maps/maps.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let columns: Vec<&str> = line.split('\t').collect();
        let output = snapshot("virtual", &decode(columns[1]));
        let origin = snapshot("host", &decode(columns[2]));
        let segments = columns[3]
            .split(',')
            .map(|specification| {
                let fields: Vec<&str> = specification.split(':').collect();
                Segment {
                    output: span(fields[0].parse().unwrap(), fields[1].parse().unwrap()),
                    kind: match fields[2] {
                        "C" => Kind::Copy,
                        "T" => Kind::Transformed,
                        _ => Kind::Generated,
                    },
                    origin: if fields[3] == "-" {
                        None
                    } else {
                        Some(
                            Location::new(
                                origin.clone(),
                                span(fields[3].parse().unwrap(), fields[4].parse().unwrap()),
                            )
                            .unwrap(),
                        )
                    },
                }
            })
            .collect();
        let map = SourceMap::new(output, segments).unwrap();
        let query = span(columns[4].parse().unwrap(), columns[5].parse().unwrap());
        let actual = map
            .diagnostics(query)
            .unwrap()
            .iter()
            .map(|value| {
                format!(
                    "{}:{}:{}",
                    value.location.span.start, value.location.span.end, value.exact
                )
            })
            .collect::<Vec<_>>()
            .join(",");
        assert_eq!(
            actual,
            if columns[6] == "-" { "" } else { columns[6] },
            "{}",
            columns[0]
        );
        if columns[7] == "REJECT" {
            assert!(map.edit(query).is_err(), "{}", columns[0]);
        } else {
            let mapped = map.edit(query).unwrap();
            assert_eq!(
                format!("{}:{}", mapped.span.start, mapped.span.end),
                columns[7],
                "{}",
                columns[0]
            );
        }
    }
}
#[test]
fn shared_positions() {
    let source = snapshot("host", "日😀\r\nx\ry\nz");
    for line in include_str!("../../../docs/fixtures/source-maps/positions.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let fields: Vec<i64> = line
            .split('\t')
            .map(|field| field.parse().unwrap())
            .collect();
        let point = fields[0] as usize;
        assert_eq!(source.utf16(point).unwrap(), fields[1] as usize);
        assert_eq!(source.utf8(point).unwrap(), fields[2] as usize);
        assert_eq!(source.from_utf16(fields[1] as usize).unwrap(), point);
        assert_eq!(source.from_utf8(fields[2] as usize).unwrap(), point);
        if fields[3] == -1 {
            assert!(source.lsp(point).is_err());
        } else {
            let position = Position {
                line: fields[3] as usize,
                character: fields[4] as usize,
            };
            assert_eq!(source.lsp(point).unwrap(), position);
            assert_eq!(source.from_lsp(position).unwrap(), point);
        }
    }
    assert!(source.from_utf16(2).is_err());
    assert!(source.from_utf8(4).is_err());
    assert!(source
        .from_lsp(Position {
            line: 0,
            character: 2
        })
        .is_err());
}
#[test]
fn composition_and_multiple_origins() {
    let host = snapshot("host", "[日😀x]");
    let middle = snapshot("tiny", "日😀x");
    let child = snapshot("java", "😀x");
    let composed = copy(child.clone(), middle.clone(), 1)
        .through(copy(middle.clone(), host.clone(), 1))
        .unwrap();
    assert_eq!(
        composed.edit(span(0, 1)).unwrap(),
        Location::new(host.clone(), span(2, 3)).unwrap()
    );
    assert!(composed.through(copy(middle, host.clone(), 1)).is_err());
    let second = Snapshot::new("include", 2, "x").unwrap();
    let multiple = SourceMap::new(
        child.clone(),
        vec![
            Segment {
                output: span(0, 1),
                kind: Kind::Copy,
                origin: Some(Location::new(host.clone(), span(2, 3)).unwrap()),
            },
            Segment {
                output: span(1, 2),
                kind: Kind::Copy,
                origin: Some(Location::new(second.clone(), span(0, 1)).unwrap()),
            },
        ],
    )
    .unwrap();
    assert_eq!(
        multiple
            .diagnostics(span(0, 2))
            .unwrap()
            .into_iter()
            .map(|value| value.location.snapshot)
            .collect::<Vec<_>>(),
        vec![host.clone(), second]
    );
    assert!(multiple.edit(span(0, 2)).is_err());
    assert!(SourceMap::new(
        child.clone(),
        vec![Segment {
            output: span(0, 2),
            kind: Kind::Copy,
            origin: Some(Location::new(host, span(0, 2)).unwrap())
        }]
    )
    .is_err());
    assert!(SourceMap::new(child, vec![]).is_err());
}
fn host() -> Snapshot {
    Snapshot::new("file:///formula", 7, "#日😀\r\n{<abc><def>}\r\n").unwrap()
}
fn language(id: &str, grammar: &str, entry: &str) -> Language {
    Language {
        id: id.into(),
        package_id: "local".into(),
        version: "1".into(),
        grammar: grammar.into(),
        entry: entry.into(),
    }
}
fn java() -> Language {
    language("java", "Java", "CompilationUnit")
}
fn region(id: &str, parent: Option<&str>, language: Language, full: Span, body: Span) -> Region {
    let host = host();
    let output = Snapshot::new(format!("virtual:///{id}"), 7, host.slice(body).unwrap()).unwrap();
    Region {
        id: id.into(),
        parent: parent.map(String::from),
        language,
        full,
        body,
        source_map: copy(output, host, body.start),
        parse_state: State::Partial,
    }
}
fn regions() -> LanguageRegions {
    LanguageRegions::new(
        host(),
        vec![
            region(
                "formula",
                None,
                language("formula", "FormulaInfo", "Document"),
                span(0, 19),
                span(0, 19),
            ),
            region(
                "tiny",
                Some("formula"),
                language("tiny", "TinyExpression", "Expression"),
                span(5, 17),
                span(6, 16),
            ),
            region("java1", Some("tiny"), java(), span(6, 11), span(7, 10)),
            region("java2", Some("tiny"), java(), span(11, 16), span(12, 15)),
        ],
    )
    .unwrap()
}
struct FixtureProvider {
    state: State,
    stale: bool,
    edits: Vec<Edit>,
}
impl Provider for FixtureProvider {
    fn capabilities(&self) -> HashSet<Operation> {
        [Operation::Parse, Operation::Completion].into()
    }
    fn invoke(&self, region: &Region, _: Operation) -> Result<Response> {
        let mut snapshot = region.source_map.output().clone();
        if self.stale {
            snapshot.version = 6;
        }
        Ok(Response {
            snapshot,
            state: self.state,
            diagnostics: vec![span(1, 2)],
            edits: self.edits.clone(),
        })
    }
}
fn provider(state: State, stale: bool, edits: Vec<Edit>) -> HashMap<Language, Box<dyn Provider>> {
    HashMap::from([(
        java(),
        Box::new(FixtureProvider {
            state,
            stale,
            edits,
        }) as Box<dyn Provider>,
    )])
}
#[test]
fn nested_dispatch_and_source_preserving_edits() {
    let regions = regions();
    assert_eq!(regions.at(8).unwrap().unwrap().id, "java1");
    assert_eq!(regions.at(13).unwrap().unwrap().id, "java2");
    assert_eq!(regions.at(10).unwrap().unwrap().id, "tiny");
    assert_eq!(regions.at(5).unwrap().unwrap().id, "formula");
    assert!(regions.at(19).unwrap().is_none());
    assert_eq!(
        regions
            .dispatch("java1", Operation::Parse, &HashMap::new(), &host())
            .unwrap()
            .state,
        State::Unavailable
    );
    let providers = provider(
        State::Partial,
        false,
        vec![Edit {
            span: span(1, 2),
            replacement: "名前😀".into(),
        }],
    );
    assert_eq!(
        regions
            .dispatch("java1", Operation::Format, &providers, &host())
            .unwrap()
            .state,
        State::Unsupported
    );
    let response = regions
        .dispatch("java1", Operation::Completion, &providers, &host())
        .unwrap();
    assert_eq!(response.state, State::Partial);
    assert_eq!(response.diagnostics[0].location.span, span(8, 9));
    assert_eq!(
        regions.apply(&host(), 8, &response.edits).unwrap().text,
        "#日😀\r\n{<a名前😀c><def>}\r\n"
    );
    assert_eq!(
        regions.apply(&host(), 8, &[]).unwrap().text.as_bytes(),
        host().text.as_bytes()
    );
    for state in [State::Failed, State::Timeout, State::Unsupported] {
        assert_eq!(
            regions
                .dispatch(
                    "java1",
                    Operation::Parse,
                    &provider(state, false, vec![]),
                    &host()
                )
                .unwrap()
                .state,
            state
        );
    }
    assert!(regions
        .dispatch(
            "java1",
            Operation::Parse,
            &provider(State::Complete, true, vec![]),
            &host()
        )
        .is_err());
    let mut stale = host();
    stale.version = 8;
    assert!(regions
        .dispatch("java1", Operation::Parse, &providers, &stale)
        .is_err());
    assert!(regions
        .apply(
            &host(),
            8,
            &[
                Edit {
                    span: span(8, 9),
                    replacement: "x".into()
                },
                Edit {
                    span: span(8, 9),
                    replacement: "y".into()
                }
            ]
        )
        .is_err());
    assert!(regions.apply(&host(), 7, &[]).is_err());
    assert!(regions
        .dispatch(
            "java1",
            Operation::Parse,
            &provider(
                State::Complete,
                false,
                vec![Edit {
                    span: span(0, 4),
                    replacement: "oops".into()
                }]
            ),
            &host()
        )
        .is_err());
}
#[test]
fn malformed_region_trees_fail() {
    let first = region("one", Some("missing"), java(), span(6, 11), span(7, 10));
    assert!(LanguageRegions::new(host(), vec![first]).is_err());
    let cycle = region("cycle", Some("cycle"), java(), span(7, 10), span(7, 10));
    assert!(LanguageRegions::new(host(), vec![cycle]).is_err());
    let overlap = region("two", None, java(), span(6, 11), span(7, 10));
    assert!(LanguageRegions::new(host(), vec![overlap.clone(), overlap]).is_err());
}

#[test]
fn composed_aliases_are_not_editable() {
    let host = snapshot("host", "x");
    let first = snapshot("first", "x");
    let second = snapshot("second", "x");
    let map = SourceMap::new(
        snapshot("output", "xx"),
        vec![
            Segment {
                output: span(0, 1),
                kind: Kind::Copy,
                origin: Some(Location::new(first.clone(), span(0, 1)).unwrap()),
            },
            Segment {
                output: span(1, 2),
                kind: Kind::Copy,
                origin: Some(Location::new(second.clone(), span(0, 1)).unwrap()),
            },
        ],
    )
    .unwrap()
    .through(copy(first, host.clone(), 0))
    .unwrap()
    .through(copy(second, host, 0))
    .unwrap();
    assert_eq!(map.diagnostics(span(0, 2)).unwrap().len(), 2);
    assert!(map.edit(span(0, 1)).is_err());
}
#[test]
fn actual_parser_callback_is_bounded_and_sibling_failure_is_isolated() {
    struct ParserProvider;
    impl Provider for ParserProvider {
        fn capabilities(&self) -> HashSet<Operation> {
            [Operation::Parse].into()
        }
        fn invoke(&self, region: &Region, _: Operation) -> Result<Response> {
            let snapshot = region.source_map.output().clone();
            let rules = [unlaxer_runtime::Rule {
                name: "CompilationUnit",
                expression: unlaxer_runtime::Expr::Literal("abc"),
            }];
            let parsed = unlaxer_runtime::parse(&rules, 0, false, &snapshot.text);
            Ok(Response {
                snapshot,
                state: if parsed.is_ok() {
                    State::Complete
                } else {
                    State::Failed
                },
                diagnostics: if parsed.is_err() {
                    vec![span(0, 0)]
                } else {
                    vec![]
                },
                edits: vec![],
            })
        }
    }
    let regions = regions();
    let providers = HashMap::from([(java(), Box::new(ParserProvider) as Box<dyn Provider>)]);
    assert_eq!(
        regions
            .dispatch("java1", Operation::Parse, &providers, &host())
            .unwrap()
            .state,
        State::Complete
    );
    let failed = regions
        .dispatch("java2", Operation::Parse, &providers, &host())
        .unwrap();
    assert_eq!(failed.state, State::Failed);
    assert_eq!(failed.diagnostics[0].location.span, span(12, 12));
    assert_eq!(
        regions
            .dispatch("java1", Operation::Parse, &providers, &host())
            .unwrap()
            .state,
        State::Complete
    );
}
