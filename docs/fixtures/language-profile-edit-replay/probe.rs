mod java;
mod rust;
mod typescript;
use std::collections::{BTreeMap, HashMap, HashSet};
use unlaxer_runtime::embedded::{self, Grammar, Output};
use unlaxer_runtime::language_profile::LanguageProfile;
use unlaxer_runtime::language_queries::{LanguageQueries, Project, Provider, Request, Response};
use unlaxer_runtime::source::{Edit, Language, Operation, Snapshot, State};
use unlaxer_runtime::Span;

struct Disabled;
impl Provider for Disabled {
    fn capabilities(&self) -> HashSet<Operation> {
        HashSet::new()
    }
    fn query(&self, _: &Request) -> Result<Response, &'static str> {
        panic!("unsupported provider invoked")
    }
}
struct Retained {
    host: Snapshot,
    parsed: Output,
    project: Project,
    queries: LanguageQueries,
}
fn bind(
    host: Snapshot,
    identity: &Language,
    registry: &HashMap<Language, &dyn Grammar>,
) -> Retained {
    let parsed = embedded::parse(&host, identity, registry, 4, 64).unwrap();
    let project = Project {
        id: "edit-replay".into(),
        version: host.version,
        documents: BTreeMap::from([(host.uri.clone(), host.clone())]),
        configuration: BTreeMap::new(),
    };
    let queries = LanguageQueries::new(
        parsed.tree().unwrap(),
        project.clone(),
        HashMap::from([(identity.clone(), Box::new(Disabled) as Box<dyn Provider>)]),
    )
    .unwrap();
    Retained {
        host,
        parsed,
        project,
        queries,
    }
}
fn decode(value: &str) -> String {
    if value == "-" {
        return String::new();
    }
    String::from_utf8(
        value
            .as_bytes()
            .chunks_exact(2)
            .map(|s| u8::from_str_radix(std::str::from_utf8(s).unwrap(), 16).unwrap())
            .collect(),
    )
    .unwrap()
}
fn main() {
    let profile_root = std::path::PathBuf::from(std::env::args().nth(1).unwrap());
    let fixture = std::fs::read_to_string(std::env::args().nth(2).unwrap()).unwrap();
    let grammars = [
        java::parser::embedded_grammar(),
        typescript::parser::embedded_grammar(),
        rust::parser::embedded_grammar(),
    ];
    for ((language, entry), grammar) in [
        ("java", "CompilationUnit"),
        ("typescript", "SourceFile"),
        ("rust", "Crate"),
    ]
    .into_iter()
    .zip(grammars)
    {
        let profile = LanguageProfile::parse(
            &std::fs::read_to_string(profile_root.join(language).join("profile.tsv")).unwrap(),
        )
        .unwrap();
        let identity = profile.identity(entry).unwrap();
        assert_eq!(
            profile.capabilities["CODE_ACTION"],
            unlaxer_runtime::language_profile::Support::Unsupported
        );
        assert_eq!(
            profile.capabilities["VALIDATE"],
            unlaxer_runtime::language_profile::Support::External
        );
        // One stable graph/registry and cumulative same-URI document session.
        let registry = HashMap::from([(identity.clone(), &grammar as &dyn Grammar)]);
        let mut current = bind(
            Snapshot::new(format!("file:///edit-replay.{language}"), 0, "").unwrap(),
            &identity,
            &registry,
        );
        let mut history = vec![];
        let mut changes = 0;
        for row in fixture
            .lines()
            .filter(|row| row.starts_with(&format!("{language}\t")))
        {
            let f: Vec<_> = row.split('\t').collect();
            let step: u64 = f[1].parse().unwrap();
            changes += 1;
            assert_eq!(step, changes);
            assert_eq!(entry, f[2]);
            let edits = vec![Edit {
                span: Span {
                    start: f[3].parse().unwrap(),
                    end: f[4].parse().unwrap(),
                },
                replacement: decode(f[5]),
            }];
            let next = current
                .parsed
                .tree()
                .unwrap()
                .apply(&current.host, step, &edits)
                .unwrap();
            assert_eq!(next.uri, current.host.uri);
            assert_eq!(next.version, step);
            assert_eq!(next.text, decode(f[6]));
            assert_eq!(next.len(), f[8].parse::<usize>().unwrap());
            assert_eq!(
                next.text.encode_utf16().count(),
                f[9].parse::<usize>().unwrap()
            );
            let eof = next.lsp(next.len()).unwrap();
            assert_eq!(eof.line, f[10].parse::<usize>().unwrap());
            assert_eq!(eof.character, f[11].parse::<usize>().unwrap());
            history.push(current);
            current = bind(next.clone(), &identity, &registry);
            let accepted = f[7] == "true";
            assert_eq!(current.parsed.regions.len(), 1);
            let region = &current.parsed.regions[0];
            assert_eq!(
                region.parse_state,
                if accepted {
                    State::Complete
                } else {
                    State::Failed
                }
            );
            assert_eq!(
                region.full,
                Span {
                    start: 0,
                    end: next.len()
                }
            );
            assert_eq!(region.source_map.output(), &next);
            let tree = match language {
                "java" => java::parser::parse_tree_detailed(&next.text),
                "typescript" => typescript::parser::parse_tree_detailed(&next.text),
                _ => rust::parser::parse_tree_detailed(&next.text),
            };
            assert_eq!(tree.is_ok(), accepted);
            if accepted {
                let tree = tree.unwrap();
                assert_eq!(
                    tree.nodes[tree.root].span,
                    Span {
                        start: 0,
                        end: next.len()
                    }
                );
                assert_eq!(tree.source, next.text);
                let ast = match language {
                    "java" => java::mapper::map(&tree).unwrap().canonical_json(),
                    "typescript" => typescript::mapper::map(&tree).unwrap().canonical_json(),
                    _ => rust::mapper::map(&tree).unwrap().canonical_json(),
                };
                assert_eq!(
                    ast,
                    format!(
                        "{{\"type\":\"Source\",\"span\":[0,{}],\"fields\":{{}}}}",
                        next.len()
                    )
                );
            } else {
                assert_eq!(tree.unwrap_err().farthest.offset, next.len());
            }
            let query = current
                .queries
                .query(
                    &next,
                    &current.project,
                    0,
                    Operation::CodeAction,
                    &BTreeMap::new(),
                )
                .unwrap();
            assert_eq!(query.state, State::Unsupported);
            assert_eq!(f[13], "UNSUPPORTED");
            assert!(query.items.is_empty());
            for name in f[15].split(',') {
                let operation = match name {
                    "COMPLETION" => Operation::Completion,
                    "HOVER" => Operation::Hover,
                    "DEFINITION" => Operation::Definition,
                    "RENAME" => Operation::Rename,
                    "FORMAT" => Operation::Format,
                    "CODE_ACTION" => Operation::CodeAction,
                    _ => panic!("unknown unsupported operation"),
                };
                assert_eq!(
                    profile.capabilities[name],
                    unlaxer_runtime::language_profile::Support::Unsupported
                );
                let result = current
                    .queries
                    .query(&next, &current.project, 0, operation, &BTreeMap::new())
                    .unwrap();
                assert_eq!(result.state, State::Unsupported);
                assert!(result.items.is_empty());
            }
            assert_eq!(
                profile.capabilities["EXECUTE"],
                unlaxer_runtime::language_profile::Support::Unsupported
            );
            for old in &history {
                assert!(old
                    .queries
                    .query(
                        &current.host,
                        &current.project,
                        0,
                        Operation::CodeAction,
                        &BTreeMap::new()
                    )
                    .is_err());
                assert!(current
                    .queries
                    .query(
                        &old.host,
                        &old.project,
                        0,
                        Operation::CodeAction,
                        &BTreeMap::new()
                    )
                    .is_err());
                assert!(old
                    .parsed
                    .tree()
                    .unwrap()
                    .apply(
                        &current.host,
                        step + 1,
                        &[Edit {
                            span: Span { start: 0, end: 0 },
                            replacement: "bad".into()
                        }]
                    )
                    .is_err());
                assert_eq!(old.parsed.snapshot, old.host);
                assert_eq!(old.queries.host(), &old.host);
            }
            assert_eq!(history.len(), f[12].parse::<usize>().unwrap());
            assert!(current
                .parsed
                .tree()
                .unwrap()
                .apply(&current.host, step, &edits)
                .is_err());
            let changed =
                Snapshot::new(next.uri.clone(), next.version, next.text.clone() + "x").unwrap();
            assert!(current
                .queries
                .query(
                    &changed,
                    &current.project,
                    0,
                    Operation::CodeAction,
                    &BTreeMap::new()
                )
                .is_err());
            assert_eq!(current.host, next);
            assert_eq!(current.queries.host(), &next);
            println!(
                "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\tUNSUPPORTED",
                language,
                step,
                entry,
                accepted,
                f[8],
                f[9],
                f[10],
                f[11],
                history.len()
            );
        }
        assert_eq!(changes, 48);
    }
}
