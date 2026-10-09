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
}
