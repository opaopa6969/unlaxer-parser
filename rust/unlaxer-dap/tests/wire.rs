use serde_json::{json, Value};
use unlaxer_dap::{parse_error, ParserBackend, Server};
use unlaxer_runtime::{parse_detailed, Expr, Rule};

#[test]
fn parse_output_uses_root_result_and_utf16_prefix_not_farthest_failure() {
    for (expression, source, expected) in [
        (
            Expr::Literal("😀").then(Expr::Literal("x")),
            "😀?",
            "Parse error at offset 0",
        ),
        (Expr::Literal("😀"), "😀?", "Parse error at offset 2"),
        (Expr::Literal("😀é"), "😀é?", "Parse error at offset 3"),
    ] {
        let diagnostic = parse_detailed(
            &[Rule {
                name: "Root",
                expression,
            }],
            0,
            false,
            source,
        )
        .unwrap_err();
        assert!(diagnostic.farthest.offset > 0);
        assert_eq!(parse_error(source, &diagnostic), expected);
    }
}

#[test]
fn source_failures_match_shared_output_and_wire_order_in_both_modes() {
    let fixture: Value = serde_json::from_str(include_str!(
        "../../../docs/fixtures/dap-failures/wire.json"
    ))
    .unwrap();
    let rows = fixture["routes"]["generated"]["requests"]
        .as_array()
        .unwrap();
    let directory = env!("CARGO_MANIFEST_DIR");
    let missing = format!("{directory}/missing-wire-945");
    assert!(!std::path::Path::new(&missing).exists());
    let invalid = std::env::temp_dir().join(format!("wire-945-invalid-{}.txt", std::process::id()));
    std::fs::write(&invalid, [0xff]).unwrap();
    let mut server = Server::new(ParserBackend {
        mapper: |_| Ok(vec![]),
        tokens: |_| Ok(vec![]),
    });
    let mut seq = 0;
    let mut last_seq = 0;
    for mode in ["token", "ast"] {
        for (case, path, marker) in [
            ("missing", missing.as_str(), "$MISSING"),
            ("directory", directory, "$DIRECTORY"),
            ("utf8", invalid.to_str().unwrap(), "$INVALID_UTF8"),
            ("nul", "bad\0path", "bad\0path"),
        ] {
            seq += 1;
            let launched = server.handle(json!({"seq":seq,"type":"request","command":"launch","arguments":{"program":path,"steppingMode":mode}}));
            assert_eq!(launched[0]["event"], "initialized");
            assert_eq!(launched[1]["request_seq"], seq);
            seq += 1;
            let messages = server.handle(
                json!({"seq":seq,"type":"request","command":"configurationDone","arguments":{}}),
            );
            let mut normalized = vec![];
            for message in launched.iter().chain(&messages) {
                assert!(message["seq"].as_i64().unwrap() > last_seq);
                last_seq = message["seq"].as_i64().unwrap();
            }
            for mut message in messages {
                if message["type"] == "response" {
                    assert_eq!(message["request_seq"], seq);
                }
                message.as_object_mut().unwrap().remove("seq");
                message.as_object_mut().unwrap().remove("request_seq");
                if let Some(text) = message["body"]["output"].as_str() {
                    message["body"]["output"] = json!(text.replace(path, marker));
                }
                normalized.push(message);
            }
            let id = format!("{mode}/{case}/configure");
            let row = rows.iter().find(|row| row["id"] == id).unwrap();
            assert_eq!(json!(normalized), row["messages"], "{id}");
            seq += 1;
            let stack = server.handle(json!({"seq":seq,"type":"request","command":"stackTrace","arguments":{"threadId":1}}));
            assert_eq!(stack[0]["body"], json!({"stackFrames":[]}));
        }
    }
    std::fs::remove_file(invalid).unwrap();
}

