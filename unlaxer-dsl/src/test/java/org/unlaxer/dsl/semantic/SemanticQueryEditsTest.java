package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.unlaxer.dsl.semantic.SemanticModelConformanceTest.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.unlaxer.dsl.semantic.SemanticQueries.*;

public class SemanticQueryEditsTest {
    private static final Path REPO=Path.of("..").toAbsolutePath().normalize();
    private static final Limits LIMITS=new Limits(4,4096,4,32,8);
    private static final Key ROOT=new Key("complete","main","4:ac:IAccessor");
    private static JsonArray states() throws Exception {return JsonParser.parseString(Files.readString(REPO.resolve("spec-corpus/semantic-queries/semantic-edits.json"))).getAsJsonObject().getAsJsonArray("states");}
    private static String semantic(JsonObject fixture,int offset) {
        try {
            ProjectSymbolIndex p=ProjectSymbolIndexConformanceTest.project(fixture);long document=p.modules().get(0).model().version();
            return String.join(";",p.complete("main",p.version(),document,4,"ac","IAccessor").stream().map(c->c.candidate().name()+":"+c.compatibility()+":"+c.edit().uri()+":"+c.edit().span().start()+"-"+c.edit().span().end()+":host:"+(c.edit().span().start()+offset)+"-"+(c.edit().span().end()+offset)).toList());
        } catch(SemanticModel.ModelException e) {return e.code()+"@"+e.start()+"-"+e.end();}
    }
    private static SemanticQueries database(JsonArray states) {
        return new SemanticQueries("query-project",LIMITS,Map.of(
            "snapshot",(key,c)->c.input(key.identity()).orElseThrow().value(),
            "complete",(key,c)->{
                int id=Integer.parseInt(c.query(new Key("snapshot","semantic","")));
                int origin=Integer.parseInt(c.query(new Key("snapshot","region","")));
                return semantic(states.get(id).getAsJsonObject().getAsJsonObject("project"),origin);
            }));
    }
    private static Map<String,Input> inputs(JsonObject state,int id,long version) {
        return Map.of("semantic",new Input("ast:project",version,Integer.toString(id)),"region",new Input("map:region",version,state.get("origin").getAsString()));
    }
    private static List<String> javaResults(JsonArray states) {
        List<String> output=new ArrayList<>();Result old=null;
        try(SemanticQueries db=database(states)) {
            for(int id=0;id<states.size();id++) {
                JsonObject s=states.get(id).getAsJsonObject();Map<String,Input> values=inputs(s,id,id+1);db.update(id+1,values);
                if(old!=null) {Result previous=old;assertFalse(db.isCurrent(previous));assertEquals(Failure.STALE,assertThrows(QueryException.class,()->db.accept(previous)).failure());}
                Result result=db.evaluate(ROOT,new Cancellation());String value=db.accept(result);assertEquals(text(s,"name"),text(s,"expected"),value);
                try(SemanticQueries fresh=database(states)) {fresh.update(id+1,values);assertEquals(fresh.accept(fresh.evaluate(ROOT,new Cancellation())),value);}
                long evaluations=db.stats().evaluated();assertEquals(value,db.accept(db.evaluate(ROOT,new Cancellation())));assertEquals(evaluations,db.stats().evaluated());
                output.add(json(value).toString());old=result;
            }
        }
        return output;
    }
    @Test public void incrementalMatchesFreshIndependentSemanticSnapshots() throws Exception {javaResults(states());}
    @Test public void rustMatchesEveryEditAndFreshRebuild() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)",Boolean.getBoolean("rustConformance"));JsonArray states=states();Path folder=Files.createTempDirectory("query-edits-");
        try {
            Path runtime=folder.resolve("libunlaxer_runtime.rlib");run(List.of("rustc","--edition=2021","--crate-name=unlaxer_runtime","--crate-type=rlib",REPO.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),folder);
            Path source=folder.resolve("probe.rs");Files.writeString(source,rustProbe(states));Path executable=folder.resolve("probe");run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,source.toString(),"-o",executable.toString()),folder);
            List<String> results=run(List.of(executable.toString()),folder).lines().toList();assertEquals(javaResults(states),results);Files.write(Path.of("target/rust-semantic-query-edits.tsv"),results);
        } finally {try(var paths=Files.walk(folder)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static String rustProbe(JsonArray states) {
        StringBuilder s=new StringBuilder("use std::collections::BTreeMap;use std::sync::Arc;use unlaxer_runtime::semantic::*;use unlaxer_runtime::semantic_project as p;use unlaxer_runtime::{Span,json_string};use unlaxer_runtime::semantic_query_cache::{Key,Input,Limits,Failure,Cancellation,Executor,Context,SemanticQueries,QueryResult};\n");
        // Model validation errors are returned, including from embedded constructor expressions.
        s.append("fn project(id:usize)->Result<p::ProjectSymbolIndex,String> {let built=(||->Result<p::ProjectSymbolIndex,ModelError>{Ok(match id {\n");
        for(int i=0;i<states.size();i++) {
            String build=ProjectSymbolIndexConformanceTest.rustProject(states.get(i).getAsJsonObject().getAsJsonObject("project")).replace(".unwrap()","?");
            s.append(i).append("=>").append(build).append(".expect(\"valid project contract\"),\n");
        }
        s.append("_=>unreachable!()})})();built.map_err(|e|format!(\"{}@{}-{}\",e.code,e.span.start,e.span.end))}\n");
        s.append("""
            struct Provider;
            impl Executor for Provider {fn execute(&self,key:&Key,c:&mut dyn Context)->Result<String,Failure> {
                if key.kind=="snapshot" {return Ok(c.input(&key.identity)?.unwrap().value);}
                let id=c.query(Key::new("snapshot","semantic","")?)?.parse::<usize>().unwrap();
                let origin=c.query(Key::new("snapshot","region","")?)?.parse::<usize>().unwrap();
                let p=match project(id) {Ok(p)=>p,Err(error)=>return Ok(error)};
                let document=p.modules()[0].model.version();
                Ok(p.complete("main",p.version(),document,4,"ac","IAccessor").unwrap().iter().map(|c|format!("{}:{}:{}:{}-{}:host:{}-{}",c.candidate.name,c.compatibility.name(),c.edit.uri,c.edit.span.start,c.edit.span.end,c.edit.span.start+origin,c.edit.span.end+origin)).collect::<Vec<_>>().join(";"))
            }}
            fn database()->SemanticQueries {let p=Arc::new(Provider) as Arc<dyn Executor>;SemanticQueries::new("query-project",Limits{entries:4,bytes:4096,inputs:4,steps:32,dependencies:8},[("snapshot".into(),p.clone()),("complete".into(),p)].into_iter().collect()).unwrap()}
            fn main() {let mut db=database();let mut old:Option<QueryResult>=None;let root=Key::new("complete","main","4:ac:IAccessor").unwrap();
            """);
        for(int i=0;i<states.size();i++) {
            JsonObject state=states.get(i).getAsJsonObject();
            s.append("{let version=").append(i+1).append(";let values:BTreeMap<String,Input>=[(\"semantic\".into(),Input{uri:\"ast:project\".into(),version,value:").append(r(Integer.toString(i))).append("}),(\"region\".into(),Input{uri:\"map:region\".into(),version,value:").append(r(state.get("origin").getAsString())).append("})].into_iter().collect();db.update(version,values.clone()).unwrap();\n");
            s.append("if let Some(previous)=&old {assert_eq!(db.accept(previous),Err(Failure::Stale));}let result=db.evaluate(root.clone(),Cancellation::default()).unwrap();let value=db.accept(&result).unwrap().to_string();assert_eq!(value,").append(r(text(state,"expected"))).append(");let mut fresh=database();fresh.update(version,values).unwrap();let rebuilt=fresh.evaluate(root.clone(),Cancellation::default()).unwrap();assert_eq!(value,fresh.accept(&rebuilt).unwrap());let count=db.stats().evaluated;let again=db.evaluate(root.clone(),Cancellation::default()).unwrap();assert_eq!(db.accept(&again).unwrap(),value);assert_eq!(db.stats().evaluated,count);println!(\"{}\",json_string(&value));old=Some(result);}\n");
        }
        return s.append("}\n").toString();
    }
}
