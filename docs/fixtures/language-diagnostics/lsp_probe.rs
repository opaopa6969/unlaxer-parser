use std::{collections::{BTreeMap,HashMap,HashSet},time::Duration};
use serde_json::{json,Value};
use unlaxer_lsp::{Backend,Diagnostic,Server};
use unlaxer_runtime::{language_queries::{LanguageQueries,Project,Provider},provider_process::ProviderProcess,provider_protocol::Identity,
    source::{Snapshot,Language,LanguageRegions,Region,SourceMap,Segment,Kind,Location,Operation,State},Span};
struct Host{command:Vec<String>}
fn span(start:usize,end:usize)->Span{Span{start,end}}
impl Backend for Host {
    fn grammar(&self)->&str{"Host"} fn entry(&self)->&str{"Root"} fn keywords(&self)->&[String]{&[]}
    fn validate(&mut self,_:&Snapshot)->Vec<Diagnostic>{vec![]}
    fn query_capabilities(&self)->HashSet<Operation>{HashSet::from([Operation::Validate])}
    fn language_queries(&mut self, source:&Snapshot)->Option<LanguageQueries>{
        let mut host=source.clone();if host.uri.contains("stale") {host.version-=1;}
        let body=host.slice(span(4,host.len()-2)).unwrap();
        let inner=Snapshot::new(format!("{}#java",host.uri),host.version,body).unwrap();
        let dependency=Snapshot::new("file:///Dep.java",5,"//😀\r\nclass Dep { String x = 2; }\r\n").unwrap();
        let language=Language{id:"java".into(),package_id:"lang/java".into(),version:"0.1.0".into(),grammar:"Java21".into(),entry:"CompilationUnit".into()};
        let map=SourceMap::new(inner.clone(),vec![Segment{output:span(0,inner.len()),kind:Kind::Copy,origin:Some(Location::new(host.clone(),span(4,host.len()-2)).unwrap())}]).unwrap();
        let region=Region{id:"java".into(),parent:None,language:language.clone(),full:span(0,host.len()),body:span(4,host.len()-2),source_map:map,parse_state:State::Complete};
        let project=Project{id:"p".into(),version:1,documents:BTreeMap::from([(host.uri.clone(),host.clone()),(dependency.uri.clone(),dependency)]),configuration:BTreeMap::new()};
        let process=ProviderProcess{command:self.command.clone(),identity:Identity{id:"javac".into(),version:"21.0.9".into()},operations:HashSet::from([Operation::Validate]),timeout:Duration::from_secs(30)};
        Some(LanguageQueries::new(LanguageRegions::new(host,vec![region]).unwrap(),project,HashMap::from([(language,Box::new(process)as Box<dyn Provider>)])).unwrap())
    }
}
fn main(){
    let args:Vec<_>=std::env::args().collect();
    let mut server=Server::new(Host{command:vec![args[2].clone(),"-cp".into(),args[3].clone(),"org.unlaxer.dsl.provider.JavacProvider".into()]});
    server.handle(json!({"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}));
    for line in std::fs::read_to_string(&args[1]).unwrap().lines(){
        let event:Value=serde_json::from_str(line).unwrap();let mut request=event.clone();request["jsonrpc"]=json!("2.0");
        println!("{}",json!(server.handle(request)));
    }
}