#[test]
fn malformed_utf8_lengths_use_the_shared_jdk_observations() {
    let fixture: Value = serde_json::from_str(include_str!(
        "../../../docs/fixtures/dap-failures/wire.json"
    ))
    .unwrap();
    let rows = fixture["routes"]["generated"]["requests"]
        .as_array()
        .unwrap();
    let path = std::env::temp_dir().join(format!("wire-945-utf8-{}.txt", std::process::id()));
    let mut server = Server::new(ParserBackend {
        mapper: |_| Ok(vec![]),
        tokens: |_| Ok(vec![]),
    });
    let mut count = 0;
    for (index, row) in rows
        .iter()
        .enumerate()
        .filter(|(_, row)| row.get("programBytes").is_some())
    {
        let hex = row["programBytes"].as_str().unwrap();
        let bytes: Vec<_> = (0..hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&hex[i..i + 2], 16).unwrap())
            .collect();
        std::fs::write(&path, bytes).unwrap();
        let mut args = row["arguments"].clone();
        args["program"] = json!(path);
        server.handle(json!({"seq":1,"type":"request","command":"launch","arguments":args}));
        let messages = server
            .handle(json!({"seq":2,"type":"request","command":"configurationDone","arguments":{}}));
        assert_eq!(messages[0]["event"], "output");
        let mut expected = rows[index + 1]["messages"][0]["body"].clone();
        expected["output"] = json!(expected["output"]
            .as_str()
            .unwrap()
            .replace("$INVALID_UTF8", path.to_str().unwrap()));
        assert_eq!(messages[0]["body"], expected, "{hex}");
        count += 1;
    }
    assert_eq!(count, 20);
    std::fs::remove_file(path).unwrap();
}

#[cfg(unix)]
#[test]
fn unix_path_failures_use_java_unix_exception_text_and_path_normalization() {
    let fixture: Value = serde_json::from_str(include_str!(
        "../../../docs/fixtures/dap-failures/wire.json"
    ))
    .unwrap();
    let rows = fixture["routes"]["generated"]["requests"]
        .as_array()
        .unwrap();
    let directory = std::env::temp_dir().join(format!("wire-945-unix-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&directory);
    std::fs::create_dir_all(directory.join("wire-directory")).unwrap();
    let markers = [
        ("$PROGRAM", directory.join("wire-program.txt")),
        ("$DIRECTORY", directory.join("wire-directory")),
        ("$LOOP", directory.join("wire-loop")),
    ];
    std::fs::write(&markers[0].1, "x").unwrap();
    std::os::unix::fs::symlink("wire-loop", &markers[2].1).unwrap();
    let substitute = |text: &str, forward: bool| {
        markers
            .iter()
            .fold(text.to_owned(), |text, (marker, path)| {
                let path = path.to_str().unwrap();
                if forward {
                    text.replace(marker, path)
                } else {
                    text.replace(path, marker)
                }
            })
    };
    let mut server = Server::new(ParserBackend {
        mapper: |_| Ok(vec![]),
        tokens: |_| Ok(vec![]),
    });
    let mut seq = 0;
    for case in ["not-directory", "symlink-loop", "long-name"] {
        let launch = rows
            .iter()
            .find(|row| row["id"] == format!("token/{case}/launch"))
            .unwrap();
        let program = substitute(launch["arguments"]["program"].as_str().unwrap(), true);
        seq += 1;
        server.handle(
            json!({"seq":seq,"type":"request","command":"launch","arguments":{"program":program}}),
        );
        seq += 1;
        let messages = server.handle(
            json!({"seq":seq,"type":"request","command":"configurationDone","arguments":{}}),
        );
        let mut normalized = vec![];
        for mut message in messages {
            let object = message.as_object_mut().unwrap();
            object.remove("seq");
            object.remove("request_seq");
            if let Some(text) = message["body"]["output"].as_str() {
                message["body"]["output"] = json!(substitute(text, false));
            }
            normalized.push(message);
        }
        let expected = rows
            .iter()
            .find(|row| row["id"] == format!("token/{case}/configure"))
            .unwrap();
        assert_eq!(json!(normalized), expected["messages"], "{case}");
    }
    // Java's Path.of drops the trailing '/', so the file itself is read (#945).
    let program = format!("{}/", markers[0].1.to_str().unwrap());
    server.handle(
        json!({"seq":seq+1,"type":"request","command":"launch","arguments":{"program":program}}),
    );
    let messages = server
        .handle(json!({"seq":seq+2,"type":"request","command":"configurationDone","arguments":{}}));
    assert_eq!(
        messages[0]["body"]["output"],
        "Token stepping unavailable: no parser steps\n"
    );
    std::fs::remove_dir_all(directory).unwrap();
}
