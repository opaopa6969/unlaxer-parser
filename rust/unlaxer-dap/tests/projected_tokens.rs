use serde_json::{json, Value};
use unlaxer_dap::{token_steps, Backend, Server, Step};
use unlaxer_runtime::{
    source::Snapshot,
    token::{TokenKind, TokenSource},
    token_trace::Limits,
    Expr, ParseContext, ParseMatch, ParseOptions, ParseResult, Span,
};

#[allow(dead_code)]
#[path = "../examples/projected_token_dap.rs"]
mod fixture;

#[test]
fn shared_fixture_preserves_projected_text_and_logical_cp_ranges() {
    static ROWS: std::sync::OnceLock<Value> = std::sync::OnceLock::new();
    let rows = ROWS.get_or_init(|| {
        serde_json::from_str(include_str!(
            "../../../docs/fixtures/projected-token-dap/cases.json"
        ))
        .unwrap()
    });
    assert_eq!(rows.as_array().unwrap().len(), 8);
    for row in rows.as_array().unwrap() {
        let input = row["input"].as_str().unwrap();
        let tree = fixture::fixture_tree(input, row, Limits::default()).unwrap();
        let steps = token_steps(&tree).unwrap();
        let snapshot = Snapshot::new("fixture", 0, input).unwrap();
        let expected = row["steps"].as_array().unwrap();
        assert_eq!(steps.len(), expected.len());
        for ((step, leaf), expected) in steps
            .iter()
            .zip(tree.token_trace().unwrap().unwrap())
            .zip(expected)
        {
            assert_eq!(step.label, expected[0]);
            assert_eq!(leaf.text(input), expected[1].as_str());
            assert_eq!(step.span.start, expected[2].as_u64().unwrap() as usize);
            assert_eq!(step.span.end, expected[3].as_u64().unwrap() as usize);
            match &leaf.source {
                TokenSource::Generated { text, .. } => {
                    assert_eq!(step.generated_text.as_ref(), Some(text));
                    assert_eq!(step.span.start, step.span.end);
                }
                TokenSource::Input(_) => assert_eq!(step.generated_text, None),
                TokenSource::Projected { text, .. } => {
                    assert_eq!(step.projected_text.as_deref(), Some(text.as_ref()));
                    assert_eq!(step.generated_text, None);
                }
            }
            let start = snapshot.lsp(step.span.start).unwrap();
            let end = snapshot.lsp(step.span.end).unwrap();
            assert_eq!(
                json!([
                    start.line + 1,
                    start.character + 1,
                    end.line + 1,
                    end.character + 1
                ]),
                json!([expected[4], expected[5], expected[6], expected[7]])
            );
        }
        drop(tree);
        for (step, expected) in steps.iter().zip(expected) {
            if let Some(text) = &step.projected_text {
                assert_eq!(text, expected[1].as_str().unwrap());
            }
        }
    }
}

struct SharedFixtureBackend(&'static Value);
impl Backend for SharedFixtureBackend {
    fn supports_token_trace(&self) -> bool {
        true
    }
    fn steps(&mut self, snapshot: &Snapshot, args: &Value) -> Result<Vec<Step>, String> {
        let row = self
            .0
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["name"] == args["fixture"])
            .unwrap();
        token_steps(&fixture::fixture_tree(
            &snapshot.text,
            row,
            Limits::default(),
        )?)
    }
}

/// In-process check that `cargo test` alone rejects an input-slice substitution.
#[test]
fn variables_show_owned_projected_text_not_the_input_slice() {
    static ROWS: std::sync::OnceLock<Value> = std::sync::OnceLock::new();
    let rows = ROWS.get_or_init(|| {
        serde_json::from_str(include_str!(
            "../../../docs/fixtures/projected-token-dap/cases.json"
        ))
        .unwrap()
    });
    let file = std::env::temp_dir().join(format!(
        "projected-token-variables-{}.txt",
        std::process::id()
    ));
    let mut server = Server::new(SharedFixtureBackend(rows));
    server.handle(request(1, "initialize", json!({})));
    let mut seq = 2;
    let mut differing = 0;
    for row in rows.as_array().unwrap() {
        let input = row["input"].as_str().unwrap();
        std::fs::write(&file, input).unwrap();
        server.handle(request(
            seq,
            "launch",
            json!({"program":file,"fixture":row["name"],"stopOnEntry":true,"runtimeMode":"inspection-token"}),
        ));
        server.handle(request(seq + 1, "configurationDone", json!({})));
        seq += 2;
        for expected in row["steps"].as_array().unwrap() {
            let text = expected[1].as_str().unwrap();
            let label = expected[0].as_str().unwrap();
            let response =
                server.handle(request(seq, "variables", json!({"variablesReference":1})));
            let value = response[0]["body"]["variables"]
                .as_array()
                .unwrap()
                .iter()
                .find(|v| v["name"] == label)
                .unwrap()["value"]
                .clone();
            assert_eq!(
                value,
                json!(format!("\"{}\"", text.trim().replace('"', "\\\""))),
                "{}",
                row["name"]
            );
            let start = expected[2].as_u64().unwrap() as usize;
            let end = expected[3].as_u64().unwrap() as usize;
            let slice: String = input.chars().skip(start).take(end - start).collect();
            if label == "ProjectedToken" && slice != text {
                differing += 1;
            }
            server.handle(request(seq + 1, "next", json!({"threadId":1})));
            seq += 2;
        }
    }
    assert!(
        differing >= 5,
        "fixtures must keep text that differs from the slice"
    );
    std::fs::remove_file(file).unwrap();
}

