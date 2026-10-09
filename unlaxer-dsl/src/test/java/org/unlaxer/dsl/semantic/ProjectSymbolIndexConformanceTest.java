package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.unlaxer.dsl.semantic.SemanticModelConformanceTest.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.unlaxer.dsl.semantic.ProjectSymbolIndex.*;
import org.unlaxer.dsl.semantic.ProjectSymbolIndex.Module;

public class ProjectSymbolIndexConformanceTest {
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static JsonArray corpus() throws Exception {
        JsonObject file = JsonParser.parseString(Files.readString(REPO.resolve("spec-corpus/project-symbols/corpus.json"))).getAsJsonObject();
        JsonArray cases = file.getAsJsonArray("cases");
        for (JsonElement entry : cases) {
            JsonObject fixture = entry.getAsJsonObject();
            JsonObject project = file.getAsJsonObject("project").deepCopy();
            if (fixture.has("overrides")) fixture.getAsJsonObject("overrides").entrySet().forEach(e -> project.add(e.getKey(), e.getValue()));
            fixture.add("project", project);
        }
        return cases;
    }
    static Module module(JsonObject o) {
        return new Module(text(o,"id"),model(o.getAsJsonObject("model")),new HashSet<>(strings(o.getAsJsonArray("exports"))),
            list(o,"imports",i -> new Import(text(i,"name"),new ModuleRef(text(i.getAsJsonObject("target"),"dependency"),text(i.getAsJsonObject("target"),"module")),
                text(i,"symbol"),text(i,"scope"),span(i.get("span")),i.get("visibleFrom").getAsInt())));
    }
    static ProjectSymbolIndex project(JsonObject o) {
        return new ProjectSymbolIndex(text(o,"id"),o.get("version").getAsLong(),list(o,"modules",ProjectSymbolIndexConformanceTest::module),
            list(o,"dependencies",d -> new Dependency(text(d,"id"),text(d,"version"),text(d,"sha256"),list(d,"modules",ProjectSymbolIndexConformanceTest::module))));
    }
    private static JsonElement normalized(JsonElement e) {
        if (e.isJsonArray()) { JsonArray result = new JsonArray(); e.getAsJsonArray().forEach(v -> result.add(normalized(v))); return result; }
        if (!e.isJsonObject()) return e;
        JsonObject o = e.getAsJsonObject();
        if (o.size()==2 && o.has("start") && o.has("end")) return json(List.of(o.get("start").getAsInt(),o.get("end").getAsInt()));
        JsonObject result = new JsonObject(); o.entrySet().forEach(v -> result.add(v.getKey(),normalized(v.getValue()))); return result;
    }
    private static JsonElement value(Object o) { return normalized(json(o)); }
    private static JsonElement binding(Binding b) { return value(Map.of("name",b.name(),"status",b.status(),"candidates",b.candidates(),"diagnostics",b.diagnostics())); }
    private static JsonElement error(ProjectException e) { return value(Map.of("error",e.diagnostic())); }
    private static JsonElement query(ProjectSymbolIndex p,JsonObject q) {
        return switch(text(q,"op")) {
            case "resolve" -> binding(p.resolve(text(q,"module"),q.get("projectVersion").getAsLong(),q.get("documentVersion").getAsLong(),q.get("cursor").getAsInt(),text(q,"name")));
            case "complete" -> value(p.complete(text(q,"module"),q.get("projectVersion").getAsLong(),q.get("documentVersion").getAsLong(),q.get("cursor").getAsInt(),text(q,"prefix"),text(q,"expectedType")));
            case "diagnostics" -> value(p.diagnostics());
            case "assign" -> value(p.isAssignable(text(q,"actual"),text(q,"expectedType")));
            default -> throw new AssertionError(q);
        };
    }
    private static List<JsonElement> javaResults(JsonArray cases) {
        List<JsonElement> result = new ArrayList<>();
        for (JsonElement entry : cases) {
            JsonObject f = entry.getAsJsonObject();
            try {
                ProjectSymbolIndex p = project(f.getAsJsonObject("project"));
                ProjectSymbolIndex original = p;
                assertFalse(text(f,"name"),f.has("error"));
                for (JsonElement element : f.getAsJsonArray("queries")) {
                    JsonObject q = element.getAsJsonObject();
                    try {
                        if (text(q,"op").equals("replace")) {
                            p = p.withModules(q.get("version").getAsLong(),list(q,"modules",ProjectSymbolIndexConformanceTest::module));
                            result.add(value(p.version()));
                        } else result.add(query(q.has("original") ? original : p,q));
                    } catch (ProjectException e) { result.add(error(e)); }
                    assertEquals(text(f,"name")+" "+q.get("op"),q.get("expected"),result.get(result.size()-1));
                }
            } catch (ProjectException e) { assertEquals(text(f,"name"),f.get("error"),error(e)); result.add(error(e)); }
        }
        return result;
    }
    private static List<JsonElement> expected(JsonArray cases) {
        List<JsonElement> result = new ArrayList<>();
        for (JsonElement e : cases) { JsonObject f=e.getAsJsonObject(); if(f.has("error")) result.add(f.get("error")); else f.getAsJsonArray("queries").forEach(q -> result.add(q.getAsJsonObject().get("expected"))); }
        return result;
    }
    @Test public void javaMatchesIndependentOracles() throws Exception { JsonArray cases=corpus(); assertEquals(expected(cases),javaResults(cases)); }
    @Test public void inputCollectionsAndSnapshotsAreOwned() throws Exception {
        ProjectSymbolIndex p=project(corpus().get(0).getAsJsonObject().getAsJsonObject("project"));
        List<Module> modules=new ArrayList<>(p.modules()); List<Dependency> deps=new ArrayList<>(p.dependencies());
        ProjectSymbolIndex copy=new ProjectSymbolIndex(p.project(),p.version(),modules,deps); modules.clear(); deps.clear();
        assertEquals(2,copy.modules().size()); assertEquals(1,copy.dependencies().size());
        assertThrows(UnsupportedOperationException.class,()->copy.modules().clear());
        assertThrows(UnsupportedOperationException.class,()->copy.modules().get(0).imports().clear());
        assertThrows(UnsupportedOperationException.class,()->copy.dependencies().get(0).modules().clear());
    }
    @Test public void rustMatchesTheSameIndependentOracles() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)",Boolean.getBoolean("rustConformance"));
        JsonArray cases=corpus(); Path folder=Files.createTempDirectory("project-symbols-");
        try {
            Path runtime=folder.resolve("libunlaxer_runtime.rlib");
            run(List.of("rustc","--edition=2021","--crate-name=unlaxer_runtime","--crate-type=rlib",REPO.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),folder);
            Path source=folder.resolve("probe.rs"); Files.writeString(source,rustProbe(cases));
            Path executable=folder.resolve("probe"); run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,source.toString(),"-o",executable.toString()),folder);
            List<JsonElement> output=run(List.of(executable.toString()),folder).lines().map(JsonParser::parseString).toList();
            assertEquals(expected(cases),output); assertEquals(javaResults(cases),output);
            List<String> report=new ArrayList<>(List.of("observation\texpected\tjava\trust")); List<JsonElement> java=javaResults(cases), oracle=expected(cases);
            for(int i=0;i<output.size();i++) report.add(i+"\t"+oracle.get(i)+"\t"+java.get(i)+"\t"+output.get(i));
            Files.createDirectories(Path.of("target")); Files.write(Path.of("target/rust-project-symbols.tsv"),report);
        } finally { try(var paths=Files.walk(folder)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); } }
    }
    private static String rustModule(JsonObject o) {
        StringBuilder code=new StringBuilder("p::Module {id:"+rs(o,"id")+",model:"+rustModel(o.getAsJsonObject("model"))+".unwrap(),exports:"+rv(o.getAsJsonArray("exports"))+".into_iter().collect(),imports:vec![");
        for(JsonElement e:o.getAsJsonArray("imports")) {
            JsonObject i=e.getAsJsonObject(),t=i.getAsJsonObject("target");
            code.append("p::Import {name:").append(rs(i,"name")).append(",target:p::ModuleRef {dependency:").append(rs(t,"dependency")).append(",module:").append(rs(t,"module"))
                .append("},symbol:").append(rs(i,"symbol")).append(",scope:").append(rs(i,"scope")).append(",span:").append(rsp(i,"span")).append(",visible_from:").append(i.get("visibleFrom")).append("},");
        }
        return code.append("]}").toString();
    }
    private static String rustModules(JsonObject o) { return "vec!["+String.join(",",o.getAsJsonArray("modules").asList().stream().map(e->rustModule(e.getAsJsonObject())).toList())+"]"; }
    static String rustProject(JsonObject o) {
        StringBuilder code=new StringBuilder("p::ProjectSymbolIndex::new("+rs(o,"id")+","+o.get("version")+","+rustModules(o)+",vec![");
        for(JsonElement e:o.getAsJsonArray("dependencies")) { JsonObject d=e.getAsJsonObject(); code.append("p::Dependency {id:").append(rs(d,"id")).append(",version:").append(rs(d,"version")).append(",sha256:").append(rs(d,"sha256")).append(",modules:").append(rustModules(d)).append("},"); }
        return code.append("])").toString();
    }
    private static String rustProbe(JsonArray cases) throws Exception {
        StringBuilder code=new StringBuilder(Files.readString(REPO.resolve("spec-corpus/project-symbols/probe-support.rs"))).append("\nfn main() {\n");
        for(JsonElement entry:cases) {
            JsonObject f=entry.getAsJsonObject(); code.append("match ").append(rustProject(f.getAsJsonObject("project"))).append(" {Err(e)=>println!(\"{}\",error(e)),Ok(mut current)=>{ let original=current.clone();\n");
            if(f.has("error")) code.append("println!(\"null\");\n");
            else for(JsonElement e:f.getAsJsonArray("queries")) {
                JsonObject q=e.getAsJsonObject(); code.append("{let p=&").append(q.has("original")?"original":"current").append(";\n");
                String args=q.has("module")?"&"+rs(q,"module")+","+q.get("projectVersion")+","+q.get("documentVersion")+","+q.get("cursor"):"";
                String expression=switch(text(q,"op")) {
                    case "resolve" -> "p.resolve("+args+",&"+rs(q,"name")+").map(binding)";
                    case "complete" -> "p.complete("+args+",&"+rs(q,"prefix")+",&"+rs(q,"expectedType")+").map(|v|array(v.iter().map(completion)))";
                    case "diagnostics" -> "Ok(array(p.diagnostics().iter().map(diagnostic)))";
                    case "assign" -> "p.is_assignable(&"+rs(q,"actual")+",&"+rs(q,"expectedType")+").map(|v|json_string(v.name()))";
                    case "replace" -> "p.with_modules("+q.get("version")+","+rustModules(q)+").map(|next|{current=next;current.version().to_string()})";
                    default -> throw new AssertionError(q);
                };
                code.append("println!(\"{}\",").append(expression).append(".unwrap_or_else(error));}\n");
            }
            code.append("}}\n");
        }
        return code.append("}\n").toString();
    }
}
