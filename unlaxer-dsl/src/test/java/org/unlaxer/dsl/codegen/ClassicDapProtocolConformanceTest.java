package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.tools.*;
import org.junit.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Actual DAP byte frames with independent AST, source and traversal-order expectations. */
public class ClassicDapProtocolConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Gson JSON = new Gson();
    private final List<String> evidence = new ArrayList<>(List.of("backend\tfixture\tstart_at_1\tline_offset\taccepted\tast_steps"));
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    @Test public void generatedJavaAndRustShareStrictTypedAstInspection() throws Exception {
        String text=Files.readString(ROOT.resolve("docs/fixtures/classic-dap/grammar.ubnf"));
        var grammar=UBNFMapper.parse(text).grammars().get(0);
        var sources=new ArrayList<JavaFileObject>();
        for(var generated:List.of(new ParserGenerator().generate(grammar),new ASTGenerator().generate(grammar),new MapperGenerator().generate(grammar),new DAPGenerator().generate(grammar))) sources.add(source(generated.packageName()+"."+generated.className(),generated.source()));
        sources.add(source("wire.dap.Main", """
            package wire.dap;
            public final class Main {
                public static void main(String[] args) throws Exception {
                    var server = new InspectionDebugAdapter() {
                        @Override protected Object parseDebugAst(String source) {
                            if (source.equals("p<m><b>[]x!")) throw new IllegalStateException("application mapper rejected input");
                            return super.parseDebugAst(source);
                        }
                        @Override protected DebugSource resolveDebugSource(String program,String original,java.util.Map<String,Object> arguments) {
                            return debugSource(arguments.getOrDefault("sourceText",original).toString(),((Number)arguments.getOrDefault("lineOffset",0)).intValue());
                        }
                        @Override protected java.util.Map<String,String> runtimeVariables(String source,String mode,java.util.Map<String,Object> arguments) {
                            return java.util.Map.of("sourceLength",String.valueOf(source.codePointCount(0,source.length())),"runtimeLabel",mode);
                        }
                    };
                    var launcher=org.eclipse.lsp4j.debug.launch.DSPLauncher.createServerLauncher(server,System.in,System.out);
                    server.connect(launcher.getRemoteProxy());launcher.startListening().get();System.exit(0);
                }
            }
            """));
        Path classes=temporary.newFolder("java-classes").toPath();var errors=new StringWriter();var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,null,null)) { assertTrue(errors.toString(),compiler.getTask(new PrintWriter(errors),manager,null,List.of("--release","17","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,sources).call()); }
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        try(var wire=new Wire(new ProcessBuilder(java,"-cp",classes+File.pathSeparator+System.getProperty("java.class.path"),"wire.dap.Main").redirectError(temporary.newFile("java-stderr.log")).start())) { exercise(wire,"java"); }
        if(!Boolean.getBoolean("rustConformance")) {System.out.println("[assumption] Rust DAP process requires -DrustConformance=true; Java wire oracle ran");return;}
        Path project=temporary.newFolder("rust-project").toPath(),modules=project.resolve("src/generated");Files.createDirectories(modules);
        Path grammarFile=project.resolve("fixture.ubnf");Files.writeString(grammarFile,text);
        for(var flags:List.of(List.<String>of(),List.of("--lsp"),List.of("--dap"),List.of("--dap","--lsp"))) {
            Path nativeOutput=temporary.newFolder().toPath(),javaOutput=temporary.newFolder().toPath();
            var command=new ArrayList<>(List.of("cargo","run","--quiet","--locked","--manifest-path",ROOT.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--bin","unlaxer","--","generate","--target","rust","--grammar",grammarFile.toString(),"--output",nativeOutput.toString()));command.addAll(flags);run(command);
            command=new ArrayList<>(List.of(java,"-cp",System.getProperty("java.class.path"),"org.unlaxer.dsl.CodegenMain","generate","--target","rust","--grammar",grammarFile.toString(),"--output",javaOutput.toString()));command.addAll(flags);run(command);command.add("--check");run(command);
            var generated=new RustBackend().generateWithProtocols(grammar,flags.contains("--lsp"),flags.contains("--dap"));assertEquals(5+flags.size(),generated.size());
            for(var file:generated) {
                assertEquals("Java/native "+flags+" "+file.relativePath(),file.content(),Files.readString(nativeOutput.resolve(file.relativePath())));
                assertEquals("Java CLI "+file.relativePath(),file.content(),Files.readString(javaOutput.resolve(file.relativePath())));
                if(flags.contains("--dap"))Files.writeString(modules.resolve(file.relativePath()),file.content());
            }
        }
        Files.writeString(project.resolve("Cargo.toml"),"[package]\nname=\"classic-dap-fixture\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[dependencies]\nunlaxer-runtime={path=\""+ROOT.resolve("rust/unlaxer-runtime")+"\"}\nunlaxer-dap={path=\""+ROOT.resolve("rust/unlaxer-dap")+"\"}\nunlaxer-lsp={path=\""+ROOT.resolve("rust/unlaxer-lsp")+"\"}\nserde_json=\"1\"\n");
        Files.writeString(project.resolve("src/main.rs"), """
            mod generated;
            use std::collections::BTreeMap;
            use serde_json::Value;
            use unlaxer_runtime::source::Snapshot;
            struct Backend;
            impl unlaxer_dap::Backend for Backend {
                fn steps(&mut self,snapshot:&Snapshot,_:&Value)->Result<Vec<unlaxer_dap::Step>,String> {if snapshot.text=="p<m><b>[]x!" {Err("application mapper rejected input".into())} else {generated::dap::steps(&snapshot.text)}}
                fn resolve_source(&mut self,_:&str,original:&str,args:&Value)->Result<unlaxer_dap::DebugSource,String> {Ok(unlaxer_dap::DebugSource{text:args["sourceText"].as_str().unwrap_or(original).into(),line_offset:args["lineOffset"].as_u64().unwrap_or(0) as usize})}
                fn runtime_variables(&mut self,snapshot:&Snapshot,mode:&str,_:&Value)->Result<BTreeMap<String,String>,String> {Ok(BTreeMap::from([("sourceLength".into(),snapshot.len().to_string()),("runtimeLabel".into(),mode.into())]))}
            }
            fn main(){unlaxer_dap::Server::new(Backend).serve_stdio().unwrap();}
            """);
        run(List.of("cargo","build","--quiet","--offline","--manifest-path",project.resolve("Cargo.toml").toString()));
        try(var wire=new Wire(new ProcessBuilder(ROOT.resolve("rust/target/debug/classic-dap-fixture").toString()).redirectError(temporary.newFile("rust-stderr.log")).start())) {exercise(wire,"rust"); Files.createDirectories(Path.of("target"));Files.write(Path.of("target/classic-dap-protocol.tsv"),evidence);}
    }
    private void exercise(Wire wire,String backend)throws Exception {
        var rows=JsonParser.parseString(Files.readString(ROOT.resolve("docs/fixtures/classic-dap/ast.json"))).getAsJsonArray();assertEquals(9,rows.size());
        for(boolean startAt1:List.of(true,false)) for(int offset:List.of(0,7)) {
            assertTrue(wire.request("initialize",Map.of("adapterID","fixture","linesStartAt1",startAt1,"columnsStartAt1",startAt1)).get("supportsConfigurationDoneRequest").getAsBoolean());
            for(var element:rows) {
                var row=element.getAsJsonObject();String input=row.get("input").getAsString(),name=row.get("name").getAsString();assertEquals(name,row.get("length").getAsInt(),input.codePointCount(0,input.length()));
                Path file=temporary.newFile("program-"+UUID.randomUUID()+"😀.txt").toPath();Files.writeString(file,offset==0?input:"original-source-prefix\n".repeat(offset)+input);
                var args=new LinkedHashMap<String,Object>();args.put("program",file.toString());args.put("runtimeMode","inspection-ast");args.put("stopOnEntry",true);args.put("lineOffset",offset);if(offset>0)args.put("sourceText",input);
                wire.request("launch",args);wire.event("initialized");wire.request("setBreakpoints",Map.of("source",Map.of("path",file.toString()),"breakpoints",List.of()));wire.request("configurationDone",Map.of());
                var steps=row.getAsJsonArray("steps");
                evidence.add(backend+"\t"+name+"\t"+startAt1+"\t"+offset+"\t"+!steps.isEmpty()+"\t"+steps.size());
                if(steps.isEmpty()) {
                    assertEquals(name,"stderr",wire.event("output").get("category").getAsString());wire.event("terminated");wire.event("exited");assertTrue(wire.request("stackTrace",Map.of("threadId",1)).getAsJsonArray("stackFrames").isEmpty());continue;
                }
                assertEquals("entry",wire.event("stopped").get("reason").getAsString());assertEquals(1,wire.request("threads",Map.of()).getAsJsonArray("threads").get(0).getAsJsonObject().get("id").getAsInt());
                for(int index=0;index<steps.size();index++) {
                    var expected=steps.get(index).getAsJsonObject();var frame=wire.request("stackTrace",Map.of("threadId",1)).getAsJsonArray("stackFrames").get(0).getAsJsonObject();
                    if(index==0) {
                        assertFalse(wire.response("launch",Map.of("program","unused","steppingMode","unsupported")).get("success").getAsBoolean());
                        assertFalse(wire.response("next",Map.of("threadId",99)).get("success").getAsBoolean());
                        assertEquals("failed requests preserve current step",frame,wire.request("stackTrace",Map.of("threadId",1)).getAsJsonArray("stackFrames").get(0));
                    }
                    assertEquals(name,expected.get("line").getAsInt()+offset-(startAt1?0:1),frame.get("line").getAsInt());assertEquals(name,expected.get("column").getAsInt()-(startAt1?0:1),frame.get("column").getAsInt());
                    assertEquals(name,expected.get("endLine").getAsInt()+offset-(startAt1?0:1),frame.get("endLine").getAsInt());assertEquals(name,expected.get("endColumn").getAsInt()-(startAt1?0:1),frame.get("endColumn").getAsInt());
                    assertEquals(name,expected.get("label").getAsString()+" ("+(index+1)+"/"+steps.size()+")",frame.get("name").getAsString());assertEquals(file.toString(),frame.getAsJsonObject("source").get("path").getAsString());
                    assertEquals("Current AST Node",wire.request("scopes",Map.of("frameId",0)).getAsJsonArray("scopes").get(0).getAsJsonObject().get("name").getAsString());
                    var variables=new HashMap<String,JsonObject>();for(var item:wire.request("variables",Map.of("variablesReference",1)).getAsJsonArray("variables")) {var variable=item.getAsJsonObject();variables.put(variable.get("name").getAsString(),variable);}
                    var variable=variables.get(expected.get("label").getAsString());assertEquals(name,"ASTNode",variable.get("type").getAsString());assertEquals(name,"\""+expected.get("text").getAsString()+"\"",variable.get("value").getAsString());
                    assertEquals(name,steps.size(),Integer.parseInt(variables.get("astNodeCount").get("value").getAsString()));assertEquals(name,"inspection-ast",variables.get("runtimeLabel").get("value").getAsString());assertEquals(name,row.get("length").getAsString(),variables.get("sourceLength").get("value").getAsString());
                    wire.request("next",Map.of("threadId",1));
                    if(index+1<steps.size())assertEquals("step",wire.event("stopped").get("reason").getAsString());else {wire.event("output");wire.event("terminated");wire.event("exited");}
                }
                assertTrue(wire.request("stackTrace",Map.of("threadId",1)).getAsJsonArray("stackFrames").isEmpty());
                // Continue visits the next node on an actual breakpoint line, then finishes.
                wire.request("launch",args);wire.event("initialized");int breakpoint=steps.get(steps.size()-1).getAsJsonObject().get("line").getAsInt()+offset-(startAt1?0:1);
                wire.request("setBreakpoints",Map.of("source",Map.of("path",file.toString()),"breakpoints",List.of(Map.of("line",breakpoint))));wire.request("configurationDone",Map.of());wire.event("stopped");
                while(true){wire.request("continue",Map.of("threadId",1));var event=wire.oneOf("stopped","output");if(event.get("event").getAsString().equals("output")){wire.event("terminated");wire.event("exited");break;}assertEquals("breakpoint",event.getAsJsonObject("body").get("reason").getAsString());}
            }
        }
        Path noEntry=temporary.newFile("no-entry-"+backend+".txt").toPath();Files.writeString(noEntry,"p<a><b>[]x!");
        wire.request("launch",Map.of("formulaSource",noEntry.toString(),"runtimeMode","ast"));wire.event("initialized");
        assertTrue(wire.request("setBreakpoints",Map.of("source",Map.of("path",noEntry.toString()))).getAsJsonArray("breakpoints").isEmpty());
        wire.request("configurationDone",Map.of());assertEquals("stdout",wire.event("output").get("category").getAsString());wire.event("terminated");wire.event("exited");
        wire.request("launch",Map.of("program",noEntry.toString(),"runtimeMode","ast"));wire.event("initialized");
        wire.request("setBreakpoints",Map.of("source",Map.of("path",noEntry.toString()),"breakpoints",List.of(Map.of("line",0))));
        wire.request("configurationDone",Map.of());assertEquals("breakpoint",wire.event("stopped").get("reason").getAsString());
        while(true){wire.request("continue",Map.of("threadId",1));var event=wire.oneOf("stopped","output");if(event.get("event").getAsString().equals("output")){wire.event("terminated");wire.event("exited");break;}}
        for(var invalid:List.of(Map.entry("stackTrace",Map.of("threadId",99)),Map.entry("scopes",Map.of("frameId",99)),Map.entry("variables",Map.of("variablesReference",99)),Map.entry("next",Map.of("threadId",1)),Map.entry("launch",Map.of("program","missing","steppingMode","unsupported")))) assertFalse(wire.response(invalid.getKey(),invalid.getValue()).get("success").getAsBoolean());
        assertFalse(wire.response("unknown-fixture-command",Map.of()).get("success").getAsBoolean());wire.request("disconnect",Map.of());
    }
    private void run(List<String> command)throws Exception {var builder=new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true);builder.environment().put("CARGO_INCREMENTAL","0");builder.environment().put("CARGO_TARGET_DIR",ROOT.resolve("rust/target").toString());Path log=temporary.newFile().toPath();builder.redirectOutput(log.toFile());var process=builder.start();try{assertTrue("timeout: "+command,process.waitFor(120,TimeUnit.SECONDS));assertEquals(command+"\n"+Files.readString(log),0,process.exitValue());}finally{if(process.isAlive())process.destroyForcibly();}}
    private static JavaFileObject source(String name,String body){return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignore){return body;}};}
    private static final class Wire implements AutoCloseable {
        final Process process;final InputStream input;final OutputStream output;final ExecutorService reader=Executors.newSingleThreadExecutor();final List<JsonObject> pending=new ArrayList<>();int next,lastSeq;
        Wire(Process process){this.process=process;input=new BufferedInputStream(process.getInputStream());output=process.getOutputStream();}
        JsonObject read()throws Exception{return reader.submit(()->{int length=-1;var line=new StringBuilder();while(true){int b=input.read();if(b<0)throw new EOFException();line.append((char)b);if(b=='\n'){String h=line.toString();line.setLength(0);if(h.equals("\r\n"))break;if(h.startsWith("Content-Length:"))length=Integer.parseInt(h.substring(15).trim());}}if(length<0||length>4*1024*1024)throw new IOException("frame limit");byte[] body=input.readNBytes(length);if(body.length!=length)throw new EOFException();var message=JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();assertTrue(message.toString(),message.get("seq").getAsInt()>lastSeq);lastSeq=message.get("seq").getAsInt();return message;}).get(20,TimeUnit.SECONDS);}
        JsonObject response(String command,Object arguments)throws Exception {int seq=++next;var message=JSON.toJsonTree(Map.of("seq",seq,"type","request","command",command,"arguments",arguments));byte[] body=JSON.toJson(message).getBytes(StandardCharsets.UTF_8);output.write(("Content-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();while(true){var result=read();if(result.get("type").getAsString().equals("response")&&result.get("request_seq").getAsInt()==seq){assertEquals(command,result.get("command").getAsString());return result;}pending.add(result);}}
        JsonObject request(String command,Object arguments)throws Exception{var response=response(command,arguments);assertTrue(response.toString(),response.get("success").getAsBoolean());return response.has("body")?response.getAsJsonObject("body"):new JsonObject();}
        JsonObject oneOf(String... events)throws Exception{var names=Set.of(events);for(var iterator=pending.iterator();iterator.hasNext();){var candidate=iterator.next();if(candidate.has("event")&&names.contains(candidate.get("event").getAsString())){iterator.remove();return candidate;}}while(true){var candidate=read();if(candidate.has("event")&&names.contains(candidate.get("event").getAsString()))return candidate;pending.add(candidate);}}
        JsonObject event(String name)throws Exception{var event=oneOf(name);return event.has("body")?event.getAsJsonObject("body"):new JsonObject();}
        @Override public void close()throws Exception{output.close();process.destroyForcibly();reader.shutdownNow();process.waitFor(5,TimeUnit.SECONDS);}
    }
}
