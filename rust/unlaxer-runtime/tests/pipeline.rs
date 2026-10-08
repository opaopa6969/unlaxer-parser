use std::cell::Cell;
use std::collections::{BTreeMap, HashMap};
use std::rc::Rc;
use unlaxer_runtime::{
    pipeline::*,
    source::{Kind, Location, Segment, Snapshot, SourceMap},
    Span,
};
fn phase(id: &str, dependencies: &[&str], inputs: &[&str], keys: &[&str]) -> Phase {
    Phase {
        id: id.into(),
        dependencies: dependencies.iter().map(|value| (*value).into()).collect(),
        inputs: inputs.iter().map(|value| (*value).into()).collect(),
        configuration_keys: keys.iter().map(|value| (*value).into()).collect(),
        executes_user_code: false,
    }
}
fn source(snapshot: &Snapshot) -> Artifact {
    Artifact {
        state: State::Complete,
        payload: snapshot.text.clone(),
        origins: vec![Location::new(
            snapshot.clone(),
            Span {
                start: 0,
                end: snapshot.len(),
            },
        )
        .unwrap()],
        diagnostics: vec![],
    }
}
fn pipeline() -> AnalysisPipeline {
    let mut executors: HashMap<String, Box<dyn Executor>> = HashMap::new();
    executors.insert(
        "include".into(),
        Box::new(|request: &Request| Ok(source(&request.inputs["included"]))),
    );
    executors.insert(
        "collect".into(),
        Box::new(|request: &Request| {
            if request.configuration.get("active").map(String::as_str) == Some("false") {
                return Ok(Artifact::empty(State::Inactive));
            }
            let main = &request.inputs["main"];
            let included = &request.dependencies["include"];
            let state = if main.text.contains('?') {
                State::Partial
            } else if main.text == "bad" {
                State::Failed
            } else {
                State::Complete
            };
            Ok(Artifact {
                state,
                payload: format!("{}|{}", main.text, included.payload),
                origins: vec![
                    Location::new(
                        main.clone(),
                        Span {
                            start: 0,
                            end: main.len(),
                        },
                    )
                    .unwrap(),
                    included.origins[0].clone(),
                ],
                diagnostics: vec![],
            })
        }),
    );
    executors.insert(
        "resolve".into(),
        Box::new(|request: &Request| Ok(request.dependencies["collect"].clone())),
    );
    AnalysisPipeline::new(
        vec![
            phase("resolve", &["collect"], &[], &[]),
            phase("collect", &["include"], &["main"], &["active"]),
            phase("include", &[], &["included"], &[]),
        ],
        executors,
    )
    .unwrap()
}
#[test]
fn shared_dependency_and_configuration_corpus() {
    let mut pipeline = pipeline();
    let mut previous_revision = 0;
    for line in include_str!("../../../docs/fixtures/pipeline/evaluations.tsv")
        .lines()
        .filter(|line| !line.starts_with('#'))
    {
        let fields: Vec<&str> = line.split('\t').collect();
        let main = Snapshot::new("main", fields[1].parse().unwrap(), fields[2]).unwrap();
        let included = Snapshot::new("included", fields[3].parse().unwrap(), fields[4]).unwrap();
        let result = pipeline
            .evaluate(
                "resolve",
                BTreeMap::from([
                    ("main".into(), main.clone()),
                    ("included".into(), included.clone()),
                ]),
                BTreeMap::from([
                    ("active".into(), fields[5].into()),
                    ("unrelated".into(), fields[6].into()),
                ]),
                3,
                false,
            )
            .unwrap();
        assert_eq!(
            format!("{:?}", result.artifact.state).to_uppercase(),
            fields[7],
            "{}",
            fields[0]
        );
        assert_eq!(
            result.artifact.payload,
            if fields[8] == "-" { "" } else { fields[8] },
            "{}",
            fields[0]
        );
        assert_eq!(
            result.evaluated.join(","),
            if fields[9] == "-" { "" } else { fields[9] },
            "{}",
            fields[0]
        );
        assert_eq!(
            result.reused.join(","),
            if fields[10] == "-" { "" } else { fields[10] },
            "{}",
            fields[0]
        );
        let revision = result.revision.unwrap();
        if result.evaluated.is_empty() {
            assert_eq!(revision, previous_revision);
        } else {
            assert!(revision > previous_revision);
        }
        previous_revision = revision;
        if result.artifact.state != State::Inactive {
            assert_eq!(
                result
                    .artifact
                    .origins
                    .into_iter()
                    .map(|location| location.snapshot)
                    .collect::<Vec<_>>(),
                vec![main, included]
            );
        }
    }
}
fn input() -> BTreeMap<String, Snapshot> {
    BTreeMap::from([
        ("main".into(), Snapshot::new("main", 1, "x").unwrap()),
        (
            "included".into(),
            Snapshot::new("included", 1, "y").unwrap(),
        ),
    ])
}
#[test]
fn incomplete_states_do_not_reuse_success() {
    let mut pipeline = pipeline();
    assert_eq!(
        pipeline
            .evaluate("resolve", input(), BTreeMap::new(), 3, false)
            .unwrap()
            .artifact
            .state,
        State::Complete
    );
    assert_eq!(
        pipeline
            .evaluate("resolve", BTreeMap::new(), BTreeMap::new(), 3, false)
            .unwrap()
            .artifact
            .state,
        State::Deferred
    );
    let limited = pipeline
        .evaluate("resolve", input(), BTreeMap::new(), 2, false)
        .unwrap();
    assert_eq!(limited.artifact.state, State::Limit);
    assert!(limited.revision.is_none());
    assert_eq!(
        pipeline
            .evaluate("resolve", input(), BTreeMap::new(), 3, false)
            .unwrap()
            .artifact
            .state,
        State::Complete
    );
    pipeline.clear_cache();
    assert_eq!(
        pipeline
            .evaluate("resolve", input(), BTreeMap::new(), 3, false)
            .unwrap()
            .evaluated,
        vec!["include", "collect", "resolve"]
    );
    assert!(pipeline
        .evaluate("resolve", input(), BTreeMap::new(), 257, false)
        .is_err());
}
#[test]
fn cycles_missing_providers_and_execution_permission() {
    let called = Rc::new(Cell::new(0));
    let executor = |counter: Rc<Cell<i32>>| -> Box<dyn Executor> {
        Box::new(move |_: &Request| {
            counter.set(counter.get() + 1);
            Ok(Artifact::empty(State::Complete))
        })
    };
    let mut cyclic = AnalysisPipeline::new(
        vec![phase("a", &["b"], &[], &[]), phase("b", &["a"], &[], &[])],
        HashMap::from([
            ("a".into(), executor(called.clone())),
            ("b".into(), executor(called.clone())),
        ]),
    )
    .unwrap();
    assert_eq!(
        cyclic
            .evaluate("a", BTreeMap::new(), BTreeMap::new(), 2, false)
            .unwrap()
            .artifact
            .state,
        State::Cycle
    );
    assert_eq!(called.get(), 0);
    let mut missing =
        AnalysisPipeline::new(vec![phase("a", &[], &[], &[])], HashMap::new()).unwrap();
    assert_eq!(
        missing
            .evaluate("a", BTreeMap::new(), BTreeMap::new(), 1, false)
            .unwrap()
            .artifact
            .state,
        State::Unsupported
    );
    let mut user_phase = phase("a", &[], &[], &[]);
    user_phase.executes_user_code = true;
    let mut external = AnalysisPipeline::new(
        vec![user_phase],
        HashMap::from([("a".into(), executor(called.clone()))]),
    )
    .unwrap();
    assert_eq!(
        external
            .evaluate("a", BTreeMap::new(), BTreeMap::new(), 1, false)
            .unwrap()
            .artifact
            .state,
        State::Unsupported
    );
    assert_eq!(called.get(), 0);
    assert_eq!(
        external
            .evaluate("a", BTreeMap::new(), BTreeMap::new(), 1, true)
            .unwrap()
            .artifact
            .state,
        State::Complete
    );
    assert_eq!(called.get(), 1);
    assert_eq!(
        external
            .evaluate("a", BTreeMap::new(), BTreeMap::new(), 1, false)
            .unwrap()
            .artifact
            .state,
        State::Unsupported
    );
    assert_eq!(called.get(), 1);
}
#[test]
fn generated_diagnostic_origins_survive_artifacts_and_repair() {
    let original = Snapshot::new("original", 3, "日😀").unwrap();
    let generated = Snapshot::new("generated", 3, "[日😀]").unwrap();
    let map = SourceMap::new(
        generated,
        vec![
            Segment {
                output: Span { start: 0, end: 1 },
                kind: Kind::Generated,
                origin: Some(Location::new(original.clone(), Span { start: 0, end: 0 }).unwrap()),
            },
            Segment {
                output: Span { start: 1, end: 3 },
                kind: Kind::Copy,
                origin: Some(Location::new(original.clone(), Span { start: 0, end: 2 }).unwrap()),
            },
            Segment {
                output: Span { start: 3, end: 4 },
                kind: Kind::Generated,
                origin: Some(Location::new(original.clone(), Span { start: 2, end: 2 }).unwrap()),
            },
        ],
    )
    .unwrap();
    let partial = Artifact {
        state: State::Partial,
        payload: "recovered".into(),
        origins: vec![Location::new(original.clone(), Span { start: 0, end: 2 }).unwrap()],
        diagnostics: map.diagnostics(Span { start: 2, end: 3 }).unwrap(),
    };
    let mut executors: HashMap<String, Box<dyn Executor>> = HashMap::new();
    executors.insert(
        "parse".into(),
        Box::new(move |_: &Request| Ok(partial.clone())),
    );
    executors.insert(
        "repair".into(),
        Box::new(|request: &Request| {
            let mut artifact = request.dependencies["parse"].clone();
            artifact.state = State::Complete;
            Ok(artifact)
        }),
    );
    let mut pipeline = AnalysisPipeline::new(
        vec![
            phase("repair", &["parse"], &[], &[]),
            phase("parse", &[], &["main"], &[]),
        ],
        executors,
    )
    .unwrap();
    let result = pipeline
        .evaluate(
            "repair",
            BTreeMap::from([("main".into(), original.clone())]),
            BTreeMap::new(),
            2,
            false,
        )
        .unwrap();
    assert_eq!(result.artifact.state, State::Complete);
    assert_eq!(
        result.artifact.diagnostics[0].location,
        Location::new(original, Span { start: 1, end: 2 }).unwrap()
    );
    assert!(map.edit(Span { start: 0, end: 1 }).is_err());
}

