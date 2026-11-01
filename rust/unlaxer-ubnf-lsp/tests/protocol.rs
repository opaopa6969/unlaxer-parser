use serde_json::{json, Value};
use unlaxer_ubnf_lsp::Server;

#[test]
fn authored_root_failure_json_and_consumed_prefix_hover() {
    let fixture: Value = serde_json::from_str(include_str!(
        "../../../spec-corpus/ubnf-authoring-lsp/syntax-status.json"
    ))
    .unwrap();
    for case in fixture["rootFailures"]
        .as_array()
        .unwrap()
        .iter()
        .chain(fixture["hoverOnly"].as_array().unwrap())
    {
        let mut server = Server::default();
        request(&mut server, "initialize", json!({"capabilities": {}}));
        let uri = "file:///failure.ubnf";
        let out = notify(
            &mut server,
            "textDocument/didOpen",
            json!({
                "textDocument": {"uri": uri, "text": case["source"]}
            }),
        );
        if let Some(expected) = case.get("diagnostics") {
            assert_eq!(
                out,
                vec![
                    json!({"jsonrpc": "2.0", "method": "textDocument/publishDiagnostics",
                "params": {"uri": uri, "version": 0, "diagnostics": expected}})
                ],
                "{}",
                case["id"]
            );
        }
        let hover = request(
            &mut server,
            "textDocument/hover",
            json!({
                "textDocument": {"uri": uri}, "position": {"line": 999, "character": 999}
            }),
        );
        assert_eq!(hover["result"], case["hover"], "{}", case["id"]);
    }
}

