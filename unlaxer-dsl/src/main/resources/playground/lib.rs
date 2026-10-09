// Generated UBNF playground ABI. Buffers own their memory; no raw-pointer dereferences.
#![allow(dead_code)]
mod generated;
mod editor_adapter;
mod region_adapter;
mod query_adapter;
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

/// Cursor is an original-source code-point offset, never a synthetic suffix location.
pub fn analyze_editor(input: &str, cursor: usize) -> String { analyze_editor_snapshot(input, cursor, 0) }

pub fn analyze_editor_snapshot(input: &str, cursor: usize, version: u64) -> String {
    if cursor > input.chars().count() { return r#"{"runtimeError":"カーソル位置が入力の外です。"}"#.into(); }
    let completions = unlaxer_runtime::editor_cst::literal_completions(generated::parser::grammar());
    let cst = match generated::parser::parse_editor_cst(input, &completions, unlaxer_runtime::editor_cst::Options::default()) {
        Ok(cst) => cst,
        Err(error) => return format!(r#"{{"runtimeError":{}}}"#, json_string(error)),
    };
    let typed = editor_adapter::analyze(input, cursor).unwrap_or_else(|| "null".into());
    let regions = region_adapter::analyze(input, cursor, version).unwrap_or_else(|| "null".into());
    let prefix: Vec<char> = input.chars().take(cursor).collect();
    let prefix: String = prefix.iter().rev().take_while(|c| c.is_alphanumeric() || **c == '_').copied().collect::<Vec<_>>().into_iter().rev().collect();
    let query = analyze_query(input, cursor, version, 1, &prefix);
    let mut value = analyze(input);
    value.pop();
    format!(r#"{},"editor":{},"typed":{},"languages":{},"query":{}}}"#, value, cst.canonical_json(), typed, regions, query)
}

/// Operations: 0 validate, 1 completion, 2 hover, 3 definition, 4 rename, 5 format, 6 code action.
/// The one optional argument is prefix/name/newName; hosts may interpret `argument` for code actions.
pub fn analyze_query(input: &str, cursor: usize, version: u64, operation: u32, argument: &str) -> String {
    use std::collections::{BTreeMap, HashSet};
    use unlaxer_runtime::{language_queries::{QueryResult, QueryView}, source::{Operation, Snapshot, State}};
    let run = || -> unlaxer_runtime::source::Result<String> {
        let operation = match operation { 0 => Operation::Validate, 1 => Operation::Completion, 2 => Operation::Hover,
            3 => Operation::Definition, 4 => Operation::Rename, 5 => Operation::Format, 6 => Operation::CodeAction,
            _ => return Err("unknown query operation") };
        let host = Snapshot::new("playground", version, input)?;
        host.check(unlaxer_runtime::Span { start: cursor, end: cursor })?;
        let Some(queries) = query_adapter::bind(&host)? else {
            return Ok(QueryView { host, cursor, operation, capabilities: HashSet::new(),
                result: QueryResult { region: String::new(), state: State::Unavailable, items: vec![] } }.canonical_json());
        };
        let key = match operation { Operation::Completion => "prefix", Operation::Hover | Operation::Definition => "name",
            Operation::Rename => "newName", _ => "argument" };
        let parameters = BTreeMap::from([(key.into(), argument.into())]);
        Ok(queries.view(&host, queries.project(), cursor, operation, &parameters)?.canonical_json())
    };
    run().unwrap_or_else(|error| format!(r#"{{"runtimeError":{}}}"#, json_string(error)))
}

/// Input buffer is UTF-8 source followed by the UTF-8 argument, separated by the source byte length.
#[no_mangle]
pub extern "C" fn pg_query(cursor: usize, version: u32, operation: u32, source_len: usize) {
    let bytes = BUFFERS.with(|buffers| buffers.borrow().input.clone());
    let result = if source_len > bytes.len() { Err("invalid query source length") } else {
        std::str::from_utf8(&bytes[..source_len]).map_err(|_| "source must be UTF-8").and_then(|source|
            std::str::from_utf8(&bytes[source_len..]).map_err(|_| "argument must be UTF-8").map(|argument|
                analyze_query(source, cursor, u64::from(version), operation, argument)))
    };
    output(result.unwrap_or_else(|error| format!(r#"{{"runtimeError":{}}}"#, json_string(error))));
}

#[no_mangle]
pub extern "C" fn pg_editor(cursor: usize) { pg_editor_snapshot(cursor, 0); }

#[no_mangle]
pub extern "C" fn pg_editor_snapshot(cursor: usize, version: u32) {
    let input = BUFFERS.with(|buffers| String::from_utf8(buffers.borrow().input.clone()));
    output(match input {
        Ok(input) => analyze_editor_snapshot(&input, cursor, u64::from(version)),
        Err(_) => r#"{"runtimeError":"入力は UTF-8 である必要があります。"}"#.into(),
    });
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
