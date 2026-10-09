package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.tools.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import org.junit.Test;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.source.*;
import org.unlaxer.dsl.semantic.*;
import org.unlaxer.editor.EditorCst;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.*;
import org.unlaxer.source.SegmentSourceMap.*;

/** Actual generated semantic parsers feed typed diagnostics through both LSP consumers. */
public class SemanticDiagnosticConformanceTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary=new org.junit.rules.TemporaryFolder();
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    @Test public void semanticDiagnosticsUseOwnedDocumentsAndExactVersions() throws Exception {
        assumeTrue("requires -DrustConformance=true and Rust",Boolean.getBoolean("rustConformance"));
        Path fixtures=ROOT.resolve("spec-corpus/semantic-diagnostics"),classes=temporary.newFolder().toPath();
        Path corpus=ROOT.resolve("spec-corpus/semantic-rules");
        var grammar=UBNFMapper.parse(Files.readString(corpus.resolve("model.ubnf")).replace("grammar TypedModel {", "grammar TypedModel { @package: diagnostic.fixture")).grammars().get(0);
        var program=SemanticRulesLoader.load(Files.readString(corpus.resolve("rules.json")),grammar);
        var inventory=SemanticRulesLoader.inventory(grammar);
        var hostGrammar=UBNFMapper.parse("grammar Host { @ubnf: v2 @package: diagnostic.fixture token TEXT ::= ANY; @root Root ::= { TEXT }; }").grammars().get(0);
        var files=new ArrayList<JavaFileObject>();
        for(CodeGenerator generator:List.of(new ASTGenerator(),new ParserGenerator(),new MapperGenerator())){
            var source=generator.generate(grammar);files.add(source(source.packageName()+"."+source.className(),source.source()));
        }
        for(var source:List.of(new ParserGenerator().generate(hostGrammar),new LSPGenerator().generate(hostGrammar)))files.add(source(source.packageName()+"."+source.className(),source.source()));
        files.add(source("diagnostic.fixture.Server","""
            package diagnostic.fixture;
            public class Server extends HostLanguageServer {
                public java.util.function.Function<org.unlaxer.source.DocumentSnapshot,org.unlaxer.source.LanguageQueries> binding;
                @Override protected org.unlaxer.source.LanguageQueries languageQueries(org.unlaxer.source.DocumentSnapshot host) {return binding.apply(host);}
                @Override protected java.util.Set<org.unlaxer.source.LanguageRegions.Operation> languageQueryCapabilities(){return java.util.Set.of(org.unlaxer.source.LanguageRegions.Operation.VALIDATE);}
            }
            """));
        var compiler=ToolProvider.getSystemJavaCompiler();var errors=new StringWriter();
        try(var manager=compiler.getStandardFileManager(null,null,null)){assertTrue(errors.toString(),compiler.getTask(new PrintWriter(errors),manager,null,List.of("--release","17","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,files).call());}
        var events=Files.readAllLines(fixtures.resolve("events.jsonl")).stream().map(JsonParser::parseString).map(JsonElement::getAsJsonObject).toList();
        var reports=new ArrayList<JsonArray>();
        try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())){
            var type=loader.loadClass("diagnostic.fixture.Server");var server=(LanguageServer)type.getConstructor().newInstance();
            var parse=loader.loadClass("diagnostic.fixture.TypedModelMapper").getMethod("parseEditorCst",String.class,List.class,EditorCst.Options.class);
            type.getField("binding").set(server,(Function<DocumentSnapshot,LanguageQueries>)host -> binding(host,program,inventory,text -> {
                try{return (EditorCst)parse.invoke(null,text,List.of("?", ")", ";", "}", "a", ":", "{"),EditorCst.Options.defaults());}
                catch(ReflectiveOperationException error){throw new AssertionError(error);}
            }));
            var messages=new ArrayList<JsonObject>();
            var client=(LanguageClient)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{LanguageClient.class},(proxy,method,args)->{
                if(method.getName().equals("publishDiagnostics")||method.getName().equals("logMessage")){
                    var message=new JsonObject();message.addProperty("method",method.getName().equals("publishDiagnostics")?"textDocument/publishDiagnostics":"window/logMessage");message.add("params",JSON.toJsonTree(args[0]));messages.add(message);
                }return null;
            });
            type.getMethod("connect",LanguageClient.class).invoke(server,client);var capabilities=server.initialize(new InitializeParams()).join().getCapabilities();
            var validation=JSON.toJsonTree(capabilities.getExperimental()).getAsJsonObject().getAsJsonObject("languageQueryConsumer").getAsJsonObject("operations").getAsJsonObject("VALIDATE");
            assertTrue(validation.get("available").getAsBoolean());
            var service=server.getTextDocumentService();
            for(var event:events){messages.clear();var params=event.get("params");switch(event.get("method").getAsString()){
                case "textDocument/didOpen"->service.didOpen(JSON.fromJson(params,DidOpenTextDocumentParams.class));
                case "textDocument/didChange"->service.didChange(JSON.fromJson(params,DidChangeTextDocumentParams.class));
                case "textDocument/didSave"->service.didSave(JSON.fromJson(params,DidSaveTextDocumentParams.class));
                case "textDocument/didClose"->service.didClose(JSON.fromJson(params,DidCloseTextDocumentParams.class));
                default->throw new AssertionError();
            }var actual=normalize(messages);check(event,actual);reports.add(actual);}
        }
        Path project=temporary.newFolder().toPath();Files.createDirectory(project.resolve("src"));Files.copy(fixtures.resolve("probe.rs"),project.resolve("src/main.rs"));
        Path generated=Files.createDirectory(project.resolve("src/generated"));
        for(var file:new RustBackend().generate(grammar))Files.writeString(generated.resolve(file.relativePath()),file.content());
        StringBuilder manifest=new StringBuilder("[package]\nname=\"semantic-diagnostic-lsp-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[dependencies]\nserde_json=\"1\"\n");
        for(String name:List.of("unlaxer-runtime","unlaxer-lsp","unlaxer-generator","unlaxer-ubnf"))manifest.append(name).append("={path=\"").append(ROOT.resolve("rust").resolve(name)).append("\"}\n");
        Files.writeString(project.resolve("Cargo.toml"),manifest);
        String output=run(List.of("cargo","run","--quiet","--offline","--manifest-path",project.resolve("Cargo.toml").toString(),"--target-dir",ROOT.resolve("rust/target").toString(),"--",fixtures.resolve("events.jsonl").toString(),corpus.toString()));
        var lines=output.lines().filter(line->line.startsWith("[")).toList();assertEquals(events.size(),lines.size());
        for(int index=0;index<lines.size();index++){
            var messages=JsonParser.parseString(lines.get(index)).getAsJsonArray().asList().stream().map(JsonElement::getAsJsonObject).toList();
            var actual=normalize(messages);check(events.get(index),actual);assertEquals("Java/Rust event "+index,reports.get(index),actual);
        }
        var evidence=new ArrayList<String>(List.of("event\tjava\trust"));
        for(int i=0;i<reports.size();i++)evidence.add(i+"\t"+reports.get(i)+"\t"+normalize(JsonParser.parseString(lines.get(i)).getAsJsonArray().asList().stream().map(JsonElement::getAsJsonObject).toList()));
        Files.write(Path.of("target/semantic-diagnostics.tsv"),evidence);
    }
    private static LanguageQueries binding(DocumentSnapshot current,SemanticRules.Program program,SemanticRules.Inventory inventory,Function<String,EditorCst> parse){
        var host=current.uri().contains("stale")?new DocumentSnapshot(current.uri(),current.version()-1,current.text()):current;
        boolean closed=host.text().endsWith("}H");
        var inner=new DocumentSnapshot(host.uri()+"#typed",host.version(),host.slice(new Span(4,host.length()-(closed?2:0))));
        var language=new Language("typed","example/typed","1","TypedModel","Document");
        var map=new SegmentSourceMap(inner,List.of(new Segment(new Span(0,inner.length()),Kind.COPY,new SegmentSourceMap.Location(host,new Span(4,4+inner.length())))));
        var region=new Region("typed",null,language,new Span(0,host.length()),new Span(4,4+inner.length()),map,closed?State.COMPLETE:State.PARTIAL);
        var project=new LanguageQueries.Project("p",1,Map.of(host.uri(),host),Map.of());
        var provider=new SemanticRuleQueryProvider(program,inventory,language,"p",host.uri().contains("project")?2:1,request -> parse.apply(request.region().sourceMap().output().text()));
        var parameters=Map.<String,String>of();
        // Typed entry rejects the same identity/staleness boundaries as item queries.
        var otherLanguage=new Language("typed","example/typed","2","TypedModel","Document");
        var other=new Region("typed",null,otherLanguage,region.full(),region.body(),map,region.parseState());
        try{provider.diagnostics(new LanguageQueries.Request(other,Operation.VALIDATE,0,project,parameters));fail("wrong package accepted");}catch(IllegalArgumentException expected){}
        if(!host.uri().contains("project")){
            assertEquals(State.UNSUPPORTED,provider.diagnostics(new LanguageQueries.Request(region,Operation.COMPLETION,0,project,parameters)).state());
            var old=new LanguageQueries.Project("p",0,project.documents(),parameters);
            try{provider.diagnostics(new LanguageQueries.Request(region,Operation.VALIDATE,0,old,parameters));fail("stale project accepted");}catch(IllegalArgumentException expected){}
            var stale=new SemanticRuleQueryProvider(program,inventory,language,"p",1,request->parse.apply(""));
            try{stale.diagnostics(new LanguageQueries.Request(region,Operation.VALIDATE,0,project,parameters));fail("stale parser accepted");}catch(IllegalArgumentException expected){}
        }
        return new LanguageQueries(new LanguageRegions(host,List.of(region)),project,Map.of(language,provider));
    }
    private static JsonArray normalize(List<JsonObject> messages){
        var result=new JsonArray();for(var message:messages){var item=new JsonObject();item.add("method",message.get("method"));var params=message.getAsJsonObject("params");
            if(message.get("method").getAsString().equals("window/logMessage")){var warning=params.deepCopy();var value=warning.get("type");if(!value.getAsJsonPrimitive().isNumber())warning.addProperty("type",MessageType.valueOf(value.getAsString()).getValue());item.add("params",warning);result.add(item);continue;}
            var normalized=new JsonObject();normalized.add("uri",params.get("uri"));normalized.add("version",params.has("version")?params.get("version"):JsonNull.INSTANCE);
            var diagnostics=new JsonArray();for(var raw:params.getAsJsonArray("diagnostics")){var diagnostic=raw.getAsJsonObject();var d=new JsonObject();
                for(String field:List.of("range","message","source","data"))d.add(field,diagnostic.get(field));
                JsonElement code=diagnostic.get("code");if(code.isJsonObject())code=code.getAsJsonObject().get("left");d.add("code",code);
                JsonElement severity=diagnostic.get("severity");d.addProperty("severity",severity.isJsonPrimitive()&&severity.getAsJsonPrimitive().isNumber()?severity.getAsInt():DiagnosticSeverity.valueOf(severity.getAsString()).getValue());diagnostics.add(d);
            }normalized.add("diagnostics",diagnostics);item.add("params",normalized);result.add(item);
        }return result;
    }
    private static void check(JsonObject event,JsonArray actual){
        var observed=new JsonArray();int warnings=0;
        for(var raw:actual){var message=raw.getAsJsonObject();if(message.get("method").getAsString().equals("window/logMessage")){warnings++;continue;}
            var params=message.getAsJsonObject("params");var row=new JsonObject();row.add("uri",params.get("uri"));row.add("version",params.get("version"));var diagnostics=new JsonArray();
            for(var item:params.getAsJsonArray("diagnostics")){var diagnostic=item.getAsJsonObject();var d=new JsonObject();
                for(String key:List.of("range","code","message","severity"))d.add(key,diagnostic.get(key));
                var data=diagnostic.getAsJsonObject("data");
                assertTrue(data.get("exact").getAsBoolean());assertEquals(params.get("uri"),data.get("uri"));assertEquals(params.get("version").getAsString(),data.get("version").getAsString());
                assertEquals("unlaxer-language",diagnostic.get("source").getAsString());d.add("state",data.get("state"));diagnostics.add(d);
            }row.add("diagnostics",diagnostics);observed.add(row);
        }
        assertEquals(event.toString(),event.get("expected"),observed);assertEquals(event.has("warnings")?event.get("warnings").getAsInt():0,warnings);
    }
    private String run(List<String> command)throws Exception{Path log=temporary.newFile().toPath();var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("CARGO_INCREMENTAL","0");var process=builder.start();if(!process.waitFor(240,TimeUnit.SECONDS)){process.destroyForcibly();fail("timeout "+command);}String output=Files.readString(log);assertEquals(output,0,process.exitValue());return output;}
    private static JavaFileObject source(String name,String text){return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return text;}};}
}
