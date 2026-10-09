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

/** Independent source-position oracle through actual generated Java/Rust stdio servers. */
public class ClassicLspProtocolConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final String GRAMMAR = "grammar Emoji { @package: wire.fixture token INNER = NEGATION('>') @root @mapping(EmojiAst) Root ::= '<' { INNER } '>' '!'; }";
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    @Test public void generatedServersShareIndependentPositionsAndVersionTransactions() throws Exception {
        var grammar = UBNFMapper.parse(GRAMMAR).grammars().get(0);
        var sources = new ArrayList<JavaFileObject>();
        for (var generated : List.of(new ParserGenerator().generate(grammar),new LSPGenerator().generate(grammar))) {
            sources.add(source(generated.packageName()+"."+generated.className(),generated.source()));
        }
        sources.add(source("wire.fixture.Main", """
            package wire.fixture;
            public final class Main {
                public static void main(String[] args) throws Exception {
                    var server = new EmojiLanguageServer() {};
                    var launcher = org.eclipse.lsp4j.launch.LSPLauncher.createServerLauncher(server, System.in, System.out);
                    server.connect(launcher.getRemoteProxy());
                    launcher.startListening().get();
                    System.exit(0);
                }
            }
            """));
        Path classes = temporary.newFolder("java-classes").toPath();
        var errors=new StringWriter();var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,null,null)) {
            assertTrue(errors.toString(),compiler.getTask(new PrintWriter(errors),manager,null,List.of("--release","17","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,sources).call());
        }
        String javaExecutable=Path.of(System.getProperty("java.home"),"bin","java").toString();
        try(var wire=new Wire(new ProcessBuilder(javaExecutable,"-cp",classes+File.pathSeparator+System.getProperty("java.class.path"),"wire.fixture.Main").redirectError(temporary.newFile("java-stderr.log")).start())) {
            exercise(wire);
        }
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] Rust stdio cross-check requires -DrustConformance=true; Java protocol oracle ran");
            return;
        }
        Path project=temporary.newFolder("rust-project").toPath(), modules=project.resolve("src/generated");Files.createDirectories(modules);
        Path grammarFile=project.resolve("fixture.ubnf");Files.writeString(grammarFile,GRAMMAR);
        Path nativeOutput=project.resolve("native");
        run(List.of("cargo","run","--quiet","--locked","--manifest-path",ROOT.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--bin","unlaxer","--","generate","--target","rust","--grammar",grammarFile.toString(),"--output",nativeOutput.toString(),"--lsp"),ROOT);
        Path javaOutput=project.resolve("java-host");
        run(List.of(javaExecutable,"-cp",System.getProperty("java.class.path"),"org.unlaxer.dsl.CodegenMain","generate","--target","rust","--grammar",grammarFile.toString(),"--output",javaOutput.toString(),"--lsp"),ROOT);
        run(List.of(javaExecutable,"-cp",System.getProperty("java.class.path"),"org.unlaxer.dsl.CodegenMain","generate","--target","rust","--grammar",grammarFile.toString(),"--output",javaOutput.toString(),"--lsp","--check"),ROOT);
        var generated=new RustBackend().generateWithLsp(grammar);assertEquals(6,generated.size());
        assertEquals(5,new RustBackend().generate(grammar).size());
        for(var file:generated) {
            assertEquals("Java/native artifact "+file.relativePath(),file.content(),Files.readString(nativeOutput.resolve(file.relativePath())));
            assertEquals("Java CLI artifact "+file.relativePath(),file.content(),Files.readString(javaOutput.resolve(file.relativePath())));
            Files.writeString(modules.resolve(file.relativePath()),file.content());
        }
        String runtime=ROOT.resolve("rust/unlaxer-runtime").toString().replace("\\","\\\\");
        String lsp=ROOT.resolve("rust/unlaxer-lsp").toString().replace("\\","\\\\");
        Files.writeString(project.resolve("Cargo.toml"),"[package]\nname=\"classic-lsp-fixture\"\nversion=\"0.1.0\"\nedition=\"2021\"\n[dependencies]\nunlaxer-runtime={path=\""+runtime+"\"}\nunlaxer-lsp={path=\""+lsp+"\"}\n");
        Files.writeString(project.resolve("src/main.rs"),"mod generated; fn main() { generated::lsp::serve_stdio().unwrap(); }\n");
        run(List.of("cargo","build","--quiet","--offline","--manifest-path",project.resolve("Cargo.toml").toString()),ROOT);
        Path executable=ROOT.resolve("rust/target/debug/classic-lsp-fixture");
        try(var wire=new Wire(new ProcessBuilder(executable.toString()).redirectError(temporary.newFile("rust-stderr.log")).start())) { exercise(wire); }
    }
    private void exercise(Wire wire) throws Exception {
        JsonObject initialized=wire.request("initialize",Map.of("capabilities",Map.of()));
        assertEquals(1,initialized.getAsJsonObject("capabilities").get("textDocumentSync").getAsInt());
        assertNotNull(initialized.getAsJsonObject("capabilities").get("completionProvider"));
        var consumer=initialized.getAsJsonObject("capabilities").getAsJsonObject("experimental").getAsJsonObject("languageQueryConsumer");
        assertEquals(1,consumer.get("schemaVersion").getAsInt());
        var operations=consumer.getAsJsonObject("operations");assertEquals(7,operations.size());
        for(String name:List.of("VALIDATE","COMPLETION","HOVER","DEFINITION","RENAME","FORMAT","CODE_ACTION")) {
            var operation=operations.getAsJsonObject(name);
            assertEquals(name,Set.of("VALIDATE","COMPLETION","HOVER","DEFINITION").contains(name),operation.get("transport").getAsBoolean());
            assertFalse(name,operation.get("providerRegistered").getAsBoolean());
            assertTrue(name,operation.get("profileAllowed").getAsBoolean());
            assertFalse(name,operation.get("available").getAsBoolean());
        }
        wire.notify("initialized",Map.of());
        var rows=Files.readAllLines(ROOT.resolve("docs/fixtures/classic-lsp/positions.tsv")).stream().filter(l->!l.startsWith("#")).toList();assertEquals(18,rows.size());
        int version=0;String uri="file:///wire😀";
        for(String row:rows) {
            String[] fields=row.split("\t",-1);String source=fields[1].equals("~")?"":fields[1].replace("\\r","\r").replace("\\n","\n");
            int[] expected=Arrays.stream(fields,2,8).mapToInt(Integer::parseInt).toArray();
            wire.notify("textDocument/didOpen",Map.of("textDocument",Map.of("uri",uri,"languageId","emoji","version",++version,"text",source)));
            var published=wire.notification("textDocument/publishDiagnostics");assertEquals(version,published.get("version").getAsInt());
            var diagnostics=published.getAsJsonArray("diagnostics");
            if(expected[0]<0)assertEquals(fields[0],0,diagnostics.size());
            else {
                assertEquals(fields[0],1,diagnostics.size());var range=diagnostics.get(0).getAsJsonObject().getAsJsonObject("range");
                assertEquals(fields[0],expected[0],range.getAsJsonObject("start").get("line").getAsInt());
                assertEquals(fields[0],expected[1],range.getAsJsonObject("start").get("character").getAsInt());
                assertEquals(fields[0],expected[2],range.getAsJsonObject("end").get("line").getAsInt());
                assertEquals(fields[0],expected[3],range.getAsJsonObject("end").get("character").getAsInt());
            }
            JsonArray completion=wire.requestArray("textDocument/completion",Map.of("textDocument",Map.of("uri",uri),"position",Map.of("line",expected[4],"character",expected[5])));
            assertEquals(fields[0],Boolean.parseBoolean(fields[8]),!completion.isEmpty());
            if(!completion.isEmpty())assertTrue(completion.asList().stream().anyMatch(item->item.getAsJsonObject().get("label").getAsString().equals("grammar")));
            wire.notify("textDocument/didClose",Map.of("textDocument",Map.of("uri",uri)));
            assertEquals(0,wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics").size());
        }
        wire.notify("textDocument/didOpen",Map.of("textDocument",Map.of("uri",uri,"languageId","emoji","version",2,"text","<😀>!")));
        assertEquals(0,wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics").size());
        for(Object changes:List.of(List.of(Map.of("text","bad")),List.of(),List.of(Map.of("text","bad","range",Map.of("start",Map.of("line",0,"character",0),"end",Map.of("line",0,"character",1)))))) {
            int next=changes instanceof List<?> list && list.size()==1 && !((Map<?,?>)list.get(0)).containsKey("range")?1:3;
            wire.notify("textDocument/didChange",Map.of("textDocument",Map.of("uri",uri,"version",next),"contentChanges",changes));
            assertFalse(wire.requestArray("textDocument/completion",Map.of("textDocument",Map.of("uri",uri),"position",Map.of("line",0,"character",5))).isEmpty());
            assertTrue("Rejected update must not publish new diagnostics",wire.pending.isEmpty());
        }
        wire.notify("textDocument/didChange",Map.of("textDocument",Map.of("uri",uri,"version",3),"contentChanges",List.of(Map.of("text","bad"),Map.of("text","<>"))));
        assertEquals(1,wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics").size());
        assertTrue(wire.requestArray("textDocument/completion",Map.of("textDocument",Map.of("uri",uri),"position",Map.of("line",0,"character",5))).isEmpty());
        wire.notify("textDocument/didClose",Map.of("textDocument",Map.of("uri",uri)));assertEquals(0,wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics").size());
        // Java/Rust use the same profile API; explicit null or extra fields must fail without mutating the selected profile.
        String profile=Files.readString(ROOT.resolve("language-profiles/java/profile.tsv")).replace("Java21","Emoji").replace("CompilationUnit","Root");
        var selected=wire.request("initialize",Map.of("initializationOptions",Map.of("languageProfile",Map.of("tsv",profile,"entry","Root"))));
        assertFalse(selected.getAsJsonObject("capabilities").has("completionProvider"));
        var metadata=selected.getAsJsonObject("capabilities").getAsJsonObject("experimental").getAsJsonObject("languageProfile");assertEquals("Root",metadata.get("entry").getAsString());assertEquals("lang/java",metadata.get("package").getAsString());
        for(Object invalid:List.of(JsonNull.INSTANCE,Map.of("tsv",profile,"entry","wrong"),Map.of("tsv",profile.replace("PARSE\tPARTIAL","PARSE\tEXTERNAL"),"entry","Root"),Map.of("tsv",profile,"entry","Root","extra",true))) {
            var error=wire.response("initialize",Map.of("initializationOptions",Map.of("languageProfile",invalid))).getAsJsonObject("error");assertEquals(-32602,error.get("code").getAsInt());
        }
        assertTrue(wire.requestArray("textDocument/completion",Map.of("textDocument",Map.of("uri",uri),"position",Map.of("line",0,"character",0))).isEmpty());
        wire.notify("textDocument/didOpen",Map.of("textDocument",Map.of("uri",uri,"languageId","emoji","version",1,"text","bad")));
        assertEquals("VALIDATE EXTERNAL without provider publishes no syntax diagnostics",0,wire.notification("textDocument/publishDiagnostics").getAsJsonArray("diagnostics").size());
        wire.response("shutdown",null);wire.notify("exit",null);
    }
    private void run(List<String> command,Path directory)throws Exception {
        ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);builder.environment().put("CARGO_INCREMENTAL","0");builder.environment().put("CARGO_TARGET_DIR",ROOT.resolve("rust/target").toString());
        Path log=temporary.newFile().toPath();builder.redirectOutput(log.toFile());var process=builder.start();
        try { assertTrue("command timeout: "+command,process.waitFor(120,TimeUnit.SECONDS));assertEquals(command+"\n"+Files.readString(log),0,process.exitValue()); } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static JavaFileObject source(String name,String body){return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignore){return body;}};}
    private static final class Wire implements AutoCloseable {
        final Process process;final BufferedInputStream input;final OutputStream output;final ExecutorService reader=Executors.newSingleThreadExecutor();final List<JsonObject> pending=new ArrayList<>();int next=0;
        Wire(Process process){this.process=process;input=new BufferedInputStream(process.getInputStream());output=process.getOutputStream();}
        void notify(String method,Object params)throws Exception{send(method,params,null);}
        void send(String method,Object params,Integer id)throws Exception {var message=new JsonObject();message.addProperty("jsonrpc","2.0");message.addProperty("method",method);if(params!=null)message.add("params",JSON.toJsonTree(params));if(id!=null)message.addProperty("id",id);byte[] body=JSON.toJson(message).getBytes(StandardCharsets.UTF_8);output.write(("Content-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));output.write(body);output.flush();}
        JsonObject read()throws Exception{return reader.submit(()->{int length=-1;StringBuilder line=new StringBuilder();while(true){int unit=input.read();if(unit<0)throw new EOFException();line.append((char)unit);if(unit=='\n'){String header=line.toString();line.setLength(0);if(header.equals("\r\n"))break;if(header.startsWith("Content-Length:"))length=Integer.parseInt(header.substring(15).trim());}}if(length<0||length>4*1024*1024)throw new IOException("invalid frame");byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException();return JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}).get(20,TimeUnit.SECONDS);}
        JsonObject response(String method,Object params)throws Exception{int id=++next;send(method,params,id);while(true){var result=read();if(result.has("id")&&result.get("id").getAsInt()==id)return result;pending.add(result);}}
        JsonObject request(String method,Object params)throws Exception{var response=response(method,params);assertFalse(response.toString(),response.has("error"));return response.getAsJsonObject("result");}
        JsonArray requestArray(String method,Object params)throws Exception{var response=response(method,params);assertFalse(response.toString(),response.has("error"));return response.getAsJsonArray("result");}
        JsonObject notification(String method)throws Exception{for(var iterator=pending.iterator();iterator.hasNext();){var candidate=iterator.next();if(candidate.has("method")&&candidate.get("method").getAsString().equals(method)){iterator.remove();return candidate.getAsJsonObject("params");}}while(true){var candidate=read();if(candidate.has("method")&&candidate.get("method").getAsString().equals(method))return candidate.getAsJsonObject("params");pending.add(candidate);}}
        @Override public void close()throws Exception{output.close();process.destroyForcibly();reader.shutdownNow();process.waitFor(5,TimeUnit.SECONDS);}
    }
}
