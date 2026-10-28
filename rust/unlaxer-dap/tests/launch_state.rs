use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::Cursor;
use unlaxer_dap::{Backend, DebugSource, Server, Step};
use unlaxer_runtime::{source::Snapshot, Span};

fn fixture() -> Value {
    serde_json::from_str(include_str!(
        "../../../docs/fixtures/dap-failures/launch-state.json"
    ))
    .unwrap()
}

struct Probe;
impl Backend for Probe {
    fn supports_token_trace(&self) -> bool {
        true
    }
    fn steps(&mut self, _: &Snapshot, _: &Value) -> Result<Vec<Step>, String> {
        Ok(vec![Step {
            label: "first".into(),
            span: Span { start: 0, end: 1 },
            generated_text: None,
            projected_text: None,
        }])
    }
    fn resolve_source(&mut self, _: &str, _: &str, _: &Value) -> Result<DebugSource, String> {
        Ok(DebugSource {
            text: "x".into(),
            line_offset: 7,
        })
    }
    fn runtime_variables(
        &mut self,
        _: &Snapshot,
        _: &str,
        _: &Value,
    ) -> Result<BTreeMap<String, String>, String> {
        // Sorts before runtimeMode, but Java appends hook probes after the two reserved keys.
        Ok(BTreeMap::from([("application".into(), "retained".into())]))
    }
}

fn request<B: Backend>(server: &mut Server<B>, command: &str, args: Value) -> Value {
    let output =
        server.handle(json!({"seq":1,"type":"request","command":command,"arguments":args}));
    assert!(output[0]["success"].as_bool().unwrap(), "{output:?}");
    output[0]["body"].clone()
}

#[test]
fn pending_launch_keeps_only_the_last_prepared_probes_and_offset() {
    let fixture = fixture();
    let mut server = Server::new(Probe);
    let vars = json!({"variablesReference":1});
    let stack = json!({"threadId":1});
    for mode in ["token", "ast"] {
        request(
            &mut server,
            "launch",
            json!({"program":"missing","steppingMode":mode}),
        );
        assert_eq!(
            request(&mut server, "variables", vars.clone())["variables"],
            fixture["freshVariables"]
        );
        let frames = request(&mut server, "stackTrace", stack.clone())["stackFrames"].clone();
        if mode == "token" {
            let mut expected = fixture["placeholder"].clone();
            expected["source"]["path"] = json!("missing");
            assert_eq!(frames, json!([expected]));
        } else {
            assert_eq!(frames, json!([]));
        }
    }
    // A stable existing file; the backend supplies its own input and offset.
    request(
        &mut server,
        "launch",
        json!({"program":concat!(env!("CARGO_MANIFEST_DIR"),"/Cargo.toml"),"runtimeMode":"prepared-runtime","steppingMode":"ToKeN","stopOnEntry":true}),
    );
    request(&mut server, "configurationDone", json!({}));
    request(&mut server, "continue", stack.clone());
    let mut expected_vars = fixture["retainedVariables"].as_array().unwrap().clone();
    expected_vars.push(
        json!({"name":"application","value":"retained","type":"String","variablesReference":0}),
    );
    for base in [true, false] {
        request(
            &mut server,
            "initialize",
            json!({"linesStartAt1":base,"columnsStartAt1":base}),
        );
        request(
            &mut server,
            "launch",
            json!({"program":5,"runtimeMode":"new-runtime","steppingMode":"token","lineOffset":99}),
        );
        let mut expected = fixture["placeholder"].clone();
        expected["source"]["path"] = json!("5.0");
        expected["line"] = json!(7 + usize::from(base));
        expected["column"] = json!(usize::from(base));
        assert_eq!(
            request(&mut server, "stackTrace", stack.clone())["stackFrames"],
            json!([expected])
        );
        assert_eq!(
            request(&mut server, "variables", vars.clone())["variables"],
            json!(expected_vars)
        );
        assert_eq!(
            request(&mut server, "scopes", json!({"frameId":0})),
            fixture["scopes"]
        );
    }
}

