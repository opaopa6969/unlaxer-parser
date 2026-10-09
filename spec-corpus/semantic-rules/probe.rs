#![allow(dead_code)]
mod generated;
mod evolved;
mod recovered;
use serde_json::{json, Value};
use unlaxer_generator::semantic_rules::{inventory,load};
use unlaxer_runtime::{editor_cst,semantic_rules as rules};
fn main() {
    let args:Vec<_>=std::env::args().collect();
    let dir=std::path::Path::new(&args[1]);
    let grammar=unlaxer_ubnf::parse(&std::fs::read_to_string(dir.join("model.ubnf")).unwrap()).unwrap().grammars.remove(0);
    let original:Value=serde_json::from_str(&std::fs::read_to_string(dir.join("rules.json")).unwrap()).unwrap();
    let cases:Value=serde_json::from_str(&std::fs::read_to_string(dir.join("cases.json")).unwrap()).unwrap();
    for case in cases["cases"].as_array().unwrap() {
        let mut schema=original.clone();let variant=case["variant"].as_str().unwrap_or("");
        match variant {
            "scopeStart"=>schema["rules"][2]["visibility"]=json!("scopeStart"),
            "symbolWrong"=>schema["rules"][2]["type"]=json!({"literal":"Wrong"}),
            "parameterWrong"=>schema["rules"][3]["parameters"]=json!({"literal":"Wrong"}),
            "referenceWrong"=>schema["rules"][7]["name"]=json!({"literal":"wrong"}),
            "referenceExpression"=>schema["rules"].as_array_mut().unwrap().push(json!({"id":"referenceType","node":"Reference","emit":"expression","type":{"literal":"Db"}})),
            "nameParents"=>schema["rules"][0]["name"]=json!({"capture":"parents"}),
            _=>{}
        }
        let program=load(&schema.to_string(),&grammar).unwrap();let mut current=inventory(&grammar).unwrap();
        if variant=="inventoryDrift" {current.nodes.get_mut("Field").unwrap().fields.remove("name");}
        let source=case["source"].as_str().unwrap();
        let fragments:Vec<&str>=case["fragments"].as_array().map(|values|values.iter().map(|v|v.as_str().unwrap()).collect()).unwrap_or_else(||vec!["?",")",";","}","a",":","{"]);
        let cst=generated::parser::parse_editor_cst(source,&fragments,editor_cst::Options::default()).unwrap();
        let result=rules::analyze(&program,&current,"memory:rules",7,&cst).unwrap();
        let query=rules::query(&result,"memory:rules",7,case["cursor"].as_u64().unwrap() as usize,case["prefix"].as_str().unwrap()).unwrap();
        let mut report=json!({"status":result.status().name(),"expected":[],"completions":[],"diagnostics":result.diagnostics().iter().map(|d|&d.code).collect::<Vec<_>>(),"diagnosticSpans":result.diagnostics().iter().map(|d|json!([d.span.start,d.span.end,&d.rule,&d.uri,d.version])).collect::<Vec<_>>(),"types":[],"symbols":[],"arguments":[]});
        if let Some(model)=result.model() {
            report["types"]=json!(model.data().types.iter().map(|t|json!([&t.id,t.span.start,t.span.end,t.fields.iter().map(|f|json!([&f.name,&f.type_id,f.span.start,f.span.end])).collect::<Vec<_>>()])).collect::<Vec<_>>());
            report["symbols"]=json!(model.data().symbols.iter().map(|s|json!([&s.name,&s.type_id,s.declaration.start,s.declaration.end,&s.scope,s.visible_from])).collect::<Vec<_>>());
            report["arguments"]=json!(model.data().calls.iter().flat_map(|c|c.arguments.iter()).map(|a|json!([&a.type_id,a.span.start,a.span.end])).collect::<Vec<_>>());
        }
        if let Some(query)=query {
            report["expected"]=json!(query.expected.iter().map(|t|t.name()).collect::<Vec<_>>());
            report["completions"]=json!(query.completions.iter().map(|c|&c.value.name).collect::<Vec<_>>());
            report["decisions"]=json!(query.completions.iter().map(|c|json!([&c.value.name,c.decision.status.name(),&c.decision.rule,c.value.span.start,c.value.span.end,&c.value.uri,c.value.version])).collect::<Vec<_>>());
            report["edit"]=json!([query.edit.start,query.edit.end]);report["resolution"]=json!(query.resolution.state.name());
            assert_eq!(rules::query(&result,"memory:rules",6,case["cursor"].as_u64().unwrap() as usize,"").unwrap_err(),"STALE_SNAPSHOT");
            assert_eq!(rules::query(&result,"memory:rules",7,case["cursor"].as_u64().unwrap() as usize,&"x".repeat(source.len()+1)).unwrap_err(),"INVALID_PREFIX");
            assert_eq!(rules::query(&result,"memory:rules",7,source.chars().count()+1,"").unwrap_err(),"INVALID_CURSOR");
        }
        if ["unknown","partial-argument","wrong-argument"].contains(&case["id"].as_str().unwrap()) {report["region"]=region(&program,&current,&cst,case["cursor"].as_u64().unwrap() as usize,case["prefix"].as_str().unwrap());}
        println!("{}",report);
    }
    let schema_cases:Value=serde_json::from_str(&std::fs::read_to_string(dir.join("schema-cases.json")).unwrap()).unwrap();
    for case in schema_cases.as_array().unwrap() {
        let mut source=std::fs::read_to_string(dir.join("model.ubnf")).unwrap();
        match case["grammarChange"].as_str().unwrap() {
            "removeField"=>source=source.replace("@mapping(Field, params=[name, type])","@mapping(Field, params=[type])"),
            "addField"=>source=source.replace("@mapping(Field, params=[name, type])","@mapping(Field, params=[name, type, ghost])"),
            _=>{}
        }
        let grammar=unlaxer_ubnf::parse(&source).unwrap().grammars.remove(0);
        let error=load(case["json"].as_str().unwrap(),&grammar).unwrap_err();
        println!("{}",json!({"code":error.code,"path":error.path}));
    }

    let evolved_grammar=unlaxer_ubnf::parse(&std::fs::read_to_string(&args[2]).unwrap()).unwrap().grammars.remove(0);
    let mut changed=original.clone();changed["rules"][2]["name"]["capture"]=json!("identifier");
    let program=load(&changed.to_string(),&evolved_grammar).unwrap();let first=&cases["cases"][0];
    let cst=evolved::parser::parse_editor_cst(first["source"].as_str().unwrap(),&[],editor_cst::Options::default()).unwrap();
    let result=rules::analyze(&program,&inventory(&evolved_grammar).unwrap(),"memory:rules",7,&cst).unwrap();
    let query=rules::query(&result,"memory:rules",7,first["cursor"].as_u64().unwrap() as usize,"").unwrap().unwrap();
    let error=load(&original.to_string(),&evolved_grammar).unwrap_err();assert_eq!(error.path,"values.name");
    println!("{}",json!({"status":result.status().name(),"expected":query.expected.iter().map(|t|t.name()).collect::<Vec<_>>(),"completions":query.completions.iter().map(|c|&c.value.name).collect::<Vec<_>>(),"edit":[query.edit.start,query.edit.end],"schemaError":error.code}));

    let grammar=unlaxer_ubnf::parse(&std::fs::read_to_string(&args[3]).unwrap()).unwrap().grammars.remove(0);
    let program=load(&original.to_string(),&grammar).unwrap();let source="builtin Int {} fn f(Int):Int; let x:Int; call f(?);";
    let cst=recovered::parser::parse_editor_cst(source,&[],editor_cst::Options::default()).unwrap();
    let result=rules::analyze(&program,&inventory(&grammar).unwrap(),"memory:rules",7,&cst).unwrap();
    assert_eq!(cst.status(),editor_cst::Status::Partial);assert!(rules::query(&result,"memory:rules",7,source.find('?').unwrap(),"").unwrap().is_none());
    println!("{}",json!({"status":result.status().name(),"diagnostics":result.diagnostics().iter().map(|d|json!([&d.code,d.span.start,d.span.end,&d.rule])).collect::<Vec<_>>(),"sites":result.sites().len(),"calls":result.model().unwrap().data().calls.len()}));

}

