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
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.RecoveryDiagnostic;
import org.unlaxer.parser.incremental.SnapshotParseCache;
import org.unlaxer.source.DocumentSnapshot;

/** Same edit/revision stream, independent expectations, and an uncached generated parser oracle. */
public class IncrementalCacheConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test public void snapshotsMatchFreshGeneratedCstAstDiagnosticsAndRecovery() throws Exception {
        Path fixture = ROOT.resolve("spec-corpus/incremental-cache");
        var rows = JsonParser.parseString(Files.readString(fixture.resolve("edits.json"))).getAsJsonArray();
        assertEquals(27, rows.size());
        Path directory = temporary.newFolder().toPath();
        var units = new ArrayList<JavaFileObject>();
        for (String name : List.of("Cache", "Other", "Recovered")) {
            var grammar = UBNFMapper.parse(Files.readString(fixture.resolve(name + ".ubnf"))).grammars().get(0);
            for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
                var generated = generator.generate(grammar);
                units.add(source(generated.packageName() + "." + generated.className(), generated.source()));
            }
            Path modules = directory.resolve(name.toLowerCase(Locale.ROOT)); Files.createDirectories(modules);
            for (var generated : new RustBackend().generate(grammar)) Files.writeString(modules.resolve(generated.relativePath()), generated.content());
        }
        var compiler = ToolProvider.getSystemJavaCompiler(); var errors = new StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            boolean ok = compiler.getTask(new PrintWriter(errors), manager, null, List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", directory.toString()), null, units).call();
            assertTrue(errors.toString(), ok);
        }
        var cache = new SnapshotParseCache<JsonObject>(64, 8192, JsonObject::deepCopy);
        try (var loader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, getClass().getClassLoader())) {
            DocumentSnapshot previous = null;
            for (var element : rows) {
                var row = element.getAsJsonObject(); String id = row.get("id").getAsString();
                var snapshot = snapshot(row); var revisions = revisions(row);
                if (row.has("edit")) {
                    var edit = row.getAsJsonArray("edit");
                    int start = previous.fromUtf16(edit.get(0).getAsInt()), end = previous.fromUtf16(edit.get(1).getAsInt());
                    String edited = previous.slice(new DocumentSnapshot.Span(0,start)) + edit.get(2).getAsString()
                        + previous.slice(new DocumentSnapshot.Span(end,previous.length()));
                    assertEquals(id + " independent edit", snapshot.text(), edited);
                }
                long hits = cache.stats().hits();
                JsonObject cached = cache.getOrCompute(snapshot,revisions,() -> observe(loader,row));
                assertEquals(id + " hit",row.get("hit").getAsBoolean(),cache.stats().hits()>hits);
                assertEquals(id + " full parse oracle",observe(loader,row),cached);
                assertEquals(id + " independent Java oracle",row.get("expected"),cached);
                previous = snapshot;
            }
        }
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] Incremental cache Rust cross-check requires -DrustConformance=true; Java oracle ran"); return;
        }
        String rustc = run(List.of("rustup","which","rustc","--toolchain","1.85.0"),directory).trim();
        Path runtime=directory.resolve("libunlaxer_runtime.rlib");
        run(List.of(rustc,"--edition=2021","--crate-type=rlib","--crate-name=unlaxer_runtime",ROOT.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),directory);
        var probe=new StringBuilder(Files.readString(fixture.resolve("probe.rs"))).append("\nfn main() { let mut cache = SnapshotParseCache::new(64,8192); let mut previous: Option<Snapshot> = None;\n");
        for (var element : rows) {
            var row=element.getAsJsonObject();
            if (row.has("edit")) {
                var edit = row.getAsJsonArray("edit");
                probe.append("{ let old = previous.as_ref().unwrap(); let a = old.from_utf16(").append(edit.get(0)).append(").unwrap(); let b = old.from_utf16(").append(edit.get(1)).append(").unwrap(); assert_eq!(format!(\"{}{}{}\", old.slice(unlaxer_runtime::Span {start:0,end:a}).unwrap(), ").append(JSON.toJson(edit.get(2))).append(", old.slice(unlaxer_runtime::Span {start:b,end:old.len()}).unwrap()), ").append(JSON.toJson(row.get("text"))).append("); }\n");
            }
            probe.append("observe(&mut cache,").append(JSON.toJson(row.get("uri"))).append(',').append(row.get("version")).append(',')
                .append(JSON.toJson(row.get("grammar"))).append(',').append(JSON.toJson(row.get("profile"))).append(',')
                .append(JSON.toJson(row.get("state"))).append(',').append(JSON.toJson(row.get("text"))).append(");\n");
            probe.append("previous = Some(Snapshot::new(").append(JSON.toJson(row.get("uri"))).append(',').append(row.get("version")).append(',').append(JSON.toJson(row.get("text"))).append(").unwrap());\n");
        }
        probe.append("}\n"); Path program=directory.resolve("probe.rs"); Files.writeString(program,probe);
        Path executable=directory.resolve("probe");
        run(List.of(rustc,"--edition=2021","--extern","unlaxer_runtime="+runtime,program.toString(),"-o",executable.toString()),directory);
        var actual=run(List.of(executable.toString()),directory).lines().toList(); assertEquals(rows.size(),actual.size());
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i).getAsJsonObject();var result=JsonParser.parseString(actual.get(i)).getAsJsonObject();
            assertEquals(row.get("id")+" Rust hit",row.get("hit"),result.get("hit"));
            assertEquals(row.get("id")+" independent Rust oracle",row.get("expected"),result.get("result"));
        }
    }
    @Test public void generatedJavaLspSharesReuseAndInvalidationOracle() throws Exception {
        var grammar = UBNFMapper.parse("grammar Cache { @package: cache.fixture @root Root ::= 'ab' | 'a' | '😀'; }").grammars().get(0);
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new LSPGenerator())) {
            var generated = generator.generate(grammar);
            units.add(source(generated.packageName()+"."+generated.className(),generated.source()));
        }
        units.add(source("cache.fixture.CacheServer", "package cache.fixture; public final class CacheServer extends CacheLanguageServer { public boolean fail; @Override protected String parseStateRevision() { if(fail) throw new IllegalStateException(); return \"\"; } public long version(String uri) { return documentVersions.get(uri); } public String current(String uri) { var d=documents.get(uri); return d==null ? null : d.content(); } }"));
        Path classes=temporary.newFolder().toPath();var compiler=ToolProvider.getSystemJavaCompiler();var errors=new StringWriter();
        try(var manager=compiler.getStandardFileManager(null,null,null)) {
            boolean ok=compiler.getTask(new PrintWriter(errors),manager,null,List.of("--release","17","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,units).call();assertTrue(errors.toString(),ok);
        }
        try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            var type=loader.loadClass("cache.fixture.CacheServer");var instance=type.getConstructor().newInstance();
            var server=(org.eclipse.lsp4j.services.LanguageServer)instance;
            server.initialize(new org.eclipse.lsp4j.InitializeParams()).join();server.initialized(new org.eclipse.lsp4j.InitializedParams());
            var service=server.getTextDocumentService();String uri="file:///cache";
            for(String line:Files.readAllLines(ROOT.resolve("spec-corpus/incremental-cache/lsp.tsv"))) {
                if(line.startsWith("#"))continue;var f=line.split("\\t",-1);int version=Integer.parseInt(f[1]);
                switch(f[0]) {
                    case "open" -> service.didOpen(new org.eclipse.lsp4j.DidOpenTextDocumentParams(new org.eclipse.lsp4j.TextDocumentItem(uri,"fixture",version,f[2])));
                    case "change","stale","invalid-range" -> {
                        var change=new org.eclipse.lsp4j.TextDocumentContentChangeEvent(f[2]);
                        if(f[0].equals("invalid-range"))change.setRange(new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(0,0),new org.eclipse.lsp4j.Position(0,0)));
                        service.didChange(new org.eclipse.lsp4j.DidChangeTextDocumentParams(new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(uri,version),List.of(change)));
                    }
                    case "save" -> service.didSave(new org.eclipse.lsp4j.DidSaveTextDocumentParams(new org.eclipse.lsp4j.TextDocumentIdentifier(uri)));
                    case "close" -> service.didClose(new org.eclipse.lsp4j.DidCloseTextDocumentParams(new org.eclipse.lsp4j.TextDocumentIdentifier(uri)));
                    case "invalidate" -> type.getMethod("invalidateParseCache").invoke(instance);
                    default -> throw new AssertionError(line);
                }
                var stats=(SnapshotParseCache.Stats)type.getMethod("parseCacheStats").invoke(instance);
                assertEquals(line,Long.parseLong(f[3]),stats.hits());assertEquals(line,Long.parseLong(f[4]),stats.misses());assertEquals(line,Integer.parseInt(f[5]),stats.entries());
                assertEquals(line,f[6].equals("~")?null:f[6],type.getMethod("current",String.class).invoke(instance,uri));
            }
            type.getField("fail").setBoolean(instance,true);
            assertThrows(IllegalStateException.class,()->service.didChange(new org.eclipse.lsp4j.DidChangeTextDocumentParams(
                new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(uri,6),List.of(new org.eclipse.lsp4j.TextDocumentContentChangeEvent("b")))));
            assertEquals("ab",type.getMethod("current",String.class).invoke(instance,uri));
            assertEquals(5L,type.getMethod("version",String.class).invoke(instance,uri));
            type.getField("fail").setBoolean(instance,false);
            service.didSave(new org.eclipse.lsp4j.DidSaveTextDocumentParams(new org.eclipse.lsp4j.TextDocumentIdentifier(uri)));
            assertEquals("ab",type.getMethod("current",String.class).invoke(instance,uri));
        }
    }
    public static class StatefulToken extends org.unlaxer.parser.elementary.WordParser {
        static boolean allowed;
        public StatefulToken() { super("a"); }
        @Override public org.unlaxer.Parsed parse(ParseContext context, org.unlaxer.TokenKind kind, boolean invert) {
            return allowed ? super.parse(context,kind,invert) : new org.unlaxer.parser.elementary.WordParser("b").parse(context,kind,invert);
        }
    }
    @Test public void externalParserStateIsNotImplicitlyCached() throws Exception {
        var grammar=UBNFMapper.parse("grammar Stateful { @package: cache.fixture token CUSTOM = org.unlaxer.dsl.codegen.IncrementalCacheConformanceTest.StatefulToken @root Root ::= CUSTOM; }").grammars().get(0);
        var units=new ArrayList<JavaFileObject>();
        for(CodeGenerator generator:List.of(new ParserGenerator(),new LSPGenerator())) {var generated=generator.generate(grammar);units.add(source(generated.packageName()+"."+generated.className(),generated.source()));}
        units.add(source("cache.fixture.StatefulServer","package cache.fixture; public final class StatefulServer extends StatefulLanguageServer {}"));
        Path classes=temporary.newFolder().toPath();var errors=new StringWriter();var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,null,null)) {boolean ok=compiler.getTask(new PrintWriter(errors),manager,null,List.of("--release","17","-classpath",System.getProperty("java.class.path"),"-d",classes.toString()),null,units).call();assertTrue(errors.toString(),ok);}
        try(var loader=new URLClassLoader(new URL[]{classes.toUri().toURL()},getClass().getClassLoader())) {
            var type=loader.loadClass("cache.fixture.StatefulServer");var instance=type.getConstructor().newInstance();
            StatefulToken.allowed=true;Object first=type.getMethod("parseDocument",String.class,String.class).invoke(instance,"x","a");
            assertEquals(true,first.getClass().getMethod("succeeded").invoke(first));
            StatefulToken.allowed=false;Object second=type.getMethod("parseDocumentIncremental",String.class,String.class).invoke(instance,"x","a");
            assertEquals(false,second.getClass().getMethod("succeeded").invoke(second));
            assertEquals(0,((SnapshotParseCache.Stats)type.getMethod("parseCacheStats").invoke(instance)).entries());
        } finally {StatefulToken.allowed=false;}
    }
    private static DocumentSnapshot snapshot(JsonObject row) { return new DocumentSnapshot(row.get("uri").getAsString(),row.get("version").getAsLong(),row.get("text").getAsString()); }
    private static SnapshotParseCache.Revisions revisions(JsonObject row) { return new SnapshotParseCache.Revisions(row.get("grammar").getAsString(),row.get("profile").getAsString(),row.get("state").getAsString()); }
    private JsonObject observe(ClassLoader loader, JsonObject row) {
        try {
            String name=row.get("grammar").getAsString();
            if (!row.get("profile").getAsString().equals("normal") || !row.get("state").getAsString().equals("normal")) name="Other";
            var parser=(Parser)loader.loadClass("cache.fixture."+name+"Parsers").getMethod("getRootParser").invoke(null);
            var mapper=loader.loadClass("cache.fixture."+name+"Mapper");
            String input=row.get("text").getAsString();var output=new JsonObject();
            try(var context=new ParseContext(StringSource.createRootSource(input))) {
                var parsed=parser.parse(context);int end=context.getConsumedPosition().value();
                boolean complete=parsed.isSucceeded()&&end==input.codePointCount(0,input.length());
                output.addProperty("accepted",complete); output.add("cst",JsonNull.INSTANCE);output.add("span",JsonNull.INSTANCE);
                output.add("ast",JsonNull.INSTANCE);output.add("diagnostic",JsonNull.INSTANCE);var recoveries=new JsonArray();
                if(complete) {
                    Token token=context.getCurrent().getTokens().get(0);
                    output.addProperty("cst",token.source.sourceAsString());output.add("span",JSON.toJsonTree(List.of(0,end)));
                    for(var recovery:RecoveryDiagnostic.from(token)) recoveries.add(JSON.toJsonTree(List.of(recovery.start(),recovery.end())));
                    if(recoveries.isEmpty()) {
                        Object mapped=mapper.getMethod("mapParsedTokenWithSourceMap",Token.class).invoke(null,token);
                        Object ast=mapped.getClass().getMethod("ast").invoke(mapped);
                        int[] span=(int[])((Optional<?>)mapped.getClass().getMethod("sourceSpanOf",Object.class).invoke(mapped,ast)).orElseThrow();
                        var canonical=new JsonObject();canonical.addProperty("type","Value");canonical.add("span",JSON.toJsonTree(span));
                        var fields=new JsonObject();fields.addProperty("text",(String)ast.getClass().getMethod("text").invoke(ast));canonical.add("fields",fields);output.add("ast",canonical);
                    } else {
                        assertThrows(java.lang.reflect.InvocationTargetException.class,()->mapper.getMethod("mapParsedToken",Token.class).invoke(null,token));
                    }
                } else {
                    var diagnostic=(Optional<?>)mapper.getMethod("diagnose",String.class).invoke(null,input);
                    Object detail=diagnostic.orElseThrow();output.addProperty("diagnostic",(Integer)detail.getClass().getMethod("offset").invoke(detail));
                }
                int point=complete?end:output.get("diagnostic").getAsInt();
                var snapshot=new DocumentSnapshot("fixture",1,input);
                output.add("coordinates",JSON.toJsonTree(List.of(point,snapshot.utf16(point),snapshot.utf8(point))));
                output.add("recoveries",recoveries);return output;
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private String run(List<String> command,Path directory)throws Exception {
        Path log=temporary.newFile().toPath();var process=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try { assertTrue("timeout "+command,process.waitFor(120,TimeUnit.SECONDS));String output=Files.readString(log);assertEquals(command+"\n"+output,0,process.exitValue());return output; }
        finally { if(process.isAlive()) process.destroyForcibly(); }
    }
    private static JavaFileObject source(String name,String body){return new SimpleJavaFileObject(URI.create("string:///"+name.replace('.','/')+".java"),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return body;}};}
}
