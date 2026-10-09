package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.unlaxer.dsl.semantic.SemanticModelConformanceTest.*;
import static org.unlaxer.dsl.semantic.TypeSystemConformanceTest.type;
import static org.unlaxer.dsl.semantic.TypeSystemConformanceTest.render;
import static org.unlaxer.dsl.semantic.TypeSystemConformanceTest.rt;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.unlaxer.dsl.semantic.TypeSystem.*;
import org.unlaxer.dsl.semantic.CallInference.*;

public class CallInferenceConformanceTest {
    private static final Path REPO=Path.of("..").toAbsolutePath().normalize();
    private static JsonObject read(String path) throws Exception { return JsonParser.parseString(Files.readString(REPO.resolve(path))).getAsJsonObject(); }
    private static JsonObject corpus() throws Exception { return read("spec-corpus/call-inference/corpus.json"); }
    private static JsonObject typeCorpus() throws Exception { return read("spec-corpus/type-relations/corpus.json"); }
    private static int budget(JsonObject q) { return q.has("inferLimit")?q.get("inferLimit").getAsInt():256; }
    private static List<JsonObject> signatureObjects(JsonObject corpus,JsonObject q) {
        List<JsonObject> result=new ArrayList<>();
        for(String id:strings(q.getAsJsonArray("signatures"))) result.add(corpus.getAsJsonArray("signatures").asList().stream().map(JsonElement::getAsJsonObject).filter(s->text(s,"id").equals(id)).findFirst().orElseThrow());
        return result;
    }
    private static Signature signature(JsonObject s) {
        return new Signature(text(s,"id"),list(s,"variables",v->new Parameter(text(v,"id"),type(v.get("bound")))),
            s.getAsJsonArray("parameters").asList().stream().map(TypeSystemConformanceTest::type).toList(),type(s.get("result")),s.get("varargs").getAsBoolean(),text(s,"uri"),s.get("version").getAsLong(),span(s.get("span")));
    }
    private static Call call(JsonObject c) { return new Call(text(c,"uri"),c.get("version").getAsLong(),span(c.get("span")),list(c,"arguments",a->new Argument(type(a.get("type")),span(a.get("span")))),type(c.get("expectedReturn"))); }
    private static List<Integer> position(SemanticModel.Span span) { return List.of(span.start(),span.end()); }
    private static JsonElement resolution(Resolution r) {
        List<Object> candidates=new ArrayList<>();
        for(Candidate c:r.candidates()) {
            Map<String,String> substitution=new TreeMap<>(); c.substitution().forEach((k,v)->substitution.put(k,render(v)));
            candidates.add(Map.of("signature",c.signature(),"status",c.status().name(),"substitution",substitution,"parameters",c.parameters().stream().map(TypeSystemConformanceTest::render).toList(),
                "result",render(c.result()),"varargs",c.varargs(),"uri",c.uri(),"version",c.version(),"span",position(c.span()),"constraints",c.constraints().stream().map(v->Map.of("role",v.role(),"status",v.decision().status().name(),"uri",v.uri(),"version",v.version(),"span",position(v.span()))).toList()));
        }
        return json(Map.of("state",r.state().name(),"uri",r.uri(),"version",r.version(),"candidates",candidates));
    }
    private static List<JsonElement> javaResults(JsonObject corpus,JsonObject types) {
        List<JsonElement> result=new ArrayList<>();
        for(JsonElement element:corpus.getAsJsonArray("cases")) {
            JsonObject q=element.getAsJsonObject(); CallInference inference=new CallInference(TypeSystemConformanceTest.system(types,q),budget(q));
            List<Signature> signatures=signatureObjects(corpus,q).stream().map(CallInferenceConformanceTest::signature).toList();
            Call call=call(q.getAsJsonObject("call")); int index=q.has("index")?q.get("index").getAsInt():0;
            Resolution r=q.has("op")?inference.expectedArgument(signatures,call,index):inference.infer(signatures,call);
            JsonElement actual=resolution(r); assertEquals(text(q,"name"),q.get("expected"),actual); result.add(actual);
            if(q.has("expectedTypes")) {
                JsonElement expected=json(inference.expectedTypes(r,index).stream().map(TypeSystemConformanceTest::render).toList());
                assertEquals(text(q,"name"),q.get("expectedTypes"),expected);result.add(expected);
            }
            if(q.has("values")) {
                List<Value> values=list(q,"values",v->new Value(text(v,"id"),text(v,"name"),type(v.get("type")),text(v,"uri"),v.get("version").getAsLong(),span(v.get("span"))));
                List<Completion> completions=inference.complete(values,r,index);
                if(Set.of(State.UNSUPPORTED,State.CYCLE,State.LIMIT,State.INVALID).contains(r.state())) for(Value value:values) {
                    var assessment=inference.assess(r,index,value.type());assertEquals("INFERENCE_FAILURE",assessment.rule());
                    assertEquals(r.candidates().stream().flatMap(candidate->candidate.constraints().stream()).map(Constraint::decision).toList(),assessment.evidence());
                }
                for(Completion completion:completions) assertEquals(inference.assess(r,index,completion.value().type()),completion.decision());
                JsonElement items=json(completions.stream().map(c->Map.of("id",c.value().id(),"name",c.value().name(),"type",render(c.value().type()),"uri",c.value().uri(),"version",c.value().version(),"span",position(c.value().span()),"status",c.decision().status().name(),"rule",c.decision().rule())).toList());
                assertEquals(text(q,"name"),q.get("completionExpected"),items);result.add(items);
                JsonElement assessments=json(values.stream().map(v->inference.assess(r,index,v.type()).status().name()).toList());
                assertEquals(text(q,"name"),q.get("assessmentExpected"),assessments);result.add(assessments);
            }
        }
        return result;
    }
    private static List<JsonElement> expected(JsonObject corpus) {
        List<JsonElement> result=new ArrayList<>();
        for(JsonElement e:corpus.getAsJsonArray("cases")) {
            JsonObject q=e.getAsJsonObject();for(String key:List.of("expected","expectedTypes","completionExpected","assessmentExpected")) if(q.has(key)) result.add(q.get(key));
        }
        return result;
    }
    @Test public void javaMatchesIndependentOracles() throws Exception { JsonObject c=corpus(); assertEquals(expected(c),javaResults(c,typeCorpus())); }
    @Test public void rustMatchesSameIndependentOracles() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)",Boolean.getBoolean("rustConformance"));
        JsonObject c=corpus(),types=typeCorpus(); Path folder=Files.createTempDirectory("call-inference-");
        try {
            Path runtime=folder.resolve("libunlaxer_runtime.rlib");
            run(List.of("rustc","--edition=2021","--crate-name=unlaxer_runtime","--crate-type=rlib",REPO.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),folder);
            Path source=folder.resolve("probe.rs"); Files.writeString(source,probe(c,types));
            Path executable=folder.resolve("probe"); run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,source.toString(),"-o",executable.toString()),folder);
            List<JsonElement> output=run(List.of(executable.toString()),folder).lines().map(JsonParser::parseString).toList();
            assertEquals(expected(c),output); assertEquals(javaResults(c,types),output);
            List<String> report=new ArrayList<>(List.of("observation\texpected\tjava\trust")); List<JsonElement> java=javaResults(c,types),oracle=expected(c);
            for(int i=0;i<output.size();i++) report.add(i+"\t"+oracle.get(i)+"\t"+java.get(i)+"\t"+output.get(i));
            Files.createDirectories(Path.of("target")); Files.write(Path.of("target/rust-call-inference.tsv"),report);
        } finally { try(var paths=Files.walk(folder)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); } }
    }
    private static String rsignature(JsonObject s) {
        StringBuilder code=new StringBuilder("ci::Signature {id:"+rs(s,"id")+",variables:vec![");
        for(JsonElement e:s.getAsJsonArray("variables")) {JsonObject v=e.getAsJsonObject();code.append("ci::Parameter {id:").append(rs(v,"id")).append(",bound:").append(rt(type(v.get("bound")))).append("},");}
        return code.append("],parameters:vec![").append(String.join(",",s.getAsJsonArray("parameters").asList().stream().map(e->rt(type(e))).toList())).append("],result:").append(rt(type(s.get("result")))).append(",varargs:").append(s.get("varargs")).append(",uri:").append(rs(s,"uri")).append(",version:").append(s.get("version")).append(",span:").append(rsp(s,"span")).append("}").toString();
    }
    private static String rcall(JsonObject c) {
        StringBuilder code=new StringBuilder("ci::Call {uri:"+rs(c,"uri")+",version:"+c.get("version")+",span:"+rsp(c,"span")+",arguments:vec![");
        for(JsonElement e:c.getAsJsonArray("arguments")) {JsonObject a=e.getAsJsonObject();code.append("ci::Argument {type_ref:").append(rt(type(a.get("type")))).append(",span:").append(rsp(a,"span")).append("},");}
        return code.append("],expected_return:").append(rt(type(c.get("expectedReturn")))).append("}").toString();
    }
    private static String probe(JsonObject c,JsonObject types) throws Exception {
        StringBuilder code=new StringBuilder(Files.readString(REPO.resolve("spec-corpus/type-relations/probe-support.rs"))).append(Files.readString(REPO.resolve("spec-corpus/call-inference/probe-support.rs"))).append("\nfn main(){\n");
        for(JsonElement e:c.getAsJsonArray("cases")) {
            JsonObject q=e.getAsJsonObject();int index=q.has("index")?q.get("index").getAsInt():0;
            code.append("{let types=").append(TypeSystemConformanceTest.rsystem(types,q)).append(";let inference=ci::CallInference::new(&types,").append(budget(q)).append(").unwrap();let signatures=vec![")
                .append(String.join(",",signatureObjects(c,q).stream().map(CallInferenceConformanceTest::rsignature).toList())).append("];let call=").append(rcall(q.getAsJsonObject("call"))).append(";let r=inference.")
                .append(q.has("op")?"expected_argument(&signatures,&call,"+index+")":"infer(&signatures,&call)").append(".unwrap();println!(\"{}\",resolution(&r));\n");
            if(q.has("expectedTypes")) code.append("println!(\"{}\",arr(inference.expected_types(&r,").append(index).append(").iter().map(|t|json_string(&render(t)))));\n");
            if(q.has("values")) {
                code.append("let values=vec![");for(JsonElement v:q.getAsJsonArray("values")) {JsonObject value=v.getAsJsonObject();code.append("ci::Value {id:").append(rs(value,"id")).append(",name:").append(rs(value,"name")).append(",type_ref:").append(rt(type(value.get("type")))).append(",uri:").append(rs(value,"uri")).append(",version:").append(value.get("version")).append(",span:").append(rsp(value,"span")).append("},");}
                code.append("];let items=inference.complete(&values,&r,").append(index).append(");for c in &items {assert_eq!(c.decision,inference.assess(&r,").append(index).append(",&c.value.type_ref));}\n")
                    .append("println!(\"{}\",arr(items.iter().map(completion)));println!(\"{}\",arr(values.iter().map(|v|json_string(inference.assess(&r,").append(index).append(",&v.type_ref).status.name()))));\n");
                if(Set.of("UNSUPPORTED","CYCLE","LIMIT","INVALID").contains(text(q.getAsJsonObject("expected"),"state")))
                    code.append("for value in &values {let assessment=inference.assess(&r,").append(index).append(",&value.type_ref);assert_eq!(assessment.rule,\"INFERENCE_FAILURE\");assert_eq!(assessment.evidence,r.candidates.iter().flat_map(|candidate|candidate.constraints.iter().map(|c|c.decision.clone())).collect::<Vec<_>>());}\n");
            }
            code.append("}\n");
        }
        return code.append("}\n").toString();
    }
}