fn region(program:&rules::Program,inventory:&rules::Inventory,cst:&editor_cst::EditorCst,cursor:usize,prefix:&str)->Value {
    use unlaxer_runtime::{source::*,language_queries as q,Span};
    use q::Provider;
    use std::collections::{BTreeMap,HashMap};
    let host=Snapshot::new("memory:host",7,format!("😀{{{}{}",cst.source(),if cst.status()==editor_cst::Status::Partial{""}else{"}"})).unwrap();
    let inner=Snapshot::new("memory:inner",7,cst.source()).unwrap();
    let language=Language{id:"typed".into(),package_id:"example/typed".into(),version:"1".into(),grammar:"TypedModel".into(),entry:"Document".into()};
    let map=SourceMap::new(inner.clone(),vec![Segment{output:Span{start:0,end:inner.len()},kind:Kind::Copy,origin:Some(Location::new(host.clone(),Span{start:2,end:2+inner.len()}).unwrap())}]).unwrap();
    let state=match cst.status(){editor_cst::Status::Complete=>State::Complete,editor_cst::Status::Partial=>State::Partial,editor_cst::Status::Failed=>State::Failed};
    let region=Region{id:"inner".into(),parent:None,language:language.clone(),full:Span{start:0,end:host.len()},body:Span{start:2,end:2+inner.len()},source_map:map,parse_state:state};
    let project=q::Project{id:"p".into(),version:3,documents:BTreeMap::from([(host.uri.clone(),host.clone())]),configuration:BTreeMap::new()};
    let owned=cst.clone();
    let provider=rules::QueryProvider::new(program.clone(),inventory.clone(),language.clone(),"p".into(),3,move |_|Ok(owned.clone())).unwrap();
    let mut other=region.clone();other.language.version="2".into();let parameters=BTreeMap::new();
    assert!(provider.query(&q::Request{region:&other,operation:Operation::Completion,cursor,project:&project,parameters:&parameters}).is_err());
    let mut old=project.clone();old.version=2;
    assert!(provider.query(&q::Request{region:&region,operation:Operation::Completion,cursor,project:&old,parameters:&parameters}).is_err());
    let layer=q::LanguageQueries::new(LanguageRegions::new(host.clone(),vec![region]).unwrap(),project.clone(),HashMap::from([(language,Box::new(provider) as Box<dyn q::Provider>)])).unwrap();
    let completion=layer.query(&host,&project,cursor+2,Operation::Completion,&BTreeMap::from([("prefix".into(),prefix.into())])).unwrap();
    let validation=layer.query(&host,&project,cursor+2,Operation::Validate,&parameters).unwrap();
    assert_eq!(layer.query(&host,&project,cursor+2,Operation::Format,&parameters).unwrap().state,State::Unsupported);
    json!({"state":format!("{:?}",completion.state).to_uppercase(),"completion":mapped(&completion),"validation":mapped(&validation),"wrongIdentity":"REJECTED","oldProject":"REJECTED","format":"UNSUPPORTED"})
}
fn mapped(result:&unlaxer_runtime::language_queries::QueryResult)->Value {
    json!(result.items.iter().map(|i|json!([&i.label,&i.detail,i.locations.iter().map(|l|json!([&l.location.snapshot.uri,l.location.snapshot.version,l.location.span.start,l.location.span.end,l.exact])).collect::<Vec<_>>(),i.edits.iter().map(|e|json!([e.span.start,e.span.end,&e.replacement])).collect::<Vec<_>>()])).collect::<Vec<_>>())
}
