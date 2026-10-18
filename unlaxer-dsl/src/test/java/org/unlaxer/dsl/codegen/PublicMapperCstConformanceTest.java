package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Identical malformed-graph categories and post-failure values through public mapper APIs. */
public class PublicMapperCstConformanceTest extends PreferredAstConformanceTest {
    private final Path root = Path.of("..").toAbsolutePath().normalize();
    private Path corpusDir() { return root.resolve("spec-corpus/public-mapper-cst"); }
    private JsonObject fixture() throws Exception { return JsonParser.parseString(Files.readString(corpusDir().resolve("cases.json"))).getAsJsonObject(); }
    private List<String> expected(JsonObject fixture, boolean java) {
        var rows = new ArrayList<String>();
        for (var raw : fixture.getAsJsonArray("cases")) {
            var item = raw.getAsJsonObject();
            for (String entry : List.of("ordinary","source-map","subtree","subtree-source-map","preferred","subtree-preferred")) {
                String category = item.get("category").getAsString();
                if (java && item.get("id").getAsString().equals("null-root")) {
                    // Existing Java argument diagnostics precede new malformed-CST diagnostics.
                    category = entry.startsWith("subtree") ? "subtreeToken must not be null"
                            : "rootToken must not be null";
                }
                rows.add(item.get("id").getAsString()+"\t"+entry+"\t"+category);
            }
        }
        for (var count : fixture.getAsJsonArray("scalingCounts")) {
            for (String entry : List.of("ordinary","source-map","subtree","subtree-source-map","preferred","subtree-preferred")) {
                rows.add("scaling-" + count + "\t" + entry + "\tOK");
            }
        }
        return rows;
    }
    @Override @Test public void javaSnapshots() throws Exception {
        var fixture=fixture();
        var grammar=UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
        var units=new ArrayList<JavaFileObject>();
        for (var item:List.of(grammar,UBNFMapper.parse(fixture.get("dagGrammar").getAsString()).grammars().get(0),UBNFMapper.parse(fixture.get("scalingGrammar").getAsString()).grammars().get(0))) {
            for (CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator())) {
                var file=generator.generate(item); units.add(unit(file.packageName()+"."+file.className(),file.source()));
            }
        }
        units.add(unit("guardprobe.JavaProbe",probe(fixture,"JavaProbe.java",false)));
        Path output=temporary.newFolder().toPath();
        var compiler=org.unlaxer.dsl.Java21TestSupport.compiler(); var diagnostics=new DiagnosticCollector<JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)) {
            boolean success=compiler.getTask(null,manager,diagnostics,List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",output.toString()),null,units).call();
            assertTrue(diagnostics.getDiagnostics().toString(),success);
        }
        try(var loader=new URLClassLoader(new URL[]{output.toUri().toURL()},getClass().getClassLoader())) {
            String[] ids=new String[fixture.getAsJsonArray("cases").size()];
            for(int i=0;i<ids.length;i++)ids[i]=fixture.getAsJsonArray("cases").get(i).getAsJsonObject().get("id").getAsString();
            var actual=loader.loadClass("guardprobe.JavaProbe").getMethod("run",String.class,String[].class).invoke(null,fixture.get("input").getAsString(),ids);
            assertEquals(expected(fixture, true),actual);
            Files.write(Path.of("target/public-mapper-cst-java.tsv"), (List<String>) actual);
        }
    }
    @Override @Test public void bothHostsSnapshots() throws Exception {
        if(!Boolean.getBoolean("rustConformance"))System.out.println("[assumption] PublicMapperCstConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        var fixture=fixture(); String grammarText=fixture.get("grammar").getAsString();
        var grammar=UBNFMapper.parse(grammarText).grammars().get(0);
        Path workspace=temporary.newFolder().toPath(), runtime=workspace.resolve("libunlaxer_runtime.rlib"), input=workspace.resolve("grammar.ubnf");
        Files.writeString(input,grammarText);
        run(List.of("rustc","--edition=2021","--crate-type=rlib","--crate-name=unlaxer_runtime",root.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),"",false,true);
        run(List.of("cargo","build","--locked","--manifest-path",root.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"),"",false,true);
        for(String host:List.of("java","native")) {
            Path dir=Files.createDirectory(workspace.resolve(host)), generated=Files.createDirectory(dir.resolve("generated"));
            if(host.equals("native"))run(List.of(root.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",input.toString(),"--output",generated.toString()),"",true,true);
            for(var file:new RustBackend().generate(grammar)) {
                if(host.equals("native"))assertEquals(file.relativePath(),file.content(),Files.readString(generated.resolve(file.relativePath())));
                else Files.writeString(generated.resolve(file.relativePath()),file.content());
            }
            Path dag=Files.createDirectory(dir.resolve("dag")), dagInput=workspace.resolve("dag.ubnf");
            Files.writeString(dagInput,fixture.get("dagGrammar").getAsString());
            if(host.equals("native"))run(List.of(root.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",dagInput.toString(),"--output",dag.toString()),"",true,true);
            for(var file:new RustBackend().generate(UBNFMapper.parse(Files.readString(dagInput)).grammars().get(0))) {
                if(host.equals("native"))assertEquals(file.relativePath(),file.content(),Files.readString(dag.resolve(file.relativePath())));
                else Files.writeString(dag.resolve(file.relativePath()),file.content());
            }
            Path scaling=Files.createDirectory(dir.resolve("scaling")), scalingInput=workspace.resolve("scaling.ubnf");
            Files.writeString(scalingInput,fixture.get("scalingGrammar").getAsString());
            if(host.equals("native"))run(List.of(root.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",scalingInput.toString(),"--output",scaling.toString()),"",true,true);
            for(var file:new RustBackend().generate(UBNFMapper.parse(Files.readString(scalingInput)).grammars().get(0))) {
                if(host.equals("native"))assertEquals(file.relativePath(),file.content(),Files.readString(scaling.resolve(file.relativePath())));
                else Files.writeString(scaling.resolve(file.relativePath()),file.content());
            }
            Path probe=dir.resolve("probe.rs"), exe=dir.resolve("probe");
            Files.writeString(probe,probe(fixture,"rust-probe.rs",true));
            run(List.of("rustc","--edition=2021",probe.toString(),"--extern","unlaxer_runtime="+runtime,"-o",exe.toString()),"",false,true);
            var actual=run(List.of(exe.toString()),"",true,true).lines().toList();
            assertEquals(host,expected(fixture, false),actual);
            Files.write(Path.of("target/public-mapper-cst-rust-"+host+".tsv"),actual);
        }
    }
    @Test public void compositionShapesAndResourcePathsMatchBothHosts() throws Exception {
        assumeTrue("enable with -DrustConformance=true",Boolean.getBoolean("rustConformance"));
        var cases=JsonParser.parseString(Files.readString(corpusDir().resolve("composition-cases.json"))).getAsJsonArray();
        Path workspace=temporary.newFolder().toPath(),runtime=workspace.resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc","--edition=2021","--crate-type=rlib","--crate-name=unlaxer_runtime",root.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),"",false,true);
        run(List.of("cargo","build","--locked","--manifest-path",root.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"),"",false,true);
        var report=new ArrayList<String>(List.of("fixture\thost\tentry\trejected\tvalue"));
        for(var raw:cases){
            var row=raw.getAsJsonObject();String name=row.get("name").getAsString(),input=row.get("input").getAsString(),expected=row.get("expected").toString(),kind=row.get("kind").getAsString();
            var grammar=UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            Path dir=Files.createDirectory(workspace.resolve(name)),classes=Files.createDirectory(dir.resolve("classes"));
            var units=new ArrayList<JavaFileObject>();
            for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator())){
                var file=generator.generate(grammar);units.add(unit(file.packageName()+"."+file.className(),file.source()));
            }
            units.add(unit("guardcomposition.CompositionProbe",Files.readString(corpusDir().resolve("CompositionProbe.java"))));
            var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
            try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,StandardCharsets.UTF_8)){
                boolean compiled=compiler.getTask(null,manager,diagnostics,List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,units).call();
                assertTrue(name+" "+diagnostics.getDiagnostics(),compiled);
            }
            List<String> javaRows;
            try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())){
                @SuppressWarnings("unchecked") var actual=(List<String>)loader.loadClass("guardcomposition.CompositionProbe").getMethod("run",String.class,String.class,String.class).invoke(null,input,expected,kind);
                javaRows=actual;
            }
            assertEquals(name,6,javaRows.size());for(String value:javaRows)report.add(name+"\tjava\t"+value);
            Path grammarFile=dir.resolve("grammar.ubnf");Files.writeString(grammarFile,row.get("grammar").getAsString());
            for(String host:List.of("java","native")){
                Path generated=Files.createDirectory(dir.resolve(host));
                if(host.equals("native"))run(List.of(root.resolve("rust/target/debug/unlaxer").toString(),"generate","--grammar",grammarFile.toString(),"--output",generated.toString()),"",true,true);
                for(var file:new RustBackend().generate(grammar)){
                    if(host.equals("native"))assertEquals(name+" "+file.relativePath(),file.content(),Files.readString(generated.resolve(file.relativePath())));
                    else Files.writeString(generated.resolve(file.relativePath()),file.content());
                }
                Path main=dir.resolve(host+"-main.rs"),exe=dir.resolve(host+"-probe");Files.writeString(main,Files.readString(corpusDir().resolve("composition-probe.rs")).replace("__MODULE__",host));
                run(List.of("rustc","--edition=2021","--crate-name",host+"_composition","--extern","unlaxer_runtime="+runtime,main.toString(),"-o",exe.toString()),"",false,true);
                var actual=run(List.of(exe.toString(),input,expected,kind),"",true,true).lines().toList();assertEquals(name+" "+host,javaRows,actual);
                for(String value:actual)report.add(name+"\trust-"+host+"\t"+value);
            }
        }
        assertEquals(163,report.size());Files.write(Path.of("target/public-mapper-composition.tsv"),report);
    }
    private String probe(JsonObject fixture,String file,boolean rust) throws Exception {
        var span=fixture.getAsJsonArray("rootSpan");
        var leaves=new ArrayList<String>();
        for(var raw:fixture.getAsJsonArray("leafSpans")) {
            var value=raw.getAsJsonArray();
            leaves.add((rust?"(":"{")+value.get(0)+","+value.get(1)+(rust?")":"}"));
        }
        return Files.readString(corpusDir().resolve(file))
            .replace("__ROOT_START__",span.get(0).toString()).replace("__ROOT_END__",span.get(1).toString())
            .replace("__LEAF_SPANS__",String.join(",",leaves))
            .replace("__DAG_DEPTH__",fixture.get("dagDepth").toString())
            .replace("__SCALING_COUNTS__",fixture.getAsJsonArray("scalingCounts").toString().replace("[", "").replace("]", ""));
    }
    private JavaFileObject unit(String name,String source) {
        return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored){return source;}
        };
    }
}
