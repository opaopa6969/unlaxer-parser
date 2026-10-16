use serde_json::json;
use unlaxer_lsp::{Backend, Diagnostic, GrammarBackend, Server};
use unlaxer_runtime::source::Snapshot;
use unlaxer_runtime::{Expr, Rule};
fn backend() -> GrammarBackend {
    GrammarBackend {
        name: "Cache".into(),
        entry: "Root".into(),
        root: 0,
        whitespace: false,
        keywords: vec![],
        grammar: vec![Rule {
            name: "Root",
            expression: Expr::Choice(vec![
                Expr::Literal("ab"),
                Expr::Literal("a"),
                Expr::Literal("😀"),
            ]),
        }]
        .into(),
    }
}
#[test]
fn shared_lsp_reuse_edit_version_and_invalid_notification_oracle() {
    use std::sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    };
    struct RevisionBackend {
        inner: GrammarBackend,
        revision: Arc<AtomicU64>,
    }
    impl Backend for RevisionBackend {
        fn grammar(&self) -> &str {
            self.inner.grammar()
        }
        fn entry(&self) -> &str {
            self.inner.entry()
        }
        fn keywords(&self) -> &[String] {
            self.inner.keywords()
        }
        fn validate(&mut self, snapshot: &Snapshot) -> Vec<Diagnostic> {
            self.inner.validate(snapshot)
        }
        fn validation_cache_key(&self) -> Option<unlaxer_lsp::ValidationCacheKey> {
            let mut key = self.inner.validation_cache_key()?;
            key.revisions.state = self.revision.load(Ordering::SeqCst).to_string();
            Some(key)
        }
    }
    let revision = Arc::new(AtomicU64::new(0));
    let mut server = Server::new(RevisionBackend {
        inner: backend(),
        revision: revision.clone(),
    });
    let uri = "file:///cache";
    server.handle(json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}));
    for line in include_str!("../../../spec-corpus/incremental-cache/lsp.tsv")
        .lines()
        .filter(|l| !l.starts_with('#'))
    {
        let f: Vec<_> = line.split('\t').collect();
        let version: u64 = f[1].parse().unwrap();
        let doc = json!({"uri":uri,"version":version,"text":f[2]});
        let (method, params) = match f[0] {
            "open" => ("textDocument/didOpen", json!({"textDocument":doc})),
            "change" | "stale" => (
                "textDocument/didChange",
                json!({"textDocument":doc,"contentChanges":[{"text":f[2]}]}),
            ),
            "invalid-range" => (
                "textDocument/didChange",
                json!({"textDocument":doc,"contentChanges":[{"text":f[2],"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}}}]}),
            ),
            "save" => ("textDocument/didSave", json!({"textDocument":{"uri":uri}})),
            "close" => ("textDocument/didClose", json!({"textDocument":{"uri":uri}})),
            "state" => {
                revision.fetch_add(1, Ordering::SeqCst);
                ("$/cancelRequest", json!({}))
            }
            "configure" => ("unlaxer/configure", json!({})),
            "badconfigure" => (
                "unlaxer/configure",
                json!({"initializationOptions":{"languageProfile":true}}),
            ),
            "invalidate" => {
                server.invalidate_parse_cache();
                ("$/cancelRequest", json!({}))
            }
            _ => panic!("bad fixture"),
        };
        let mut message = json!({"jsonrpc":"2.0","method":method,"params":params});
        if method == "unlaxer/configure" {
            message["id"] = json!(2);
        }
        let response = server.handle(message);
        if f[0] == "badconfigure" {
            assert_eq!(response[0]["error"]["code"], -32602);
        } else if f[0] == "configure" {
            assert!(response[0].get("error").is_none());
        }
        let stats = server.validation_cache_stats();
        assert_eq!(
            (stats.hits, stats.misses, stats.entries),
            (
                f[3].parse().unwrap(),
                f[4].parse().unwrap(),
                f[5].parse().unwrap()
            ),
            "{line}"
        );
        assert_eq!(
            server.snapshot(uri).map(|s| s.text.as_str()),
            if f[6] == "~" { None } else { Some(f[6]) },
            "{line}"
        );
    }
}
#[test]
fn grammar_mutation_and_arbitrary_backend_state_cannot_reuse_stale_validation() {
    let mut server = Server::new(backend());
    server.handle(json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}));
    server.handle(json!({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"x","version":1,"text":"a"}}}));
    // The cache keeps a strong grammar owner, so even direct in-place mutation is impossible.
    let rules = &mut server.backend_mut().grammar;
    std::sync::Arc::make_mut(rules)[0].expression = Expr::Literal("b");
    let notifications=server.handle(json!({"jsonrpc":"2.0","method":"textDocument/didSave","params":{"textDocument":{"uri":"x"}}}));
    assert_eq!(
        notifications[0]["params"]["diagnostics"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    struct Custom {
        calls: usize,
    }
    impl Backend for Custom {
        fn grammar(&self) -> &str {
            "Custom"
        }
        fn entry(&self) -> &str {
            "Root"
        }
        fn keywords(&self) -> &[String] {
            &[]
        }
        fn validate(&mut self, _: &Snapshot) -> Vec<Diagnostic> {
            self.calls += 1;
            vec![]
        }
    }
    let mut custom = Server::new(Custom { calls: 0 });
    custom.handle(json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}));
    custom.handle(json!({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"x","version":1,"text":"a"}}}));
    custom.handle(json!({"jsonrpc":"2.0","method":"textDocument/didSave","params":{"textDocument":{"uri":"x"}}}));
    assert_eq!(custom.backend_mut().calls, 2);
    assert_eq!(custom.validation_cache_stats().entries, 0);
}

#[test]
fn custom_grammar_callbacks_are_not_implicitly_cached() {
    use std::sync::atomic::{AtomicBool, Ordering};
    static ALLOWED: AtomicBool = AtomicBool::new(true);
    fn custom(context: &mut unlaxer_runtime::ParseContext<'_>) -> unlaxer_runtime::ParseResult {
        context.parse(&Expr::Literal(if ALLOWED.load(Ordering::SeqCst) {
            "a"
        } else {
            "b"
        }))
    }
    let mut backend = backend();
    backend.grammar = vec![Rule {
        name: "Root",
        expression: Expr::CustomWith {
            parser: custom,
            reads_diagnostics: false,
            replayable: true,
        },
    }]
    .into();
    assert!(backend.validation_cache_key().is_none());
    let mut server = Server::new(backend);
    server.handle(json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}));
    ALLOWED.store(true, Ordering::SeqCst);
    let first=server.handle(json!({"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"x","version":1,"text":"a"}}}));
    assert!(first[0]["params"]["diagnostics"]
        .as_array()
        .unwrap()
        .is_empty());
    ALLOWED.store(false, Ordering::SeqCst);
    let second=server.handle(json!({"jsonrpc":"2.0","method":"textDocument/didSave","params":{"textDocument":{"uri":"x"}}}));
    assert_eq!(
        second[0]["params"]["diagnostics"].as_array().unwrap().len(),
        1
    );
    assert_eq!(server.validation_cache_stats().entries, 0);
}
