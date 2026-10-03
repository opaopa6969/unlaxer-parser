// Generated UBNF playground ABI. Buffers own their memory; no raw-pointer dereferences.
#![allow(dead_code)]
mod generated;
use std::cell::RefCell;
use unlaxer_runtime::{json_string, ParseContext};

const INPUT_LIMIT: usize = 65536;
const OUTPUT_LIMIT: usize = 4 * 1024 * 1024;
#[derive(Default)]
struct Buffers { input: Vec<u8>, output: Vec<u8> }
thread_local! { static BUFFERS: RefCell<Buffers> = RefCell::new(Buffers::default()); }

/// JS may write exactly `len` initialized bytes; a new allocation invalidates earlier views.
#[no_mangle]
pub extern "C" fn pg_input(len: usize) -> usize {
    if len > INPUT_LIMIT { return 0; }
    BUFFERS.with(|buffers| {
        let mut buffers = buffers.borrow_mut();
        buffers.input.resize(len, 0);
        buffers.input.as_mut_ptr() as usize
    })
}

#[no_mangle]
pub extern "C" fn pg_output() -> usize {
    BUFFERS.with(|buffers| buffers.borrow().output.as_ptr() as usize)
}
#[no_mangle]
pub extern "C" fn pg_output_len() -> usize {
    BUFFERS.with(|buffers| buffers.borrow().output.len())
}
fn output(value: String) {
    BUFFERS.with(|buffers| buffers.borrow_mut().output = if value.len() > OUTPUT_LIMIT {
        r#"{"runtimeError":"解析結果が 4 MiB を超えました。入力を短くしてください。"}"#.as_bytes().to_vec()
    } else { value.into_bytes() });
}

pub fn analyze(input: &str) -> String {
    let mut context = ParseContext::new(input);
    let prefix_ok = generated::parser::parse_context(&mut context).is_ok();
    let prefix = format!("[{},{},{}]", prefix_ok, context.position(), context.matched_position());
    match generated::parser::parse_tree_detailed(input) {
        Err(diagnostic) => format!(r#"{{"ok":false,"prefix":{},"diagnostic":{}}}"#, prefix, diagnostic.canonical_json()),
        Ok(tree) => {
            let rules = generated::parser::grammar();
            let nodes = tree.nodes.iter().map(|node| {
                let name = rules.get(node.rule).map_or("<text>", |rule| rule.name);
                let children = node.children.iter().map(usize::to_string).collect::<Vec<_>>().join(",");
                let captures = node.captures.iter().map(|capture| format!(
                    r#"{{"name":{},"span":[{},{}]}}"#, json_string(capture.name), capture.span.start, capture.span.end
                )).collect::<Vec<_>>().join(",");
                format!(r#"{{"rule":{},"span":[{},{}],"children":[{}],"captures":[{}]}}"#,
                    json_string(name), node.span.start, node.span.end, children, captures)
            }).collect::<Vec<_>>().join(",");
            let (ast, mapping_error) = match generated::mapper::map(&tree) {
                Ok(ast) => (ast.canonical_json(), "null".into()),
                Err(error) => ("null".into(), json_string(&error.to_string())),
            };
            format!(r#"{{"ok":true,"prefix":{},"cst":{{"root":{},"nodes":[{}]}},"ast":{},"mappingError":{}}}"#,
                prefix, tree.root, nodes, ast, mapping_error)
        }
    }
}

#[no_mangle]
pub extern "C" fn pg_parse() {
    let input = BUFFERS.with(|buffers| String::from_utf8(buffers.borrow().input.clone()));
    output(match input {
        Ok(input) => analyze(&input),
        Err(_) => r#"{"runtimeError":"入力は UTF-8 である必要があります。"}"#.into(),
    });
}

#[no_mangle]
pub extern "C" fn pg_catalog() {
    let docs: &[(&str, &[&str])] = @@DOCS@@;
    let rules = generated::parser::grammar().iter().map(|rule| {
        let text = docs.iter().find(|(name, _)| *name == rule.name).map_or(&[][..], |(_, text)| *text);
        let paragraphs = text.iter().map(|text| json_string(text)).collect::<Vec<_>>().join(",");
        format!(r#"{{"name":{},"docs":[{}]}}"#, json_string(rule.name), paragraphs)
    }).collect::<Vec<_>>().join(",");
    output(format!(r#"{{"name":{},"root":{},"rules":[{}]}}"#, json_string(@@NAME@@), @@ROOT@@, rules));
}
