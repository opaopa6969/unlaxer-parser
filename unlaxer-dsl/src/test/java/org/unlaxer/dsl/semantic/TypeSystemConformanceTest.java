package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.unlaxer.dsl.semantic.SemanticModelConformanceTest.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import org.unlaxer.dsl.semantic.TypeSystem.*;

public class TypeSystemConformanceTest {
    private static final Path REPO=Path.of("..").toAbsolutePath().normalize();
    private static JsonObject corpus() throws Exception { return JsonParser.parseString(Files.readString(REPO.resolve("spec-corpus/type-relations/corpus.json"))).getAsJsonObject(); }
    static TypeRef type(JsonElement e) {
        if(e.isJsonPrimitive()) {
            String s=e.getAsString();
            return s.equals("?")?TypeRef.unknown():s.equals("null")?new TypeRef(Kind.NULL,"",List.of()):TypeRef.named(s);
        }
        JsonObject o=e.getAsJsonObject();
        if(o.has("var")) return TypeRef.variable(text(o,"var"));
        for(String name:List.of("named","alias")) if(o.has(name)) return new TypeRef(Kind.valueOf(name.toUpperCase(Locale.ROOT)),text(o,name),types(o.getAsJsonArray("args")));
        if(o.has("nullable")) return new TypeRef(Kind.NULLABLE,"",List.of(type(o.get("nullable"))));
        for(String name:List.of("union","intersection","function")) if(o.has(name)) return new TypeRef(Kind.valueOf(name.toUpperCase(Locale.ROOT)),"",types(o.getAsJsonArray(name)));
        throw new AssertionError(e);
    }
    private static List<TypeRef> types(JsonArray a) { return a.asList().stream().map(TypeSystemConformanceTest::type).toList(); }
    private static Map<String,TypeRef> bindings(JsonObject o) { Map<String,TypeRef> m=new LinkedHashMap<>(); o.entrySet().forEach(e->m.put(e.getKey(),type(e.getValue()))); return m; }
    private static int limit(JsonObject q) { return q.has("limit")?q.get("limit").getAsInt():256; }
    private static Set<Capability> capabilities(JsonObject q) {
        Set<Capability> caps=EnumSet.allOf(Capability.class);
        if(q.has("omit")) strings(q.getAsJsonArray("omit")).forEach(s->caps.remove(Capability.valueOf(s))); return caps;
    }
    static TypeSystem system(JsonObject c,JsonObject q) {
        List<Definition> defs=list(c,"definitions",d->new Definition(text(d,"name"),strings(d.getAsJsonArray("parameters")),strings(d.getAsJsonArray("variance")).stream().map(Variance::valueOf).toList(),types(d.getAsJsonArray("parents")),bindings(d.getAsJsonObject("fields")),d.get("structural").getAsBoolean()));
        List<Alias> aliases=list(c,"aliases",a->new Alias(text(a,"name"),strings(a.getAsJsonArray("parameters")),type(a.get("body"))));
        return new TypeSystem(new DeclaredProvider(q.has("policy")?Policy.valueOf(text(q,"policy")):Policy.NOMINAL,defs,capabilities(q)),aliases,limit(q));
    }
    static String render(TypeRef t) { return t.kind()+"("+t.name()+(t.arguments().isEmpty()?"":","+String.join(",",t.arguments().stream().map(TypeSystemConformanceTest::render).toList()))+")"; }
    private static void leaves(Decision d,List<String> result) { if(d.evidence().isEmpty()) result.add(d.rule()); else d.evidence().forEach(c->leaves(c,result)); }
    private static JsonElement decision(Decision d) { List<String> leaves=new ArrayList<>(); leaves(d,leaves); return json(Map.of("status",d.status().name(),"rule",d.rule(),"leaves",leaves)); }
    private static JsonElement query(JsonObject c,JsonObject q) {
        try {
            if(text(q,"op").equals("substitute")) return json(render(TypeSystem.substitute(type(q.get("type")),bindings(q.getAsJsonObject("bindings")),limit(q))));
            return decision(system(c,q).compare(type(q.get("actual")),type(q.get("expectedType"))));
        } catch(TypeException e) { return decision(Decision.of(e.status(),e.getMessage())); }
    }
    private static List<JsonElement> expected(JsonObject c) { return c.getAsJsonArray("cases").asList().stream().map(e->e.getAsJsonObject().get("expected")).toList(); }
    private static List<JsonElement> results(JsonObject c) {
        List<JsonElement> results=new ArrayList<>();
        for(JsonElement e:c.getAsJsonArray("cases")) { JsonObject q=e.getAsJsonObject(); JsonElement result=query(c,q); assertEquals(text(q,"name"),q.get("expected"),result); results.add(result); }
        return results;
    }
    @Test public void javaMatchesIndependentOracles() throws Exception { JsonObject c=corpus(); assertEquals(expected(c),results(c)); }
    @Test public void malformedShapesAndInputMutationAreRejected() {
        assertThrows(IllegalArgumentException.class,()->TypeRef.named(""));
        assertThrows(IllegalArgumentException.class,()->new TypeRef(Kind.UNKNOWN,"X",List.of()));
        assertThrows(IllegalArgumentException.class,()->new TypeRef(Kind.NULLABLE,"",List.of()));
        assertThrows(IllegalArgumentException.class,()->new TypeRef(Kind.FUNCTION,"",List.of()));
        List<TypeRef> arguments=new ArrayList<>(List.of(TypeRef.named("Int")));
        TypeRef t=new TypeRef(Kind.NAMED,"Box",arguments); arguments.clear(); assertEquals(1,t.arguments().size());
        assertThrows(UnsupportedOperationException.class,()->t.arguments().clear());
        TypeRef deep=TypeRef.named("Int"); for(int i=0;i<150;i++) deep=new TypeRef(Kind.NULLABLE,"",List.of(deep));
        TypeRef finalDeep=deep; assertEquals(Status.LIMIT,assertThrows(TypeException.class,()->TypeSystem.substitute(finalDeep,Map.of(),4096)).status());
    }
    @Test public void deeplyNestedRelationsReturnALimit() {
        List<Definition> definitions=new ArrayList<>();
        for(int i=0;i<256;i++) definitions.add(new Definition("T"+i,List.of(),List.of(),i==255?List.of():List.of(TypeRef.named("T"+(i+1))),Map.of(),false));
        TypeSystem system=new TypeSystem(new DeclaredProvider(Policy.NOMINAL,definitions,Set.of(Capability.NAMED)),List.of(),4096);
        assertEquals(Status.LIMIT,system.compare(TypeRef.named("T0"),TypeRef.named("T255")).status());
    }
    @Test public void rustMatchesSameIndependentOracles() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)",Boolean.getBoolean("rustConformance"));
        JsonObject c=corpus(); Path folder=Files.createTempDirectory("type-relations-");
        try {
            Path runtime=folder.resolve("libunlaxer_runtime.rlib");
            run(List.of("rustc","--edition=2021","--crate-name=unlaxer_runtime","--crate-type=rlib",REPO.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),folder);
            Path source=folder.resolve("probe.rs"); Files.writeString(source,probe(c));
            Path executable=folder.resolve("probe"); run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,source.toString(),"-o",executable.toString()),folder);
            List<JsonElement> output=run(List.of(executable.toString()),folder).lines().map(JsonParser::parseString).toList();
            assertEquals(expected(c),output); assertEquals(results(c),output);
            List<String> report=new ArrayList<>(List.of("case\texpected\tjava\trust")); List<JsonElement> java=results(c),oracle=expected(c);
            for(int i=0;i<output.size();i++) report.add(text(c.getAsJsonArray("cases").get(i).getAsJsonObject(),"name")+"\t"+oracle.get(i)+"\t"+java.get(i)+"\t"+output.get(i));
            Files.createDirectories(Path.of("target")); Files.write(Path.of("target/rust-type-relations.tsv"),report);
        } finally { try(var paths=Files.walk(folder)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); } }
    }
    private static String variant(Enum<?> e) { return e.name().charAt(0)+e.name().substring(1).toLowerCase(Locale.ROOT); }
    static String rt(TypeRef t) { return "TypeRef::new(Kind::"+variant(t.kind())+","+r(t.name())+",vec!["+String.join(",",t.arguments().stream().map(TypeSystemConformanceTest::rt).toList())+"]).unwrap()"; }
    static String rtypes(List<TypeRef> ts) { return "vec!["+String.join(",",ts.stream().map(TypeSystemConformanceTest::rt).toList())+"]"; }
    private static String rm(Map<String,TypeRef> m) { return "vec!["+String.join(",",m.entrySet().stream().map(e->"("+r(e.getKey())+","+rt(e.getValue())+")").toList())+"].into_iter().collect()"; }
    static String rsystem(JsonObject c,JsonObject q) {
        StringBuilder code=new StringBuilder("TypeSystem::new(DeclaredProvider::new(Policy::"+variant(q.has("policy")?Policy.valueOf(text(q,"policy")):Policy.NOMINAL)+",vec![");
        for(JsonElement e:c.getAsJsonArray("definitions")) {
            JsonObject d=e.getAsJsonObject(); code.append("Definition {name:").append(rs(d,"name")).append(",parameters:").append(rv(d.getAsJsonArray("parameters"))).append(",variance:vec![")
                .append(String.join(",",strings(d.getAsJsonArray("variance")).stream().map(v->"Variance::"+variant(Variance.valueOf(v))).toList())).append("],parents:")
                .append(rtypes(types(d.getAsJsonArray("parents")))).append(",fields:").append(rm(bindings(d.getAsJsonObject("fields")))).append(",structural:").append(d.get("structural")).append("},");
        }
        code.append("],vec![").append(String.join(",",capabilities(q).stream().map(cap->"Capability::"+variant(cap)).toList())).append("].into_iter().collect()).unwrap(),vec![");
        for(JsonElement e:c.getAsJsonArray("aliases")) { JsonObject a=e.getAsJsonObject(); code.append("Alias {name:").append(rs(a,"name")).append(",parameters:").append(rv(a.getAsJsonArray("parameters"))).append(",body:").append(rt(type(a.get("body")))).append("},"); }
        return code.append("],").append(limit(q)).append(").unwrap()").toString();
    }
    private static String probe(JsonObject c) throws Exception {
        StringBuilder code=new StringBuilder(Files.readString(REPO.resolve("spec-corpus/type-relations/probe-support.rs"))).append("\nfn main(){ shape_checks();\n");
        for(JsonElement e:c.getAsJsonArray("cases")) {
            JsonObject q=e.getAsJsonObject();
            if(text(q,"op").equals("substitute")) code.append("println!(\"{}\",substitute(&").append(rt(type(q.get("type")))).append(",&").append(rm(bindings(q.getAsJsonObject("bindings")))).append(",").append(limit(q)).append(").map(|t|json_string(&render(&t))).unwrap_or_else(|e|decision(e.decision())));\n");
            else code.append("{ let system=").append(rsystem(c,q)).append(";println!(\"{}\",decision(system.compare(&").append(rt(type(q.get("actual")))).append(",&").append(rt(type(q.get("expectedType")))).append(")));}\n");
        }
        return code.append("}\n").toString();
    }
}
