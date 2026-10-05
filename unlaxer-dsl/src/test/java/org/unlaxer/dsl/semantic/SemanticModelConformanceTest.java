package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.unlaxer.dsl.semantic.SemanticModel.*;

public class SemanticModelConformanceTest {
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static JsonArray corpus() throws Exception {
        var file = JsonParser.parseString(Files.readString(REPO.resolve("spec-corpus/semantic-model/corpus.json"))).getAsJsonObject();
        var result = file.getAsJsonArray("cases");
        for (var entry : result) {
            var fixture = entry.getAsJsonObject(); var model = file.getAsJsonObject("model").deepCopy();
            if (fixture.has("overrides")) fixture.getAsJsonObject("overrides").entrySet().forEach(e -> model.add(e.getKey(), e.getValue()));
            fixture.add("model", model);
        }
        return result;
    }
    static String text(JsonObject o, String key) { return o.get(key).getAsString(); }
    static Span span(JsonElement e) { var a=e.getAsJsonArray(); return new Span(a.get(0).getAsInt(),a.get(1).getAsInt()); }
    static List<String> strings(JsonArray a) { return a.asList().stream().map(JsonElement::getAsString).toList(); }
    static <T> List<T> list(JsonObject o,String key,java.util.function.Function<JsonObject,T> f) {
        return o.getAsJsonArray(key).asList().stream().map(JsonElement::getAsJsonObject).map(f).toList();
    }
    static SemanticModel model(JsonObject o) {
        return new SemanticModel(text(o,"uri"),o.get("version").getAsLong(),text(o,"source"),
            list(o,"types",t -> new Type(text(t,"id"),TypeKind.valueOf(text(t,"kind")),strings(t.getAsJsonArray("supertypes")),
                list(t,"fields",f -> new Field(text(f,"name"),text(f,"type"),span(f.get("span")))),span(t.get("span")))),
            list(o,"scopes",s -> new Scope(text(s,"id"),s.get("parent").isJsonNull()?null:text(s,"parent"),span(s.get("span")))),
            list(o,"symbols",s -> new Symbol(text(s,"id"),text(s,"name"),text(s,"type"),text(s,"scope"),span(s.get("declaration")),s.get("visibleFrom").getAsInt())),
            list(o,"signatures",s -> new Signature(text(s,"id"),text(s,"name"),strings(s.getAsJsonArray("parameters")),text(s,"result"),span(s.get("span")))),
            list(o,"calls",c -> new Call(text(c,"id"),strings(c.getAsJsonArray("signatures")),
                list(c,"arguments",a -> new Argument(text(a,"type"),span(a.get("span")))),span(c.get("span")))));
    }
    static JsonElement json(Object o) { return new Gson().toJsonTree(o); }
    static JsonElement error(ModelException e) { return json(Map.of("error",e.code(),"span",List.of(e.start(),e.end()))); }
    static JsonElement query(SemanticModel m,JsonObject q) {
        try {
            return switch(text(q,"op")) {
                case "assign" -> json(m.isAssignable(text(q,"actual"),text(q,"target")).name());
                case "visible" -> json(m.visibleSymbolsAt(q.get("cursor").getAsInt()).stream().map(Symbol::id).toList());
                case "fields" -> json(m.types().get(text(q,"type")).fields().stream().map(f -> f.name()+":"+f.type()).toList());
                case "expected" -> json(m.expectedTypes(text(q,"call"),q.get("index").getAsInt()));
                case "complete" -> json(m.completeArgument(text(q,"call"),q.get("index").getAsInt(),q.get("cursor").getAsInt(),q.get("version").getAsLong(),text(q,"prefix")).stream().map(c -> Map.of(
                    "id",c.symbol().id(),"name",c.symbol().name(),"type",c.symbol().type(),"span",List.of(c.symbol().declaration().start(),c.symbol().declaration().end()),
                    "compatibility",c.compatibility().name(),"expectedTypes",c.expectedTypes(),"reason",c.reason())).toList());
                default -> throw new AssertionError(q);
            };
        } catch(ModelException e) { return error(e); }
    }
    private static List<JsonElement> javaResults(JsonArray corpus) {
        var results=new ArrayList<JsonElement>();
        for(var entry:corpus) {
            var f=entry.getAsJsonObject();
            try {
                var model=model(f.getAsJsonObject("model"));
                assertFalse(text(f,"name")+" should fail",f.has("error"));
                for(var q:f.getAsJsonArray("queries")) results.add(query(model,q.getAsJsonObject()));
            } catch(ModelException e) { assertTrue(text(f,"name"),f.has("error")); results.add(error(e)); }
        }
        return results;
    }
    private static List<JsonElement> expected(JsonArray corpus) {
        var results=new ArrayList<JsonElement>();
        for(var e:corpus) { var f=e.getAsJsonObject(); if(f.has("error")) results.add(f.get("error")); else for(var q:f.getAsJsonArray("queries")) results.add(q.getAsJsonObject().get("expected")); }
        return results;
    }
    @Test public void javaMatchesIndependentOracles() throws Exception { assertEquals(expected(corpus()),javaResults(corpus())); }
    @Test public void immutableModelOwnsItsInputCollections() {
        var parents=new ArrayList<String>(); var fields=new ArrayList<Field>();
        var t=new Type("T",TypeKind.RECORD,parents,fields,new Span(0,0));
        parents.add("Missing"); fields.add(new Field("bad","Missing",new Span(0,0)));
        var types=new ArrayList<>(List.of(t));
        var m=new SemanticModel("memory:empty",0,"",types,List.of(new Scope("root",null,new Span(0,0))),List.of(),List.of(),List.of());
        types.clear(); assertEquals(Compatibility.YES,m.isAssignable("T","T"));
        assertTrue(m.types().get("T").fields().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->m.types().clear());
    }
    @Test public void rustMatchesTheSameIndependentOracles() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)",Boolean.getBoolean("rustConformance"));
        var corpus=corpus(); var folder=Files.createTempDirectory("semantic-conformance-");
        try {
            var runtime=folder.resolve("libunlaxer_runtime.rlib");
            run(List.of("rustc","--edition=2021","--crate-name=unlaxer_runtime","--crate-type=rlib",REPO.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),folder);
            var source=folder.resolve("probe.rs"); Files.writeString(source,rustProbe(corpus));
            var executable=folder.resolve("probe"); run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,source.toString(),"-o",executable.toString()),folder);
            var output=run(List.of(executable.toString()),folder).lines().map(JsonParser::parseString).toList();
            assertEquals(expected(corpus),output); assertEquals(javaResults(corpus),output);
            var report=new ArrayList<>(List.of("observation\texpected\tjava\trust")); var java=javaResults(corpus); var oracle=expected(corpus);
            for(int i=0;i<output.size();i++) report.add(i+"\t"+oracle.get(i)+"\t"+java.get(i)+"\t"+output.get(i));
            Files.createDirectories(Path.of("target"));Files.write(Path.of("target/rust-semantic-model.tsv"),report);
        } finally { try(var paths=Files.walk(folder)) { for(var p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); } }
    }
    static String run(List<String> command,Path cwd) throws Exception {
        var output=Files.createTempFile(cwd,"output-",".txt");
        var process=new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if(!process.waitFor(120,TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout: "+command); }
        String result=Files.readString(output);assertEquals(command+"\n"+result,0,process.exitValue());return result;
    }
    static String r(String s) { return "String::from(r###\""+s+"\"###)"; }
    static String rs(JsonObject o,String key) { return r(text(o,key)); }
    static String rsp(JsonObject o,String key) { Span s=span(o.get(key));return "Span {start:"+s.start()+",end:"+s.end()+"}"; }
    static String rv(JsonArray a) { return "vec!["+String.join(",",strings(a).stream().map(SemanticModelConformanceTest::r).toList())+"]"; }
    static String rustModel(JsonObject o) {
        var code=new StringBuilder("SemanticModel::new("+rs(o,"uri")+","+o.get("version")+","+rs(o,"source")+",ModelData {types:vec![");
        for(var e:o.getAsJsonArray("types")) { var t=e.getAsJsonObject();String kind=switch(text(t,"kind")){case "BUILTIN"->"Builtin";case "INTERFACE"->"Interface";default->"Record";};
            code.append("Type {id:").append(rs(t,"id")).append(",kind:TypeKind::").append(kind).append(",supertypes:").append(rv(t.getAsJsonArray("supertypes"))).append(",span:").append(rsp(t,"span")).append(",fields:vec![");
            for(var fe:t.getAsJsonArray("fields")) {var f=fe.getAsJsonObject();code.append("Field {name:").append(rs(f,"name")).append(",type_id:").append(rs(f,"type")).append(",span:").append(rsp(f,"span")).append("},");}
            code.append("]},"); }
        code.append("],scopes:vec![");
        for(var e:o.getAsJsonArray("scopes")) {var s=e.getAsJsonObject();code.append("Scope {id:").append(rs(s,"id")).append(",parent:").append(s.get("parent").isJsonNull()?"None":"Some("+rs(s,"parent")+")").append(",span:").append(rsp(s,"span")).append("},");}
        code.append("],symbols:vec![");
        for(var e:o.getAsJsonArray("symbols")) {var s=e.getAsJsonObject();code.append("Symbol {id:").append(rs(s,"id")).append(",name:").append(rs(s,"name")).append(",type_id:").append(rs(s,"type")).append(",scope:").append(rs(s,"scope")).append(",declaration:").append(rsp(s,"declaration")).append(",visible_from:").append(s.get("visibleFrom")).append("},");}
        code.append("],signatures:vec![");
        for(var e:o.getAsJsonArray("signatures")) {var s=e.getAsJsonObject();code.append("Signature {id:").append(rs(s,"id")).append(",name:").append(rs(s,"name")).append(",parameters:").append(rv(s.getAsJsonArray("parameters"))).append(",result:").append(rs(s,"result")).append(",span:").append(rsp(s,"span")).append("},");}
        code.append("],calls:vec![");
        for(var e:o.getAsJsonArray("calls")) {var c=e.getAsJsonObject();code.append("Call {id:").append(rs(c,"id")).append(",signatures:").append(rv(c.getAsJsonArray("signatures"))).append(",span:").append(rsp(c,"span")).append(",arguments:vec![");for(var ae:c.getAsJsonArray("arguments")){var a=ae.getAsJsonObject();code.append("Argument {type_id:").append(rs(a,"type")).append(",span:").append(rsp(a,"span")).append("},");}code.append("]},");}
        return code.append("]})").toString();
    }
    static String rustProbe(JsonArray corpus) throws Exception {
        var code=new StringBuilder(Files.readString(REPO.resolve("spec-corpus/semantic-model/probe-support.rs"))).append("\nfn main() {\n");
        for(var entry:corpus) {
            var f=entry.getAsJsonObject(); code.append("match ").append(rustModel(f.getAsJsonObject("model"))).append(" { Err(e)=>println!(\"{}\",err(e)), Ok(m)=>{\n");
            if(!f.has("error")) for(var e:f.getAsJsonArray("queries")) {
                var q=e.getAsJsonObject();String expr=switch(text(q,"op")) {
                    case "assign" -> "m.is_assignable(&"+rs(q,"actual")+",&"+rs(q,"target")+").map(|v|json_string(v.name()))";
                    case "visible" -> "m.visible_symbols_at("+q.get("cursor")+").map(|v|strings(v.iter().map(|s|s.id.clone()).collect()))";
                    case "fields" -> "Ok(strings(m.data().types.iter().find(|t|t.id=="+rs(q,"type")+").unwrap().fields.iter().map(|f|format!(\"{}:{}\",f.name,f.type_id)).collect()))";
                    case "expected" -> "m.expected_types(&"+rs(q,"call")+","+q.get("index")+").map(strings)";
                    case "complete" -> "m.complete_argument(&"+rs(q,"call")+","+q.get("index")+","+q.get("cursor")+","+q.get("version")+",&"+rs(q,"prefix")+").map(completions)";
                    default -> throw new AssertionError(q);
                };code.append("println!(\"{}\", ").append(expr).append(".unwrap_or_else(err));\n");
            } else code.append("println!(\"null\");\n");
            code.append("}}\n");
        }
        return code.append("}\n").toString();
    }
}
