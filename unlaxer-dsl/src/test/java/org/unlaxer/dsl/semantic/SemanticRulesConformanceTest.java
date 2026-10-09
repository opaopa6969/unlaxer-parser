package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.Test;
import org.unlaxer.editor.EditorCst;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.codegen.*;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Same authored grammar/JSON schema -> actual generated parsers -> typed queries, with independent oracles. */
public class SemanticRulesConformanceTest {
    private static final Path REPO=Path.of("..").toAbsolutePath().normalize();
    private static final Path CORPUS=REPO.resolve("spec-corpus/semantic-rules");
    private static final Gson JSON=new Gson();
    @Test public void generatedCstAndRulesDriveBothRuntimes() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",Boolean.getBoolean("rustConformance"));
        var original=JsonParser.parseString(Files.readString(CORPUS.resolve("rules.json"))).getAsJsonObject();
        var fixture=JsonParser.parseString(Files.readString(CORPUS.resolve("cases.json"))).getAsJsonObject();
        Path directory=Files.createTempDirectory("semantic-rules-");
        try {
            GrammarDecl grammar=UBNFMapper.parse(Files.readString(CORPUS.resolve("model.ubnf"))).grammars().get(0);
            var settings=new ArrayList<>(grammar.settings());settings.add(new GlobalSetting("package",new StringSettingValue("example.rules")));
            grammar=new GrammarDecl(grammar.name(),grammar.imports(),settings,grammar.tokens(),grammar.rules());
            Path java=Files.createDirectories(directory.resolve("java"));var paths=new ArrayList<String>();
            for(CodeGenerator generator:List.of(new ASTGenerator(),new ParserGenerator(),new MapperGenerator())) {
                var source=generator.generate(grammar);Path path=java.resolve(source.className()+".java");Files.writeString(path,source.source());paths.add(path.toString());
            }
            var arguments=new ArrayList<>(List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",java.toString()));arguments.addAll(paths);
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,arguments.toArray(String[]::new)));
            String evolvedSource=Files.readString(CORPUS.resolve("model.ubnf"))
                .replace("@mapping(ValueDecl, params=[name, type])","@mapping(ValueDeclV2, params=[identifier, type])")
                .replace("ValueDecl ::= 'let' ID @name", "ValueDecl ::= 'let' ID @identifier");
            Path evolvedPath=directory.resolve("evolved.ubnf");Files.writeString(evolvedPath,evolvedSource);
            GrammarDecl evolvedGrammar=UBNFMapper.parse(evolvedSource).grammars().get(0);
            var evolvedSettings=new ArrayList<>(evolvedGrammar.settings());evolvedSettings.add(new GlobalSetting("package",new StringSettingValue("example.evolved")));
            evolvedGrammar=new GrammarDecl(evolvedGrammar.name(),evolvedGrammar.imports(),evolvedSettings,evolvedGrammar.tokens(),evolvedGrammar.rules());
            Path evolvedJava=Files.createDirectories(directory.resolve("evolved-java"));var evolvedPaths=new ArrayList<String>();
            for(CodeGenerator generator:List.of(new ASTGenerator(),new ParserGenerator(),new MapperGenerator())) {
                var file=generator.generate(evolvedGrammar);Path path=evolvedJava.resolve(file.className()+".java");Files.writeString(path,file.source());evolvedPaths.add(path.toString());
            }
            var evolvedArguments=new ArrayList<>(List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",java.toString()));evolvedArguments.addAll(evolvedPaths);
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,evolvedArguments.toArray(String[]::new)));
            String recoveredSource=Files.readString(CORPUS.resolve("model.ubnf"))
                .replace("Call ::= 'call' ID @name", "Call ::= 'call' RecoverName @name");
            recoveredSource=recoveredSource.substring(0,recoveredSource.lastIndexOf('}'))+"  @recovery(sync='f')\n  RecoverName ::= '@' ID;\n}\n";
            Path recoveredPath=directory.resolve("recovered.ubnf");Files.writeString(recoveredPath,recoveredSource);
            GrammarDecl recoveredGrammar=UBNFMapper.parse(recoveredSource).grammars().get(0);
            var recoveredSettings=new ArrayList<>(recoveredGrammar.settings());recoveredSettings.add(new GlobalSetting("package",new StringSettingValue("example.recovered")));
            recoveredGrammar=new GrammarDecl(recoveredGrammar.name(),recoveredGrammar.imports(),recoveredSettings,recoveredGrammar.tokens(),recoveredGrammar.rules());
            Path recoveredJava=Files.createDirectories(directory.resolve("recovered-java"));var recoveredPaths=new ArrayList<String>();
            for(CodeGenerator generator:List.of(new ASTGenerator(),new ParserGenerator(),new MapperGenerator())) {
                var file=generator.generate(recoveredGrammar);Path path=recoveredJava.resolve(file.className()+".java");Files.writeString(path,file.source());recoveredPaths.add(path.toString());
            }
            var recoveredArguments=new ArrayList<>(List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",java.toString()));recoveredArguments.addAll(recoveredPaths);
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,recoveredArguments.toArray(String[]::new)));
            Path src=Files.createDirectories(directory.resolve("src")),generated=Files.createDirectories(src.resolve("generated"));
            var files=new RustBackend().generate(grammar);for(var file:files)Files.writeString(generated.resolve(file.relativePath()),file.content());
            Path target=REPO.resolve("rust/target");
            SemanticModelConformanceTest.run(List.of("cargo","build","--locked","--manifest-path",REPO.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",target.toString()),directory);
            Path nativeOutput=directory.resolve("native");
            SemanticModelConformanceTest.run(List.of(target.resolve("debug/unlaxer").toString(),"generate","--grammar",CORPUS.resolve("model.ubnf").toString(),"--output",nativeOutput.toString()),directory);
            for(var file:files)assertEquals(file.relativePath(),file.content(),Files.readString(nativeOutput.resolve(file.relativePath())));
            Path evolved=Files.createDirectories(src.resolve("evolved"));var evolvedFiles=new RustBackend().generate(evolvedGrammar);
            for(var file:evolvedFiles)Files.writeString(evolved.resolve(file.relativePath()),file.content());
            Path evolvedNative=directory.resolve("native-evolved");
            SemanticModelConformanceTest.run(List.of(target.resolve("debug/unlaxer").toString(),"generate","--grammar",evolvedPath.toString(),"--output",evolvedNative.toString()),directory);
            for(var file:evolvedFiles)assertEquals(file.relativePath(),file.content(),Files.readString(evolvedNative.resolve(file.relativePath())));
            Path recovered=Files.createDirectories(src.resolve("recovered"));var recoveredFiles=new RustBackend().generate(recoveredGrammar);
            for(var file:recoveredFiles)Files.writeString(recovered.resolve(file.relativePath()),file.content());
            Path recoveredNative=directory.resolve("native-recovered");
            SemanticModelConformanceTest.run(List.of(target.resolve("debug/unlaxer").toString(),"generate","--grammar",recoveredPath.toString(),"--output",recoveredNative.toString()),directory);
            for(var file:recoveredFiles)assertEquals(file.relativePath(),file.content(),Files.readString(recoveredNative.resolve(file.relativePath())));
            Files.copy(CORPUS.resolve("probe.rs"),src.resolve("main.rs"));
            StringBuilder manifest=new StringBuilder("[package]\nname=\"semantic-rules-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[dependencies]\nserde_json=\"1\"\n");
            for(String name:List.of("unlaxer-runtime","unlaxer-generator","unlaxer-ubnf"))manifest.append(name).append("={path=\"").append(REPO.resolve("rust").resolve(name)).append("\"}\n");
            Files.writeString(directory.resolve("Cargo.toml"),manifest);
            String output=SemanticModelConformanceTest.run(List.of("cargo","run","--quiet","--offline","--manifest-path",directory.resolve("Cargo.toml").toString(),"--target-dir",target.toString(),"--",CORPUS.toString(),evolvedPath.toString(),recoveredPath.toString()),directory);
            var rust=output.lines().filter(line->line.startsWith("{")).map(JsonParser::parseString).toList();
            var schemaCases=JsonParser.parseString(Files.readString(CORPUS.resolve("schema-cases.json"))).getAsJsonArray();
            assertEquals(fixture.getAsJsonArray("cases").size()+schemaCases.size()+2,rust.size());
            var evidence=new ArrayList<String>(List.of("case\tjava\trust"));
            try(var loader=new URLClassLoader(new java.net.URL[]{java.toUri().toURL()},getClass().getClassLoader())) {
                var parse=loader.loadClass("example.rules.TypedModelMapper").getMethod("parseEditorCst",String.class,List.class,EditorCst.Options.class);int index=0;
                for(JsonElement item:fixture.getAsJsonArray("cases")) {
                    JsonObject test=item.getAsJsonObject(),schema=original.deepCopy();String id=test.get("id").getAsString();
                    String variant=test.has("variant")?test.get("variant").getAsString():"";
                    switch(variant) {
                        case "scopeStart" -> schema.getAsJsonArray("rules").get(2).getAsJsonObject().addProperty("visibility","scopeStart");
                        case "symbolWrong" -> schema.getAsJsonArray("rules").get(2).getAsJsonObject().add("type",JsonParser.parseString("{\"literal\":\"Wrong\"}"));
                        case "parameterWrong" -> schema.getAsJsonArray("rules").get(3).getAsJsonObject().add("parameters",JsonParser.parseString("{\"literal\":\"Wrong\"}"));
                        case "referenceWrong" -> schema.getAsJsonArray("rules").get(7).getAsJsonObject().add("name",JsonParser.parseString("{\"literal\":\"wrong\"}"));
                        case "referenceExpression" -> schema.getAsJsonArray("rules").add(JsonParser.parseString("{\"id\":\"referenceType\",\"node\":\"Reference\",\"emit\":\"expression\",\"type\":{\"literal\":\"Db\"}}"));
                        case "nameParents" -> schema.getAsJsonArray("rules").get(0).getAsJsonObject().add("name",JsonParser.parseString("{\"capture\":\"parents\"}"));
                        default -> {}
                    }
                    var program=SemanticRulesLoader.load(schema.toString(),grammar);var current=SemanticRulesLoader.inventory(grammar);
                    if(variant.equals("inventoryDrift")) {
                        var nodes=new LinkedHashMap<>(current.nodes());var shape=nodes.get("Field");var fields=new HashSet<>(shape.fields());fields.remove("name");nodes.put("Field",new SemanticRules.Shape(shape.captures(),fields));current=new SemanticRules.Inventory(current.grammar(),nodes);
                    }
                    String source=test.get("source").getAsString();List<String> fragments=test.has("fragments")?test.getAsJsonArray("fragments").asList().stream().map(JsonElement::getAsString).toList():List.of("?",")",";","}","a",":","{");
                    EditorCst cst=(EditorCst)parse.invoke(null,source,fragments,EditorCst.Options.defaults());
                    var analysis=SemanticRuleEngine.analyze(program,current,"memory:rules",7,cst);
                    int cursor=test.get("cursor").getAsInt();String prefix=test.get("prefix").getAsString();
                    var query=SemanticRuleEngine.query(analysis,"memory:rules",7,cursor,prefix);
                    JsonObject actual=report(analysis,query);if(Set.of("unknown","partial-argument","wrong-argument").contains(id))actual.add("region",region(program,current,cst,cursor,prefix));assertEquals(id+" Java/Rust",rust.get(index++),actual);
                    for(var entry:test.getAsJsonObject("expected").entrySet())assertEquals(id+" "+entry.getKey(),entry.getValue(),actual.get(entry.getKey()));
                    assertEquals(id,test.get("sourceLength").getAsInt(),source.codePointCount(0,source.length()));assertEquals(id,test.get("utf16Length").getAsInt(),source.length());
                    if(query.isPresent()) {
                        try {SemanticRuleEngine.query(analysis,"memory:rules",6,cursor,"");fail("stale accepted");}catch(IllegalArgumentException error){assertEquals("STALE_SNAPSHOT",error.getMessage());}
                        try {SemanticRuleEngine.query(analysis,"memory:rules",7,cursor,"x".repeat(source.length()+1));fail("oversized prefix accepted");}catch(IllegalArgumentException error){assertEquals("INVALID_PREFIX",error.getMessage());}
                        try {SemanticRuleEngine.query(analysis,"memory:rules",7,source.codePointCount(0,source.length())+1,"");fail("outside cursor accepted");}catch(IllegalArgumentException error){assertEquals("INVALID_CURSOR",error.getMessage());}
                        assertEquals("memory:rules",query.get().resolution().uri());assertEquals(7,query.get().resolution().version());
                    }
                    evidence.add(id+"\t"+actual+"\t"+rust.get(index-1));
                }
            }
            try(var loader=new URLClassLoader(new java.net.URL[]{java.toUri().toURL()},getClass().getClassLoader())) {
                JsonObject changed=original.deepCopy();changed.getAsJsonArray("rules").get(2).getAsJsonObject().getAsJsonObject("name").addProperty("capture","identifier");
                var program=SemanticRulesLoader.load(changed.toString(),evolvedGrammar);var first=fixture.getAsJsonArray("cases").get(0).getAsJsonObject();
                var parse=loader.loadClass("example.evolved.TypedModelMapper").getMethod("parseEditorCst",String.class,List.class,EditorCst.Options.class);
                var cst=(EditorCst)parse.invoke(null,first.get("source").getAsString(),List.of(),EditorCst.Options.defaults());
                var analysis=SemanticRuleEngine.analyze(program,SemanticRulesLoader.inventory(evolvedGrammar),"memory:rules",7,cst);
                var query=SemanticRuleEngine.query(analysis,"memory:rules",7,first.get("cursor").getAsInt(),"").orElseThrow();
                JsonObject actual=JSON.toJsonTree(Map.of("status",analysis.status().name(),"expected",query.expected().stream().map(TypeSystem.TypeRef::name).toList(),"completions",query.completions().stream().map(c->c.value().name()).toList(),"edit",List.of(query.edit().start(),query.edit().end()),"schemaError","UNDEFINED_CAPTURE")).getAsJsonObject();
                try {SemanticRulesLoader.load(original.toString(),evolvedGrammar);fail("old capture silently accepted");}catch(SemanticRules.SchemaException error){assertEquals("UNDEFINED_CAPTURE",error.code());assertEquals("values.name",error.path());}
                for(String key:List.of("status","expected","completions","edit"))assertEquals("evolved "+key,first.getAsJsonObject("expected").get(key),actual.get(key));
                assertEquals("evolved parity",rust.get(rust.size()-2),actual);evidence.add("evolved\t"+actual+"\t"+rust.get(rust.size()-2));
            }
            try(var loader=new URLClassLoader(new java.net.URL[]{java.toUri().toURL()},getClass().getClassLoader())) {
                String source="builtin Int {} fn f(Int):Int; let x:Int; call f(?);";
                var parse=loader.loadClass("example.recovered.TypedModelMapper").getMethod("parseEditorCst",String.class,List.class,EditorCst.Options.class);
                var cst=(EditorCst)parse.invoke(null,source,List.of(),EditorCst.Options.defaults());
                var program=SemanticRulesLoader.load(original.toString(),recoveredGrammar);
                var analysis=SemanticRuleEngine.analyze(program,SemanticRulesLoader.inventory(recoveredGrammar),"memory:rules",7,cst);
                assertEquals(EditorCst.Status.PARTIAL,cst.status());assertTrue(SemanticRuleEngine.query(analysis,"memory:rules",7,source.indexOf('?'),"").isEmpty());
                JsonObject actual=JSON.toJsonTree(Map.of("status",analysis.status().name(),"diagnostics",analysis.diagnostics().stream().map(d->List.of(d.code(),d.span().start(),d.span().end(),d.rule())).toList(),"sites",analysis.sites().size(),"calls",analysis.model().calls().size())).getAsJsonObject();
                assertEquals(JsonParser.parseString("{\"status\":\"PARTIAL\",\"diagnostics\":[[\"INCOMPLETE_BINDING\",46,47,\"calls.name\"]],\"sites\":0,\"calls\":0}"),actual);
                assertEquals("recovered name parity",rust.get(rust.size()-1),actual);evidence.add("recovered-name\t"+actual+"\t"+rust.get(rust.size()-1));
            }
            int schemaIndex=fixture.getAsJsonArray("cases").size();
            for(var item:schemaCases) {
                var test=item.getAsJsonObject();var actual=schemaError(test);
                assertEquals(test.get("id").getAsString()+" schema parity",rust.get(schemaIndex++),actual);
                evidence.add(test.get("id").getAsString()+"\t"+actual+"\t"+rust.get(schemaIndex-1));
            }
            Files.write(Path.of("target/rust-semantic-rules.tsv"),evidence);
        } finally {try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static JsonObject region(SemanticRules.Program program,SemanticRules.Inventory inventory,EditorCst cst,int cursor,String prefix) {
        var host=new org.unlaxer.source.DocumentSnapshot("memory:host",7,"😀{"+cst.source()+(cst.status()==EditorCst.Status.PARTIAL?"":"}"));
        var inner=new org.unlaxer.source.DocumentSnapshot("memory:inner",7,cst.source());
        var language=new org.unlaxer.source.LanguageRegions.Language("typed","example/typed","1","TypedModel","Document");
        var map=new org.unlaxer.source.SegmentSourceMap(inner,List.of(new org.unlaxer.source.SegmentSourceMap.Segment(new org.unlaxer.source.DocumentSnapshot.Span(0,inner.length()),org.unlaxer.source.SegmentSourceMap.Kind.COPY,new org.unlaxer.source.SegmentSourceMap.Location(host,new org.unlaxer.source.DocumentSnapshot.Span(2,2+inner.length())))));
        var region=new org.unlaxer.source.LanguageRegions.Region("inner",null,language,new org.unlaxer.source.DocumentSnapshot.Span(0,host.length()),new org.unlaxer.source.DocumentSnapshot.Span(2,2+inner.length()),map,org.unlaxer.source.LanguageRegions.State.valueOf(cst.status().name()));
        var project=new org.unlaxer.source.LanguageQueries.Project("p",3,Map.of(host.uri(),host),Map.of());
        var provider=new SemanticRuleQueryProvider(program,inventory,language,"p",3,request->cst);
        var layer=new org.unlaxer.source.LanguageQueries(new org.unlaxer.source.LanguageRegions(host,List.of(region)),project,Map.of(language,provider));
        var completion=layer.query(host,project,cursor+2,org.unlaxer.source.LanguageRegions.Operation.COMPLETION,Map.of("prefix",prefix));
        var validation=layer.query(host,project,cursor+2,org.unlaxer.source.LanguageRegions.Operation.VALIDATE,Map.of());
        var otherLanguage=new org.unlaxer.source.LanguageRegions.Language("typed","example/typed","2","TypedModel","Document");
        var otherRegion=new org.unlaxer.source.LanguageRegions.Region("inner",null,otherLanguage,region.full(),region.body(),map,region.parseState());
        try {provider.query(new org.unlaxer.source.LanguageQueries.Request(otherRegion,org.unlaxer.source.LanguageRegions.Operation.COMPLETION,cursor,project,Map.of()));fail("wrong package version accepted");}catch(IllegalArgumentException expected){}
        var oldProject=new org.unlaxer.source.LanguageQueries.Project("p",2,project.documents(),Map.of());
        try {provider.query(new org.unlaxer.source.LanguageQueries.Request(region,org.unlaxer.source.LanguageRegions.Operation.COMPLETION,cursor,oldProject,Map.of()));fail("old project accepted");}catch(IllegalArgumentException expected){}
        assertEquals(org.unlaxer.source.LanguageRegions.State.UNSUPPORTED,layer.query(host,project,cursor+2,org.unlaxer.source.LanguageRegions.Operation.FORMAT,Map.of()).state());
        return JSON.toJsonTree(Map.of("state",completion.state().name(),"completion",mapped(completion),"validation",mapped(validation),"wrongIdentity","REJECTED","oldProject","REJECTED","format","UNSUPPORTED")).getAsJsonObject();
    }
    private static List<?> mapped(org.unlaxer.source.LanguageQueries.Result result) {
        return result.items().stream().map(i->List.of(i.label(),i.detail(),i.locations().stream().map(l->List.of(l.location().snapshot().uri(),l.location().snapshot().version(),l.location().span().start(),l.location().span().end(),l.exact())).toList(),i.edits().stream().map(e->List.of(e.span().start(),e.span().end(),e.replacement())).toList())).toList();
    }
    @Test public void invalidSchemasAreRejectedBeforeExecution() throws Exception {
        for(var item:JsonParser.parseString(Files.readString(CORPUS.resolve("schema-cases.json"))).getAsJsonArray())schemaError(item.getAsJsonObject());
    }
    private static JsonObject schemaError(JsonObject test) throws Exception {
        String source=Files.readString(CORPUS.resolve("model.ubnf"));
        switch(test.get("grammarChange").getAsString()) {
            case "removeField" -> source=source.replace("@mapping(Field, params=[name, type])","@mapping(Field, params=[type])");
            case "addField" -> source=source.replace("@mapping(Field, params=[name, type])","@mapping(Field, params=[name, type, ghost])");
            default -> {}
        }
        var grammar=UBNFMapper.parse(source).grammars().get(0);
        try {SemanticRulesLoader.load(test.get("json").getAsString(),grammar);throw new AssertionError("accepted "+test.get("id"));}
        catch(SemanticRules.SchemaException error) {
            assertEquals(test.get("id").getAsString(),test.get("code").getAsString(),error.code());
            assertEquals(test.get("id").getAsString(),test.get("path").getAsString(),error.path());
            return JSON.toJsonTree(Map.of("code",error.code(),"path",error.path())).getAsJsonObject();
        }
    }
    private static JsonObject report(SemanticRules.Analysis analysis,Optional<SemanticRuleEngine.Query> query) {
        var report=new LinkedHashMap<String,Object>();report.put("status",analysis.status().name());report.put("expected",List.of());report.put("completions",List.of());
        report.put("diagnostics",analysis.diagnostics().stream().map(SemanticRules.Diagnostic::code).toList());
        report.put("diagnosticSpans",analysis.diagnostics().stream().map(d->List.of(d.span().start(),d.span().end(),d.rule(),d.uri(),d.version())).toList());
        report.put("types",List.of());report.put("symbols",List.of());report.put("arguments",List.of());
        var model=analysis.model();
        if(model!=null) {
            report.put("types",model.types().values().stream().map(t->List.of(t.id(),t.span().start(),t.span().end(),t.fields().stream().map(f->List.of(f.name(),f.type(),f.span().start(),f.span().end())).toList())).toList());
            report.put("symbols",model.symbols().values().stream().map(s->List.of(s.name(),s.type(),s.declaration().start(),s.declaration().end(),s.scope(),s.visibleFrom())).toList());
            report.put("arguments",model.calls().values().stream().flatMap(c->c.arguments().stream()).map(a->List.of(a.type(),a.span().start(),a.span().end())).toList());
        }
        query.ifPresent(q->{report.put("expected",q.expected().stream().map(TypeSystem.TypeRef::name).toList());report.put("completions",q.completions().stream().map(c->c.value().name()).toList());
            report.put("decisions",q.completions().stream().map(c->List.of(c.value().name(),c.decision().status().name(),c.decision().rule(),c.value().span().start(),c.value().span().end(),c.value().uri(),c.value().version())).toList());
            report.put("edit",List.of(q.edit().start(),q.edit().end()));report.put("resolution",q.resolution().state().name());});
        return JSON.toJsonTree(report).getAsJsonObject();
    }
}