#[test]
fn shared_dependencies_are_evaluated_once_within_budget() {
    let definitions = vec![
        phase("root", &["left", "right"], &[], &[]),
        phase("left", &["shared"], &[], &[]),
        phase("right", &["shared"], &[], &[]),
        phase("shared", &[], &[], &[]),
    ];
    let mut executors: HashMap<String, Box<dyn Executor>> = HashMap::new();
    for id in ["root", "left", "right", "shared"] {
        executors.insert(
            id.into(),
            Box::new(|request: &Request| {
                Ok(Artifact {
                    state: State::Complete,
                    payload: request.phase.id.clone(),
                    origins: vec![],
                    diagnostics: vec![],
                })
            }),
        );
    }
    let mut pipeline = AnalysisPipeline::new(definitions, executors).unwrap();
    let first = pipeline
        .evaluate("root", BTreeMap::new(), BTreeMap::new(), 4, false)
        .unwrap();
    assert_eq!(first.evaluated, vec!["shared", "left", "right", "root"]);
    let second = pipeline
        .evaluate("root", BTreeMap::new(), BTreeMap::new(), 4, false)
        .unwrap();
    assert_eq!(first.revision, second.revision);
    assert_eq!(second.reused, vec!["shared", "left", "right", "root"]);
    assert!(
        AnalysisPipeline::new(vec![phase("a", &["missing"], &[], &[])], HashMap::new()).is_err()
    );
}
