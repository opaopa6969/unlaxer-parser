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

#[test]
fn token_backend_requires_complete_real_trace_and_rejects_detached_source() {
    use unlaxer_runtime::{
        token::{TokenKind, TokenSource},
        token_trace::Limits,
        Expr, ParseContext, ParseOptions, Rule,
    };
    let rule = vec![Rule {
        name: "Root",
        expression: Expr::Literal("😀"),
    }];
    let plain = unlaxer_runtime::parse(&rule, 0, false, "😀").unwrap();
    assert!(unlaxer_dap::token_steps(&plain).is_err());
    let traced = unlaxer_runtime::parse_with_options(
        &rule,
        0,
        false,
        "😀",
        ParseOptions::default().with_token_trace(Limits::default()),
    )
    .unwrap();
    let steps = unlaxer_dap::token_steps(&traced).unwrap();
    assert_eq!(steps.len(), 1);
    assert_eq!(steps[0].span, Span { start: 0, end: 1 });
    let limited = unlaxer_runtime::parse_with_options(
        &rule,
        0,
        false,
        "😀",
        ParseOptions::default().with_token_trace(Limits {
            leaves: 0,
            source_bytes: 0,
        }),
    )
    .unwrap();
    assert!(unlaxer_dap::token_steps(&limited)
        .unwrap_err()
        .contains("Limit"));
    let mut context = ParseContext::with_options(
        "😀",
        ParseOptions::default().with_token_trace(Limits::default()),
    );
    context.parse(&Expr::Literal("😀")).unwrap();
    let token = context
        .add_token_node(
            0,
            TokenKind::VirtualConsumed,
            TokenSource::Generated {
                anchor: 1,
                text: "generated".into(),
            },
        )
        .unwrap();
    assert!(
        unlaxer_dap::token_steps(&context.tree(token.index()).unwrap())
            .unwrap_err()
            .contains("detached generated")
    );
}

#[test]
fn disconnect_flushes_response_without_consuming_or_closing_borrowed_transport() {
    use std::io::{Read, Write};
    let mut bytes = Vec::new();
    unlaxer_protocol::write_frame(
        &mut bytes,
        &serde_json::to_vec(&request(1, "disconnect", json!({}))).unwrap(),
    )
    .unwrap();
    bytes.extend_from_slice(b"TAIL");
    let mut input = Cursor::new(bytes);
    let mut output = Vec::new();
    Server::new(Hooks(Arc::new(Mutex::new(vec![]))))
        .serve(&mut input, &mut output)
        .unwrap();
    let mut tail = String::new();
    input.read_to_string(&mut tail).unwrap();
    assert_eq!(tail, "TAIL");
    let frame = unlaxer_protocol::read_frame(&mut Cursor::new(&output))
        .unwrap()
        .unwrap();
    let reply: Value = serde_json::from_slice(&frame).unwrap();
    assert_eq!(reply["command"], "disconnect");
    assert_eq!(reply["success"], true);
    output.write_all(b"caller still owns output").unwrap();
}