fn projected(context: &mut ParseContext<'_>) -> ParseResult {
    context.declare_complete_token_trace();
    let span = Span { start: 0, end: 2 };
    let id = context
        .add_traced_token_node(
            0,
            "ProjectedToken",
            TokenKind::Consumed,
            TokenSource::Projected {
                span,
                text: "aa".into(),
            },
        )
        .unwrap();
    Ok(ParseMatch {
        span,
        nodes: vec![id.index()],
        captures: vec![],
    })
}

#[test]
fn discarded_projected_leaves_never_become_steps() {
    let rows: Value = serde_json::from_str(include_str!(
        "../../../docs/fixtures/projected-token-dap/rollback.json"
    ))
    .unwrap();
    assert_eq!(rows.as_array().unwrap().len(), 5);
    for row in rows.as_array().unwrap() {
        let expr = match row["name"].as_str().unwrap() {
            "committed" => Expr::Custom(projected),
            "choice-loser" => Expr::Custom(projected)
                .then(Expr::Literal("x"))
                .or(Expr::Literal("a")),
            "lookahead" => Expr::Custom(projected).ahead().then(Expr::Literal("a")),
            "negative-lookahead" => Expr::Custom(projected)
                .then(Expr::Literal("x"))
                .not_ahead()
                .then(Expr::Literal("a")),
            "outer-failure" => Expr::Custom(projected).then(Expr::Literal("x")),
            _ => panic!("unknown fixture"),
        };
        let mut context = ParseContext::with_options(
            row["input"].as_str().unwrap(),
            ParseOptions::default().with_token_trace(Limits::default()),
        );
        let parsed = context.parse(&expr);
        assert_eq!(parsed.is_ok(), row["accepted"].as_bool().unwrap());
        let trace = context.token_trace().unwrap().unwrap();
        assert_eq!(
            trace
                .iter()
                .filter(|leaf| matches!(leaf.source, TokenSource::Projected { .. }))
                .count(),
            row["projected"].as_u64().unwrap() as usize
        );
        for leaf in trace
            .iter()
            .filter(|leaf| matches!(leaf.source, TokenSource::Projected { .. }))
        {
            assert_eq!(leaf.text(context.source()), row["text"].as_str());
            assert_eq!(json!([leaf.span().start, leaf.span().end]), row["span"]);
        }
        if parsed.is_ok() {
            let steps = retained_steps(&mut context).unwrap();
            assert_eq!(
                steps
                    .iter()
                    .filter(|step| step.projected_text.is_some())
                    .count(),
                row["projected"].as_u64().unwrap() as usize
            );
        } else {
            assert!(trace.is_empty());
            assert_eq!(context.position(), 0);
            context.parse(&Expr::Literal("a")).unwrap();
            assert_eq!(
                retained_steps(&mut context).unwrap()[0].generated_text,
                None
            );
        }
    }
}

fn incomplete(context: &mut ParseContext<'_>) -> ParseResult {
    let span = Span { start: 0, end: 1 };
    let id = context
        .add_traced_token_node(
            0,
            "ProjectedToken",
            TokenKind::Consumed,
            TokenSource::Projected {
                span,
                text: "é".into(),
            },
        )
        .unwrap();
    Ok(ParseMatch {
        span,
        nodes: vec![id.index()],
        captures: vec![],
    })
}
fn recursive(context: &mut ParseContext<'_>) -> ParseResult {
    context.parse(&Expr::Custom(recursive))
}
struct FailureBackend;
impl Backend for FailureBackend {
    fn supports_token_trace(&self) -> bool {
        true
    }
    fn steps(&mut self, snapshot: &Snapshot, args: &Value) -> Result<Vec<Step>, String> {
        let case = args["failure"].as_str().unwrap();
        if matches!(
            case,
            "dual-source" | "crlf-span" | "outside" | "reversed" | "ast"
        ) {
            return Ok(vec![Step {
                label: "ProjectedToken".into(),
                span: match case {
                    "crlf-span" => Span { start: 2, end: 3 },
                    "outside" => Span { start: 0, end: 20 },
                    "reversed" => Span { start: 2, end: 1 },
                    _ => Span { start: 0, end: 1 },
                },
                generated_text: (case == "dual-source").then(|| "repair".into()),
                projected_text: Some("é".into()),
            }]);
        }
        let options = if case == "missing" {
            ParseOptions::default()
        } else {
            ParseOptions::default().with_token_trace(match case {
                "leaves" => Limits {
                    leaves: 0,
                    source_bytes: 100,
                },
                "bytes" => Limits {
                    leaves: 10,
                    source_bytes: 1,
                },
                _ => Limits::default(),
            })
        };
        let mut context = ParseContext::with_options(&snapshot.text, options);
        let expr = match case {
            "incomplete" => Expr::Custom(incomplete),
            "resource" => Expr::Custom(projected).then(Expr::Custom(recursive)),
            _ => Expr::Custom(projected),
        };
        context.parse(&expr).map_err(|e| e.to_string())?;
        retained_steps(&mut context)
    }
}

