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

pub fn nested(source:&str) {
    use unlaxer_runtime::{editor_queries::EditorQueryProvider,language_queries::{LanguageQueries,Project,Provider,Request},source::{Kind,Language,LanguageRegions,Location,Operation,Region,Snapshot,SourceMap,Segment,State},Span};
    use std::collections::{BTreeMap,HashMap};
    fn copy(output:Snapshot,origin:Snapshot,start:usize)->SourceMap {
        let length=output.len();SourceMap::new(output,vec![Segment { output:Span {start:0,end:length},kind:Kind::Copy,origin:Some(Location::new(origin,Span {start,end:start+length}).unwrap()) }]).unwrap()
    }
    let host=Snapshot::new("memory:host",7,format!("😀MDX{{SQL{{MODEL{{{source}}}}}}}")).unwrap();
    let outer=Snapshot::new("memory:outer",7,format!("SQL{{MODEL{{{source}}}}}")).unwrap();
    let middle=Snapshot::new("memory:middle",7,format!("MODEL{{{source}}}")).unwrap();
    let inner=Snapshot::new("memory:inner",7,source).unwrap();
    let outer_map=copy(outer.clone(),host.clone(),5);let middle_map=copy(middle.clone(),outer,4).through(outer_map.clone()).unwrap();let inner_map=copy(inner.clone(),middle,6).through(middle_map.clone()).unwrap();
    let language=|id:&str,grammar:&str|Language {id:id.into(),package_id:"example".into(),version:"1".into(),grammar:grammar.into(),entry:"Document".into()};
    let typed=language("typed","TypedModel");
    let outer_region=Region {id:"outer".into(),parent:None,language:language("outer","Outer"),full:Span {start:0,end:host.len()},body:Span {start:5,end:host.len()-1},source_map:outer_map,parse_state:State::Complete};
    let middle_region=Region {id:"middle".into(),parent:Some("outer".into()),language:language("sql","Sql"),full:Span {start:5,end:host.len()-1},body:Span {start:9,end:host.len()-2},source_map:middle_map,parse_state:State::Complete};
    let project=Project {id:"project".into(),version:3,documents:BTreeMap::from([(host.uri.clone(),host.clone())]),configuration:BTreeMap::new()};
    let parameters=BTreeMap::from([("prefix".into(),"d".into())]);
    let mut blocked=vec![];let mut result=String::new();
    for kind in [Kind::Copy,Kind::Transformed,Kind::Generated] {
        let map=if kind==Kind::Copy { inner_map.clone() } else { SourceMap::new(inner.clone(),vec![Segment {output:Span {start:0,end:inner.len()},kind,origin:if kind==Kind::Generated {None}else{Some(Location::new(host.clone(),Span {start:15,end:15+inner.len()}).unwrap())}}]).unwrap() };
        let region=Region {id:"inner".into(),parent:Some("middle".into()),language:typed.clone(),full:Span {start:9,end:host.len()-2},body:Span {start:15,end:15+inner.len()},source_map:map,parse_state:State::Partial};
        let provider=EditorQueryProvider::new("project".into(),3,|request:&Request<'_>|{
            let snapshot=request.region.source_map.output();
            editor_adapter::parse(&snapshot.uri,snapshot.version as i64,&snapshot.text,Some(&request.region.id),Options::default()).map(|parsed|parsed.result).map_err(|_|"partial adapter failed")
        }).unwrap();
        let providers:HashMap<_,Box<dyn Provider>>=HashMap::from([(typed.clone(),Box::new(provider) as Box<dyn Provider>)]);
        let layer=LanguageQueries::new(LanguageRegions::new(host.clone(),vec![outer_region.clone(),middle_region.clone(),region]).unwrap(),project.clone(),providers).unwrap();
        let response=layer.query(&host,&project,241,Operation::Completion,&parameters).unwrap();
        if kind!=Kind::Copy {assert!(response.items.is_empty());blocked.push(q(match response.state {State::Unsupported=>"UNSUPPORTED",_=>panic!("unsupported origin dispatched") }));continue;}
        let items=array(response.items.iter().map(|item|format!("{{\"label\":{},\"detail\":{},\"locations\":{},\"edits\":{}}}",q(&item.label),q(&item.detail),array(item.locations.iter().map(|location|format!("[{},{}]",location.location.span.start,location.location.span.end))),array(item.edits.iter().map(|edit|format!("[{},{},{}]",edit.span.start,edit.span.end,q(&edit.replacement)))))));
        assert_eq!(response.state,State::Partial);assert_eq!(response.region,"inner");
        result=format!("{{\"state\":\"PARTIAL\",\"region\":\"inner\",\"items\":{},\"utf16Cursor\":{}",items,host.utf16(241).unwrap());
        let stale=Snapshot::new(host.uri.clone(),8,host.text.clone()).unwrap();assert!(layer.query(&stale,&project,241,Operation::Completion,&parameters).is_err());
        assert!(layer.query(&host,&project,241,Operation::Completion,&BTreeMap::from([("prefix".into(),"synthetic".into())])).is_err());
    }
    println!("{},\"blocked\":{}}}",result,array(blocked));
}
