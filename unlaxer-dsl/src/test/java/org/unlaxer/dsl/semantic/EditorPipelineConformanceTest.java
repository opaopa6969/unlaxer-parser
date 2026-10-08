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
import org.unlaxer.dsl.codegen.*;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Authored input and independent expectations drive actual UBNF-generated editor parsers. */
public class EditorPipelineConformanceTest {
    private static final Path REPO=Path.of("..").toAbsolutePath().normalize();
    private static final Gson JSON=new Gson();
    @Test public void generatedCstRetainsOriginalTypedContext() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",Boolean.getBoolean("rustConformance"));
        JsonObject fixture=JsonParser.parseString(Files.readString(REPO.resolve("spec-corpus/editor-pipeline/corpus.json"))).getAsJsonObject();
        Path directory=Files.createTempDirectory("editor-pipeline-");
        try {
            var grammar=UBNFMapper.parse(Files.readString(REPO.resolve("spec-corpus/editor-pipeline/model.ubnf"))).grammars().get(0);
            var settings=new ArrayList<>(grammar.settings());
            settings.add(new org.unlaxer.dsl.bootstrap.UBNFAST.GlobalSetting("package",new org.unlaxer.dsl.bootstrap.UBNFAST.StringSettingValue("example.semantic")));
            grammar=new org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl(grammar.name(),grammar.imports(),settings,grammar.tokens(),grammar.rules());
            Path java=Files.createDirectories(directory.resolve("java")); var paths=new ArrayList<String>();
            for(CodeGenerator generator:List.of(new ASTGenerator(),new ParserGenerator(),new MapperGenerator(),new LSPGenerator())) {
                var source=generator.generate(grammar);Path path=java.resolve(source.className()+".java");Files.writeString(path,source.source());paths.add(path.toString());
            }
            Path adapter=java.resolve("TypedModelEditor.java");Files.copy(REPO.resolve("examples/semantic-model/TypedModelEditor.java"),adapter);paths.add(adapter.toString());
            Path server=java.resolve("TypedModelEditorServer.java");Files.copy(REPO.resolve("examples/semantic-model/TypedModelEditorServer.java"),server);paths.add(server.toString());
            var arguments=new ArrayList<>(List.of("--release","21","-classpath",System.getProperty("java.class.path"),"-d",java.toString()));arguments.addAll(paths);
            assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,System.out,System.err,arguments.toArray(String[]::new)));
            Path generated=Files.createDirectories(directory.resolve("generated"));
            var files=new RustBackend().generate(grammar);for(var file:files)Files.writeString(generated.resolve(file.relativePath()),file.content());
            Path nativeTarget=REPO.resolve("rust/target");
            SemanticModelConformanceTest.run(List.of("cargo","build","--locked","--manifest-path",REPO.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget.toString()),directory);
            Path nativeOutput=directory.resolve("native");
            SemanticModelConformanceTest.run(List.of(nativeTarget.resolve("debug/unlaxer").toString(),"generate","--grammar",REPO.resolve("spec-corpus/editor-pipeline/model.ubnf").toString(),"--output",nativeOutput.toString()),directory);
            for(var file:files)assertEquals(file.relativePath(),file.content(),Files.readString(nativeOutput.resolve(file.relativePath())));
            Path runtime=directory.resolve("libunlaxer_runtime.rlib");
            SemanticModelConformanceTest.run(List.of("rustc","--edition=2021","--crate-name=unlaxer_runtime","--crate-type=rlib",REPO.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),directory);
            Files.copy(REPO.resolve("examples/semantic-model/editor_adapter.rs"),directory.resolve("editor_adapter.rs"));
            Files.copy(REPO.resolve("spec-corpus/editor-pipeline/probe.rs"),directory.resolve("probe.rs"));
            var probe=new StringBuilder("#![allow(dead_code)]\nmod generated; mod editor_adapter; mod probe;\nfn main(){\n");
            for(JsonElement element:fixture.getAsJsonArray("cases")) {
                JsonObject test=element.getAsJsonObject();String source=fixture.get("prefix").getAsString()+test.get("tail").getAsString();
                String fragments=test.has("fragments")?test.getAsJsonArray("fragments").asList().stream().map(value->rustString(value.getAsString())).reduce((left,right)->left+","+right).orElse(""):"\"?\",\")\",\";\",\"}\",\"a\",\":\",\"{\"";
                probe.append("probe::report(").append(rustString(source)).append(", &[").append(fragments).append("], ").append(test.has("maxAttempts")?test.get("maxAttempts").getAsInt():256).append(", ").append(test.get("cursor").getAsInt()).append(", ").append(rustString(test.get("prefix").getAsString())).append(");\n");
            }
            Files.writeString(directory.resolve("main.rs"),probe.append("}\n").toString());
            SemanticModelConformanceTest.run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,directory.resolve("main.rs").toString(),"-o",directory.resolve("probe").toString()),directory);
            var rust=SemanticModelConformanceTest.run(List.of(directory.resolve("probe").toString()),directory).lines().map(JsonParser::parseString).toList();
            var evidence=new ArrayList<String>(List.of("case\tjava\trust"));
            try(var loader=new URLClassLoader(new java.net.URL[]{java.toUri().toURL()},getClass().getClassLoader())) {
                var method=loader.loadClass("example.semantic.TypedModelEditor").getMethod("parseWithCompletions",String.class,long.class,String.class,String.class,EditorCst.Options.class,List.class);
                int index=0;
                for(JsonElement element:fixture.getAsJsonArray("cases")) {
                    JsonObject test=element.getAsJsonObject();String id=test.get("id").getAsString();String source=fixture.get("prefix").getAsString()+test.get("tail").getAsString();
                    List<String> fragments=test.has("fragments")?test.getAsJsonArray("fragments").asList().stream().map(JsonElement::getAsString).toList():List.of("?",")",";","}","a",":","{");
                    Object parsed=method.invoke(null,"memory:editor",7L,source,"region:inner",new EditorCst.Options(4,test.has("maxAttempts")?test.get("maxAttempts").getAsInt():256),fragments);
                    EditorParseResult<?> result=(EditorParseResult<?>)parsed.getClass().getMethod("result").invoke(parsed);
                    EditorCst cst=(EditorCst)parsed.getClass().getMethod("cst").invoke(parsed);
                    JsonObject actual=report(result,cst,test.get("cursor").getAsInt(),test.get("prefix").getAsString());
                    assertEquals(id+" runtime parity",actual,rust.get(index++));
                    assertEquals(id,test.get("status").getAsString(),result.status().name());assertEquals(id,test.get("reason").getAsString(),cst.reason().name());assertEquals(id,test.get("strict").getAsBoolean(),result.strictAst().isPresent());
                    assertEquals(id,test.get("expectedTypes"),actual.get("expected"));assertEquals(id,test.get("arguments"),actual.get("arguments"));
                    assertEquals(id,test.get("completions"),actual.get("completions"));assertEquals(id,test.get("sourceLength").getAsInt(),source.codePointCount(0,source.length()));
                    assertEquals(id,test.get("utf16Length").getAsInt(),result.utf16Span(new SemanticModel.Span(0,source.codePointCount(0,source.length()))).end());
                    if(!id.equals("limit")&&!id.equals("syntax")) {assertEquals(id,fixture.get("expectedSymbols"),actual.get("symbols"));assertEquals(id,fixture.get("expectedTypes"),actual.get("types"));}
                    if(test.has("defectSpan"))assertEquals(id,test.get("defectSpan"),actual.getAsJsonArray("defects").get(0).getAsJsonObject().get("span"));
                    if(test.has("syntheticArgument"))assertTrue(id,cst.nodes().stream().flatMap(node->node.captures().stream()).anyMatch(capture->capture.name().equals("arguments")&&capture.synthetic()&&capture.text().equals("d")));
                    if(test.has("syntheticCapture"))assertTrue(id,cst.nodes().stream().flatMap(node->node.captures().stream()).anyMatch(capture->capture.name().equals("name")&&capture.synthetic()&&capture.text().equals(test.getAsJsonObject("syntheticCapture").get("text").getAsString())));
                    for(var node:cst.nodes())for(var capture:node.captures()) {
                        assertTrue(id,capture.span().end()<=source.codePointCount(0,source.length()));
                        assertEquals(id,source.substring(source.offsetByCodePoints(0,capture.span().start()),source.offsetByCodePoints(0,capture.span().end())),capture.text());
                    }
                    if(!test.get("strict").getAsBoolean())assertTrue(id,loader.loadClass("example.semantic.TypedModelMapper").getMethod("diagnose",String.class).invoke(null,source) instanceof Optional<?> diagnostic && diagnostic.isPresent());
                    if(result.status()!=EditorParseResult.Status.FAILED) {
                        try {result.completeAt(test.get("cursor").getAsInt(),"region:inner",6,"");fail("stale snapshot accepted");}catch(EditorParseResult.EditorException error){assertEquals("EDITOR_STALE_SNAPSHOT",error.code());}
                        assertTrue(result.completeAt(test.get("cursor").getAsInt(),"other-region",7,"").isEmpty());
                    }
                    if (!id.equals("inserted-identifier") && !id.equals("crossing-name") && !id.equals("limit") && !id.equals("keyword-fragment")) {
                        var languageServer=(org.eclipse.lsp4j.services.LanguageServer)loader.loadClass("example.semantic.TypedModelEditorServer").getConstructor().newInstance();
                        var service=languageServer.getTextDocumentService();
                        service.didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(new org.eclipse.lsp4j.TextDocumentItem("memory:editor","TypedModel",7,source)));
                        int offset=source.offsetByCodePoints(0,test.get("cursor").getAsInt());String before=source.substring(0,offset);
                        int line=(int)before.chars().filter(value->value=='\n').count();int character=offset-(source.lastIndexOf('\n',Math.max(0,offset-1))+1);
                        var position=new org.eclipse.lsp4j.Position(line,character);
                        var response=service.completion(new org.eclipse.lsp4j.CompletionParams(new org.eclipse.lsp4j.TextDocumentIdentifier("memory:editor"),position)).get();
                        var typed=response.getLeft().stream().filter(item->item.getKind()==org.eclipse.lsp4j.CompletionItemKind.Variable).toList();
                        assertEquals(id+" LSP",test.get("completions"),JSON.toJsonTree(typed.stream().map(org.eclipse.lsp4j.CompletionItem::getLabel).toList()));
                        for(var item:typed) {assertEquals(position,item.getTextEdit().getLeft().getRange().getEnd());assertEquals(result.status().name(),((Map<?,?>)item.getData()).get("status"));assertEquals(7L,((Map<?,?>)item.getData()).get("version"));}
                    }
                    evidence.add(id+"\t"+actual+"\t"+rust.get(index-1));
                }
            }
            Files.write(Path.of("target/rust-editor-pipeline.tsv"),evidence);
        } finally {try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static String rustString(String value) {return JSON.toJson(value);}

    private static JsonObject report(EditorParseResult<?> result,EditorCst cst,int cursor,String prefix) {
        var model=result.semantics().orElseThrow();var output=new LinkedHashMap<String,Object>();
        output.put("status",result.status().name());output.put("reason",cst.reason().name());output.put("strict",result.strictAst().isPresent());
        output.put("types",model.types().keySet().stream().sorted().toList());
        output.put("symbols",model.symbols().values().stream().sorted(Comparator.comparing(SemanticModel.Symbol::name)).map(symbol->Map.of("name",symbol.name(),"type",symbol.type(),"span",List.of(symbol.declaration().start(),symbol.declaration().end()))).toList());
        output.put("defects",result.nodes().stream().map(node->Map.of("kind",node.kind().name(),"span",List.of(node.span().start(),node.span().end()),"candidates",node.candidateRules(),"region",node.regionId())).toList());
        output.put("expected",result.expectedTypesAt(cursor,"region:inner"));output.put("completions",result.completeAt(cursor,"region:inner",7,prefix).stream().map(item->item.symbol().name()).toList());
        output.put("arguments",model.calls().values().stream().flatMap(call->call.arguments().stream()).map(argument->Map.of("type",argument.type(),"span",List.of(argument.span().start(),argument.span().end()))).toList());
        return JSON.toJsonTree(output).getAsJsonObject();
    }
}
