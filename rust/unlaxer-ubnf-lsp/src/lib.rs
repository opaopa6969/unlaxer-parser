//! JVM-free UBNF authoring server. Capability scope is recorded in
//! docs/ubnf-authoring-lsp.md; generated-language LSPs keep their own backend.
mod configuration;
mod features;
mod index;
mod navigation;
mod rpc;
mod text;
use index::Index;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::io::{self, BufRead, Write};

// Java Ext ignores LSP document versions; only their wire type is checked.
struct Document {
    source: String,
}
#[derive(Default)]
pub struct Server {
    documents: BTreeMap<String, Document>,
    // Only snapshots verified during prior indexing. packageSource must never
    // build an index, resolve a URI, read a file, or mutate this cache.
    package_indexes: BTreeMap<String, BTreeMap<String, String>>,
    configuration: configuration::Configuration,
    initialized: bool,
    shutdown: bool,
    exited: bool,
}
type Error = (i64, String);
fn invalid(message: &str) -> Error {
    (-32602, message.into())
}
impl Server {
    fn configure(&mut self, params: &Value) -> Result<Value, Error> {
        // Validate before committing: failed configuration preserves all session state.
        let configuration = configuration::Configuration::read(params)?;
        let result = configuration.capabilities();
        self.configuration = configuration;
        // Parsing and queries are rebuilt from current buffers, never cached across
        // configurations. Verified package snapshots have the Java Ext index lifetime.
        Ok(result)
    }
    fn index(&mut self, uri: &str) -> Option<Index> {
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
        let index = Index::new(uri, source, buffers);
        self.package_indexes
            .insert(uri.into(), index.virtual_sources.clone());
        Some(index)
    }
    fn publish(&mut self, uri: &str, out: &mut Vec<Value>) {
        // Java skips additionalDiagnostics/ensureIndex entirely when VALIDATE is
        // disabled. Do not populate verified package snapshots as a side effect.
        let diagnostics = if self.configuration.allows("VALIDATE") {
            let Some(index) = self.index(uri) else {
                return;
            };
            index.diagnostics
        } else if self.documents.contains_key(uri) {
            Vec::new()
        } else {
            return;
        };
        out.push(json!({"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":uri,"version":0,"diagnostics":diagnostics}}));
    }
    pub fn handle(&mut self, message: Value) -> Vec<Value> {
        self.handle_checked(message, None)
    }
    fn handle_checked(&mut self, message: Value, reader_error: Option<Error>) -> Vec<Value> {
        let mut out = Vec::new();
        // lsp4j treats an explicit null id exactly like an absent id.
        let request = message.get("id").is_some_and(|id| !id.is_null());
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
            if let Some(error) = reader_error {
                return Err(error);
            }
            // Gson deserializes typed params before the lifecycle endpoint runs.
            rpc::params_shape(method, params)?;
            let params = if rpc::typed_request(method) {
                rpc::initialize_params(params)
            } else {
                params
            };
            if method == "exit" && !request {
                self.exited = true;
                return Ok(Value::Null);
            }
            if self.shutdown || self.exited {
                return Err((-32600, "Server has shut down".into()));
            }
            if method == "initialize" && request {
                if self.initialized {
                    return Err((-32600, "Server already initialized".into()));
                }
                let result = self.configure(params)?;
                self.initialized = true;
                return Ok(result);
            }
            if !self.initialized {
                return Err((-32002, "Server not initialized".into()));
            }
            if method == "shutdown" && request {
                self.shutdown = true;
                return Ok(Value::Null);
            }
            if request && configuration::notification_method(method) {
                return Err((-32600, "Notification method requires a notification".into()));
            }
            match method {
                "unlaxer/configure" if request => self.configure(params),
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
                            rpc::notified_version(doc)?;
                            self.documents.insert(uri.into(), Document { source });
                            self.publish(uri, &mut out);
                        }
                        "textDocument/didChange" => {
                            rpc::change_shape(params)?;
                            let changes = params["contentChanges"]
                                .as_array()
                                .ok_or_else(|| invalid("contentChanges required"))?;
                            let change = changes.first().ok_or_else(|| invalid("empty change"))?;
                            // Java Ext ignores range and applies the first text as a full snapshot.
                            let source = rpc::gson_string(&change["text"])
                                .ok_or_else(|| invalid("change text required"))?;
                            rpc::notified_version(doc)?;
                            // Java Ext parseDocument also opens a URI that was never opened.
                            self.documents.insert(uri.into(), Document { source });
                            self.publish(uri, &mut out);
                        }
                        "textDocument/didClose" => {
                            self.documents.remove(uri);
                        }
                        _ => {}
                    }
                    // Java refreshImporters clears all index snapshots after each
                    // document notification, then indexes the other importers.
                    self.package_indexes.clear();
                    let importers: Vec<_> = self
                        .documents
                        .iter()
                        .filter(|(other, doc)| {
                            other.as_str() != uri && doc.source.contains("@import")
                        })
                        .map(|(other, _)| other.clone())
                        .collect();
                    for other in importers {
                        self.publish(&other, &mut out);
                    }
                    Ok(Value::Null)
                }
                "textDocument/hover" | "textDocument/completion" if request => {
                    // Java dereferences textDocument first. A null/missing uri is
                    // an unopened document there, not a params error (#1005).
                    if params["textDocument"].is_null() {
                        return Err(rpc::internal());
                    }
                    let uri = rpc::gson_string(&params["textDocument"]["uri"]);
                    let Some((uri, doc)) = uri
                        .as_deref()
                        .and_then(|uri| self.documents.get(uri).map(|doc| (uri, doc)))
                    else {
                        return Ok(if method.ends_with("completion") {
                            json!([])
                        } else {
                            Value::Null
                        });
                    };
                    // wordAt/substring use the raw character; negative characters
                    // fail in hover and completion, while navigation clamps to zero.
                    if params["position"].is_null()
                        || rpc::gson_integer(&params["position"]["character"])
                            .is_some_and(|n| n < 0)
                    {
                        return Err(rpc::internal());
                    }
                    let offset = text::offset(&doc.source, &params["position"])
                        .ok_or_else(|| invalid("invalid UTF-16 position"))?;
                    let source = doc.source.clone();
                    let index = self.index(uri).unwrap();
                    let empty_line = rpc::gson_integer(&params["position"]["line"])
                        .is_some_and(|n| n < 0 || n as usize >= source.split('\n').count());
                    Ok(if method.ends_with("completion") {
                        features::completion(&source, &index, offset, empty_line)
                    } else {
                        features::hover(&source, &index, offset, empty_line)
                    })
                }
                "ubnf/packageSource" if request => {
                    let uri = params["uri"].as_str().unwrap_or("");
                    Ok(if uri.starts_with("ubnf-package:/") {
                        self.package_indexes
                            .values()
                            .find_map(|sources| sources.get(uri))
                            .map_or(Value::Null, |source| json!(source))
                    } else {
                        Value::Null
                    })
                }
                "textDocument/definition"
                | "textDocument/references"
                | "textDocument/rename"
                | "textDocument/linkedEditingRange"
                | "textDocument/documentSymbol"
                | "textDocument/foldingRange"
                    if request =>
                {
                    if params["textDocument"].is_null() {
                        return Err(rpc::internal());
                    }
                    let uri = rpc::gson_string(&params["textDocument"]["uri"]);
                    let Some((uri, source)) = uri
                        .as_deref()
                        .and_then(|uri| self.documents.get(uri).map(|d| (uri, d.source.clone())))
                    else {
                        return Ok(match method {
                            "textDocument/rename" => json!({"changes":{}}),
                            "textDocument/linkedEditingRange" => Value::Null,
                            _ => json!([]),
                        });
                    };
                    if method == "textDocument/foldingRange" {
                        return Ok(navigation::folding(&source));
                    }
                    let index = self.index(uri).unwrap();
                    if method == "textDocument/documentSymbol" {
                        return Ok(navigation::symbols(&source, &index));
                    }
                    if params["position"].is_null() {
                        return Err(rpc::internal());
                    }
                    let offset = text::binding_offset(&source, &params["position"])
                        .ok_or_else(|| invalid("invalid UTF-16 position"))?;
                    match method {
                        "textDocument/definition" => Ok(navigation::definition(&index, offset)),
                        "textDocument/references" => {
                            let context = &params["context"];
                            let locations = |include| {
                                navigation::reference_locations(
                                    &source, uri, &index, offset, include,
                                )
                            };
                            // Gson coerces the primitive boolean like validation does.
                            // Java reads a null context only at a declaration reference.
                            let include = rpc::gson_boolean(&context["includeDeclaration"]);
                            if context.is_null() && locations(true) != locations(false) {
                                return Err(rpc::internal());
                            }
                            Ok(locations(include))
                        }
                        "textDocument/rename" => navigation::rename(
                            &source,
                            uri,
                            &index,
                            offset,
                            rpc::gson_string(&params["newName"]).as_deref(),
                        ),
                        _ => Ok(navigation::linked(&source, uri, &index, offset)),
                    }
                }
                _ if request && method.starts_with("$/") => Ok(Value::Null),
                _ if request && rpc::unimplemented_request(method) => Err(rpc::internal()),
                _ => Err((-32601, format!("Unsupported request method: {method}"))),
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
            let messages = match rpc::read_message(&bytes) {
                Ok((value, error)) => self.handle_checked(value, error),
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
