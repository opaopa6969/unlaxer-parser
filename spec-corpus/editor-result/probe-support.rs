use unlaxer_runtime::editor::{CallSite, EditorError, EditorParseResult, Node as EditorNode, NodeKind, Status};
fn editor_error(error: EditorError) -> String {
    format!("{{\"error\":{},\"span\":[{},{}]}}",json_string(error.code),error.span.start,error.span.end)
}
fn editor_value(result: EditorParseResult<String>, cursor: usize, region: Option<String>, version: i64, prefix: String) -> Result<String,EditorError> {
    let selected=result.call_at(cursor,region.as_deref())?;
    let call=selected.map(|site|format!("{{\"call\":{},\"index\":{},\"region\":{}}}",json_string(&site.call_id),site.argument_index,site.region_id.as_ref().map(|r|json_string(r)).unwrap_or("null".into()))).unwrap_or("null".into());
    let nodes=result.nodes().iter().map(|node|{
        let utf16=result.utf16_span(node.span).unwrap();
        format!("{{\"kind\":{},\"span\":[{},{}],\"utf16Span\":[{},{}],\"candidates\":{},\"region\":{}}}",
            json_string(if node.kind==NodeKind::Missing {"MISSING"} else {"ERROR"}),node.span.start,node.span.end,utf16.start,utf16.end,
            strings(node.candidate_rules.clone()),node.region_id.as_ref().map(|r|json_string(r)).unwrap_or("null".into()))
    }).collect::<Vec<_>>().join(",");
    let visible=result.semantics().unwrap().visible_symbols_at(cursor).unwrap().iter().map(|symbol|symbol.id.clone()).collect();
    let expected=result.expected_types_at(cursor,region.as_deref())?;
    let completed=result.complete_at(cursor,region.as_deref(),version,&prefix)?.iter().map(|completion|completion.symbol.id.clone()).collect();
    Ok(format!("{{\"status\":{},\"strictAst\":{},\"nodes\":[{}],\"visible\":{},\"call\":{},\"expectedTypes\":{},\"completions\":{}}}",
        json_string(result.status().name()),result.strict_ast().map(|value|json_string(value)).unwrap_or("null".into()),nodes,strings(visible),call,strings(expected),strings(completed)))
}
fn editor_observe(result: Result<EditorParseResult<String>,EditorError>, cursor: usize, region: Option<String>, version: i64, prefix: String) -> String {
    result.and_then(|result|editor_value(result,cursor,region,version,prefix)).unwrap_or_else(editor_error)
}
