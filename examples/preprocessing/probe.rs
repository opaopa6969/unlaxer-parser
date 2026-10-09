#![allow(dead_code)]
mod generated;
mod pipeline;
use serde_json::{json,Value};
use std::collections::BTreeMap;
use unlaxer_generator::semantic_rules::{load,inventory};
use unlaxer_runtime::{editor_cst,source::Snapshot};
fn parse(source:&str)->editor_cst::EditorCst {
    generated::parser::parse_editor_cst(source,&["?",")",";","}"],editor_cst::Options::default()).unwrap()
}
fn main() {
    let root=std::path::PathBuf::from(std::env::args().nth(1).unwrap());
    let grammar=unlaxer_ubnf::parse(&std::fs::read_to_string(root.join("spec-corpus/semantic-rules/model.ubnf")).unwrap()).unwrap().grammars.remove(0);
    let program=load(&std::fs::read_to_string(root.join("spec-corpus/semantic-rules/rules.json")).unwrap(),&grammar).unwrap();
    let mut pipeline=pipeline::pipeline(program,inventory(&grammar).unwrap(),parse);
    let cases:Value=serde_json::from_str(&std::fs::read_to_string(root.join("examples/preprocessing/cases.json")).unwrap()).unwrap();
    for case in cases.as_array().unwrap() {
        let snapshots=case["snapshots"].as_object().unwrap().iter().map(|(key,value)|(key.clone(),Snapshot::new(value["uri"].as_str().unwrap(),value["version"].as_u64().unwrap(),value["text"].as_str().unwrap()).unwrap())).collect::<BTreeMap<_,_>>();
        let configuration=case["configuration"].as_object().unwrap().iter().map(|(key,value)|(key.clone(),value.as_str().unwrap().to_string())).collect();
        let result=match pipeline.evaluate("semantic",snapshots,configuration,case["budget"].as_u64().unwrap()as usize,false) {
            Ok(result)=>result,
            Err(error)=>{println!("{}",json!({"error":error}));continue;}
        };
        let report=if result.artifact.payload.is_empty(){Value::Null}else{serde_json::from_str(&result.artifact.payload).unwrap()};
        let actual=json!({"state":format!("{:?}",result.artifact.state).to_uppercase(),"evaluated":result.evaluated,"reused":result.reused,"revision":result.revision.is_some(),"report":report,"origins":result.artifact.origins.iter().map(|l|&l.snapshot.uri).collect::<Vec<_>>()});
        println!("{}",actual);
    }
}
