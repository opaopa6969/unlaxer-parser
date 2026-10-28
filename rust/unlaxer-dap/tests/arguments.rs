use serde_json::{json, Value};
use std::io::Cursor;
use unlaxer_dap::{AstBackend, Server};

fn fixture() -> Value {
    serde_json::from_str(include_str!(
        "../../../docs/fixtures/dap-failures/arguments.json"
    ))
    .unwrap()
}

fn exchange(request: &str) -> Value {
    let mut server = Server::new(AstBackend {
        mapper: |_| Ok(vec![]),
    });
    let mut input = Cursor::new(format!(
        "Content-Length: {}\r\n\r\n{request}",
        request.len()
    ));
    let mut output = vec![];
    server.serve(&mut input, &mut output).unwrap();
    let bytes = unlaxer_protocol::read_frame(&mut Cursor::new(output))
        .unwrap()
        .unwrap();
    serde_json::from_slice(&bytes).unwrap()
}

#[test]
fn both_reader_orders_follow_the_independent_java_integer_oracle() {
    let fixture = fixture();
    let cases = fixture["numbers"].as_array().unwrap();
    assert_eq!(cases.len(), 54);
    for row in cases {
        for tree in [true, false] {
            let value = row["valueJson"].as_str().unwrap();
            let arguments = format!("\"arguments\":{{\"breakpoints\":[{{\"line\":{value}}}]}}");
            let command = "\"command\":\"setBreakpoints\"";
            let fields = if tree {
                format!("{arguments},{command}")
            } else {
                format!("{command},{arguments}")
            };
            let raw = format!("{{\"seq\":1,\"type\":\"request\",{fields}}}");
            let response = exchange(&raw);
            let integer = row.get(if tree { "int32" } else { "streamInt32" });
            assert_eq!(response["success"], integer.is_some(), "{raw}: {response}");
            if let Some(integer) = integer {
                assert_eq!(
                    &response["body"]["breakpoints"][0]["line"], integer,
                    "{raw}"
                );
                assert!(response.get("message").is_none());
            } else {
                let position = raw.rfind(value).unwrap()
                    + if row["value"].is_array() || row["value"].is_object() {
                        1
                    } else {
                        value.len()
                    };
                let expected = row[if tree { "error" } else { "streamError" }]
                    .as_str()
                    .unwrap()
                    .replace("{column}", &(position + 1).to_string())
                    .replace("{path}", "$.arguments.breakpoints[0].line");
                assert_eq!(response["message"], "Message could not be parsed.", "{raw}");
                assert_eq!(response["body"], json!({"message":expected}), "{raw}");
            }
        }
    }
}

#[test]
fn reader_locations_keep_the_measured_whitespace_unicode_and_number_spelling() {
    let fixture = fixture();
    let cases = fixture["literals"].as_array().unwrap();
    assert_eq!(cases.len(), 15);
    for row in cases
        .iter()
        .filter(|row| row.get("launchProgram").is_none())
    {
        let response = exchange(row["raw"].as_str().unwrap());
        assert_eq!(response["success"], false);
        assert_eq!(response["message"], row["expected"]["message"]);
        assert_eq!(
            response["body"],
            json!({"message":row["expected"]["bodyMessage"]}),
            "{}",
            row["id"]
        );
    }
}

#[test]
fn every_lsp4j_argument_dto_field_follows_the_java_oracle() {
    let fixture = fixture();
    let cases = fixture["requests"].as_array().unwrap();
    assert_eq!(cases.len(), 557);
    for row in cases {
        let response = exchange(row["raw"].as_str().unwrap());
        let expected = &row["expected"];
        assert_eq!(response["success"], expected["success"], "{}", row["id"]);
        assert_eq!(response["message"], expected["message"], "{}", row["id"]);
        if expected["bodyType"] == "object" {
            assert_eq!(
                response["body"],
                json!({"message":expected["bodyMessage"]}),
                "{}",
                row["id"]
            );
        } else {
            assert_eq!(expected["bodyType"], "string");
            let first = response["body"]
                .as_str()
                .and_then(|body| body.lines().next());
            assert_eq!(first, expected["firstLine"].as_str(), "{}", row["id"]);
        }
    }
}

#[test]
fn launch_string_lookups_do_not_rewrite_application_hook_arguments() {
    use std::collections::BTreeMap;
    use unlaxer_dap::{Backend, Step};
    use unlaxer_runtime::{source::Snapshot, Span};
    struct Hooks;
    impl Backend for Hooks {
        fn steps(&mut self, _: &Snapshot, arguments: &Value) -> Result<Vec<Step>, String> {
            assert_eq!(arguments["runtimeMode"], 5);
            assert_eq!(arguments["formulaSource"], json!({"z":[1,true]}));
            Ok(vec![Step {
                label: "node".into(),
                span: Span { start: 0, end: 1 },
                generated_text: None,
                projected_text: None,
            }])
        }
        fn runtime_variables(
            &mut self,
            _: &Snapshot,
            mode: &str,
            arguments: &Value,
        ) -> Result<BTreeMap<String, String>, String> {
            assert_eq!(mode, "5.0");
            assert_eq!(arguments["runtimeMode"], 5);
            Ok(BTreeMap::new())
        }
    }
    let file = std::env::temp_dir().join(format!("dap-905-hooks-{}.txt", std::process::id()));
    std::fs::write(&file, "x").unwrap();
    let mut server = Server::new(Hooks);
    let response = server.handle(json!({"seq":1,"type":"request","command":"launch","arguments":{"program":file,"runtimeMode":5,"formulaSource":{"z":[1,true]},"steppingMode":"ast","stopOnEntry":true}}));
    assert_eq!(response[0]["success"], true);
    let configured = server
        .handle(json!({"seq":2,"type":"request","command":"configurationDone","arguments":{}}));
    assert_eq!(configured[0]["success"], true);
    assert_eq!(configured[1]["event"], "stopped");
    std::fs::remove_file(file).unwrap();
}
