use serde_json::{json, Value};
use std::io::Cursor;
use unlaxer_lsp::{read_frame, write_frame, GrammarBackend, Server};
use unlaxer_runtime::{Expr, Rule};
fn backend() -> GrammarBackend {
    GrammarBackend {
        name: "Emoji".into(),
        entry: "Root".into(),
        root: 0,
        whitespace: true,
        keywords: vec!["grammar".into(), "<".into(), ">".into(), "!".into()],
        grammar: vec![Rule {
            name: "Root",
            expression: Expr::Sequence(vec![
                Expr::Literal("<"),
                Expr::Repeat {
                    child: Box::new(Expr::Except(">")),
                    min: 0,
                    max: None,
                },
                Expr::Literal(">"),
                Expr::Literal("!"),
            ]),
        }]
        .into(),
    }
}
fn call(method: &str, params: Value, id: Option<i64>) -> Value {
    let mut value = json!({"jsonrpc":"2.0","method":method,"params":params});
    if let Some(id) = id {
        value["id"] = json!(id);
    }
    value
}
#[test]
fn shared_independent_positions_run_through_real_byte_frames() {
    let fixture = include_str!("../../../docs/fixtures/classic-lsp/positions.tsv");
    let rows: Vec<_> = fixture.lines().filter(|l| !l.starts_with('#')).collect();
    assert_eq!(18, rows.len());
    for row in rows {
        let fields: Vec<_> = row.split('\t').collect();
        let id = fields[0];
        let source = if fields[1] == "~" {
            String::new()
        } else {
            fields[1].replace("\\r", "\r").replace("\\n", "\n")
        };
        let nums: Vec<i64> = fields[2..8].iter().map(|s| s.parse().unwrap()).collect();
        let messages = vec![
            call("initialize", json!({}), Some(1)),
            call("initialized", json!({}), None),
            call(
                "textDocument/didOpen",
                json!({"textDocument":{"uri":"file:///fixture","version":1,"text":source}}),
                None,
            ),
            call(
                "textDocument/completion",
                json!({"textDocument":{"uri":"file:///fixture"},"position":{"line":nums[4],"character":nums[5]}}),
                Some(2),
            ),
            call("shutdown", Value::Null, Some(3)),
            call("exit", Value::Null, None),
        ];
        let mut input = vec![];
        for message in messages {
            write_frame(&mut input, &message).unwrap();
        }
        let mut output = vec![];
        Server::new(backend())
            .serve(&mut Cursor::new(input), &mut output)
            .unwrap();
        let mut reader = Cursor::new(output);
        let mut emitted = vec![];
        while let Some(bytes) = read_frame(&mut reader).unwrap() {
            emitted.push(serde_json::from_slice::<Value>(&bytes).unwrap());
        }
        let diagnostics = emitted
            .iter()
            .find(|m| m["method"] == "textDocument/publishDiagnostics")
            .unwrap()["params"]["diagnostics"]
            .as_array()
            .unwrap();
        if nums[0] < 0 {
            assert!(diagnostics.is_empty(), "{id}: {diagnostics:?}");
        } else {
            assert_eq!(1, diagnostics.len(), "{id}");
            assert_eq!(
                json!({"start":{"line":nums[0],"character":nums[1]},"end":{"line":nums[2],"character":nums[3]}}),
                diagnostics[0]["range"],
                "{id}"
            );
        }
        let completion = emitted.iter().find(|m| m["id"] == 2).unwrap()["result"]
            .as_array()
            .unwrap();
        assert_eq!(fields[8] == "true", !completion.is_empty(), "{id}");
        assert_eq!(Value::Null, emitted.last().unwrap()["result"]);
    }
}
#[test]
fn version_and_failure_transactions_preserve_last_snapshot_and_close_clears_diagnostics() {
    let mut server = Server::new(backend());
    server.handle(call("initialize", json!({}), Some(1)));
    let uri = "file:///😀";
    let open = server.handle(call(
        "textDocument/didOpen",
        json!({"textDocument":{"uri":uri,"version":2,"text":"<😀>!"}}),
        None,
    ));
    assert_eq!(2, open[0]["params"]["version"]);
    for params in [
        json!({"textDocument":{"uri":uri,"version":1},"contentChanges":[{"text":"bad"}]}),
        json!({"textDocument":{"uri":uri,"version":3},"contentChanges":[]}),
        json!({"textDocument":{"uri":uri,"version":3},"contentChanges":[{"text":"bad","range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}}}]}),
    ] {
        assert!(server
            .handle(call("textDocument/didChange", params, None))
            .is_empty());
        assert_eq!("<😀>!", server.snapshot(uri).unwrap().text);
        assert_eq!(2, server.snapshot(uri).unwrap().version);
    }
    let changed=server.handle(call("textDocument/didChange",json!({"textDocument":{"uri":uri,"version":3},"contentChanges":[{"text":"bad"},{"text":"<>"}]}),None));
    assert_eq!(3, changed[0]["params"]["version"]);
    assert_eq!("<>", server.snapshot(uri).unwrap().text);
    let close = server.handle(call(
        "textDocument/didClose",
        json!({"textDocument":{"uri":uri}}),
        None,
    ));
    assert_eq!(json!([]), close[0]["params"]["diagnostics"]);
    assert!(server.snapshot(uri).is_none());
    assert_eq!(
        -32601,
        server.handle(call("unsupported", json!({}), Some(3)))[0]["error"]["code"]
    );
}
#[test]
fn bounded_framing_and_json_failure_are_distinct_and_stream_remains_synchronized() {
    for bad in [
        b"Content-Length: 1\n\nx".as_slice(),
        b"Content-Length: 1\r\nContent-Length: 1\r\n\r\nx",
        b"Content-Length: 99999999\r\n\r\n",
        b"Other: 1\r\n\r\nx",
        b"Content-Length: 2\r\n\r\nx",
        b"Content-Length: 1\r\nContent-Type: application/vscode-jsonrpc; charset=latin1\r\n\r\nx",
    ] {
        assert!(read_frame(&mut Cursor::new(bad)).is_err());
    }
    let mut input = b"Content-Length: 1\r\n\r\n{".to_vec();
    write_frame(&mut input, &call("initialize", json!({}), Some(7))).unwrap();
    let mut output = vec![];
    Server::new(backend())
        .serve(&mut Cursor::new(input), &mut output)
        .unwrap();
    let mut cursor = Cursor::new(output);
    let first: Value = serde_json::from_slice(&read_frame(&mut cursor).unwrap().unwrap()).unwrap();
    assert_eq!(-32700, first["error"]["code"]);
    let next: Value = serde_json::from_slice(&read_frame(&mut cursor).unwrap().unwrap()).unwrap();
    assert_eq!(7, next["id"]);
}