#[test]
fn trace_limit_resource_and_incomplete_failures_terminate_without_stopping() {
    let file = std::env::temp_dir().join(format!(
        "projected-token-rejection-{}.txt",
        std::process::id()
    ));
    std::fs::write(&file, "a\r\nb").unwrap();
    let mut server = Server::new(FailureBackend);
    server.handle(request(1, "initialize", json!({})));
    let mut seq = 2;
    for (failure, message) in [
        ("missing", "unavailable"),
        ("leaves", "Limit"),
        ("bytes", "Limit"),
        ("incomplete", "Unsupported"),
        ("resource", "256"),
        ("dual-source", "unambiguous"),
        ("ast", "unambiguous"),
        ("crlf-span", "CRLF"),
        ("outside", "outside snapshot"),
        ("reversed", "outside snapshot"),
    ] {
        assert_eq!(
            server.handle(request(
                seq,
                "launch",
                json!({"program":file,"failure":failure,"stopOnEntry":true,"steppingMode":if failure == "ast" {"ast"} else {"token"}})
            ))[0]["success"],
            true
        );
        seq += 1;
        let messages = server.handle(request(seq, "configurationDone", json!({})));
        seq += 1;
        assert!(messages.iter().all(|m| m["event"] != "stopped"));
        assert!(
            messages.iter().any(|m| m["event"] == "output"
                && m["body"]["category"] == "stderr"
                && m["body"]["output"].as_str().unwrap().contains(message)),
            "{failure}: {messages:?}"
        );
        assert!(messages.iter().any(|m| m["event"] == "terminated"));
        assert!(messages.iter().any(|m| m["event"] == "exited"));
        assert_eq!(
            server.handle(request(seq, "stackTrace", json!({"threadId":1})))[0]["body"]
                ["stackFrames"],
            json!([])
        );
        seq += 1;
    }
    std::fs::remove_file(file).unwrap();
}
fn request(seq: usize, command: &str, arguments: Value) -> Value {
    json!({"seq":seq,"type":"request","command":command,"arguments":arguments})
}

fn retained_steps(context: &mut ParseContext<'_>) -> Result<Vec<Step>, String> {
    let root = context
        .add_token_node(
            0,
            TokenKind::Consumed,
            TokenSource::Input(Span {
                start: 0,
                end: context.position(),
            }),
        )
        .unwrap();
    token_steps(&context.tree(root.index()).unwrap())
}

#[test]
fn explicit_terminal_api_retains_kinds_limits_and_single_generated_observation() {
    for kind in [
        TokenKind::Consumed,
        TokenKind::VirtualConsumed,
        TokenKind::MatchOnly,
        TokenKind::VirtualMatchOnly,
    ] {
        let mut context = ParseContext::with_options(
            "a",
            ParseOptions::default().with_token_trace(Limits::default()),
        );
        let id = context
            .add_traced_token_node(
                0,
                "ProjectedToken",
                kind,
                TokenSource::Projected {
                    span: Span { start: 0, end: 1 },
                    text: "é".into(),
                },
            )
            .unwrap();
        assert_eq!(context.position(), 0);
        assert_eq!(context.matched_position(), 0);
        let tree = context.tree(id.index()).unwrap();
        let trace = tree.token_trace().unwrap().unwrap();
        assert_eq!(trace.len(), 1);
        assert_eq!(trace[0].kind, kind);
        assert_eq!(
            token_steps(&tree).unwrap().len(),
            usize::from(kind.is_consumed())
        );
    }
    for traced in [true, false] {
        let options = if traced {
            ParseOptions::default().with_token_trace(Limits::default())
        } else {
            ParseOptions::default()
        };
        let mut context = ParseContext::with_options("a", options);
        let id = context
            .add_traced_token_node(
                0,
                "CustomGenerated",
                TokenKind::VirtualConsumed,
                TokenSource::Generated {
                    anchor: 0,
                    text: "x".into(),
                },
            )
            .unwrap();
        let tree = context.tree(id.index()).unwrap();
        if traced {
            assert_eq!(tree.token_trace().unwrap().unwrap().len(), 1);
            assert_eq!(token_steps(&tree).unwrap()[0].label, "CustomGenerated");
        } else {
            assert!(token_steps(&tree).is_err());
        }
        assert!(context
            .add_traced_token_node(
                0,
                "Bad",
                TokenKind::Consumed,
                TokenSource::Projected {
                    span: Span { start: 0, end: 1 },
                    text: "too long".into()
                }
            )
            .is_none());
    }
}