#[test]
fn token_protocol_shows_exact_budget_trace_and_never_a_partial_limit_or_unsupported() {
    use unlaxer_runtime::{token_trace::Limits, Expr, ParseContext, ParseOptions, Rule};
    // The match-only lookahead's leaves are removed by the consumed root (#866), so
    // three retained leaves fit exactly three; two is a real Limit.
    fn traced(source: &str, limits: Limits, custom: bool) -> Result<Vec<Step>, String> {
        let separator = if custom {
            Expr::Custom(|context| context.parse(&Expr::Literal("|")))
        } else {
            Expr::Literal("|")
        };
        let rules = vec![Rule {
            name: "Root",
            expression: Expr::Sequence(vec![
                Expr::Literal("a"),
                Expr::Sequence(vec![Expr::Literal("|"), Expr::Literal("x")]).match_only(),
                separator,
                Expr::Literal("x"),
            ]),
        }];
        // Classic collectors are entered through the parser entry, as in the oracle.
        let options = ParseOptions::default().with_token_trace(limits);
        let mut context = ParseContext::with_options(source, options);
        let root = context
            .parse_grammar(rules, 0, false)
            .map_err(|error| format!("{error:?}"))?;
        assert_eq!(context.position(), source.len());
        let tree = context.tree(root.root_node().unwrap()).unwrap();
        unlaxer_dap::token_steps(&tree)
    }
    fn exact(source: &str) -> Result<Vec<Step>, String> {
        traced(
            source,
            Limits {
                leaves: 3,
                source_bytes: 3,
            },
            false,
        )
    }
    fn short(source: &str) -> Result<Vec<Step>, String> {
        traced(
            source,
            Limits {
                leaves: 2,
                source_bytes: 3,
            },
            false,
        )
    }
    fn unsupported(source: &str) -> Result<Vec<Step>, String> {
        traced(source, Limits::default(), true)
    }
    fn no_ast(_: &str) -> Result<Vec<Step>, String> {
        Err("AST mode is not used".into())
    }
    type Tokens = fn(&str) -> Result<Vec<Step>, String>;
    let cases: [(Tokens, &str); 3] = [
        (exact, ""),
        (short, "token trace Limit"),
        (unsupported, "token trace Unsupported"),
    ];
    for (tokens, error) in cases {
        let path = temp("a|x");
        let mut input = Vec::new();
        let requests = [
            request(1, "initialize", json!({})),
            request(
                2,
                "launch",
                json!({"program":path,"steppingMode":"token","stopOnEntry":true}),
            ),
            request(3, "configurationDone", json!({})),
            request(4, "stackTrace", json!({"threadId":1})),
            request(5, "next", json!({"threadId":1})),
            request(6, "stackTrace", json!({"threadId":1})),
            request(7, "next", json!({"threadId":1})),
            request(8, "stackTrace", json!({"threadId":1})),
            request(9, "next", json!({"threadId":1})),
            request(10, "disconnect", json!({})),
        ];
        for message in &requests {
            unlaxer_protocol::write_frame(&mut input, &serde_json::to_vec(message).unwrap())
                .unwrap();
        }
        let mut output = Vec::new();
        Server::new(unlaxer_dap::ParserBackend {
            mapper: no_ast,
            tokens,
        })
        .serve(&mut Cursor::new(input), &mut output)
        .unwrap();
        let mut reader = Cursor::new(output);
        let mut messages = Vec::new();
        while let Some(frame) = unlaxer_protocol::read_frame(&mut reader).unwrap() {
            messages.push(serde_json::from_slice::<Value>(&frame).unwrap());
        }
        let frames = messages
            .iter()
            .filter(|m| m["command"] == "stackTrace")
            .map(|m| {
                m["body"]["stackFrames"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .map(|frame| format!("{}@{}", frame["name"], frame["column"]))
                    .collect::<Vec<_>>()
                    .join(",")
            })
            .collect::<Vec<_>>();
        let stopped = messages.iter().filter(|m| m["event"] == "stopped").count();
        let stderr = messages
            .iter()
            .filter(|m| m["event"] == "output" && m["body"]["category"] == "stderr")
            .map(|m| m["body"]["output"].as_str().unwrap().to_owned())
            .collect::<String>();
        assert!(
            messages.iter().any(|m| m["event"] == "terminated"),
            "{error}"
        );
        if error.is_empty() {
            assert_eq!(
                frames,
                [
                    "\"WordParser (1/3)\"@1",
                    "\"WordParser (2/3)\"@2",
                    "\"WordParser (3/3)\"@3"
                ]
            );
            assert_eq!(stopped, 3);
            assert!(stderr.is_empty(), "{stderr}");
        } else {
            assert_eq!(frames, ["", "", ""], "{error}");
            assert_eq!(stopped, 0, "{error}");
            assert!(stderr.contains(error), "{stderr}");
        }
        std::fs::remove_file(path).unwrap();
    }
}
