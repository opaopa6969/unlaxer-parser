#![allow(dead_code)]
mod generated;
use std::collections::{BTreeMap,HashMap,HashSet};
use serde_json::{json,Value};
use unlaxer_lsp::{Backend,Diagnostic,Server};
use unlaxer_runtime::{language_queries::{LanguageQueries,Project,Provider,Request},semantic_rules as rules,editor_cst,
    source::{Snapshot,Language,LanguageRegions,Region,SourceMap,Segment,Kind,Location,Operation,State},Span};
struct Host{program:rules::Program,inventory:rules::Inventory}
fn span(start:usize,end:usize)->Span{Span{start,end}}
fn parse(text:&str)->unlaxer_runtime::source::Result<editor_cst::EditorCst>{generated::parser::parse_editor_cst(text,&["?",")",";","}","a",":","{"],editor_cst::Options::default()).map_err(|_|"parser failed")}
impl Backend for Host {
    fn grammar(&self)->&str{"Host"} fn entry(&self)->&str{"Root"} fn keywords(&self)->&[String]{&[]}
    fn validate(&mut self,_:&Snapshot)->Vec<Diagnostic>{vec![]}
    fn query_capabilities(&self)->HashSet<Operation>{HashSet::from([Operation::Validate])}
    fn language_queries(&mut self,source:&Snapshot)->Option<LanguageQueries>{
        let mut host=source.clone();if host.uri.contains("stale") {host.version-=1;}
        let closed=host.text.ends_with("}H");
        let body=host.slice(span(4,host.len()-if closed{2}else{0})).unwrap();
        let inner=Snapshot::new(format!("{}#typed",host.uri),host.version,body).unwrap();
        let language=Language{id:"typed".into(),package_id:"example/typed".into(),version:"1".into(),grammar:"TypedModel".into(),entry:"Document".into()};
        let map=SourceMap::new(inner.clone(),vec![Segment{output:span(0,inner.len()),kind:Kind::Copy,origin:Some(Location::new(host.clone(),span(4,4+inner.len())).unwrap())}]).unwrap();
        let region=Region{id:"typed".into(),parent:None,language:language.clone(),full:span(0,host.len()),body:span(4,4+inner.len()),source_map:map,parse_state:if closed{State::Complete}else{State::Partial}};
        let project=Project{id:"p".into(),version:1,documents:BTreeMap::from([(host.uri.clone(),host.clone())]),configuration:BTreeMap::new()};
        let provider=rules::QueryProvider::new(self.program.clone(),self.inventory.clone(),language.clone(),"p".into(),if host.uri.contains("project"){2}else{1},|request|parse(&request.region.source_map.output().text)).unwrap();
        let parameters=BTreeMap::new();let mut other=region.clone();other.language.version="2".into();
        assert!(provider.diagnostics(&Request{region:&other,operation:Operation::Validate,cursor:0,project:&project,parameters:&parameters}).is_err());
        if !host.uri.contains("project") {
            assert_eq!(provider.diagnostics(&Request{region:&region,operation:Operation::Completion,cursor:0,project:&project,parameters:&parameters}).unwrap().state,State::Unsupported);
            let mut old=project.clone();old.version=0;
            assert!(provider.diagnostics(&Request{region:&region,operation:Operation::Validate,cursor:0,project:&old,parameters:&parameters}).is_err());
            let stale=rules::QueryProvider::new(self.program.clone(),self.inventory.clone(),language.clone(),"p".into(),1,|_|parse("")).unwrap();
            assert!(stale.diagnostics(&Request{region:&region,operation:Operation::Validate,cursor:0,project:&project,parameters:&parameters}).is_err());
        }
        Some(LanguageQueries::new(LanguageRegions::new(host,vec![region]).unwrap(),project,HashMap::from([(language,Box::new(provider)as Box<dyn Provider>)])).unwrap())
    }
}
fn main(){
    let args:Vec<_>=std::env::args().collect();let corpus=std::path::Path::new(&args[2]);
    let grammar=unlaxer_ubnf::parse(&std::fs::read_to_string(corpus.join("model.ubnf")).unwrap()).unwrap().grammars.remove(0);
    let program=unlaxer_generator::semantic_rules::load(&std::fs::read_to_string(corpus.join("rules.json")).unwrap(),&grammar).unwrap();
    let inventory=unlaxer_generator::semantic_rules::inventory(&grammar).unwrap();
    let mut server=Server::new(Host{program,inventory});
    let initialized=server.handle(json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}));
    assert_eq!(initialized[0]["result"]["capabilities"]["experimental"]["languageQueryConsumer"]["operations"]["VALIDATE"]["available"],true);
    for line in std::fs::read_to_string(&args[1]).unwrap().lines(){
        let event:Value=serde_json::from_str(line).unwrap();let mut request=event.clone();request["jsonrpc"]=json!("2.0");
        println!("{}",json!(server.handle(request)));
    }
}
