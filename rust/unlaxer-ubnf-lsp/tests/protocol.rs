use serde_json::{json, Value};
use unlaxer_ubnf_lsp::Server;

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
            json!({"initializationOptions":{"profile":"missing"}})
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
    for changes in [
        json!([]),
        json!([{"text":"broken","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}}]),
    ] {
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
    }
    notify(
        &mut server,
        "textDocument/didChange",
        json!({"textDocument":{"uri":"file:///test.ubnf","version":1},"contentChanges":[{"text":"broken"}]}),
    );
    assert_eq!(
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
fn transport_recovers_from_invalid_json_and_requires_shutdown_before_exit() {
    for (messages, code) in [
        (vec![json!({"jsonrpc":"2.0","method":"exit"})], 1),
        (
            vec![
                json!({"jsonrpc":"2.0","id":1,"method":"initialize"}),
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
}
