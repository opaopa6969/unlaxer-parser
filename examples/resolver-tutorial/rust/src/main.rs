#[allow(dead_code)]
mod generated;
mod resolvers;
use resolvers::{load, Snapshot};
use serde_json::{json, Value};
use std::{collections::BTreeMap, path::Path};
use unlaxer_runtime::{ParseContext, ParseMatch, ParseOptions, ParseResult, Span};
const BINDING: &str = "address.towns";

fn town_token(context: &mut ParseContext<'_>) -> ParseResult {
    let length = context
        .binding_values(BINDING)
        .iter()
        .find(|word| context.remaining().starts_with(word.as_str()))
        .map(|word| word.chars().count());
    match length {
        Some(length) => {
            let start = context.position();
            assert!(context.advance(length));
            Ok(ParseMatch::empty(Span {
                start,
                end: context.position(),
            }))
        }
        None => Err(context.error("town")),
    }
}
fn parse(source: &str, snapshot: &Snapshot) -> Value {
    let bindings = BTreeMap::from([(BINDING.to_owned(), snapshot.words().to_vec())]);
    let mut context = ParseContext::with_bindings(source, bindings, ParseOptions::default());
    let result = generated::parser::parse_context(&mut context);
    let mut output = json!({"stage":"parse", "revision":snapshot.revision(), "accepted":result.is_ok(),
        "consumed":context.position(), "matched":context.matched_position(), "ast":null, "captures":[]});
    if let Ok(result) = result {
        let tree = context.tree(result.root_node().unwrap()).unwrap();
        let ast = generated::mapper::map(&tree).unwrap();
        let canonical: Value = serde_json::from_str(&ast.canonical_json()).unwrap();
        output["ast"] = canonical["fields"].clone();
        output["captures"] = Value::Array(tree.nodes[tree.root].captures.iter().map(|capture|
            json!({"text":context.text(capture.span).unwrap(), "span":[capture.span.start,capture.span.end]})).collect());
    }
    output
}
fn main() {
    let args: Vec<_> = std::env::args().skip(1).collect();
    if args.len() != 2 {
        eprintln!("usage: CONFIG.json INPUT");
        std::process::exit(2);
    }
    match load(Path::new(&args[0])) {
        Ok(snapshot) => {
            let result = parse(&args[1], &snapshot);
            println!("{result}");
            if result["accepted"] != true {
                std::process::exit(1);
            }
        }
        Err(code) => {
            println!("{}", json!({"stage":"resolve","code":code}));
            std::process::exit(2);
        }
    }
}
