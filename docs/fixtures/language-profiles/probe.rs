mod java;
mod rust;
mod typescript;
use unlaxer_runtime::embedded::Grammar;
use unlaxer_runtime::language_profile::LanguageProfile;
use unlaxer_runtime::source::{Snapshot, State};
fn main() {
    let root = std::path::PathBuf::from(std::env::args().nth(1).unwrap());
    let grammars = [
        java::parser::embedded_grammar(),
        typescript::parser::embedded_grammar(),
        rust::parser::embedded_grammar(),
    ];
    for (language, grammar) in ["java", "typescript", "rust"].iter().zip(grammars) {
        let directory = root.join(language);
        let profile = LanguageProfile::parse(
            &std::fs::read_to_string(directory.join("profile.tsv")).unwrap(),
        )
        .unwrap();
        let corpus = std::fs::read_to_string(directory.join("corpus.tsv")).unwrap()
            + &std::fs::read_to_string(directory.join("mutations.tsv")).unwrap();
        for row in corpus.lines() {
            let fields: Vec<_> = row.split('\t').collect();
            let bytes: Vec<_> = fields[2]
                .as_bytes()
                .chunks_exact(2)
                .map(|chunk| u8::from_str_radix(std::str::from_utf8(chunk).unwrap(), 16).unwrap())
                .collect();
            let source = String::from_utf8(bytes).unwrap();
            let snapshot = Snapshot::new("file:///profile", 7, &source).unwrap();
            assert_eq!(snapshot.len(), fields[4].parse::<usize>().unwrap());
            let identity = profile.identity(fields[1]).unwrap();
            assert_eq!(identity.grammar, grammar.name());
            let result = grammar.parse(&identity.entry, &snapshot).unwrap();
            let accepted = result.state == State::Complete;
            assert_eq!(accepted, fields[3] == "true", "{}:{}", language, fields[0]);
            if fields[5] != "SKIP" {
                let tree = match *language {
                    "java" => java::parser::parse_tree_detailed(&source),
                    "typescript" => typescript::parser::parse_tree_detailed(&source),
                    _ => rust::parser::parse_tree_detailed(&source),
                };
                if accepted {
                    let tree = tree.unwrap();
                    assert_eq!(
                        tree.nodes[tree.root].span,
                        unlaxer_runtime::Span {
                            start: 0,
                            end: snapshot.len()
                        }
                    );
                    assert_eq!(tree.source, source);
                    let ast = match *language {
                        "java" => java::mapper::map(&tree).unwrap().canonical_json(),
                        "typescript" => typescript::mapper::map(&tree).unwrap().canonical_json(),
                        _ => rust::mapper::map(&tree).unwrap().canonical_json(),
                    };
                    assert_eq!(
                        ast,
                        format!(
                            "{{\"type\":\"Source\",\"span\":[0,{}],\"fields\":{{}}}}",
                            snapshot.len()
                        )
                    );
                } else if fields[0] == "unclosed" || fields[0].ends_with("remove-close") {
                    assert_eq!(
                        tree.unwrap_err().farthest.offset,
                        snapshot.len(),
                        "{}:{}",
                        language,
                        fields[0]
                    );
                }
            }
            println!(
                "{}\t{}\t{}\t{}\t{}",
                language,
                fields[0],
                identity.entry,
                accepted,
                snapshot.len()
            );
        }
    }
}
