use std::collections::BTreeMap;
use std::sync::{Arc,Mutex};
use unlaxer_runtime::semantic_query_cache::*;
struct Probe { token:Arc<Mutex<Cancellation>> }
impl Executor for Probe {
 fn execute(&self,key:&Key,c:&mut dyn Context)->Result<String,Failure> {
  match key.kind.as_str() {
   "read"=>Ok(c.input(&key.identity)?.map_or("<missing>".into(),|i|i.value)),
   "join"=>key.identity.split(',').map(|id|c.query(Key::new("read",id,"")?)).collect::<Result<Vec<_>,_>>().map(|values|values.join("|")),
   "cancel"=>{c.query(Key::new("read",&key.identity,"")?)?;self.token.lock().unwrap().cancel();Ok("discard".into())},
   "cycle"=>c.query(key.clone()),
   "caught"=>{let _=c.query(Key::new("cycle",&key.identity,"")?);Ok("must not mask error".into())},
   "chain"=>{let n=key.identity.parse::<usize>().unwrap();if n==0 {Ok("end".into())} else {c.query(Key::new("chain",&(n-1).to_string(),"")?)}},
   _=>Err(Failure::Unsupported)
  }
 }
}
fn database(token:Arc<Mutex<Cancellation>>,limits:Limits)->SemanticQueries {
 let provider=Arc::new(Probe {token}) as Arc<dyn Executor>;
 SemanticQueries::new("project",limits,["read","join","cancel","cycle","caught","chain"].into_iter().map(|k|(k.into(),provider.clone())).collect()).unwrap()
}
fn standard()->Limits {Limits {entries:32,bytes:65536,inputs:32,steps:256,dependencies:32}}
fn result(r:Result<&str,Failure>)->String {r.map(str::to_string).unwrap_or_else(|e|e.name().into())}
fn output(value:&str,hits:u64,evaluated:u64) {println!("{}\t{}\t{}",unlaxer_runtime::json_string(value),hits,evaluated);}
fn retention()->String {let enabled=measure(true);measure(false);enabled}
fn measure(enabled:bool)->String {
 let token=Arc::new(Mutex::new(Cancellation::default()));let mut db=database(token,Limits{entries:if enabled {4}else{0},bytes:512,inputs:4,steps:256,dependencies:32});
 let begin=std::time::Instant::now();let root=Key::new("join","a,b","").unwrap();
 for version in 0..1000 {
  db.update(version,[("a".into(),Input{uri:"file:a".into(),version,value:version.to_string()}),("b".into(),Input{uri:"file:b".into(),version:0,value:"B".into()})].into_iter().collect()).unwrap();
  let r=db.evaluate(root.clone(),Cancellation::default()).unwrap();assert_eq!(db.accept(&r).unwrap(),format!("{version}|B"));
  let again=db.evaluate(root.clone(),Cancellation::default()).unwrap();assert_eq!(db.accept(&again).unwrap(),r.value());
 }
 for id in 0..1000 {assert_eq!(db.evaluate(Key::new("read","a",&id.to_string()).unwrap(),Cancellation::default()).unwrap().value(),"999");}
 let elapsed=begin.elapsed().as_nanos();let stats=db.stats();assert_eq!(stats.evaluated,if enabled {3001}else{7000});assert_eq!(stats.hits,if enabled {1999}else{0});assert_eq!(stats.entries,if enabled {4}else{0});assert_eq!(stats.maximum_entries,if enabled {4}else{0});assert!(stats.maximum_bytes<=512);
 let value=format!("{}/{}/{}/{}/{}",stats.evaluated,stats.hits,stats.entries,stats.maximum_entries,stats.maximum_bytes);
 use std::io::Write;let mut file=std::fs::OpenOptions::new().create(true).write(true).truncate(enabled).append(!enabled).open("rust-query-retention-metrics.tsv").unwrap();if enabled {writeln!(file,"host\tcache\toperations\tnanoseconds\thits\tevaluated\tmaximum_entries\tmaximum_logical_bytes").unwrap();}writeln!(file,"rust\t{}\t3000\t{elapsed}\t{}\t{}\t{}\t{}",if enabled {"on"}else{"off"},stats.hits,stats.evaluated,stats.maximum_entries,stats.maximum_bytes).unwrap();
 db.close();assert_eq!(db.stats().bytes,0);assert_eq!(db.stats().entries,0);assert_eq!(db.retained_inputs(),0);value
}
