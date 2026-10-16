package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Independent values, recursive shape and occurrence CP spans, including real DAP consumers. */
public class NestedMixedValueConformanceTest extends PreferredAstConformanceTest {
    private final Path repo=Path.of("..").toAbsolutePath().normalize();
    private final Path fixtures=repo.resolve("spec-corpus/nested-mixed-values");
    @Override protected JsonArray corpus()throws Exception{return JsonParser.parseString(Files.readString(fixtures.resolve("cases.json"))).getAsJsonArray();}
    @Override @Test public void javaSnapshots()throws Exception {
        for(var raw:corpus()) {
            var fixture=raw.getAsJsonObject();var grammar=UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            var units=new ArrayList<JavaFileObject>();
            for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator(),new EvaluatorGenerator(21),new DAPGenerator())) {
                var generated=generator.generate(grammar);units.add(unit(generated.packageName()+"."+generated.className(),generated.source()));
            }
            units.add(unit("nestedmixed.Probe",template(fixture,false)));
            Path classes=temporary.newFolder().toPath();var compiler=org.unlaxer.dsl.Java21TestSupport.compiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
            try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
                boolean ok=compiler.getTask(null,manager,diagnostics,List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,units).call();
                assertTrue(fixture.get("name")+diagnostics.getDiagnostics().toString(),ok);
            }
            try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
                var method=loader.loadClass("nestedmixed.Probe").getMethod("run",String.class,String.class,int.class,int.class,String.class);
                for(var value:fixture.getAsJsonArray("cases")) {
                    var row=value.getAsJsonObject();
                    for(int memo=0;memo<2;memo++)for(int diagnostic=0;diagnostic<3;diagnostic++) {
                        Object actual;
                        try{actual=method.invoke(null,input(row),row.has("stage")?row.get("stage").getAsString():"ok",memo,diagnostic,fixture.get("valid").getAsString());}
                        catch(java.lang.reflect.InvocationTargetException e){throw new AssertionError(fixture.get("name")+" "+row+" mode "+memo+"/"+diagnostic,e.getCause());}
                        assertEquals(fixture.get("name")+" "+row,row.get("expected"),actual);
                    }
                }
            }
        }
    }
    @Override @Test public void bothHostsSnapshots()throws Exception {
        if(!Boolean.getBoolean("rustConformance"))System.out.println("[assumption] NestedMixedValueConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","-p","unlaxer-dap","-p","unlaxer-runtime"),"",false,true);
        Path target=repo.resolve("rust/target/debug"),generator=target.resolve("unlaxer");
        for(var raw:corpus()) {
            var fixture=raw.getAsJsonObject();var grammar=UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            var files=new RustBackend().generateWithDap(grammar);assertEquals(6,files.size());
            for(String host:List.of("java","native")) {
                Path dir=temporary.newFolder().toPath(),input=dir.resolve("grammar.ubnf"),generated=dir.resolve("generated");
                Files.writeString(input,fixture.get("grammar").getAsString());
                if(host.equals("native")) {
                    run(List.of(generator.toString(),"generate","--grammar",input.toString(),"--output",generated.toString(),"--dap"),"",true,true);
                    for(var file:files)assertEquals(fixture.get("name")+" "+file.relativePath(),file.content(),Files.readString(generated.resolve(file.relativePath())));
                }else for(var file:files){Files.createDirectories(generated);Files.writeString(generated.resolve(file.relativePath()),file.content());}
                Files.writeString(dir.resolve("main.rs"),template(fixture,true));
                Path executable=dir.resolve("probe");
                run(List.of("rustc","--edition=2021",dir.resolve("main.rs").toString(),"-L","dependency="+target.resolve("deps"),"--extern","unlaxer_runtime="+target.resolve("libunlaxer_runtime.rlib"),"--extern","unlaxer_dap="+target.resolve("libunlaxer_dap.rlib"),"-o",executable.toString()),"",false,true);
                for(var value:fixture.getAsJsonArray("cases")) {
                    var row=value.getAsJsonObject();var lines=run(List.of(executable.toString(),"--stdin",row.has("stage")?row.get("stage").getAsString():"ok",fixture.get("valid").getAsString()),input(row),true,true).lines().toList();
                    assertEquals(6,lines.size());for(String line:lines)assertEquals(fixture.get("name")+"/"+host+"/"+row,row.get("expected"),JsonParser.parseString(line));
                }
            }
        }
    }
    @Test public void schemasDescribeSumVariantsAndRecursiveChanges()throws Exception {
        assumeTrue(Boolean.getBoolean("rustConformance"));
        String before=corpus().get(0).getAsJsonObject().get("grammar").getAsString();
        String after=before.replace("{ (Mode", "[ (Mode").replace(" } @values", " ] @values");
        for(String target:List.of("java","rust")) {
            var report=org.unlaxer.dsl.impact.ApiImpact.compare(target,before,after);
            assertTrue(report.toJson(),report.ok());
            assertTrue(report.changes().stream().anyMatch(c->c.kind().equals("FIELD_TYPE_CHANGED")&&c.subject().equals("Root.values")));
            assertTrue(report.changes().stream().anyMatch(c->c.kind().equals("FIELD_CARDINALITY_CHANGED")&&c.subject().equals("Root.values")));
            var sum=report.before().nodes().stream().filter(n->n.name().equals("NestedValue")).findFirst().orElseThrow();
            assertEquals(5,sum.variants().size());
            assertFalse(sum.origins().isEmpty());assertNotNull(sum.generated());
            var integer=report.before().nodes().stream().filter(n->n.name().equals("NestedValue.Integer")).findFirst().orElseThrow();
            assertEquals(target.equals("java")?"int":"i32",integer.fields().get(0).type());
        }
        Path directory=temporary.newFolder().toPath(),a=directory.resolve("before.ubnf"),b=directory.resolve("after.ubnf");
        Files.writeString(a,before);Files.writeString(b,after);
        buildNative();
        String output=run(List.of(nativeGenerator(),"impact","--target","rust","--before",a.toString(),"--after",b.toString()),"",true,true);
        assertEquals(JsonParser.parseString(org.unlaxer.dsl.impact.ApiImpact.compare("rust",before,after).toJson()),JsonParser.parseString(output));
    }
    @Test public void unsupportedShapesAndReservedNamesFailWithOrigins()throws Exception {
        assumeTrue(Boolean.getBoolean("rustConformance"));buildNative();
        var rows=JsonParser.parseString(Files.readString(fixtures.resolve("invalid.json"))).getAsJsonArray();
        for(var raw:rows) {
            var row=raw.getAsJsonObject();String text=row.get("grammar").getAsString();var source=UBNFMapper.parseWithSource(text);var grammar=source.ast().grammars().get(0);
            var diagnostic=GrammarValidator.validateWithoutClassLoading(grammar).stream().filter(d->d.code().equals("E-NESTED-SHAPE")).findFirst().orElseThrow(()->new AssertionError(row));
            assertEquals(row.get("rule").getAsString(),diagnostic.rule());
            var rule=grammar.rules().stream().filter(r->r.name().equals(diagnostic.rule())).findFirst().orElseThrow();var span=source.spanOf(rule).orElseThrow();
            assertEquals(row.get("startCP").getAsInt(),span.start());assertEquals(row.get("endCP").getAsInt(),span.end());
            for(CodeGenerator generator:List.of(new ParserGenerator(),new ASTGenerator(),new MapperGenerator(),new EvaluatorGenerator(21),new DAPGenerator()))
                assertTrue(row+" "+generator,assertThrows(IllegalArgumentException.class,()->generator.generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
            assertTrue(assertThrows(IllegalArgumentException.class,()->new RustBackend().generate(grammar)).getMessage().contains("E-NESTED-SHAPE"));
            var directory=temporary.newFolder().toPath();var input=directory.resolve("invalid.ubnf");var output=directory.resolve("output");Files.writeString(input,text);
            String nativeError=run(List.of(nativeGenerator(),"generate","--grammar",input.toString(),"--output",output.toString()),"",true,false);
            assertTrue(row+nativeError,nativeError.contains("E-NESTED-SHAPE"));
            assertTrue(row+nativeError,nativeError.contains("at CP "+span.start()+".."+span.end()));assertFalse(Files.exists(output));
        }
    }
    private String nativeGenerator(){return repo.resolve("rust/target/debug/unlaxer").toString();}
    private void buildNative()throws Exception {run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator"),"",false,true);}
    private String template(JsonObject fixture,boolean rust)throws Exception {
        String handlers="";
        if(fixture.get("mixed").getAsBoolean())handlers=rust?"""
            impl Observe for NestedValue {fn observe(&self)->String{let span=self.span();match self {
             Self::Text{text,..}=>format!("[\\\"text\\\",{},{},{}]",json_string(text),span.start,span.end),
             Self::Integer{value,..}=>format!("[\\\"integer\\\",{},{},{}]",value,span.start,span.end),
             Self::Node(node)=>node.observe(),
             Self::EnumMode{value,..}=>format!("[\\\"enum\\\",\\\"Mode\\\",{},{},{}]",json_string(value.text()),span.start,span.end),
             Self::EnumHeat{value,..}=>format!("[\\\"enum\\\",\\\"Heat\\\",{},{},{}]",json_string(value.text()),span.start,span.end),
            }}}
            """:"""
            if(value instanceof NestedMixedAST.NestedValue.Text item){var span=span(item);return array("text",item.value(),span[0],span[1]);}
            if(value instanceof NestedMixedAST.NestedValue.Integer item){var span=span(item);return array("integer",item.value(),span[0],span[1]);}
            if(value instanceof NestedMixedAST.NestedValue.Node item){if(!Arrays.equals(span(item),span(item.value())))throw new AssertionError("node occurrence span");return eval(item.value());}
            if(value instanceof NestedMixedAST.NestedValue.EnumMode item){var span=span(item);return array("enum","Mode",item.value().text(),span[0],span[1]);}
            if(value instanceof NestedMixedAST.NestedValue.EnumHeat item){var span=span(item);return array("enum","Heat",item.value().text(),span[0],span[1]);}
            """;
        String source=Files.readString(fixtures.resolve(rust?"rust-probe.rs.txt":"java-probe.java.txt"));
        if(fixture.has("family")) {
            if(rust)source=source.replace("let value=generated::evaluator::evaluate(&ast,&mut Eval);", "let value=generated::evaluator::evaluate(&ast,&mut Eval);assert_eq!(ast.common_root().unwrap().values.observe(),value);").replace("fn eval_root(&mut self,values:&__RUST_TYPE__,_span:Span)->String{values.observe()}","fn eval_first(&mut self,values:&__RUST_TYPE__,_span:Span)->String{values.observe()}\n fn eval_second(&mut self,values:&__RUST_TYPE__,_span:Span)->String{values.observe()}");
            else source=source.replace("@Override protected JsonElement evalRoot(NestedMixedAST.Root node){checkType(node);return value(node.values());}","@Override protected JsonElement evalFirst(NestedMixedAST.First node){checkType(node);return value(node.values());}\n @Override protected JsonElement evalSecond(NestedMixedAST.Second node){checkType(node);return value(node.values());}");
        }
        if(fixture.has("leafType")) {
            String leaf=fixture.get("leafType").getAsString();
            source=source.replace("Leaf",leaf).replace("eval_leaf","eval_"+leaf.replaceAll("([a-z])([A-Z])","$1_$2").toLowerCase(Locale.ROOT));
        }
        return source
            .replace("__MIXED_HANDLERS__",handlers).replace("__JAVA_TYPE__",fixture.get("javaType").getAsString()).replace("__RUST_TYPE__",fixture.get("rustType").getAsString())
            .replace("NestedMixed",fixture.has("apiName")?fixture.get("apiName").getAsString():"NestedMixed");
    }
    private static String input(JsonObject row) {return row.has("repeat")?"["+"x".repeat(row.get("repeat").getAsInt())+"]":row.get("input").getAsString();}
    private static JavaFileObject unit(String name,String source){return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return source;}};}
}
