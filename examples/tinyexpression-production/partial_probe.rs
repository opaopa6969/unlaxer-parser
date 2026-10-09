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
fn state(value: State) -> String {
    format!("{value:?}").to_uppercase()
}
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let fixture = Path::new(&args[1]);
    for name in std::fs::read_to_string(fixture.join("partial/cases.tsv"))
        .unwrap()
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
    {
        let host = Snapshot::new(
            "file:///partial.formula",
            7,
            std::fs::read_to_string(fixture.join(format!("partial/{name}.txt"))).unwrap(),
        )
        .unwrap();
        let strict = bridge::parse(&host).unwrap();
        let binding = bridge::parse_editor(&host).unwrap();
        println!(
            "{name}\tstrict\t{}\t{}",
            strict.java_files.len(),
            strict
                .regions
                .iter()
                .filter(|region| region.parse_state == State::Failed)
                .count()
        );
        for region in binding
            .regions
            .iter()
            .filter(|region| region.language == bridge::language("java"))
        {
            assert_eq!(
                host.slice(region.body).unwrap(),
                region.source_map.output().text
            );
            println!(
                "{name}\tjava\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
                region.id,
                state(region.parse_state),
                region.full.start,
                region.full.end,
                region.body.start,
                region.body.end,
                binding.open_ends.contains(&region.id)
            );
        }
        if ["eof", "multiple", "comments-crlf"].contains(&name) {
            let project = Project {
                id: "partial".into(),
                version: 1,
                documents: BTreeMap::from([(host.uri.clone(), host.clone())]),
                configuration: BTreeMap::from([("classpath".into(), args[3].clone())]),
            };
            let provider = ProviderProcess {
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
                operations: HashSet::from([Operation::Completion]),
                timeout: Duration::from_secs(30),
            };
            let queries = binding
                .queries(project.clone(), Box::new(provider))
                .unwrap();
            let result = queries
                .query(
                    &host,
                    &project,
                    host.len(),
                    Operation::Completion,
                    &BTreeMap::from([("prefix".into(), "tar".into())]),
                )
                .unwrap();
            let edit = &result
                .items
                .iter()
                .find(|item| item.label == "target")
                .unwrap()
                .edits[0];
            let changed = binding
                .tree()
                .unwrap()
                .apply(&host, 8, std::slice::from_ref(edit))
                .unwrap();
            assert_eq!(changed.text, format!("{}get", host.text));
            assert!(bridge::parse(&changed)
                .unwrap()
                .regions
                .iter()
                .any(|region| region.parse_state == State::Failed));
            println!(
                "{name}\tcompletion\t{}\t{}\t{}\t{}",
                state(result.state),
                edit.span.start,
                edit.span.end,
                edit.replacement
            );
        }
        let tree = binding.tree().unwrap();
        println!(
            "{name}\teof\t{}",
            tree.at(host.len())
                .unwrap()
                .map_or("none", |region| &region.id)
        );
    }
}
