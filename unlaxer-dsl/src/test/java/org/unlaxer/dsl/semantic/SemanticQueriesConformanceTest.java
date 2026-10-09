package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.unlaxer.dsl.semantic.SemanticModelConformanceTest.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.unlaxer.dsl.semantic.SemanticQueries.*;

public class SemanticQueriesConformanceTest {
    private static final Path REPO=Path.of("..").toAbsolutePath().normalize();
    private static final Limits STANDARD=new Limits(32,65536,32,256,32);
    private static JsonArray steps() throws Exception { return JsonParser.parseString(Files.readString(REPO.resolve("spec-corpus/semantic-queries/corpus.json"))).getAsJsonObject().getAsJsonArray("steps"); }
    private static SemanticQueries database(AtomicReference<Cancellation> token,Limits limits) {
        Executor executor=(key,c)->switch(key.kind()) {
            case "read" -> c.input(key.identity()).map(Input::value).orElse("<missing>");
            case "join" -> String.join("|",Arrays.stream(key.identity().split(",")).map(id->c.query(new Key("read",id,""))).toList());
            case "cancel" -> { c.query(new Key("read",key.identity(),"")); token.get().cancel(); yield "discard"; }
            case "cycle" -> c.query(key);
            case "caught" -> { try {c.query(new Key("cycle",key.identity(),""));} catch(QueryException ignored) {} yield "must not mask error"; }
            case "chain" -> { int n=Integer.parseInt(key.identity()); yield n==0?"end":c.query(new Key("chain",Integer.toString(n-1),"")); }
            default -> throw new AssertionError(key);
        };
        Map<String,Executor> executors=new HashMap<>();for(String kind:List.of("read","join","cancel","cycle","caught","chain"))executors.put(kind,executor);
        return new SemanticQueries("project",limits,executors);
    }
    private static List<String> javaResults(JsonArray steps) {
        AtomicReference<Cancellation> token=new AtomicReference<>(new Cancellation());SemanticQueries db=database(token,STANDARD);
        Map<String,Result> saved=new HashMap<>();Map<String,Cancellation> tokens=new HashMap<>();Map<String,Input> previous=Map.of();List<String> output=new ArrayList<>();
        for(JsonElement item:steps) {
            JsonObject step=item.getAsJsonObject();String value;Stats before=db.stats();String op=text(step,"op");
            try {
                switch(op) {
                    case "update" -> {
                        Map<String,Input> next=new HashMap<>();long version=step.get("version").getAsLong();
                        for(var e:step.getAsJsonObject("inputs").entrySet()) { Input old=previous.get(e.getKey());String content=e.getValue().getAsString();next.put(e.getKey(),old!=null&&old.value().equals(content)?old:new Input("file:///"+e.getKey(),version,content)); }
                        db.update(version,next);previous=next;value="updated";
                    }
                    case "query","cancelBefore" -> {
                        Cancellation cancellation=new Cancellation();token.set(cancellation);if(op.equals("cancelBefore"))cancellation.cancel();
                        Key key=new Key(text(step,"kind"),text(step,"id"),text(step,"args"));Result result=step.has("version")?db.evaluate(step.get("version").getAsLong(),key,cancellation):db.evaluate(key,cancellation);value=db.accept(result);
                        if(step.has("save")) {saved.put(text(step,"save"),result);tokens.put(text(step,"save"),cancellation);}
                    }
                    case "accept" -> value=db.accept(saved.get(text(step,"save")));
                    case "cancelResult" -> {tokens.get(text(step,"save")).cancel();value=db.accept(saved.get(text(step,"save")));}
                    case "foreign" -> {try(SemanticQueries foreign=database(token,STANDARD)) {foreign.update(2,previous);value=foreign.accept(saved.get(text(step,"save")));}}
                    case "close" -> {db.close();value=db.stats().entries()+"/"+db.stats().bytes()+"/"+db.retainedInputs();}
                    default -> throw new AssertionError(op);
                }
            } catch(QueryException e) {value=e.failure().name();}
            Stats after=db.stats();long hits=after.hits()-before.hits(),evaluated=after.evaluated()-before.evaluated();
            assertEquals(step.toString(),text(step,"expected"),value);
            assertEquals(step.toString(),step.has("hits")?step.get("hits").getAsLong():0,hits);
            assertEquals(step.toString(),step.has("evaluated")?step.get("evaluated").getAsLong():0,evaluated);
            output.add(json(value)+"\t"+hits+"\t"+evaluated);
        }
        return output;
    }
    @Test public void javaMatchesIndependentEditAndCancellationOracles() throws Exception {javaResults(steps());}
    @Test public void javaRejectsExpiredContextAndReentrantMutation() {
        AtomicReference<Context> escaped=new AtomicReference<>();AtomicReference<SemanticQueries> dbref=new AtomicReference<>();
        try(SemanticQueries db=new SemanticQueries("p",STANDARD,Map.of("x",(key,c)->{escaped.set(c);assertThrows(IllegalStateException.class,()->dbref.get().update(2,Map.of()));return "value";}))) {
            dbref.set(db);db.update(1,Map.of());db.evaluate(new Key("x","x",""),new Cancellation());
            assertThrows(IllegalStateException.class,()->escaped.get().input("a"));assertThrows(IllegalStateException.class,()->escaped.get().query(new Key("x","x","")));
        }
    }
    @Test public void rustMatchesSameIndependentOracles() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)",Boolean.getBoolean("rustConformance"));JsonArray cases=steps();Path folder=Files.createTempDirectory("queries-");
        try {
            Path runtime=folder.resolve("libunlaxer_runtime.rlib");run(List.of("rustc","--edition=2021","--crate-name=unlaxer_runtime","--crate-type=rlib",REPO.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),folder);
            Path source=folder.resolve("probe.rs");Files.writeString(source,rustProbe(cases));Path executable=folder.resolve("probe");run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,source.toString(),"-o",executable.toString()),folder);
            List<String> observed=run(List.of(executable.toString()),folder).lines().toList();List<String> expected=new ArrayList<>(javaResults(cases));expected.add(json(retention())+"\t0\t0");assertEquals(expected,observed);
            Files.copy(folder.resolve("rust-query-retention-metrics.tsv"),Path.of("target/rust-query-retention-metrics.tsv"),StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(Path.of("target"));Files.write(Path.of("target/rust-semantic-queries.tsv"),observed);
        } finally {try(var paths=Files.walk(folder)) {for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    private static String retention() throws Exception {
        String enabled=measure(true);measure(false);return enabled;
    }
    private static String measure(boolean enabled) throws Exception {
        try(SemanticQueries db=database(new AtomicReference<>(new Cancellation()),new Limits(enabled?4:0,512,4,256,32))) {
            long begin=System.nanoTime();Key key=new Key("join","a,b","");
            for(int version=0;version<1000;version++) {
                db.update(version,Map.of("a",new Input("file:a",version,Integer.toString(version)),"b",new Input("file:b",0,"B")));
                Result result=db.evaluate(key,new Cancellation());assertEquals(version+"|B",db.accept(result));assertEquals(result.value(),db.accept(db.evaluate(key,new Cancellation())));
            }
            for(int id=0;id<1000;id++) assertEquals("999",db.evaluate(new Key("read","a",Integer.toString(id)),new Cancellation()).value());
            long elapsed=System.nanoTime()-begin;Stats stats=db.stats();assertEquals(enabled?3001:7000,stats.evaluated());assertEquals(enabled?1999:0,stats.hits());assertEquals(enabled?4:0,stats.entries());assertEquals(enabled?4:0,stats.maximumEntries());assertTrue(stats.maximumBytes()<=512);
            Files.writeString(Path.of("target/java-query-retention-metrics.tsv"),(enabled?"host\tcache\toperations\tnanoseconds\thits\tevaluated\tmaximum_entries\tmaximum_logical_bytes\n":"")+"java\t"+(enabled?"on":"off")+"\t3000\t"+elapsed+"\t"+stats.hits()+"\t"+stats.evaluated()+"\t"+stats.maximumEntries()+"\t"+stats.maximumBytes()+"\n",enabled?StandardOpenOption.TRUNCATE_EXISTING:StandardOpenOption.APPEND,StandardOpenOption.CREATE);
            String result=stats.evaluated()+"/"+stats.hits()+"/"+stats.entries()+"/"+stats.maximumEntries()+"/"+stats.maximumBytes();
            db.close();assertEquals(0,db.stats().bytes());assertEquals(0,db.stats().entries());assertEquals(0,db.retainedInputs());return result;
        }
    }
    @Test public void capacityAndLongEditSequenceStayBounded() throws Exception {assertEquals("3001/1999/4/4/464",retention());}
    @Test public void cancellationFromAnotherThreadDiscardsStagedChildren() throws Exception {
        Cancellation cancellation=new Cancellation();java.util.concurrent.CyclicBarrier barrier=new java.util.concurrent.CyclicBarrier(2);
        Map<String,Executor> executors=Map.of("read",(key,c)->c.input("a").orElseThrow().value(),"cancel",(key,c)->{
            c.query(new Key("read","a",""));await(barrier);await(barrier);return "discard";
        });
        try(SemanticQueries db=new SemanticQueries("p",STANDARD,executors)) {
            db.update(1,Map.of("a",new Input("file:a",1,"😀")));
            Thread other=new Thread(()->{await(barrier);cancellation.cancel();await(barrier);});other.start();
            assertEquals(Failure.CANCELLED,assertThrows(QueryException.class,()->db.evaluate(new Key("cancel","a",""),cancellation)).failure());other.join(5000);assertFalse(other.isAlive());
            assertEquals(0,db.stats().entries());assertEquals(0,db.stats().evaluated());assertEquals("😀",db.accept(db.evaluate(new Key("read","a",""),new Cancellation())));assertEquals(1,db.stats().evaluated());
        }
    }
    private static void await(java.util.concurrent.CyclicBarrier barrier) {try {barrier.await(5,java.util.concurrent.TimeUnit.SECONDS);} catch(Exception e) {throw new AssertionError(e);}}
    @Test public void rejectedUpdatesAndCapacityLimitsPreserveTheSnapshot() {
        AtomicReference<Cancellation> token=new AtomicReference<>(new Cancellation());
        try(SemanticQueries db=database(token,new Limits(4,1024,2,256,8))) {
            db.update(1,Map.of("a",new Input("file:a",1,"😀")));Result result=db.evaluate(new Key("read","a",""),new Cancellation());
            assertEquals(Failure.STALE,assertThrows(QueryException.class,()->db.update(2,Map.of("a",new Input("file:a",1,"changed")))).failure());
            assertEquals("😀",db.accept(result));assertEquals(Failure.LIMIT,assertThrows(QueryException.class,()->db.update(2,Map.of("a",new Input("a",1,""),"b",new Input("b",1,""),"c",new Input("c",1,"")))).failure());assertTrue(db.isCurrent(result));
        }
        for(Limits limits:List.of(new Limits(0,1024,2,256,8),new Limits(4,1,2,256,8))) try(SemanticQueries db=database(token,limits)) {
            db.update(1,Map.of("a",new Input("file:a",1,"😀")));for(int i=0;i<2;i++)assertEquals("😀",db.evaluate(new Key("read","a",""),new Cancellation()).value());assertEquals(2,db.stats().evaluated());assertEquals(0,db.stats().entries());assertEquals(0,db.stats().bytes());
        }
        for(Limits limits:List.of(new Limits(4,1024,2,256,1),new Limits(4,1024,2,1,8))) try(SemanticQueries db=database(token,limits)) {
            db.update(1,Map.of());assertEquals(Failure.LIMIT,assertThrows(QueryException.class,()->db.evaluate(new Key("join","a,b",""),new Cancellation())).failure());assertEquals(0,db.stats().entries());
        }
    }
    private static String q(String value) {return r(value);}
    private static String rustProbe(JsonArray steps) throws Exception {
        StringBuilder code=new StringBuilder(Files.readString(REPO.resolve("spec-corpus/semantic-queries/probe-support.rs")));
        code.append("\nfn main() { let token=Arc::new(Mutex::new(Cancellation::default()));let mut db=database(token.clone(),standard());let mut saved:BTreeMap<String,QueryResult>=BTreeMap::new();let mut tokens:BTreeMap<String,Cancellation>=BTreeMap::new();let mut previous:BTreeMap<String,Input>=BTreeMap::new();\n");
        for(JsonElement element:steps) {
            JsonObject s=element.getAsJsonObject();String op=text(s,"op");code.append("{let before=db.stats();let outcome=(||->Result<String,Failure>{\n");
            switch(op) {
                case "update" -> {
                    code.append("let version=").append(s.get("version")).append(";let mut next=BTreeMap::new();\n");
                    for(var e:s.getAsJsonObject("inputs").entrySet()) code.append("{let key=").append(q(e.getKey())).append(";let content=").append(q(e.getValue().getAsString())).append(";let input=previous.get(&key).filter(|old|old.value==content).cloned().unwrap_or(Input {uri:format!(\"file:///{}\",key),version,value:content});next.insert(key,input);}\n");
                    code.append("db.update(version,next.clone())?;previous=next;Ok(\"updated\".into())\n");
                }
                case "query","cancelBefore" -> {
                    code.append("let cancel=Cancellation::default();*token.lock().unwrap()=cancel.clone();");if(op.equals("cancelBefore"))code.append("cancel.cancel();");
                    code.append(s.has("version")?"let r=db.evaluate_at("+s.get("version")+",Key::new(&":"let r=db.evaluate(Key::new(&").append(q(text(s,"kind"))).append(",&").append(q(text(s,"id"))).append(",&").append(q(text(s,"args"))).append(")?,cancel.clone())?;let value=db.accept(&r)?.to_string();");
                    if(s.has("save"))code.append("saved.insert(").append(q(text(s,"save"))).append(",r);tokens.insert(").append(q(text(s,"save"))).append(",cancel);");code.append("Ok(value)\n");
                }
                case "accept" -> code.append("db.accept(&saved[&").append(q(text(s,"save"))).append("]).map(str::to_string)\n");
                case "cancelResult" -> code.append("tokens[&").append(q(text(s,"save"))).append("].cancel();db.accept(&saved[&").append(q(text(s,"save"))).append("]).map(str::to_string)\n");
                case "foreign" -> code.append("let mut foreign=database(token.clone(),standard());foreign.update(2,previous.clone())?;foreign.accept(&saved[&").append(q(text(s,"save"))).append("]).map(str::to_string)\n");
                case "close" -> code.append("db.close();Ok(format!(\"{}/{}/{}\",db.stats().entries,db.stats().bytes,db.retained_inputs()))\n");
                default -> throw new AssertionError(op);
            }
            code.append("})();let value=outcome.unwrap_or_else(|e|e.name().into());let after=db.stats();assert_eq!(value,").append(q(text(s,"expected"))).append(");assert_eq!(after.hits-before.hits,").append(s.has("hits")?s.get("hits"):0).append(");assert_eq!(after.evaluated-before.evaluated,").append(s.has("evaluated")?s.get("evaluated"):0).append(");output(&value,after.hits-before.hits,after.evaluated-before.evaluated);}\n");
        }
        return code.append("output(&retention(),0,0); }\n").toString();
    }
}
