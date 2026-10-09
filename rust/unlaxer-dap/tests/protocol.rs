use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::Cursor;
use std::sync::{Arc, Mutex};
use unlaxer_dap::{Backend, DebugSource, Server, Step};
use unlaxer_runtime::{source::Snapshot, Span};

struct Hooks(Arc<Mutex<Vec<String>>>);
impl Backend for Hooks {
    fn steps(&mut self, source: &Snapshot, _: &Value) -> Result<Vec<Step>, String> {
        match source.text.as_ref() {
            "map-error" => Err("strict mapper failed".into()),
            "empty" => Ok(vec![]),
            "bad-span" => Ok(vec![Step {
                label: "bad".into(),
                span: Span { start: 0, end: 99 },
            }]),
            "A\r\nB" => Ok(vec![Step {
                label: "bad".into(),
                span: Span { start: 2, end: 3 },
            }]),
            _ => Ok(vec![
                Step {
                    label: "Parent".into(),
                    span: Span {
                        start: 0,
                        end: source.len(),
                    },
                },
                Step {
                    label: "Child".into(),
                    span: Span {
                        start: 1,
                        end: source.len(),
                    },
                },
            ]),
        }
    }
    fn resolve_source(
        &mut self,
        _: &str,
        original: &str,
        _: &Value,
    ) -> Result<DebugSource, String> {
        Ok(DebugSource {
            text: original.into(),
            line_offset: 3,
        })
    }
    fn runtime_variables(
        &mut self,
        source: &Snapshot,
        _: &str,
        _: &Value,
    ) -> Result<BTreeMap<String, String>, String> {
        Ok(BTreeMap::from([(
            "sourceLength".into(),
            source.len().to_string(),
        )]))
    }
    fn before_step(&mut self, index: usize, label: &str) {
        self.0
            .lock()
            .unwrap()
            .push(format!("before:{index}:{label}"));
    }
    fn after_step(&mut self, index: usize, label: &str) {
        self.0
            .lock()
            .unwrap()
            .push(format!("after:{index}:{label}"));
    }
}
fn request(seq: i64, command: &str, args: Value) -> Value {
    json!({"seq":seq,"type":"request","command":command,"arguments":args})
}
fn temp(source: &str) -> std::path::PathBuf {
    let path = std::env::temp_dir().join(format!(
        "unlaxer-dap-{}-{}.txt",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    std::fs::write(&path, source).unwrap();
    path
}
#[test]
fn real_frames_preserve_sequence_state_and_application_hooks() {
    let path = temp("😀x");
    let calls = Arc::new(Mutex::new(vec![]));
    let messages = vec![
        request(
            1,
            "initialize",
            json!({"linesStartAt1":false,"columnsStartAt1":false}),
        ),
        request(
            2,
            "launch",
            json!({"program":path,"runtimeMode":"application_ast","stopOnEntry":true}),
        ),
        request(3, "configurationDone", json!({})),
        request(4, "stackTrace", json!({"threadId":1})),
        request(5, "variables", json!({"variablesReference":1})),
        request(6, "next", json!({"threadId":1})),
        request(7, "stackTrace", json!({"threadId":1})),
        request(8, "continue", json!({"threadId":1})),
        request(9, "stackTrace", json!({"threadId":1})),
        request(10, "unknown", json!({})),
        request(11, "disconnect", json!({})),
    ];
    let mut input = vec![];
    for message in messages {
        unlaxer_protocol::write_frame(&mut input, &serde_json::to_vec(&message).unwrap()).unwrap();
    }
    let mut output = vec![];
    Server::new(Hooks(Arc::clone(&calls)))
        .serve(&mut Cursor::new(input), &mut output)
        .unwrap();
    let mut reader = Cursor::new(output);
    let mut emitted = vec![];
    while let Some(bytes) = unlaxer_protocol::read_frame(&mut reader).unwrap() {
        emitted.push(serde_json::from_slice::<Value>(&bytes).unwrap());
    }
    for (index, message) in emitted.iter().enumerate() {
        assert_eq!(message["seq"], index + 1);
    }
    let response = |id| {
        emitted
            .iter()
            .find(|m| m["type"] == "response" && m["request_seq"] == id)
            .unwrap()
    };
    assert_eq!(response(4)["body"]["stackFrames"][0]["line"], 3);
    assert_eq!(response(4)["body"]["stackFrames"][0]["column"], 0);
    assert_eq!(response(7)["body"]["stackFrames"][0]["column"], 2);
    assert_eq!(response(7)["body"]["stackFrames"][0]["endColumn"], 3);
    assert!(response(9)["body"]["stackFrames"]
        .as_array()
        .unwrap()
        .is_empty());
    assert_eq!(response(10)["success"], false);
    assert!(response(5)["body"]["variables"]
        .as_array()
        .unwrap()
        .iter()
        .any(|v| v["name"] == "sourceLength" && v["value"] == "2"));
    assert_eq!(*calls.lock().unwrap(), ["before:1:Parent", "after:1:Child"]);
    std::fs::remove_file(path).unwrap();
}
#[test]
fn strict_failures_never_substitute_token_or_stale_steps() {
    let calls = Arc::new(Mutex::new(vec![]));
    let mut server = Server::new(Hooks(calls));
    assert_eq!(
        server.handle(request(1, "launch", json!({})))[0]["success"],
        false
    );
    server.handle(request(2, "initialize", json!({})));
    assert_eq!(
        server.handle(request(
            3,
            "launch",
            json!({"program":"unused","steppingMode":"token"})
        ))[0]["success"],
        false
    );
    for source in ["map-error", "empty", "bad-span", "A\r\nB"] {
        let path = temp(source);
        assert_eq!(
            server.handle(request(
                4,
                "launch",
                json!({"program":path,"steppingMode":"ast","stopOnEntry":true})
            ))[0]["success"],
            true
        );
        let result = server.handle(request(5, "configurationDone", json!({})));
        assert!(result.iter().any(|m| m["event"] == "terminated"));
        assert!(!result.iter().any(|m| m["event"] == "stopped"));
        assert!(
            server.handle(request(6, "stackTrace", json!({"threadId":1})))[0]["body"]
                ["stackFrames"]
                .as_array()
                .unwrap()
                .is_empty()
        );
        assert_eq!(
            server.handle(request(7, "next", json!({"threadId":1})))[0]["success"],
            false
        );
        std::fs::remove_file(path).unwrap();
    }
    assert_eq!(
        server.handle(request(8, "scopes", json!({"frameId":99})))[0]["success"],
        false
    );
    assert_eq!(
        server.handle(request(9, "variables", json!({"variablesReference":99})))[0]["success"],
        false
    );
}
#[test]
fn malformed_frames_and_requests_fail_with_bounded_transport() {
    for input in [
        b"Content-Length: 4194305\r\n\r\n".as_slice(),
        b"Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
        b"Content-Length: 20\r\n\r\n{}",
    ] {
        let mut server = Server::new(Hooks(Arc::new(Mutex::new(vec![]))));
        assert!(server.serve(&mut Cursor::new(input), &mut vec![]).is_err());
    }
    let mut server = Server::new(Hooks(Arc::new(Mutex::new(vec![]))));
    assert_eq!(
        server.handle(json!({"seq":0,"type":"request","command":"initialize"}))[0]["success"],
        false
    );
    assert_eq!(
        server.handle(json!({"seq":1,"type":"event","command":"initialize"}))[0]["success"],
        false
    );
}
