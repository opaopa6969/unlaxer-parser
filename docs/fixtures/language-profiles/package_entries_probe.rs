use std::collections::HashMap;
use unlaxer_runtime::embedded::{self, Grammar};
use unlaxer_runtime::language_profile::LanguageProfile;
use unlaxer_runtime::source::{Language, Snapshot, State};
fn main() {
    let args: Vec<_> = std::env::args().collect();
    let directory = std::path::Path::new(&args[2]);
    let children: Vec<(&str, Box<dyn Grammar>)> = vec![
        ("java", Box::new(java::parser::embedded_grammar())),
        (
            "typescript",
            Box::new(typescript::parser::embedded_grammar()),
        ),
        ("rust", Box::new(rust::parser::embedded_grammar())),
    ];
    for (line, parent) in std::fs::read_to_string(&args[1])
        .unwrap()
        .lines()
        .zip(parents())
    {
        let row: Vec<_> = line.split('\t').collect();
        let profile = LanguageProfile::parse(
            &std::fs::read_to_string(directory.join(row[1]).join("native/public/profile.tsv"))
                .unwrap(),
        )
        .unwrap();
        assert!(profile.identity("Missing").is_err());
        let child = children
            .iter()
            .find(|(id, _)| *id == row[1])
            .unwrap()
            .1
            .as_ref();
        let bytes: Vec<_> = row[6]
            .as_bytes()
            .chunks_exact(2)
            .map(|pair| u8::from_str_radix(std::str::from_utf8(pair).unwrap(), 16).unwrap())
            .collect();
        let body = String::from_utf8(bytes).unwrap();
        let host = Snapshot::new("file:///packaged.formula", 7, format!("😀F[{body}]F")).unwrap();
        let root = Language {
            id: "host".into(),
            package_id: "example".into(),
            version: "1".into(),
            grammar: parent.name().into(),
            entry: "Root".into(),
        };
        let registered = profile
            .identity(if row[4] == "Missing" { row[3] } else { row[4] })
            .unwrap();
        let providers: HashMap<Language, &dyn Grammar> =
            HashMap::from([(root.clone(), parent.as_ref()), (registered, child)]);
        let result = embedded::parse(&host, &root, &providers, 8, 32).unwrap();
        assert_eq!(result.regions.len(), 2, "{}", row[0]);
        assert_eq!(result.regions[0].parse_state, State::Complete);
        let region = &result.regions[1];
        let actual = format!(
            "{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            row[0],
            format!("{:?}", region.parse_state).to_uppercase(),
            region.language.package_id,
            region.language.version,
            region.language.grammar,
            region.language.entry,
            region.full.start,
            region.full.end,
            region.body.start,
            region.body.end
        );
        let expected = format!(
            "{}\t{}\tlang/{}\t{}\t{}\t{}\t{}\t{}\t{}\t{}",
            row[0], row[7], row[1], row[5], row[2], row[4], row[8], row[9], row[10], row[11]
        );
        assert_eq!(actual, expected, "{}", row[0]);
        assert_eq!(region.source_map.output().text, body);
        let mapped = region
            .source_map
            .edit(unlaxer_runtime::Span {
                start: 0,
                end: body.chars().count(),
            })
            .unwrap();
        assert_eq!(mapped.snapshot, host);
        assert_eq!(mapped.span, region.body);
        let tree = result.tree().unwrap();
        assert_eq!(tree.at(0).unwrap().unwrap().language, root);
        assert_eq!(
            tree.at(row[11].parse().unwrap()).unwrap().unwrap().language,
            root
        );
        assert_eq!(
            tree.at(row[10].parse().unwrap()).unwrap().unwrap().language,
            region.language
        );
        assert_eq!(
            child
                .parse("Missing", &Snapshot::new("child", 7, body).unwrap())
                .unwrap()
                .state,
            State::Unsupported
        );
        println!("{actual}");
    }
}
