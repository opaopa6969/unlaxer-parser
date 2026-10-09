package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import javax.tools.ToolProvider;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.codegen.*;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.editor.EditorCst;
import org.unlaxer.pipeline.AnalysisPipeline;
import org.unlaxer.source.DocumentSnapshot;

/** Real expansion -> generated parser -> portable semantic model, with original-document oracles. */
public class PreprocessingConformanceTest {
    private static final Path REPO=Path.of("..").toAbsolutePath().normalize();
    private static final Gson JSON=new Gson();
    @Test public void generatedSemanticPipelinePreservesOriginalFilesAndConditionalStates() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",Boolean.getBoolean("rustConformance"));
        Path directory=Files.createTempDirectory("preprocessing-conformance-");
        try {
            GrammarDecl grammar=UBNFMapper.parse(Files.readString(REPO.resolve("spec-corpus/semantic-rules/model.ubnf"))).grammars().get(0);
            var settings=new ArrayList<>(grammar.settings());settings.add(new GlobalSetting("package",new StringSettingValue("example.generated")));
            grammar=new GrammarDecl(grammar.name(),grammar.imports(),settings,grammar.tokens(),grammar.rules());
            Path java=Files.createDirectories(directory.resolve("java"));var files=new ArrayList<String>();
            for(CodeGenerator generator:List.of(new ASTGenerator(),new ParserGenerator(),new MapperGenerator())) {
                var source=generator.generate(grammar);Path file=java.resolve(source.className()+".java");Files.writeString(file,source.source());files.add(file.toString());
            }
            files.add(REPO.resolve("examples/preprocessing/PreprocessingExample.java").toString());
            var arguments=new ArrayList<>(List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",java.toString()));arguments.addAll(files);
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,arguments.toArray(String[]::new)));
            Path src=Files.createDirectories(directory.resolve("src")),generated=Files.createDirectories(src.resolve("generated"));
            var rustFiles=new RustBackend().generate(grammar);for(var file:rustFiles)Files.writeString(generated.resolve(file.relativePath()),file.content());
            Path target=REPO.resolve("rust/target");
            SemanticModelConformanceTest.run(List.of("cargo","build","--locked","--manifest-path",REPO.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",target.toString()),directory);
            Path nativeOutput=directory.resolve("native");
            SemanticModelConformanceTest.run(List.of(target.resolve("debug/unlaxer").toString(),"generate","--grammar",REPO.resolve("spec-corpus/semantic-rules/model.ubnf").toString(),"--output",nativeOutput.toString()),directory);
            for(var file:rustFiles)assertEquals(file.relativePath(),file.content(),Files.readString(nativeOutput.resolve(file.relativePath())));
            Files.copy(REPO.resolve("examples/preprocessing/probe.rs"),src.resolve("main.rs"));Files.copy(REPO.resolve("examples/preprocessing/pipeline.rs"),src.resolve("pipeline.rs"));
            StringBuilder manifest=new StringBuilder("[package]\nname=\"preprocessing-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[dependencies]\nserde_json=\"1\"\n");
            for(String name:List.of("unlaxer-runtime","unlaxer-generator","unlaxer-ubnf"))manifest.append(name).append("={path=\"").append(REPO.resolve("rust").resolve(name)).append("\"}\n");
            Files.writeString(directory.resolve("Cargo.toml"),manifest);
            String output=SemanticModelConformanceTest.run(List.of("cargo","run","--quiet","--offline","--manifest-path",directory.resolve("Cargo.toml").toString(),"--target-dir",target.toString(),"--",REPO.toString()),directory);
            var rust=output.lines().filter(line->line.startsWith("{")).map(JsonParser::parseString).toList();
            var cases=JsonParser.parseString(Files.readString(REPO.resolve("examples/preprocessing/cases.json"))).getAsJsonArray();assertEquals(cases.size(),rust.size());
            var evidence=new ArrayList<String>();evidence.add("case\tjava\trust");
            try(var loader=new URLClassLoader(new java.net.URL[]{java.toUri().toURL()},getClass().getClassLoader())) {
                var parse=loader.loadClass("example.generated.TypedModelMapper").getMethod("parseEditorCst",String.class,List.class,EditorCst.Options.class);
                Function<String,EditorCst> parser=source->{try{return (EditorCst)parse.invoke(null,source,List.of("?",")",";","}"),EditorCst.Options.defaults());}catch(ReflectiveOperationException error){throw new IllegalStateException(error);}};
                var program=SemanticRulesLoader.load(Files.readString(REPO.resolve("spec-corpus/semantic-rules/rules.json")),grammar);
                var pipeline=(AnalysisPipeline)loader.loadClass("example.pipeline.PreprocessingExample").getMethod("pipeline",SemanticRules.Program.class,SemanticRules.Inventory.class,Function.class).invoke(null,program,SemanticRulesLoader.inventory(grammar),parser);
                int index=0;
                for(var item:cases) {
                    var test=item.getAsJsonObject();String id=test.get("id").getAsString();Map<String,DocumentSnapshot> snapshots=new HashMap<>();
                    for(var entry:test.getAsJsonObject("snapshots").entrySet()) {
                        var value=entry.getValue().getAsJsonObject();snapshots.put(entry.getKey(),new DocumentSnapshot(value.get("uri").getAsString(),value.get("version").getAsLong(),value.get("text").getAsString()));
                    }
                    Map<String,String> configuration=new HashMap<>();test.getAsJsonObject("configuration").entrySet().forEach(entry->configuration.put(entry.getKey(),entry.getValue().getAsString()));
                    if(test.has("expectedError")) {
                        var error=assertThrows(IllegalArgumentException.class,()->pipeline.evaluate("semantic",snapshots,configuration,test.get("budget").getAsInt(),false));
                        var actual=JSON.toJsonTree(Map.of("error",error.getMessage()));
                        assertEquals(id+" Java/Rust",rust.get(index++),actual);assertEquals(id+" independent oracle",test.get("expected"),actual);
                        evidence.add(id+"\t"+actual+"\t"+rust.get(index-1));continue;
                    }
                    var result=pipeline.evaluate("semantic",snapshots,configuration,test.get("budget").getAsInt(),false);
                    JsonObject actual=new JsonObject();actual.addProperty("state",result.artifact().state().name());actual.add("evaluated",JSON.toJsonTree(result.evaluated()));actual.add("reused",JSON.toJsonTree(result.reused()));actual.addProperty("revision",result.revision().isPresent());
                    actual.add("report",result.artifact().payload().isEmpty()?JsonNull.INSTANCE:JsonParser.parseString(result.artifact().payload()));actual.add("origins",JSON.toJsonTree(result.artifact().origins().stream().map(location->location.snapshot().uri()).toList()));
                    assertEquals(id+" Java/Rust",rust.get(index++),actual);assertEquals(id+" independent oracle",test.get("expected"),actual);
                    evidence.add(id+"\t"+actual+"\t"+rust.get(index-1));
                }
            }
            Files.write(REPO.resolve("unlaxer-dsl/target/rust-preprocessing.tsv"),evidence);
        } finally {
            try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
