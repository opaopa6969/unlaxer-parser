use serde_json::{json, Value};
use unlaxer_lsp::{Backend, Diagnostic, SemanticTokensLegend, Server};
use unlaxer_runtime::source::Snapshot;

struct Provider {
    fixture: Value,
}
impl Backend for Provider {
    fn grammar(&self) -> &str {
        "Emoji"
    }
    fn entry(&self) -> &str {
        "Root"
    }
    fn keywords(&self) -> &[String] {
        &[]
    }
    fn validate(&mut self, _: &Snapshot) -> Vec<Diagnostic> {
        vec![]
    }
    fn semantic_tokens_legend(&self) -> SemanticTokensLegend {
        SemanticTokensLegend {
            token_types: vec!["word".into(), "emoji".into()],
            token_modifiers: vec!["declaration".into()],
        }
    }
    fn semantic_tokens(&mut self, snapshot: &Snapshot) -> Result<Vec<u32>, String> {
        let row = self.fixture["cases"]
            .as_array()
            .unwrap()
            .iter()
            .find(|row| format!("file:///{}", row["id"].as_str().unwrap()) == snapshot.uri)
            .ok_or("unexpected provider call")?;
        if row["providerFailure"] == true {
            return Err("provider failure".into());
        }
        Ok(row["tokens"]
            .as_array()
            .unwrap()
            .iter()
            .map(|value| {
                value
                    .as_u64()
                    .and_then(|n| u32::try_from(n).ok())
                    .unwrap_or(u32::MAX)
            })
            .collect())
    }
}
fn request(method: &str, params: Value) -> Value {
    json!({"jsonrpc":"2.0","id":1,"method":method,"params":params})
}
#[test]
fn full_tokens_use_shared_utf16_legend_and_reject_invalid_provider_data() {
    let fixture: Value = serde_json::from_str(include_str!(
        "../../../docs/fixtures/classic-lsp/semantic-tokens.json"
    ))
    .unwrap();
    let mut server = Server::new(Provider {
        fixture: fixture.clone(),
    });
    assert_eq!(
        -32002,
        server.handle(request(
            "textDocument/semanticTokens/full",
            json!({"textDocument":{"uri":"file:///missing"}})
        ))[0]["error"]["code"]
    );
    let initialized = server.handle(request("initialize", json!({})));
    let provider = &initialized[0]["result"]["capabilities"]["semanticTokensProvider"];
    assert_eq!(fixture["legend"], provider["legend"]);
    assert_eq!(json!(true), provider["full"]);
    assert!(provider.get("range").is_none());
    assert_eq!(
        json!([]),
        server.handle(request(
            "textDocument/semanticTokens/full",
            json!({"textDocument":{"uri":"file:///missing"}})
        ))[0]["result"]["data"]
    );
    for (multiline, overlapping) in [(false, false), (true, false), (false, true), (true, true)] {
        server.handle(request("initialize", json!({"capabilities":{"textDocument":{"semanticTokens":{"multilineTokenSupport":multiline,"overlappingTokenSupport":overlapping}}}})));
        for row in fixture["cases"].as_array().unwrap() {
            if row["largeLegend"] == true {
                continue;
            }
            let uri = format!("file:///{}", row["id"].as_str().unwrap());
            server.handle(json!({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":uri,"version":1,"text":row["text"]}}}));
            let response = server.handle(request(
                "textDocument/semanticTokens/full",
                json!({"textDocument":{"uri":uri}}),
            ));
            let invalid = row.get("error").is_some()
                || multiline && row.get("errorWhenMultiline").is_some()
                || !overlapping
                    && (row["requiresOverlap"] == true
                        || multiline && row["overlapsWhenMultiline"] == true);
            if invalid {
                assert_eq!(-32603, response[0]["error"]["code"], "{}", row["id"]);
            } else {
                assert_eq!(
                    row["expected"], response[0]["result"]["data"],
                    "{}",
                    row["id"]
                );
            }
            server.handle(json!({"jsonrpc":"2.0","method":"textDocument/didClose","params":{"textDocument":{"uri":uri}}}));
            assert_eq!(
                json!([]),
                server.handle(request(
                    "textDocument/semanticTokens/full",
                    json!({"textDocument":{"uri":uri}})
                ))[0]["result"]["data"]
            );
        }
    }
    for method in [
        "textDocument/semanticTokens/range",
        "textDocument/semanticTokens/full/delta",
    ] {
        assert_eq!(
            -32601,
            server.handle(request(method, json!({})))[0]["error"]["code"]
        );
    }
    server.handle(request("shutdown", Value::Null));
    assert_eq!(
        -32600,
        server.handle(request(
            "textDocument/semanticTokens/full",
            json!({"textDocument":{"uri":"file:///missing"}})
        ))[0]["error"]["code"]
    );
}
