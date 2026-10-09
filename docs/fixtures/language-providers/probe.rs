use std::collections::HashSet;
use std::path::Path;
use std::time::Duration;
use unlaxer_runtime::provider_process::ProviderProcess;
use unlaxer_runtime::provider_protocol::{self, Response};
use unlaxer_runtime::source::Operation;
fn observation(name: &str, response: &Response) -> String {
    let mut diagnostics: Vec<_> = response
        .diagnostics
        .iter()
        .flat_map(|d| {
            d.locations.iter().map(move |l| {
                format!(
                    "{}@{}:{}:{}:{}",
                    d.code, l.snapshot.uri, l.span.start, l.span.end, d.severity
                )
            })
        })
        .collect();
    diagnostics.sort();
    let items: Vec<_> = response
        .items
        .iter()
        .map(|item| {
            let locations = item
                .locations
                .iter()
                .map(|l| format!("{}:{}:{}", l.snapshot.uri, l.span.start, l.span.end))
                .collect::<Vec<_>>()
                .join(",");
            let edits = item
                .edits
                .iter()
                .map(|e| {
                    format!(
                        "{}:{}:{}:{}",
                        e.location.snapshot.uri,
                        e.location.span.start,
                        e.location.span.end,
                        provider_protocol::hex(&e.replacement)
                    )
                })
                .collect::<Vec<_>>()
                .join(",");
            format!(
                "{}:{}[{}][{}]",
                provider_protocol::hex(&item.label),
                provider_protocol::hex(&item.detail),
                locations,
                edits
            )
        })
        .collect();
    format!(
        "{}\t{}\t{}\t{}",
        name,
        format!("{:?}", response.status).to_uppercase(),
        if diagnostics.is_empty() {
            "-".into()
        } else {
            diagnostics.join(",")
        },
        if items.is_empty() {
            "-".into()
        } else {
            items.join(",")
        }
    )
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let directory = Path::new(&args[1]);
    let repo = Path::new(&args[2]);
    for expected in std::fs::read_to_string(directory.join("expected.tsv"))
        .unwrap()
        .lines()
    {
        let name = expected.split('\t').next().unwrap();
        let wire = std::fs::read_to_string(directory.join(format!("{name}.wire"))).unwrap();
        let owned = provider_protocol::read_request(&wire).unwrap();
        let request = owned.as_request();
        assert_eq!(
            provider_protocol::encode(&request).unwrap().text,
            wire,
            "canonical {name}"
        );
        let command = match request.provider.id.as_str() {
            "javac" => vec![
                args[3].clone(),
                "-cp".into(),
                args[4].clone(),
                "org.unlaxer.dsl.provider.JavacProvider".into(),
            ],
            "typescript" => vec![
                "python3".into(),
                repo.join("scripts/language-providers/provider.py")
                    .display()
                    .to_string(),
                "typescript".into(),
                args[5].clone(),
            ],
            "rustc" => vec![
                "python3".into(),
                repo.join("scripts/language-providers/provider.py")
                    .display()
                    .to_string(),
                "rust".into(),
                args[6].clone(),
            ],
            _ => panic!("unknown fixture provider"),
        };
        let provider = ProviderProcess {
            command,
            identity: request.provider.clone(),
            operations: HashSet::from([
                Operation::Parse,
                Operation::Validate,
                Operation::Hover,
                Operation::Definition,
                Operation::Completion,
                Operation::Format,
            ]),
            timeout: Duration::from_secs(30),
        };
        let response = provider.invoke(&request).unwrap();
        if name == "rust-macro-origin" {
            assert_eq!(response.diagnostics.len(), 1);
            assert_eq!(response.diagnostics[0].locations.len(), 2);
        }
        let actual = observation(name, &response);
        assert_eq!(actual, expected, "{name}");
        assert!(!response.capabilities.contains("EXECUTE_USER_CODE"));
        println!("{actual}");
    }
    let owned = provider_protocol::read_request(
        &std::fs::read_to_string(directory.join("ts-completion.wire")).unwrap(),
    )
    .unwrap();
    let base = owned.as_request();
    use std::collections::{BTreeMap, HashMap};
    use unlaxer_runtime::{
        language_queries::{LanguageQueries, Project, Provider},
        source::{Kind, LanguageRegions, Location, Region, Segment, Snapshot, SourceMap, State},
        Span,
    };
    let snapshot =
        Snapshot::new(&base.snapshot.uri, 7, format!("{} ", base.snapshot.text)).unwrap();
    let host = Snapshot::new("file:///host.formula", 7, format!("😀[{}]", snapshot.text)).unwrap();
    let body = Span {
        start: 2,
        end: host.len() - 1,
    };
    let map = SourceMap::new(
        snapshot.clone(),
        vec![Segment {
            output: Span {
                start: 0,
                end: snapshot.len(),
            },
            kind: Kind::Copy,
            origin: Some(Location::new(host.clone(), body).unwrap()),
        }],
    )
    .unwrap();
    let region = Region {
        id: "ts".into(),
        parent: None,
        language: base.language.clone(),
        full: Span {
            start: 0,
            end: host.len(),
        },
        body,
        source_map: map,
        parse_state: State::Partial,
    };
    let project = Project {
        id: "project".into(),
        version: 12,
        documents: BTreeMap::from([(host.uri.clone(), host.clone())]),
        configuration: BTreeMap::new(),
    };
    let process = ProviderProcess {
        command: vec![
            "python3".into(),
            repo.join("scripts/language-providers/provider.py")
                .display()
                .to_string(),
            "typescript".into(),
            args[5].clone(),
        ],
        identity: base.provider.clone(),
        operations: HashSet::from([Operation::Completion]),
        timeout: Duration::from_secs(30),
    };
    let queries = LanguageQueries::new(
        LanguageRegions::new(host.clone(), vec![region]).unwrap(),
        project.clone(),
        HashMap::from([(
            base.language.clone(),
            Box::new(process) as Box<dyn Provider>,
        )]),
    )
    .unwrap();
    let result = queries
        .query(
            &host,
            &project,
            22,
            Operation::Completion,
            &BTreeMap::from([("prefix".into(), "alp".into())]),
        )
        .unwrap();
    assert_eq!(result.state, State::Partial);
    assert_eq!(result.items.len(), 1);
    let edit = &result.items[0].edits[0];
    assert_eq!(edit.span, Span { start: 19, end: 22 });
    assert_eq!(edit.replacement, "alpha");
    assert_eq!(host.slice(edit.span).unwrap(), "alp");
}
