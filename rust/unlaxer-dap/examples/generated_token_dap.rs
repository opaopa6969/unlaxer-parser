//! Executable custom-backend example used by the shared Java/Rust stdio fixture.
use serde_json::Value;
use std::sync::OnceLock;
use unlaxer_dap::{Backend, DebugSource, Server, Step};
use unlaxer_runtime::{
    source::Snapshot,
    token::{TokenExpr, TokenKind, TokenSource},
    token_trace::Limits,
    ParseContext, ParseOptions, Tree,
};

pub fn fixture_tree(input: &str, row: &'static Value, limits: Limits) -> Result<Tree, String> {
    let mut context =
        ParseContext::with_options(input, ParseOptions::default().with_token_trace(limits));
    let mut root = None;
    for token in row["tokens"].as_array().ok_or("missing tokens")? {
        if let Some(text) = token["input"].as_str() {
            let word = TokenExpr::Word { rule: 0, text };
            root = context
                .parse_token(&word, TokenKind::Consumed, false)
                .map_err(|_| "Parse error at offset 0".to_owned())?
                .root_node();
        } else {
            root = Some(
                context
                    .add_token_node(
                        0,
                        TokenKind::VirtualConsumed,
                        TokenSource::Generated {
                            anchor: token["anchor"].as_u64().ok_or("missing anchor")? as usize,
                            text: token["generated"].as_str().ok_or("missing text")?.into(),
                        },
                    )
                    .ok_or("invalid generated token")?
                    .index(),
            );
        }
    }
    if context.position() != input.chars().count() {
        return Err("incomplete input".into());
    }
    context
        .tree(root.ok_or("empty fixture")?)
        .ok_or("missing tree".into())
}

struct FixtureBackend {
    rows: &'static Value,
}
impl FixtureBackend {
    fn row(&self, args: &Value) -> Result<&'static Value, String> {
        self.rows
            .as_array()
            .unwrap()
            .iter()
            .find(|row| row["name"] == args["fixture"])
            .ok_or("unknown fixture".into())
    }
}
impl Backend for FixtureBackend {
    fn supports_token_trace(&self) -> bool {
        true
    }
    fn steps(&mut self, snapshot: &Snapshot, args: &Value) -> Result<Vec<Step>, String> {
        match args["wireFailure"].as_str().unwrap_or("") {
            "ast-empty" => return Ok(vec![]),
            "ast-span" => return Err("AST source span unavailable".into()),
            "ast-throw" => return Err("fixture mapper failure".into()),
            _ => {}
        }
        let tree = fixture_tree(&snapshot.text, self.row(args)?, Limits::default())?;
        unlaxer_dap::token_steps(&tree)
    }
    fn resolve_source(&mut self, _: &str, _: &str, args: &Value) -> Result<DebugSource, String> {
        match args["wireFailure"].as_str().unwrap_or("") {
            "source-throw" => return Err("fixture source failure".into()),
            "source-null" => return Err("resolveDebugSource returned no content".into()),
            _ => {}
        }
        Ok(DebugSource {
            text: args["sourceText"]
                .as_str()
                .unwrap_or(self.row(args)?["input"].as_str().unwrap())
                .into(),
            line_offset: args["lineOffset"].as_u64().unwrap_or(0) as usize,
        })
    }
}
fn main() {
    // Retain immutable parser literals once for the lifetime of this example process.
    static ROWS: OnceLock<Value> = OnceLock::new();
    let fixtures = std::env::args().nth(1).expect("fixture JSON path");
    let rows = ROWS
        .get_or_init(|| serde_json::from_str(&std::fs::read_to_string(fixtures).unwrap()).unwrap());
    Server::new(FixtureBackend { rows }).serve_stdio().unwrap();
}
