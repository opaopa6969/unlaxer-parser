package org.unlaxer.dsl.semantic;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.*;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** The same UBNF and authored source drive real generated parsers and semantic adapters. */
public class SemanticModelUbnfTest {
    private static final Path REPO=Path.of("..").toAbsolutePath().normalize();
    @Test public void generatedJavaAndNativeRustDriveTypedCompletion() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",Boolean.getBoolean("rustConformance"));
        Path dir=Files.createTempDirectory("semantic-ubnf-");
        try {
            String grammarSource=Files.readString(REPO.resolve("spec-corpus/semantic-model/model.ubnf"));
            var grammar=UBNFMapper.parse(grammarSource).grammars().get(0);
            var settings=new ArrayList<>(grammar.settings());
            settings.add(new org.unlaxer.dsl.bootstrap.UBNFAST.GlobalSetting("package",new org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue("example.semantic")));
            grammar=new org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl(grammar.name(),grammar.imports(),settings,grammar.tokens(),grammar.rules());
            GrammarValidator.validateOrThrow(grammar);
            Path java=Files.createDirectories(dir.resolve("java"));
            var sources=new ArrayList<String>();
            for(CodeGenerator generator:List.of(new ASTGenerator(),new ParserGenerator(),new MapperGenerator())) {
                var source=generator.generate(grammar);Path path=java.resolve(source.className()+".java");Files.writeString(path,source.source());sources.add(path.toString());
            }
            Path adapter=java.resolve("TypedModelExample.java");Files.copy(REPO.resolve("examples/semantic-model/TypedModelExample.java"),adapter);sources.add(adapter.toString());
            var command=new ArrayList<>(List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",java.toString()));command.addAll(sources);
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,command.toArray(String[]::new)));
            var files=new RustBackend().generate(grammar);
            Path generated=Files.createDirectories(dir.resolve("generated"));for(var f:files) Files.writeString(generated.resolve(f.relativePath()),f.content());
            // The native frontend sees the exact same grammar, including package metadata.
            Path nativeTarget=REPO.resolve("rust/target");
            SemanticModelConformanceTest.run(List.of("cargo","build","--locked","--manifest-path",REPO.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget.toString()),dir);
            Path nativeOutput=dir.resolve("native");
            SemanticModelConformanceTest.run(List.of(nativeTarget.resolve("debug/unlaxer").toString(),"generate","--grammar",REPO.resolve("spec-corpus/semantic-model/model.ubnf").toString(),"--output",nativeOutput.toString()),dir);
            for(var f:files) assertEquals(f.relativePath(),f.content(),Files.readString(nativeOutput.resolve(f.relativePath())));
            Path runtime=dir.resolve("libunlaxer_runtime.rlib");
            SemanticModelConformanceTest.run(List.of("rustc","--edition=2021","--crate-name=unlaxer_runtime","--crate-type=rlib",REPO.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),dir);
            Files.copy(REPO.resolve("examples/semantic-model/adapter.rs"),dir.resolve("adapter.rs"));
            String original=Files.readString(REPO.resolve("examples/semantic-model/example.model"));
            List<String> inputs=List.of(original,original.replace("let db: DbAccessor;","let db: String;"),original.replace("fn process(Context, IAccessor)","fn process(String, IAccessor)"));
            var probe=new StringBuilder("#![allow(dead_code)]\nmod generated; mod adapter;\nfn main(){\n");
            for(String input:inputs) {
                probe.append("let m=adapter::model(&").append(SemanticModelConformanceTest.r(input)).append(",1).unwrap();\n");
                probe.append("let call=&m.data().calls[0]; let pos=call.arguments[1].span.start; let cs=m.complete_argument(&call.id,1,pos,1,\"\").unwrap();\n");
                probe.append("println!(\"[{}]\",cs.iter().map(|c|format!(\"{{\\\"name\\\":{},\\\"type\\\":{},\\\"span\\\":[{},{}],\\\"expectedTypes\\\":[{}]}}\",unlaxer_runtime::json_string(&c.symbol.name),unlaxer_runtime::json_string(&c.symbol.type_id),c.symbol.declaration.start,c.symbol.declaration.end,c.expected_types.iter().map(|t|unlaxer_runtime::json_string(t)).collect::<Vec<_>>().join(\",\"))).collect::<Vec<_>>().join(\",\"));\n");
            }
            Files.writeString(dir.resolve("main.rs"),probe.append("}\n").toString());
            SemanticModelConformanceTest.run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,dir.resolve("main.rs").toString(),"-o",dir.resolve("probe").toString()),dir);
            var rust=SemanticModelConformanceTest.run(List.of(dir.resolve("probe").toString()),dir).lines().map(JsonParser::parseString).toList();
            var report=new ArrayList<>(List.of("case\texpected_names\tjava\trust"));
            try(var loader=new URLClassLoader(new java.net.URL[]{java.toUri().toURL()},getClass().getClassLoader())) {
                var method=loader.loadClass("example.semantic.TypedModelExample").getMethod("model",String.class,long.class);
                for(int i=0;i<inputs.size();i++) {
                    var model=(SemanticModel)method.invoke(null,inputs.get(i),1L);var call=model.calls().values().iterator().next();
                    var items=model.completeArgument(call.id(),1,call.arguments().get(1).span().start(),1,"");
                    var expectedNames=i==0?List.of("cached","db"):i==1?List.of("cached"):List.of();
                    assertEquals("authored source determines completion "+i,expectedNames,items.stream().map(c->c.symbol().name()).toList());
                    var actual=new Gson().toJsonTree(items.stream().map(c->Map.of("name",c.symbol().name(),"type",c.symbol().type(),"span",List.of(c.symbol().declaration().start(),c.symbol().declaration().end()),"expectedTypes",c.expectedTypes())).toList());
                    assertEquals("source spans and typed values "+i,actual,rust.get(i));
                    for(var c:items) {
                        var s=c.symbol();String source=inputs.get(i);int start=source.offsetByCodePoints(0,s.declaration().start()),end=source.offsetByCodePoints(0,s.declaration().end());
                        assertTrue("independent original-source slice",source.substring(start,end).trim().startsWith("let "+s.name()+":"));
                    }
                    assertEquals("composed type field",List.of("Context","Request"),model.types().get("Request").fields().stream().map(SemanticModel.Field::type).toList());
                    report.add(i+"\t"+expectedNames+"\t"+actual+"\t"+rust.get(i));
                }
            }
            Files.write(Path.of("target/rust-semantic-ubnf.tsv"),report);
        } finally { try(var paths=Files.walk(dir)) {for(var p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);} }
    }
}