fn request(server: &mut Server, method: &str, params: Value) -> Value {
    server
        .handle(json!({"jsonrpc":"2.0","id":1,"method":method,"params":params}))
        .pop()
        .unwrap()
}
fn notify(server: &mut Server, method: &str, params: Value) -> Vec<Value> {
    server.handle(json!({"jsonrpc":"2.0","method":method,"params":params}))
}
#[test]
fn rejected_lifecycle_and_changes_leave_the_document_unchanged() {
    let mut server = Server::default();
    let open = json!({"textDocument":{"uri":"file:///test.ubnf","version":1,"text":"grammar T { @root A ::= 'a'; }"}});
    assert!(notify(&mut server, "textDocument/didOpen", open.clone()).is_empty());
    assert_eq!(
        request(
            &mut server,
            "initialize",
            json!({"initializationOptions":{"languageProfile":null}})
        )["error"]["code"],
        -32602
    );
    assert_eq!(
        request(&mut server, "shutdown", json!({}))["error"]["code"],
        -32002
    );
    assert!(request(&mut server, "initialize", json!({}))["result"].is_object());
    let params =
        json!({"textDocument":{"uri":"file:///test.ubnf"},"position":{"line":0,"character":18}});
    assert!(request(&mut server, "textDocument/hover", params.clone())["result"].is_null());
    assert_eq!(notify(&mut server, "textDocument/didOpen", open).len(), 1);
    let before = request(&mut server, "textDocument/hover", params.clone());
    let changes = json!([]);
    assert!(notify(
        &mut server,
        "textDocument/didChange",
        json!({"textDocument":{"uri":"file:///test.ubnf","version":2},"contentChanges":changes})
    )
    .is_empty());
    assert_eq!(
        request(&mut server, "textDocument/hover", params.clone()),
        before
    );
    notify(
        &mut server,
        "textDocument/didChange",
        json!({"textDocument":{"uri":"file:///test.ubnf","version":1},"contentChanges":[{"text":"broken","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}}]}),
    );
    // The Java authoring server applies Full changes regardless of version order.
    assert_ne!(
        request(&mut server, "textDocument/hover", params.clone()),
        before
    );
    assert!(request(&mut server, "shutdown", json!({}))["result"].is_null());
    assert_eq!(
        request(&mut server, "textDocument/hover", params)["error"]["code"],
        -32600
    );
}
#[test]
fn document_versions_are_typed_but_ignored_and_change_opens_like_java_ext() {
    let mut server = Server::default();
    request(&mut server, "initialize", json!({}));
    let text = "grammar T { @root A ::= B; }";
    for (uri, version) in [
        ("file:///o.ubnf", json!(null)),
        ("file:///i.ubnf", json!(i32::MIN)),
    ] {
        let open = json!({"textDocument":{"uri":uri,"version":version,"text":text}});
        assert_eq!(notify(&mut server, "textDocument/didOpen", open).len(), 1);
    }
    // lsp4j's Integer version drops wider or non-integral values.
    for version in [json!(i64::from(i32::MAX) + 1), json!(1.5), json!("x")] {
        let open = json!({"textDocument":{"uri":"file:///d.ubnf","version":version,"text":text}});
        assert!(notify(&mut server, "textDocument/didOpen", open).is_empty());
    }
    let hover =
        json!({"textDocument":{"uri":"file:///c.ubnf"},"position":{"line":0,"character":0}});
    assert!(request(&mut server, "textDocument/hover", hover.clone())["result"].is_null());
    let change = json!({"textDocument":{"uri":"file:///c.ubnf"},"contentChanges":[{"text":text}]});
    assert_eq!(
        notify(&mut server, "textDocument/didChange", change).len(),
        1
    );
    assert!(!request(&mut server, "textDocument/hover", hover)["result"].is_null());
}
#[test]
fn transport_recovers_from_invalid_json_and_requires_shutdown_before_exit() {
    for (messages, code) in [
        (vec![json!({"jsonrpc":"2.0","method":"exit"})], 1),
        (
            vec![
                json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}),
                json!({"jsonrpc":"2.0","id":2,"method":"shutdown"}),
                json!({"jsonrpc":"2.0","method":"exit"}),
            ],
            0,
        ),
    ] {
        let mut input = b"Content-Length: 1\r\n\r\n{".to_vec();
        for message in messages {
            unlaxer_lsp::write_frame(&mut input, &message).unwrap();
        }
        let mut output = Vec::new();
        assert_eq!(
            Server::default()
                .serve(&mut input.as_slice(), &mut output)
                .unwrap(),
            code
        );
        let frame = unlaxer_lsp::read_frame(&mut output.as_slice())
            .unwrap()
            .unwrap();
        let error: Value = serde_json::from_slice(&frame).unwrap();
        assert_eq!(error["error"]["code"], -32700);
    }
    let mut server = Server::default();
    notify(&mut server, "exit", json!({}));
    assert_eq!(
        request(&mut server, "unlaxer/configure", json!({}))["error"],
        json!({"code":-32600,"message":"Server has shut down"})
    );
}
#[test]
fn positions_past_line_or_document_end_clamp_like_java() {
    let mut server = Server::default();
    assert!(request(&mut server, "initialize", json!({}))["result"].is_object());
    let uri = "file:///clamp.ubnf";
    let text = "grammar T {\r\n A ::= '😀';\r\n}";
    notify(
        &mut server,
        "textDocument/didOpen",
        json!({"textDocument":{"uri":uri,"version":1,"text":text}}),
    );
    for position in [
        json!({"line":1,"character":99}),
        json!({"line":9,"character":0}),
    ] {
        let params = json!({"textDocument":{"uri":uri},"position":position});
        let hover = request(&mut server, "textDocument/hover", params.clone());
        assert!(hover["result"]["contents"].is_object(), "{hover}");
        let completion = request(&mut server, "textDocument/completion", params);
        assert!(completion["result"].is_array(), "{completion}");
    }
    let inside_pair = json!({"textDocument":{"uri":uri},"position":{"line":1,"character":9}});
    assert_eq!(
        request(&mut server, "textDocument/hover", inside_pair)["error"]["code"],
        -32602
    );
}