#[test]
fn multiple_method_arguments_fail_without_dispatching_the_first_dto() {
    let fixture = fixture();
    for row in fixture["arrayRequests"].as_array().unwrap() {
        for count in [2, 3] {
            let mut server = Server::new(Probe);
            request(
                &mut server,
                "launch",
                json!({"program":"original","runtimeMode":"token"}),
            );
            let before = request(&mut server, "stackTrace", json!({"threadId":1}));
            let mut arguments = vec![row["arguments"].clone()];
            arguments.resize(count, json!({}));
            let raw = format!(
                "{{\"seq\":2,\"type\":\"request\",\"command\":{},\"arguments\":{}}}",
                row["command"],
                json!(arguments)
            );
            let mut input = Cursor::new(format!("Content-Length: {}\r\n\r\n{raw}", raw.len()));
            let mut output = vec![];
            server.serve(&mut input, &mut output).unwrap();
            let mut frames = Cursor::new(output);
            let bytes = unlaxer_protocol::read_frame(&mut frames).unwrap().unwrap();
            let response: Value = serde_json::from_slice(&bytes).unwrap();
            assert_eq!(response["success"], false, "{raw}: {response}");
            assert_eq!(response["message"], fixture["arrayFailure"]["message"]);
            assert!(response["body"]["message"]
                .as_str()
                .unwrap()
                .contains("Expected END_ARRAY but was BEGIN_OBJECT"));
            assert!(
                unlaxer_protocol::read_frame(&mut frames).unwrap().is_none(),
                "no lifecycle event"
            );
            assert_eq!(
                request(&mut server, "stackTrace", json!({"threadId":1})),
                before,
                "{raw}"
            );
            assert_eq!(
                request(&mut server, "threads", json!({})),
                fixture["threads"]
            );
        }
    }
}

/// Resolves with the requested offset, then fails while collecting steps when asked.
struct FailAfterResolve;
impl Backend for FailAfterResolve {
    fn supports_token_trace(&self) -> bool {
        true
    }
    fn steps(&mut self, _: &Snapshot, args: &Value) -> Result<Vec<Step>, String> {
        if args["fail"] == true {
            return Err("parse error".into());
        }
        Ok(vec![Step {
            label: "first".into(),
            span: Span { start: 0, end: 1 },
            generated_text: None,
            projected_text: None,
        }])
    }
    fn resolve_source(&mut self, _: &str, _: &str, args: &Value) -> Result<DebugSource, String> {
        if args["unresolved"] == true {
            return Err("unknown source".into());
        }
        Ok(DebugSource {
            text: "x".into(),
            line_offset: args["lineOffset"].as_u64().unwrap() as usize,
        })
    }
}

#[test]
fn a_preparation_failing_after_resolution_still_moves_the_pending_offset() {
    let mut server = Server::new(FailAfterResolve);
    let program = concat!(env!("CARGO_MANIFEST_DIR"), "/Cargo.toml");
    let stack = json!({"threadId":1});
    let pending_line = |server: &mut Server<FailAfterResolve>| {
        request(
            server,
            "launch",
            json!({"program":"missing","steppingMode":"token"}),
        );
        request(server, "stackTrace", stack.clone())["stackFrames"][0]["line"].clone()
    };
    request(
        &mut server,
        "launch",
        json!({"program":program,"runtimeMode":"prepared","lineOffset":7,"stopOnEntry":true}),
    );
    request(&mut server, "configurationDone", json!({}));
    request(&mut server, "continue", stack.clone());
    assert_eq!(pending_line(&mut server), json!(8));
    // Java assigns sourceLineOffset before parsing: a parse/step failure keeps the new offset,
    // while the variables still come from the last successful preparation.
    request(
        &mut server,
        "launch",
        json!({"program":program,"runtimeMode":"failed","lineOffset":3,"fail":true}),
    );
    request(&mut server, "configurationDone", json!({}));
    assert_eq!(pending_line(&mut server), json!(4));
    assert_eq!(
        request(&mut server, "variables", json!({"variablesReference":1}))["variables"][0]["value"],
        json!("prepared")
    );
    // A failure while resolving the source happens before Java reads the offset.
    request(
        &mut server,
        "launch",
        json!({"program":program,"lineOffset":5,"unresolved":true}),
    );
    request(&mut server, "configurationDone", json!({}));
    assert_eq!(pending_line(&mut server), json!(4));
}
