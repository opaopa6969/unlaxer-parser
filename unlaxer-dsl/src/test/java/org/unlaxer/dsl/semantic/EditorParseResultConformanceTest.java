package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.unlaxer.dsl.semantic.SemanticModelConformanceTest.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.unlaxer.dsl.semantic.EditorParseResult.*;

public class EditorParseResultConformanceTest {
    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static JsonArray corpus() throws Exception {
        JsonObject file=JsonParser.parseString(Files.readString(REPO.resolve("spec-corpus/editor-result/corpus.json"))).getAsJsonObject();
        JsonArray cases=file.getAsJsonArray("cases");
        for(var entry:cases) {
            JsonObject fixture=entry.getAsJsonObject();
            if(!fixture.get("model").isJsonNull()) {
                JsonObject model=file.getAsJsonObject("modelDefaults").deepCopy();
                fixture.getAsJsonObject("model").entrySet().forEach(value -> model.add(value.getKey(),value.getValue()));
                fixture.add("model",model);
            }
        }
        return cases;
    }
    private static String region(JsonObject object) { return object.get("region").isJsonNull() ? null : text(object,"region"); }
    private static EditorParseResult<String> result(JsonObject fixture) {
        return new EditorParseResult<>(text(fixture,"uri"),fixture.get("version").getAsLong(),text(fixture,"source"),
            Status.valueOf(text(fixture,"status")),fixture.get("ast").isJsonNull()?null:text(fixture,"ast"),
            list(fixture,"nodes",node -> new Node(NodeKind.valueOf(text(node,"kind")),span(node.get("span")),
                strings(node.getAsJsonArray("candidates")),region(node))),
            fixture.get("model").isJsonNull()?null:model(fixture.getAsJsonObject("model")),
            list(fixture,"calls",site -> new CallSite(text(site,"call"),site.get("index").getAsInt(),region(site))));
    }
    private static JsonElement observe(JsonObject fixture) {
        try {
            var result=result(fixture); int cursor=fixture.get("cursor").getAsInt(); String region=region(fixture);
            var selected=result.callAt(cursor,region);
            JsonObject observation=new JsonObject(); observation.addProperty("status",result.status().name());
            observation.add("strictAst",json(result.strictAst().orElse(null)));
            observation.add("nodes",new GsonBuilder().serializeNulls().create().toJsonTree(result.nodes().stream().map(node -> {
                var utf16=result.utf16Span(node.span()); var value=new LinkedHashMap<String,Object>();
                value.put("kind",node.kind().name());value.put("span",List.of(node.span().start(),node.span().end()));
                value.put("utf16Span",List.of(utf16.start(),utf16.end()));value.put("candidates",node.candidateRules());
                value.put("region",node.regionId());return value;
            }).toList()));
            observation.add("visible",json(result.semantics().orElseThrow().visibleSymbolsAt(cursor).stream().map(SemanticModel.Symbol::id).toList()));
            observation.add("call",selected.map(site -> {
                JsonObject call=new JsonObject();call.addProperty("call",site.callId());call.addProperty("index",site.argumentIndex());
                call.add("region",json(site.regionId()));return (JsonElement)call;
            }).orElse(JsonNull.INSTANCE));
            observation.add("expectedTypes",json(result.expectedTypesAt(cursor,region)));
            long snapshotVersion=fixture.has("snapshotVersion")?fixture.get("snapshotVersion").getAsLong():fixture.get("version").getAsLong();
            observation.add("completions",json(result.completeAt(cursor,region,snapshotVersion,text(fixture,"prefix")).stream().map(item -> item.symbol().id()).toList()));
            return observation;
        } catch(EditorException error) {
            return json(Map.of("error",error.code(),"span",List.of(error.span().start(),error.span().end())));
        }
    }
    @Test public void javaMatchesIndependentEditorOracles() throws Exception {
        for(var item:corpus()) {var fixture=item.getAsJsonObject(); assertEquals(text(fixture,"name"),fixture.get("expected"),observe(fixture));}
    }
    @Test public void rustMatchesTheSameIndependentEditorOracles() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] EditorParseResultConformanceTest requires -DrustConformance=true and rustc");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path folder=Files.createTempDirectory("editor-conformance-");
        try {
            Path runtime=folder.resolve("libunlaxer_runtime.rlib");
            run(List.of("rustc","--edition=2021","--crate-name=unlaxer_runtime","--crate-type=rlib",REPO.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),folder);
            var corpus=corpus();Path source=folder.resolve("probe.rs");Files.writeString(source,rustProbe(corpus));
            Path executable=folder.resolve("probe");run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,source.toString(),"-o",executable.toString()),folder);
            var output=run(List.of(executable.toString()),folder).lines().map(JsonParser::parseString).toList();
            assertEquals(corpus.size(),output.size());
            for(int index=0;index<corpus.size();index++) {
                var fixture=corpus.get(index).getAsJsonObject();
                assertEquals(text(fixture,"name"),fixture.get("expected"),output.get(index));
                assertEquals(text(fixture,"name"),observe(fixture),output.get(index));
            }
            Files.createDirectories(Path.of("target"));Files.write(Path.of("target/rust-editor-result.jsonl"),output.stream().map(JsonElement::toString).toList());
        } finally {try(var paths=Files.walk(folder)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    @Test public void completeAndFailedResultsMayOmitSemanticMetadata() {
        var complete=new EditorParseResult<Integer>("memory:complete",0,"",Status.COMPLETE,42,List.of(),null,List.of());
        assertEquals(Integer.valueOf(42),complete.strictAst().orElseThrow());
        var failed=new EditorParseResult<Integer>("memory:failed",0,"",Status.FAILED,null,List.of(),null,List.of());
        assertTrue(failed.strictAst().isEmpty());assertTrue(failed.expectedTypesAt(0,null).isEmpty());
    }
    @Test public void resultOwnsDefectAndCallCollections() {
        var candidates=new ArrayList<>(List.of("Argument"));
        var nodes=new ArrayList<>(List.of(new Node(NodeKind.MISSING,new SemanticModel.Span(0,0),candidates,null)));
        var result=new EditorParseResult<String>("memory:empty",0,"",Status.PARTIAL,null,nodes,null,List.of());
        candidates.clear();nodes.clear();assertEquals(List.of("Argument"),result.nodes().get(0).candidateRules());
        assertThrows(UnsupportedOperationException.class,()->result.nodes().clear());
        assertTrue(result.strictAst().isEmpty());
    }
    private static String rustText(String value) {
        return "String::from(\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\")";
    }
    private static String rustEditorModel(JsonObject object) {
        return rustModel(object).replace(rs(object,"source"),rustText(text(object,"source")));
    }
    private static String rustRegion(JsonObject object) {return object.get("region").isJsonNull()?"None":"Some("+rs(object,"region")+")";}
    private static String rustProbe(JsonArray corpus) throws Exception {
        var code=new StringBuilder(Files.readString(REPO.resolve("spec-corpus/semantic-model/probe-support.rs")))
            .append(Files.readString(REPO.resolve("spec-corpus/editor-result/probe-support.rs"))).append("\nfn main(){\n");
        for(var entry:corpus) {
            var fixture=entry.getAsJsonObject();String status=switch(text(fixture,"status")){case "COMPLETE"->"Complete";case "PARTIAL"->"Partial";default->"Failed";};
            code.append("{let result=EditorParseResult::new(").append(rs(fixture,"uri")).append(',').append(fixture.get("version")).append(',').append(rustText(text(fixture,"source")))
                .append(",Status::").append(status).append(',').append(fixture.get("ast").isJsonNull()?"None":"Some("+rs(fixture,"ast")+")").append(",vec![");
            for(var item:fixture.getAsJsonArray("nodes")) {
                var node=item.getAsJsonObject();code.append("EditorNode{kind:NodeKind::").append(text(node,"kind").equals("MISSING")?"Missing":"Error")
                    .append(",span:").append(rsp(node,"span")).append(",candidate_rules:").append(rv(node.getAsJsonArray("candidates")))
                    .append(",region_id:").append(rustRegion(node)).append("},");
            }
            code.append("],").append(fixture.get("model").isJsonNull()?"None":"Some("+rustEditorModel(fixture.getAsJsonObject("model"))+".unwrap())").append(",vec![");
            for(var item:fixture.getAsJsonArray("calls")) {var site=item.getAsJsonObject();code.append("CallSite{call_id:").append(rs(site,"call"))
                .append(",argument_index:").append(site.get("index")).append(",region_id:").append(rustRegion(site)).append("},");}
            code.append("]);println!(\"{}\",editor_observe(result,").append(fixture.get("cursor")).append(',').append(rustRegion(fixture)).append(',')
                .append(fixture.has("snapshotVersion")?fixture.get("snapshotVersion"):fixture.get("version")).append(',').append(rs(fixture,"prefix")).append("));}\n");
        }
        return code.append("}\n").toString();
    }
}
