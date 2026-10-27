//! JVM-free UBNF authoring server. Capability scope is recorded in
//! docs/ubnf-authoring-lsp.md; generated-language LSPs keep their own backend.
mod features;
mod index;
mod text;
use index::Index;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::{self, BufRead, Write};

struct Document {
    source: String,
    version: i64,
}
#[derive(Default)]
pub struct Server {
    documents: BTreeMap<String, Document>,
    initialized: bool,
    shutdown: bool,
    exited: bool,
}
type Error = (i64, String);
fn invalid(message: &str) -> Error {
    (-32602, message.into())
}
impl Server {
    fn index(&self, uri: &str) -> Option<Index> {
        let source = &self.documents.get(uri)?.source;
        let buffers = self
            .documents
            .iter()
            .filter_map(|(uri, doc)| {
                text::file_path(uri)
                    .ok()
                    .map(|path| (path, doc.source.clone()))
            })
            .collect();
        Some(Index::new(uri, source, buffers))
    }
    fn publish(&self, uri: &str, out: &mut Vec<Value>) {
        if let Some(index) = self.index(uri) {
            out.push(json!({"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":uri,"diagnostics":index.diagnostics}}));
        }
    }
    pub fn handle(&mut self, message: Value) -> Vec<Value> {
        let mut out = Vec::new();
        let request = message.get("id").is_some();
        let id = message.get("id").cloned().unwrap_or(Value::Null);
        let method = message["method"].as_str().unwrap_or("");
        if message["jsonrpc"] != "2.0"
            || method.is_empty()
            || (request && !(id.is_string() || id.is_number() || id.is_null()))
        {
            return vec![
                json!({"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Invalid Request"}}),
            ];
        }
        let params = &message["params"];
        let result = (|| -> Result<Value, Error> {
            if method == "exit" && !request {
                self.exited = true;
                return Ok(Value::Null);
            }
            if self.shutdown || self.exited {
                return Err((-32600, "Server has stopped".into()));
            }
            if method == "initialize" && request {
                if self.initialized {
                    return Err((-32600, "Server already initialized".into()));
                }
                if params
                    .get("initializationOptions")
                    .is_some_and(|v| !v.is_null() && v != &json!({}))
                {
                    return Err(invalid("Authoring profile options are not yet supported"));
                }
                self.initialized = true;
                return Ok(
                    json!({"capabilities":{"textDocumentSync":1,"hoverProvider":true,"completionProvider":{"resolveProvider":false,"triggerCharacters":["@","=","."]}}}),
                );
            }
            if !self.initialized {
                return Err((-32002, "Server not initialized".into()));
            }
            if method == "shutdown" && request {
                self.shutdown = true;
                return Ok(Value::Null);
            }
            match method {
                "initialized"
                | "$/setTrace"
                | "$/cancelRequest"
                | "workspace/didChangeConfiguration"
                | "workspace/didChangeWatchedFiles"
                    if !request =>
                {
                    Ok(Value::Null)
                }
                "textDocument/didOpen"
                | "textDocument/didChange"
                | "textDocument/didClose"
                | "textDocument/didSave"
                    if !request =>
                {
                    let doc = &params["textDocument"];
                    let uri = doc["uri"]
                        .as_str()
                        .ok_or_else(|| invalid("document URI required"))?;
                    match method {
                        "textDocument/didOpen" => {
                            let source = doc["text"]
                                .as_str()
                                .ok_or_else(|| invalid("document text required"))?
                                .to_owned();
                            let version = doc["version"]
                                .as_i64()
                                .ok_or_else(|| invalid("document version required"))?;
                            self.documents
                                .insert(uri.into(), Document { source, version });
                            self.publish(uri, &mut out);
                        }
                        "textDocument/didChange" => {
                            let changes = params["contentChanges"]
                                .as_array()
                                .ok_or_else(|| invalid("contentChanges required"))?;
                            let change = changes.first().ok_or_else(|| invalid("empty change"))?;
                            if change.get("range").is_some_and(|v| !v.is_null()) {
                                return Err(invalid("full text change required"));
                            }
                            let source = change["text"]
                                .as_str()
                                .ok_or_else(|| invalid("change text required"))?
                                .to_owned();
                            let version = doc["version"]
                                .as_i64()
                                .ok_or_else(|| invalid("document version required"))?;
                            let state = self
                                .documents
                                .get_mut(uri)
                                .ok_or_else(|| invalid("document not open"))?;
                            if version <= state.version {
                                return Err(invalid("stale document version"));
                            }
                            state.source = source;
                            state.version = version;
                            self.publish(uri, &mut out);
                        }
                        "textDocument/didClose" => {
                            self.documents.remove(uri);
                        }
                        _ => {}
                    }
                    for (other, doc) in &self.documents {
                        if other != uri && doc.source.contains("@import") {
                            self.publish(other, &mut out);
                        }
                    }
                    Ok(Value::Null)
                }
                "textDocument/hover" | "textDocument/completion" if request => {
                    let uri = params["textDocument"]["uri"]
                        .as_str()
                        .ok_or_else(|| invalid("document URI required"))?;
                    let Some(doc) = self.documents.get(uri) else {
                        return Ok(if method.ends_with("completion") {
                            json!([])
                        } else {
                            Value::Null
                        });
                    };
                    let offset = text::offset(&doc.source, &params["position"])
                        .ok_or_else(|| invalid("invalid UTF-16 position"))?;
                    let index = self.index(uri).unwrap();
                    Ok(if method.ends_with("completion") {
                        features::completion(&doc.source, &index, offset)
                    } else {
                        features::hover(&doc.source, &index, offset)
                    })
                }
                _ => Err((-32601, "Method not found".into())),
            }
        })();
        if request {
            out.push(match result {
                Ok(value) => json!({"jsonrpc":"2.0","id":id,"result":value}),
                Err((code, message)) => {
                    json!({"jsonrpc":"2.0","id":id,"error":{"code":code,"message":message}})
                }
            });
        }
        out
    }
    pub fn serve<R: BufRead, W: Write>(
        &mut self,
        input: &mut R,
        output: &mut W,
    ) -> io::Result<i32> {
        while !self.exited {
            let Some(bytes) = unlaxer_lsp::read_frame(input)? else {
                break;
            };
            let messages = match serde_json::from_slice(&bytes) {
                Ok(value) => self.handle(value),
                Err(_) => vec![
                    json!({"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}),
                ],
            };
            for message in messages {
                unlaxer_lsp::write_frame(output, &message)?;
            }
        }
        Ok(if self.exited && self.shutdown { 0 } else { 1 })
    }
}
