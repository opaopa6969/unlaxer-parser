use crate::editor_adapter;
use unlaxer_runtime::{editor_cst::{DefectKind, Options}, json_string as q};
fn array(values: impl IntoIterator<Item=String>) -> String { format!("[{}]", values.into_iter().collect::<Vec<_>>().join(",")) }
pub fn report(source: &str, completions: &[&str], attempts: usize, cursor: usize, prefix: &str) {
    let parsed=editor_adapter::parse_with_completions("memory:editor",7,source,Some("region:inner"),Options { max_fragments: 4,max_attempts: attempts },completions).unwrap();
    let result=&parsed.result;let cst=&parsed.cst;let model=result.semantics().unwrap();
    let mut types=model.data().types.iter().map(|value|value.id.as_str()).collect::<Vec<_>>();types.sort();
    let mut symbols=model.data().symbols.iter().collect::<Vec<_>>();symbols.sort_by_key(|value|&value.name);
    let symbols=array(symbols.iter().map(|value|format!("{{\"name\":{},\"type\":{},\"span\":[{},{}]}}",q(&value.name),q(&value.type_id),value.declaration.start,value.declaration.end)));
    let defects=array(cst.defects().iter().map(|value|format!("{{\"kind\":{},\"span\":[{},{}],\"candidates\":{},\"region\":\"region:inner\"}}",q(match value.kind { DefectKind::Missing=>"MISSING",DefectKind::Error=>"ERROR" }),value.span.start,value.span.end,array(value.candidate_rules.iter().map(|value|q(value))))));
    let expected=result.expected_types_at(cursor,Some("region:inner")).unwrap();
    let candidates=result.complete_at(cursor,Some("region:inner"),7,prefix).unwrap();
    let arguments=array(model.data().calls.iter().flat_map(|call|call.arguments.iter()).map(|value|format!("{{\"type\":{},\"span\":[{},{}]}}",q(&value.type_id),value.span.start,value.span.end)));
    for node in cst.nodes() { for capture in &node.captures {
        assert!(capture.span.end <= source.chars().count());
        assert_eq!(capture.text,source.chars().skip(capture.span.start).take(capture.span.end-capture.span.start).collect::<String>());
    } }
    assert_eq!(result.complete_at(cursor,Some("other-region"),7,"").unwrap().len(),0);
    assert_eq!(result.complete_at(cursor,Some("region:inner"),6,"").unwrap_err().code,"EDITOR_STALE_SNAPSHOT");
    println!("{{\"status\":{},\"reason\":{},\"strict\":{},\"types\":{},\"symbols\":{},\"defects\":{},\"expected\":{},\"completions\":{},\"arguments\":{}}}",q(cst.status().name()),q(cst.reason().name()),result.strict_ast().is_some(),array(types.iter().map(|value|q(value))),symbols,defects,array(expected.iter().map(|value|q(value))),array(candidates.iter().map(|value|q(&value.symbol.name))),arguments);
}
