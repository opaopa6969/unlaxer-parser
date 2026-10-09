use std::{collections::HashSet, path::PathBuf, time::Duration};
use unlaxer_runtime::{
    provider_process::ProviderProcess,
    provider_protocol::{self, Frame, Status},
    source::Operation,
};
fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../../docs/fixtures/language-providers/protocol")
}
#[test]
fn shared_malformed_protocol_cases() {
    let path = fixtures();
    let owned = provider_protocol::read_request(
        &std::fs::read_to_string(path.join("request.wire")).unwrap(),
    )
    .unwrap();
    let request = owned.as_request();
    let frame = provider_protocol::encode(&request).unwrap();
    for line in std::fs::read_to_string(path.join("cases.tsv"))
        .unwrap()
        .lines()
    {
        let row: Vec<_> = line.split('\t').collect();
        let wire = std::fs::read_to_string(path.join(row[1])).unwrap();
        let accepted = if row[0] == "request" {
            provider_protocol::read_request(&wire).is_ok()
        } else {
            provider_protocol::decode(&request, &frame, &wire).is_ok()
        };
        assert_eq!(accepted, row[2] == "true", "{}", row[1]);
    }
    assert!(provider_protocol::decode(
        &request,
        &Frame {
            text: frame.text,
            fingerprint: "old".into()
        },
        &std::fs::read_to_string(path.join("response-ok.wire")).unwrap()
    )
    .is_err());
}
#[test]
fn process_failure_states_and_no_execution_gate() {
    let path = fixtures();
    let mut owned = provider_protocol::read_request(
        &std::fs::read_to_string(path.join("request.wire")).unwrap(),
    )
    .unwrap();
    for line in std::fs::read_to_string(path.join("transport.tsv"))
        .unwrap()
        .lines()
    {
        let row: Vec<_> = line.split('\t').collect();
        let command = if row[0] == "missing" {
            vec!["/unlaxer-provider-missing".into()]
        } else {
            vec![
                "python3".into(),
                path.join("transport.py").display().to_string(),
                row[0].into(),
            ]
        };
        let process = ProviderProcess {
            command,
            identity: owned.provider.clone(),
            operations: HashSet::from([Operation::Hover]),
            timeout: Duration::from_millis(200),
        };
        assert_eq!(
            format!("{:?}", process.invoke(&owned.as_request()).unwrap().status).to_uppercase(),
            row[1],
            "{}",
            row[0]
        );
    }
    let marker =
        std::env::temp_dir().join(format!("unlaxer-provider-execute-{}", std::process::id()));
    let process = ProviderProcess {
        command: vec![
            "python3".into(),
            path.join("transport.py").display().to_string(),
            "marker".into(),
            marker.display().to_string(),
        ],
        identity: owned.provider.clone(),
        operations: HashSet::from([Operation::Hover]),
        timeout: Duration::from_secs(1),
    };
    owned.execute_user_code = true;
    assert_eq!(
        process.invoke(&owned.as_request()).unwrap().status,
        Status::Unsupported
    );
    assert!(!marker.exists());
}

#[test]
fn diagnostic_projection_rejects_stale_snapshot() {
    use unlaxer_runtime::{
        provider_protocol::{Diagnostic, Response},
        source::{Kind, Location, Segment, Snapshot, SourceMap},
        Span,
    };
    let owned = provider_protocol::read_request(
        &std::fs::read_to_string(fixtures().join("request.wire")).unwrap(),
    )
    .unwrap();
    let span = Span {
        start: 0,
        end: owned.snapshot.len(),
    };
    let origin = Snapshot::new("host", 7, &owned.snapshot.text).unwrap();
    let map = SourceMap::new(
        owned.snapshot.clone(),
        vec![Segment {
            output: span,
            kind: Kind::Copy,
            origin: Some(Location::new(origin, span).unwrap()),
        }],
    )
    .unwrap();
    for stale in [
        Snapshot::new(&owned.snapshot.uri, 8, &owned.snapshot.text).unwrap(),
        Snapshot::new(&owned.snapshot.uri, 7, "changed").unwrap(),
    ] {
        let response = Response {
            status: Status::Diagnostics,
            capabilities: HashSet::new(),
            diagnostics: vec![Diagnostic {
                code: "E".into(),
                message: "error".into(),
                severity: "ERROR".into(),
                locations: vec![Location::new(stale, Span { start: 0, end: 1 }).unwrap()],
            }],
            items: vec![],
        };
        assert!(provider_protocol::map_diagnostics(&response, &map).is_err());
    }
}
