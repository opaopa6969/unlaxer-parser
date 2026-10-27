use serde_json::{json, Value};
use unlaxer_dap::{token_steps, Backend, Server, Step};
use unlaxer_runtime::{
    source::Snapshot,
    token::{TokenKind, TokenSource},
    token_trace::Limits,
    Expr, ParseContext, ParseMatch, ParseOptions, ParseResult, Span,
};

#[allow(dead_code)]
#[path = "../examples/generated_token_dap.rs"]
mod fixture;

#[test]
fn shared_fixture_preserves_owned_text_and_original_cp_anchors() {
    static ROWS: std::sync::OnceLock<Value> = std::sync::OnceLock::new();
    let rows = ROWS.get_or_init(|| {
        serde_json::from_str(include_str!(
            "../../../docs/fixtures/generated-token-dap/cases.json"
        ))
        .unwrap()
    });
    assert_eq!(rows.as_array().unwrap().len(), 6);
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
                _ => panic!("unexpected fixture provenance"),
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
        assert_eq!(steps.len(), expected.len()); // Steps own generated text after the tree is gone.
    }
}

fn generated(context: &mut ParseContext<'_>) -> ParseResult {
    context.declare_complete_token_trace();
    let anchor = context.position();
    let id = context
        .add_token_node(
            0,
            TokenKind::VirtualConsumed,
            TokenSource::Generated {
                anchor,
                text: "😀repair".into(),
            },
        )
        .unwrap();
    Ok(ParseMatch {
        span: Span {
            start: anchor,
            end: anchor,
        },
        nodes: vec![id.index()],
        captures: vec![],
    })
}

#[test]
fn discarded_generated_leaves_never_become_steps() {
    let rows: Value = serde_json::from_str(include_str!(
        "../../../docs/fixtures/generated-token-dap/rollback.json"
    ))
    .unwrap();
    assert_eq!(rows.as_array().unwrap().len(), 5);
    for row in rows.as_array().unwrap() {
        let expr = match row["name"].as_str().unwrap() {
            "committed" => Expr::Custom(generated),
            "choice-loser" => Expr::Custom(generated)
                .then(Expr::Literal("x"))
                .or(Expr::Literal("a")),
            "lookahead" => Expr::Custom(generated).ahead().then(Expr::Literal("a")),
            "negative-lookahead" => Expr::Custom(generated)
                .then(Expr::Literal("x"))
                .not_ahead()
                .then(Expr::Literal("a")),
            "outer-failure" => Expr::Custom(generated).then(Expr::Literal("x")),
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
                .filter(|leaf| matches!(leaf.source, TokenSource::Generated { .. }))
                .count(),
            row["generated"].as_u64().unwrap() as usize
        );
        if parsed.is_ok() {
            let steps = retained_steps(&mut context).unwrap();
            assert_eq!(
                steps
                    .iter()
                    .filter(|step| step.generated_text.is_some())
                    .count(),
                row["generated"].as_u64().unwrap() as usize
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
    context.parse(&Expr::Literal("a"))
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
        if case == "bad-extent" || case == "crlf-anchor" {
            return Ok(vec![Step {
                label: "GeneratedToken".into(),
                span: if case == "bad-extent" {
                    Span { start: 0, end: 1 }
                } else {
                    Span { start: 2, end: 2 }
                },
                generated_text: Some("repair".into()),
                projected_text: None,
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
            "resource" => Expr::Custom(generated).then(Expr::Custom(recursive)),
            _ => Expr::Custom(generated),
        };
        context.parse(&expr).map_err(|e| e.to_string())?;
        retained_steps(&mut context)
    }
}

#[test]
fn trace_limit_resource_and_incomplete_failures_terminate_without_stopping() {
    let file = std::env::temp_dir().join(format!(
        "generated-token-rejection-{}.txt",
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
        ("bad-extent", "zero-width"),
        ("crlf-anchor", "CRLF"),
    ] {
        assert_eq!(
            server.handle(request(
                seq,
                "launch",
                json!({"program":file,"failure":failure,"stopOnEntry":true})
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
