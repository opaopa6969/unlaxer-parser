mod bridge;
use std::{
    collections::{BTreeMap, HashSet},
    path::Path,
    time::Duration,
};
use unlaxer_runtime::{
    language_queries::Project, provider_process::ProviderProcess, provider_protocol::Identity,
    source::*,
};
fn state(s: State) -> String {
    format!("{s:?}").to_uppercase()
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let fixture = Path::new(&args[1]);
    let profile = unlaxer_runtime::language_profile::LanguageProfile::parse(
        &std::fs::read_to_string(fixture.join("../../../language-profiles/java/profile.tsv"))
            .unwrap(),
    )
    .unwrap();
    assert_eq!(
        profile.identity("CompilationUnit").unwrap(),
        bridge::language("java")
    );
    for row in std::fs::read_to_string(fixture.join("cases.tsv"))
        .unwrap()
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
    {
        let fields: Vec<_> = row.split('\t').collect();
        let name = fields[0];
        let host = Snapshot::new(
            "file:///production.formula",
            7,
            std::fs::read_to_string(fixture.join(format!("{name}.txt"))).unwrap(),
        )
        .unwrap();
        let binding = match bridge::parse(&host) {
            Ok(value) => value,
            Err(_) => {
                println!("{name}\trejected");
                continue;
            }
        };
        for r in &binding.regions {
            println!(
                "{name}\tregion\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
                r.id,
                state(r.parse_state),
                r.full.start,
                r.full.end,
                r.body.start,
                r.body.end,
                binding.java_files.get(&r.id).map_or("-", String::as_str)
            );
        }
        let project = Project {
            id: "tiny-production".into(),
            version: 12,
            documents: BTreeMap::from([(host.uri.clone(), host.clone())]),
            configuration: BTreeMap::from([("classpath".into(), args[3].clone())]),
        };
        let process = ProviderProcess {
            command: vec![
                args[2].clone(),
                "-cp".into(),
                args[3].clone(),
                "org.unlaxer.dsl.provider.JavacProvider".into(),
            ],
            identity: Identity {
                id: "javac".into(),
                version: "21.0.9".into(),
            },
            operations: HashSet::from([
                Operation::Validate,
                Operation::Completion,
                Operation::Hover,
                Operation::Definition,
            ]),
            timeout: Duration::from_secs(30),
        };
        let queries = binding.queries(project.clone(), Box::new(process)).unwrap();
        if fields[1] == "validate" {
            for result in queries
                .diagnostics_all(&host, &project, &BTreeMap::new())
                .unwrap()
            {
                if !binding.java_files.contains_key(&result.region) {
                    continue;
                }
                println!(
                    "{name}\tvalidate\t{}\t{}\t{}",
                    result.region,
                    state(result.state),
                    result.diagnostics.len()
                );
                for diagnostic in result.diagnostics {
                    for mapped in diagnostic.locations {
                        let location = mapped.location;
                        assert_eq!(location.snapshot, host);
                        assert!(mapped.exact);
                        assert!(!diagnostic.message.is_empty());
                        let position = host.lsp(location.span.start).unwrap();
                        println!(
                            "{name}\tdiagnostic\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
                            result.region,
                            diagnostic.code,
                            diagnostic.severity,
                            location.span.start,
                            location.span.end,
                            position.line,
                            position.character
                        );
                    }
                }
            }
        }
        for query in std::fs::read_to_string(fixture.join("queries.tsv"))
            .unwrap()
            .lines()
        {
            let q: Vec<_> = query.split('\t').collect();
            if q[0] != name {
                continue;
            }
            let operation = if q[2] == "HOVER" {
                Operation::Hover
            } else {
                Operation::Definition
            };
            let result = queries
                .query(
                    &host,
                    &project,
                    q[1].parse().unwrap(),
                    operation,
                    &BTreeMap::new(),
                )
                .unwrap();
            assert_eq!(result.items.len(), 1);
            let item = &result.items[0];
            let location = &item.locations[0].location;
            assert_eq!(location.snapshot, host);
            assert!(item.locations[0].exact);
            println!(
                "{name}\tquery\t{}\t{}\t{}\t{}\t{}\t{}",
                q[2],
                state(result.state),
                item.label,
                item.detail,
                location.span.start,
                location.span.end
            );
        }
        if fields[1] == "completion" {
            let cursor = fields[2].parse().unwrap();
            let parameters = BTreeMap::from([("prefix".into(), "tar".into())]);
            let result = queries
                .query(&host, &project, cursor, Operation::Completion, &parameters)
                .unwrap();
            let item = result
                .items
                .iter()
                .find(|item| item.label == "target")
                .unwrap();
            let edit = &item.edits[0];
            let changed = binding
                .tree()
                .unwrap()
                .apply(&host, 8, std::slice::from_ref(edit))
                .unwrap();
            assert_eq!(
                changed.text,
                std::fs::read_to_string(fixture.join("completion-edited.txt")).unwrap()
            );
            println!(
                "{name}\tcompletion\t{}\t{}\t{}\t{}",
                state(result.state),
                edit.span.start,
                edit.span.end,
                edit.replacement
            );
            bridge::parse(&changed).unwrap();
        }
        let next = Snapshot::new(&host.uri, 8, &host.text).unwrap();
        assert!(queries
            .query(&next, &project, 0, Operation::Completion, &BTreeMap::new())
            .is_err());
        let mut next_project = project.clone();
        next_project.version += 1;
        assert!(queries
            .query(
                &host,
                &next_project,
                0,
                Operation::Completion,
                &BTreeMap::new()
            )
            .is_err());
        let changed = Snapshot::new(&host.uri, 7, format!("{} ", host.text)).unwrap();
        assert!(queries
            .query(
                &changed,
                &project,
                0,
                Operation::Completion,
                &BTreeMap::new()
            )
            .is_err());
        let mut config = project.clone();
        config.configuration.clear();
        assert!(queries
            .query(&host, &config, 0, Operation::Completion, &BTreeMap::new())
            .is_err());
        for r in &binding.regions {
            if r.language == bridge::language("java") {
                assert_ne!(
                    binding
                        .tree()
                        .unwrap()
                        .at(r.body.end)
                        .unwrap()
                        .unwrap()
                        .language,
                    bridge::language("java")
                );
                assert_eq!(
                    queries
                        .query(
                            &host,
                            &project,
                            r.body.start,
                            Operation::Rename,
                            &BTreeMap::new()
                        )
                        .unwrap()
                        .state,
                    State::Unsupported
                );
            }
        }
        println!("{name}\tguards\tPASS");
    }
}
