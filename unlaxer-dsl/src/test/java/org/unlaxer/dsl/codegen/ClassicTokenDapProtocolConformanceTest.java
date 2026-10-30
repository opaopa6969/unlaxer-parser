package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.codegen.ClassicDapProtocolConformanceTest.Wire;

/** Real Java/Rust token-mode DAP processes, independent terminal/UTF-16/CRLF/EOF oracle. */
public class ClassicTokenDapProtocolConformanceTest {
 @Rule public org.junit.rules.TemporaryFolder temporary=new org.junit.rules.TemporaryFolder();
 private static final Path ROOT=Path.of("..").toAbsolutePath().normalize();
 private final List<String> evidence=new ArrayList<>(List.of("backend\tfixture\tbase\tline_offset\tsteps"));
 @Test public void generatedTokenDapUsesRealBoundedTerminalTrace() throws Exception {
  var grammarFile=ROOT.resolve("docs/fixtures/classic-token-dap/grammar.ubnf");String text=Files.readString(grammarFile);var grammar=UBNFMapper.parse(text).grammars().get(0);
  var sources=new ArrayList<JavaFileObject>();for(var file:List.of(new ParserGenerator().generate(grammar),new ASTGenerator().generate(grammar),new MapperGenerator().generate(grammar),new DAPGenerator().generate(grammar)))sources.add(source(file.packageName()+"."+file.className(),file.source()));
  sources.add(source("wire.tokens.Main","""
   package wire.tokens;
   public class Main {
    public static void main(String[] args)throws Exception {
     var server=new TokenInspectionDebugAdapter(){
      String wireFailure = "";
      @Override protected Object parseDebugAst(String source) {
       if (wireFailure.equals("ast-empty")) return null;
       if (wireFailure.equals("ast-span")) return new Object();
       if (wireFailure.equals("ast-throw")) throw new IllegalStateException("fixture mapper failure");
       return super.parseDebugAst(source);
      }
      @Override protected DebugSource resolveDebugSource(String program,String original,java.util.Map<String,Object> args){
       wireFailure = String.valueOf(args.getOrDefault("wireFailure", ""));
       if (wireFailure.equals("source-throw")) throw new IllegalArgumentException("fixture source failure");
       if (wireFailure.equals("source-null")) return null;
       return debugSource(args.getOrDefault("sourceText",original).toString(),((Number)args.getOrDefault("lineOffset",0)).intValue());}
     };
     var launcher=org.eclipse.lsp4j.debug.launch.DSPLauncher.createServerLauncher(server,System.in,System.out);server.connect(launcher.getRemoteProxy());launcher.startListening().get();
    }
   }
   """));
  Path classes=temporary.newFolder("classes").toPath();var errors=new StringWriter();assertTrue(errors.toString(),ToolProvider.getSystemJavaCompiler().getTask(errors,null,null,List.of("-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,sources).call());
  String java=Path.of(System.getProperty("java.home"),"bin/java").toString();try(var wire=new Wire(new ProcessBuilder(java,"-cp",classes+File.pathSeparator+System.getProperty("java.class.path"),"wire.tokens.Main").redirectError(temporary.newFile("java-stderr.log")).start())){exercise(wire,"java");}
  Assume.assumeTrue("[assumption] token DAP Rust conformance requires -DrustConformance=true",Boolean.getBoolean("rustConformance"));
  Path project=temporary.newFolder("rust").toPath(),modules=project.resolve("src/generated");Files.createDirectories(modules);Path nativeOutput=project.resolve("native");
  run(List.of("cargo","run","--quiet","--locked","--manifest-path",ROOT.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--bin","unlaxer","--","generate","--target","rust","--grammar",grammarFile.toString(),"--output",nativeOutput.toString(),"--dap"));
  for(var file:new RustBackend().generateWithProtocols(grammar,false,true)){assertEquals("Java/native artifact "+file.relativePath(),file.content(),Files.readString(nativeOutput.resolve(file.relativePath())));Files.writeString(modules.resolve(file.relativePath()),file.content());}
  Files.writeString(project.resolve("Cargo.toml"),"[package]\nname=\"classic-token-dap-fixture\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[dependencies]\nunlaxer-runtime={path=\""+ROOT.resolve("rust/unlaxer-runtime")+"\"}\nunlaxer-dap={path=\""+ROOT.resolve("rust/unlaxer-dap")+"\"}\nserde_json=\"1\"\n");
  Files.writeString(project.resolve("src/main.rs"),"""
   mod generated;
   struct Backend;
   impl unlaxer_dap::Backend for Backend {
    fn supports_token_trace(&self)->bool {true}
    fn steps(&mut self,s:&unlaxer_runtime::source::Snapshot,args:&serde_json::Value)->Result<Vec<unlaxer_dap::Step>,String>{
     match args["wireFailure"].as_str().unwrap_or("") {
      "ast-empty" => return Ok(vec![]),
      "ast-span" => return Err("AST source span unavailable".into()),
      "ast-throw" => return Err("fixture mapper failure".into()),
      _ => {}
     }
     unlaxer_dap::Backend::steps(&mut generated::dap::backend(),s,args)}
    fn resolve_source(&mut self,_:&str,original:&str,args:&serde_json::Value)->Result<unlaxer_dap::DebugSource,String>{
     match args["wireFailure"].as_str().unwrap_or("") {
      "source-throw" => return Err("fixture source failure".into()),
      "source-null" => return Err("resolveDebugSource returned no content".into()),
      _ => {}
     }
     Ok(unlaxer_dap::DebugSource{text:args["sourceText"].as_str().unwrap_or(original).into(),line_offset:args["lineOffset"].as_u64().unwrap_or(0) as usize})}
   }
   fn main(){unlaxer_dap::Server::new(Backend).serve_stdio().unwrap();}
   """);
  for(String host:List.of("rust","rust-native")){
   if(host.equals("rust-native"))for(var file:new RustBackend().generateWithProtocols(grammar,false,true))Files.copy(nativeOutput.resolve(file.relativePath()),modules.resolve(file.relativePath()),StandardCopyOption.REPLACE_EXISTING);
   run(List.of("cargo","build","--quiet","--offline","--manifest-path",project.resolve("Cargo.toml").toString()));
   try(var wire=new Wire(new ProcessBuilder(ROOT.resolve("rust/target/debug/classic-token-dap-fixture").toString()).redirectError(temporary.newFile(host+"-stderr.log")).start())){exercise(wire,host);}
  }
  Files.createDirectories(Path.of("target"));Files.write(Path.of("target/classic-token-dap-protocol.tsv"),evidence);
 }
 private void exercise(Wire wire,String backend)throws Exception {
        wire.failures = new DapFailureConformance("classic-token", backend);
        wire.probeFailures("fresh");
        DapLaunchStateConformance.check(wire, "classic-token", backend, temporary.getRoot().toPath());
        DapWireConformance.check(wire, "classic-token", backend, temporary.getRoot().toPath());
  var rows=JsonParser.parseString(Files.readString(ROOT.resolve("docs/fixtures/classic-token-dap/steps.json"))).getAsJsonArray();assertEquals(8,rows.size());
  for(boolean base:List.of(true,false))for(int offset:List.of(0,7)){
   wire.request("initialize",Map.of("adapterID","token-fixture","linesStartAt1",base,"columnsStartAt1",base));
   for(var element:rows){var row=element.getAsJsonObject();String name=row.get("name").getAsString(),input=row.get("input").getAsString();var steps=row.getAsJsonArray("steps");Path file=temporary.newFile(UUID.randomUUID()+"😀.txt").toPath();Files.writeString(file,offset==0?input:"outer\n".repeat(offset)+input);
    var args=new LinkedHashMap<String,Object>();args.put("program",file.toString());args.put("stopOnEntry",true);args.put("runtimeMode","inspection-token");args.put("lineOffset",offset);if(offset>0)args.put("sourceText",input);
    wire.request("launch",args);wire.event("initialized");wire.request("setBreakpoints",Map.of("source",Map.of("path",file.toString()),"breakpoints",List.of()));wire.request("configurationDone",Map.of());
    evidence.add(backend+"\t"+name+"\t"+base+"\t"+offset+"\t"+steps.size());
    if(steps.isEmpty()){assertEquals("stderr",wire.event("output").get("category").getAsString());wire.event("terminated");wire.event("exited");assertTrue(wire.request("stackTrace",Map.of("threadId",1)).getAsJsonArray("stackFrames").isEmpty());continue;}
    assertEquals("entry",wire.event("stopped").get("reason").getAsString());
    for(int index=0;index<steps.size();index++){
     var expected=steps.get(index).getAsJsonObject();var frame=wire.request("stackTrace",Map.of("threadId",1)).getAsJsonArray("stackFrames").get(0).getAsJsonObject();
     for(String key:List.of("line","endLine"))assertEquals(name+" "+key,expected.get(key).getAsInt()+offset-(base?0:1),frame.get(key).getAsInt());for(String key:List.of("column","endColumn"))assertEquals(name+" "+key,expected.get(key).getAsInt()-(base?0:1),frame.get(key).getAsInt());
     assertEquals(name,expected.get("label").getAsString()+" ("+(index+1)+"/"+steps.size()+")",frame.get("name").getAsString());assertEquals(file.toString(),frame.getAsJsonObject("source").get("path").getAsString());
     assertEquals("Current Token",wire.request("scopes",Map.of("frameId",0)).getAsJsonArray("scopes").get(0).getAsJsonObject().get("name").getAsString());
     var variables=new HashMap<String,JsonObject>();for(var item:wire.request("variables",Map.of("variablesReference",1)).getAsJsonArray("variables")){var v=item.getAsJsonObject();variables.put(v.get("name").getAsString(),v);}
     var variable=variables.get(expected.get("label").getAsString());assertEquals("Token",variable.get("type").getAsString());assertEquals(name,"\""+expected.get("text").getAsString().strip().replace("\"","\\\"")+"\"",variable.get("value").getAsString());assertEquals("0",variables.get("astNodeCount").get("value").getAsString());assertEquals("",variables.get("astCurrentNode").get("value").getAsString());
     if(index==0){DapFailureConformance.invalidRequest(wire.response("next",Map.of("threadId",99)));DapFailureConformance.assertError(wire.response("launch",Map.of("program","unused","steppingMode","unsupported")), "java.util.concurrent.CompletionException: java.lang.IllegalArgumentException: invalid program or stepping mode");assertEquals(frame,wire.request("stackTrace",Map.of("threadId",1)).getAsJsonArray("stackFrames").get(0));}
     wire.request("next",Map.of("threadId",1));if(index+1<steps.size())assertEquals("step",wire.event("stopped").get("reason").getAsString());else{wire.event("output");wire.event("terminated");wire.event("exited");}
    }
    // A breakpoint on the EOF line reaches the real zero-width EOF token.
    args.put("stopOnEntry",false);wire.request("launch",args);wire.event("initialized");int eofLine=steps.get(steps.size()-1).getAsJsonObject().get("line").getAsInt()+offset-(base?0:1);wire.request("setBreakpoints",Map.of("source",Map.of("path",file.toString()),"breakpoints",List.of(Map.of("line",eofLine))));wire.request("configurationDone",Map.of());assertEquals("breakpoint",wire.event("stopped").get("reason").getAsString());assertTrue(wire.request("stackTrace",Map.of("threadId",1)).getAsJsonArray("stackFrames").get(0).getAsJsonObject().get("name").getAsString().startsWith("EndOfSourceParser"));wire.request("continue",Map.of("threadId",1));wire.event("output");wire.event("terminated");wire.event("exited");
   }
  }
  wire.failures.complete(); wire.failures = null;
  DapArgumentConformance.check(wire, "classic-token", backend, temporary.getRoot().toPath());
  wire.request("disconnect",Map.of());
 }
 private void run(List<String> command)throws Exception{Path log=temporary.newFile().toPath();var b=new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());b.environment().put("CARGO_TARGET_DIR",ROOT.resolve("rust/target").toString());b.environment().put("CARGO_INCREMENTAL","0");var p=b.start();try{assertTrue("timeout "+command,p.waitFor(180,TimeUnit.SECONDS));assertEquals(command+"\n"+Files.readString(log),0,p.exitValue());}finally{if(p.isAlive())p.destroyForcibly();}}
 private static JavaFileObject source(String name,String body){return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignore){return body;}};}
}
