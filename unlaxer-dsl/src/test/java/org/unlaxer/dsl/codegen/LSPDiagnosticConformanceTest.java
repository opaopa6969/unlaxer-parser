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
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.*;
import org.unlaxer.source.SegmentSourceMap.*;

/** Actual javac metadata crosses generated Java/Rust LSP consumers and shared multi-host lifecycle events. */
public class LSPDiagnosticConformanceTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary=new org.junit.rules.TemporaryFolder();
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
    @Test public void realCompilerDiagnosticsUseOwnedDocumentsAndExactVersions() throws Exception {
        assumeTrue("requires -DrustConformance=true, Rust and pinned JDK",Boolean.getBoolean("rustConformance"));
        assertTrue(Runtime.version().toString().startsWith("21.0.9"));
        Path fixtures=ROOT.resolve("docs/fixtures/language-diagnostics"),classes=temporary.newFolder().toPath();
        var grammar=UBNFMapper.parse("grammar Host { @ubnf: v2 @package: diagnostic.fixture token TEXT ::= ANY; @root Root ::= { TEXT }; }").grammars().get(0);
        var files=new ArrayList<JavaFileObject>();
        for(var source:List.of(new ParserGenerator().generate(grammar),new LSPGenerator().generate(grammar)))files.add(source(source.packageName()+"."+source.className(),source.source()));
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
            type.getField("binding").set(server,(Function<DocumentSnapshot,LanguageQueries>)LSPDiagnosticConformanceTest::binding);
            var messages=new ArrayList<JsonObject>();
            var client=(LanguageClient)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{LanguageClient.class},(proxy,method,args)->{
                if(method.getName().equals("publishDiagnostics")||method.getName().equals("logMessage")){
                    var message=new JsonObject();message.addProperty("method",method.getName().equals("publishDiagnostics")?"textDocument/publishDiagnostics":"window/logMessage");message.add("params",JSON.toJsonTree(args[0]));messages.add(message);
                }return null;
            });
            type.getMethod("connect",LanguageClient.class).invoke(server,client);server.initialize(new InitializeParams()).join();
            var service=server.getTextDocumentService();
            for(var event:events){messages.clear();var params=event.get("params");switch(event.get("method").getAsString()){
                case "textDocument/didOpen"->service.didOpen(JSON.fromJson(params,DidOpenTextDocumentParams.class));
                case "textDocument/didChange"->service.didChange(JSON.fromJson(params,DidChangeTextDocumentParams.class));
                case "textDocument/didSave"->service.didSave(JSON.fromJson(params,DidSaveTextDocumentParams.class));
                case "textDocument/didClose"->service.didClose(JSON.fromJson(params,DidCloseTextDocumentParams.class));
                default->throw new AssertionError();
            }var actual=normalize(messages);check(event,actual);reports.add(actual);}
        }
        Path project=temporary.newFolder().toPath();Files.createDirectory(project.resolve("src"));Files.copy(fixtures.resolve("lsp_probe.rs"),project.resolve("src/main.rs"));
        Files.writeString(project.resolve("Cargo.toml"),"[package]\nname=\"diagnostic-lsp-probe\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[dependencies]\nserde_json=\"1\"\nunlaxer-runtime={path=\""+ROOT.resolve("rust/unlaxer-runtime")+"\"}\nunlaxer-lsp={path=\""+ROOT.resolve("rust/unlaxer-lsp")+"\"}\n");
        String output=run(List.of("cargo","run","--quiet","--offline","--manifest-path",project.resolve("Cargo.toml").toString(),"--target-dir",ROOT.resolve("rust/target").toString(),"--",fixtures.resolve("events.jsonl").toString(),java(),System.getProperty("java.class.path")));
        var lines=output.lines().filter(line->line.startsWith("[")).toList();assertEquals(events.size(),lines.size());
        for(int index=0;index<lines.size();index++){
            var messages=JsonParser.parseString(lines.get(index)).getAsJsonArray().asList().stream().map(JsonElement::getAsJsonObject).toList();
            var actual=normalize(messages);check(events.get(index),actual);assertEquals("Java/Rust event "+index,reports.get(index),actual);
        }
    }
    private static LanguageQueries binding(DocumentSnapshot current){
        var host=current.uri().contains("stale")?new DocumentSnapshot(current.uri(),current.version()-1,current.text()):current;
        var inner=new DocumentSnapshot(host.uri()+"#java",host.version(),host.slice(new Span(4,host.length()-2)));
        var dependency=new DocumentSnapshot("file:///Dep.java",5,"//😀\r\nclass Dep { String x = 2; }\r\n");
        var language=new Language("java","lang/java","0.1.0","Java21","CompilationUnit");
        var map=new SegmentSourceMap(inner,List.of(new Segment(new Span(0,inner.length()),Kind.COPY,new SegmentSourceMap.Location(host,new Span(4,host.length()-2)))));
        var region=new Region("java",null,language,new Span(0,host.length()),new Span(4,host.length()-2),map,State.COMPLETE);
        var project=new LanguageQueries.Project("p",1,Map.of(host.uri(),host,dependency.uri(),dependency),Map.of());
        var process=new ProviderProcess(List.of(java(),"-cp",System.getProperty("java.class.path"),"org.unlaxer.dsl.provider.JavacProvider"),new ProviderProtocol.Identity("javac","21.0.9"),Set.of(Operation.VALIDATE),Duration.ofSeconds(30));
        return new LanguageQueries(new LanguageRegions(host,List.of(region)),project,Map.of(language,process));
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
        var counts=new JsonArray();int warnings=0;for(var raw:actual){var message=raw.getAsJsonObject();if(message.get("method").getAsString().equals("window/logMessage")){warnings++;continue;}
            var params=message.getAsJsonObject("params");var row=new JsonArray();row.add(params.get("uri"));row.add(params.get("version"));row.add(params.getAsJsonArray("diagnostics").size());counts.add(row);
            for(var item:params.getAsJsonArray("diagnostics")){var diagnostic=item.getAsJsonObject();assertEquals("compiler.err.prob.found.req",diagnostic.get("code").getAsString());assertEquals(1,diagnostic.get("severity").getAsInt());
                int line=params.get("uri").getAsString().endsWith("Dep.java")?1:2;var range=diagnostic.getAsJsonObject("range");assertEquals(line,range.getAsJsonObject("start").get("line").getAsInt());assertEquals(line,range.getAsJsonObject("end").get("line").getAsInt());
                int start=line==1?23:24;assertEquals(start,range.getAsJsonObject("start").get("character").getAsInt());assertEquals(start+1,range.getAsJsonObject("end").get("character").getAsInt());assertTrue(diagnostic.getAsJsonObject("data").get("exact").getAsBoolean());}
        }assertEquals(event.toString(),event.get("expected"),counts);assertEquals(event.has("warnings")?event.get("warnings").getAsInt():0,warnings);
    }
    private static String java(){return Path.of(System.getProperty("java.home"),"bin","java").toString();}
    private String run(List<String> command)throws Exception{Path log=temporary.newFile().toPath();var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("CARGO_INCREMENTAL","0");var process=builder.start();if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();fail("timeout "+command);}String output=Files.readString(log);assertEquals(output,0,process.exitValue());return output;}
    private static JavaFileObject source(String name,String text){return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return text;}};}
}
