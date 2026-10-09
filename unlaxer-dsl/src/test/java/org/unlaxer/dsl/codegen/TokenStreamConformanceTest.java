package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.File;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;
import org.unlaxer.dsl.runtime.Lexing;
import org.unlaxer.dsl.PortabilityCheck;


/** Independent oracles across four input modes, generated Java/Rust and native emission. */
public class TokenStreamConformanceTest {
    private static final String PACKAGE = "example.lexing";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private java.util.Map<String,String> captureNames=java.util.Map.of();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private JsonArray corpus(String file) throws Exception {
        return JsonParser.parseString(Files.readString(repo.resolve("spec-corpus/token-stream/"+file))).getAsJsonArray();
    }
    private Path fixtureSource(JsonObject fixture) throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path source = directory.resolve("input.ubnf");
        Files.writeString(source, fixture.get("grammar").getAsString());
        if (fixture.has("package")) {
            Path manifest = directory.resolve("ubnf.json");
            Files.writeString(manifest, "{\"schemaVersion\":1,\"dependencies\":{\"std/layout\":{\"version\":\"1.0.0\",\"source\":\"builtin:std/layout@1.0.0\"}}}");
            org.unlaxer.dsl.bootstrap.UBNFPackageResolver.resolve(manifest);
        }
        return source;
    }
    @Test public void javaModesPreserveAuthoredOracles() throws Exception {
        for (var fixture : corpus("corpus.json")) {
            var f=fixture.getAsJsonObject();
            String example = switch (f.get("name").getAsString()) {
                case "character-kinds" -> "character-kinds.ubnf";
                case "guarded-first-match" -> "first-match.ubnf";
                default -> null;
            };
            if (example != null) assertEquals("runnable example drift: " + example,
                f.get("grammar").getAsString().strip(), Files.readString(repo.resolve("examples/parse-composition/" + example)).strip());
            var grammar=org.unlaxer.dsl.bootstrap.UBNFModuleLoader.load(fixtureSource(f)).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            try(var loader=compileJava(grammar)) {
                for(var c:f.getAsJsonArray("cases")) for(var mode:Lexing.Mode.values()) for(boolean keep:List.of(true,false)) {
                    var row=c.getAsJsonObject(); String name=f.get("name").getAsString()+"/"+row.get("name")+"/"+mode+"/"+keep;
                    var observed=javaObservation(loader,row.get("input").getAsString(),mode,keep);
                    assertOracle(name,row,mode,keep,observed);
                    if(mode==Lexing.Mode.DIRECT && keep) {
                        var parser=(Parser)loader.loadClass(PACKAGE+".InputParsers").getMethod("getRootParser").invoke(null);
                        String input=row.get("input").getAsString();var legacy=prefix(parser,input);
                        assertEquals(name+" ordinary entry acceptance",observed.get("accepted").getAsBoolean(),
                            legacy.get(0).getAsBoolean() && legacy.get(1).getAsInt()==input.codePointCount(0,input.length()));
                        assertEquals(name+" ordinary entry consumed",observed.get("consumed"),legacy.get(1));
                        assertEquals(name+" ordinary entry matched",observed.get("matched"),legacy.get(2));
                        if(observed.get("accepted").getAsBoolean()) {
                            var mapped=loader.loadClass(PACKAGE+".InputMapper").getMethod("parseWithSourceMap",String.class).invoke(null,input);
                            assertEquals(name+" ordinary entry AST",observed.get("ast"),canonical(mapped.getClass().getMethod("ast").invoke(mapped),mapped));
                        }
                    }
                }
            }
        }
    }
    @Test public void unsupportedProfilesAreExplicit() throws Exception {
        for(var entry:corpus("invalid.json")) {
            var f=entry.getAsJsonObject();var g=UBNFMapper.parse(f.get("grammar").getAsString()).grammars().get(0);
            assertTrue(f.toString(),GrammarValidator.validate(g).stream().anyMatch(i->i.code().equals(f.get("code").getAsString())));
            assertThrows(IllegalArgumentException.class,()->new ParserGenerator().generate(g));
            assertThrows(IllegalArgumentException.class,()->new RustBackend().generate(g));
            var d=PortabilityCheck.check(f.get("grammar").getAsString()).diagnostics().stream()
                .filter(i->i.code().equals(f.get(f.has("portabilityCode")?"portabilityCode":"code").getAsString())).findFirst().orElseThrow();
            assertEquals(f.get("subject").getAsString(),d.subject());assertNotNull(d.span());
        }
    }
    private JsonObject javaObservation(URLClassLoader loader,String input,Lexing.Mode mode,boolean keep) throws Exception {
        var parsers=loader.loadClass(PACKAGE+".InputParsers"); var mapper=loader.loadClass(PACKAGE+".InputMapper");
        var result=(Lexing.Outcome)parsers.getMethod("parseWithLexing",String.class,Lexing.Options.class)
            .invoke(null,input,new Lexing.Options(mode,keep));
        var observed=new JsonObject();observed.addProperty("accepted",result.succeeded());
        observed.addProperty("consumed",result.consumed()); observed.addProperty("matched",result.matched());
        observed.addProperty("farthest",result.farthest());
        observed.add("ast",JsonNull.INSTANCE);
        var captures=new JsonArray();
        if(result.succeeded()) captures(result.root(),captures);
        observed.add("captures",captures);
        if(result.succeeded()) {
            var mapped=mapper.getMethod("mapParsedTokenWithSourceMap",org.unlaxer.Token.class).invoke(null,result.root());
            var ast=mapped.getClass().getMethod("ast").invoke(mapped); observed.add("ast",canonical(ast,mapped));
        }
        var entries=new JsonArray();
        for(var e:result.session().lexemes()) {
            var item=new JsonObject();item.addProperty("kind",e.kind());item.addProperty("name",e.name());
            var span=new JsonArray();span.add(e.start());span.add(e.end());item.add("span",span);
            item.addProperty("text",result.session().text(e));entries.add(item);
        }
        observed.add("lexemes",entries);return observed;
    }
    private void captures(org.unlaxer.Token token,JsonArray output) throws Exception {
        if(token.parser.getClass().getSimpleName().equals("__CaptureSite")) {
            for(Object name:(List<?>)token.parser.getClass().getMethod("captureBindings").invoke(token.parser)) {
                var item=new JsonObject();if(!captureNames.containsKey(name.toString())) continue;
                item.addProperty("name",captureNames.get(name.toString()));
                var span=new JsonArray();int start=token.source.offsetFromRoot().value();span.add(start);span.add(start+token.source.codePointLength().value());item.add("span",span);output.add(item);
            }
        }
        for(var child:token.getOriginalChildren()) captures(child,output);
    }
    private void assertOracle(String name,JsonObject row,Lexing.Mode mode,boolean keep,JsonObject actual) {
        boolean tokens=mode==Lexing.Mode.TOKENS_EAGER||mode==Lexing.Mode.TOKENS_LAZY;
        var oracle=tokens&&row.has("tokens")?row.getAsJsonObject("tokens"):row;
        boolean accepted=oracle.get("accepted").getAsBoolean();
        assertEquals(name+" acceptance",accepted,actual.get("accepted").getAsBoolean());
        String input=row.get("input").getAsString();
        if(accepted) {
            assertEquals(name+" AST/source spans",oracle.get("ast"),actual.get("ast"));
            assertEquals(name+" named CST captures",oracle.get("captures"),actual.get("captures"));
            assertEquals(name+" consumption",input.codePointCount(0,input.length()),actual.get("consumed").getAsInt());
            assertEquals(name+" matched",actual.get("consumed"),actual.get("matched"));
        } else {
            assertEquals(name+" failure",oracle.get("farthest"),actual.get("farthest"));
            assertEquals(name+" failure consumption",oracle.has("consumed")?oracle.get("consumed").getAsInt():0,actual.get("consumed").getAsInt());
            assertEquals(name+" failure matched",oracle.has("matched")?oracle.get("matched").getAsInt():0,actual.get("matched").getAsInt());
            assertEquals(name+" no mapped AST",JsonNull.INSTANCE,actual.get("ast"));
        }
        var entries=new JsonArray();
        for(var e:row.getAsJsonArray("lexemes")) {
            String kind=e.getAsJsonObject().get("kind").getAsString();
            if(keep||kind.equals("token")||kind.equals("error")) entries.add(e);
        }
        assertEquals(name+" token/trivia/source positions",entries,actual.get("lexemes"));
        if(keep) {
            String rebuilt=String.join("",actual.getAsJsonArray("lexemes").asList().stream().map(e->e.getAsJsonObject().get("text").getAsString()).toList());
            assertEquals(name+" lossless reconstruction",input,rebuilt);
        }
    }
    @Test public void rustAndNativeFrontendMatchAllModes() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",Boolean.getBoolean("rustConformance"));
        Path runtime=temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc","--edition=2021","--crate-type=rlib","--crate-name=unlaxer_runtime",repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),"-o",runtime.toString()),"",false));
        Path nativeTarget=repo.resolve("rust/target");
        success(run(List.of("cargo","build","--locked","--manifest-path",repo.resolve("rust/Cargo.toml").toString(),"-p","unlaxer-generator","--target-dir",nativeTarget.toString()),"",false));
        Path nativeGenerator=nativeTarget.resolve("debug/unlaxer");
        var report=new ArrayList<>(List.of("fixture\tcase\tmode\tpreserve_trivia\tjava\trust"));
        for(var fixture:corpus("corpus.json")) {
            var f=fixture.getAsJsonObject();Path ubnf=fixtureSource(f);Path folder=ubnf.getParent();
            var grammar=org.unlaxer.dsl.bootstrap.UBNFModuleLoader.load(ubnf).grammars().get(0);
            var generated=new RustBackend().generate(grammar);
            Path nativeOutput=folder.resolve("generated");
            success(run(List.of(nativeGenerator.toString(),"generate","--grammar",ubnf.toString(),"--output",nativeOutput.toString()),"",true));
            for(var file:generated) assertEquals(f.get("name")+" emitted "+file.relativePath(),file.content(),Files.readString(nativeOutput.resolve(file.relativePath())));
            Files.writeString(folder.resolve("main.rs"),rustProbe());
            success(run(List.of("rustc","--edition=2021","--extern","unlaxer_runtime="+runtime,folder.resolve("main.rs").toString(),"-o",folder.resolve("probe").toString()),"",false));
            var framed=new StringBuilder();
            for(var c:f.getAsJsonArray("cases")) for(var mode:Lexing.Mode.values()) for(boolean keep:List.of(true,false)) {
                framed.append(mode.ordinal()).append(keep?'1':'0').append(':').append(HexFormat.of().formatHex(c.getAsJsonObject().get("input").getAsString().getBytes(StandardCharsets.UTF_8))).append('\n');
            }
            var output=run(List.of(folder.resolve("probe").toString()),framed.toString(),false);success(output);
            var results=output.output().lines().map(JsonParser::parseString).toList(); int n=0;
            try(var loader=compileJava(grammar)) {
                for(var c:f.getAsJsonArray("cases")) for(var mode:Lexing.Mode.values()) for(boolean keep:List.of(true,false)) {
                    var row=c.getAsJsonObject();String name=f.get("name")+"/"+row.get("name")+"/"+mode+"/"+keep;
                    var rust=results.get(n++).getAsJsonObject();var java=javaObservation(loader,row.get("input").getAsString(),mode,keep);
                    assertOracle(name+" Rust",row,mode,keep,rust);
                    // Successful parses may retain backend-specific speculative failure hints.
                    if(java.get("accepted").getAsBoolean()) {java.remove("farthest");rust.remove("farthest");}
                    assertEquals(name+" Java/Rust",java,rust);
                    report.add(f.get("name")+"\t"+row.get("name")+"\t"+mode+"\t"+keep+"\t"+java+"\t"+rust);
                }
            }
        }
        for(var entry:corpus("invalid.json")) {
            var f=entry.getAsJsonObject();Path grammar=temporary.newFile("invalid-"+f.get("name").getAsString()+".ubnf").toPath();Files.writeString(grammar,f.get("grammar").getAsString());
            var output=run(List.of(nativeGenerator.toString(),"check","--target","rust","--grammar",grammar.toString(),"--format","json"),"",true);
            var rust=JsonParser.parseString(output.output()).getAsJsonObject().getAsJsonArray("diagnostics");
            var java=PortabilityCheck.check(f.get("grammar").getAsString()).diagnostics().stream().filter(d->d.code().startsWith("E-TOKEN-STREAM") || d.code().equals("P-WHITESPACE")).toList();
            var selected=new JsonArray();for(var d:rust) if(d.getAsJsonObject().get("code").getAsString().startsWith("E-TOKEN-STREAM") || d.getAsJsonObject().get("code").getAsString().equals("P-WHITESPACE")) {
                var normalized=d.getAsJsonObject().deepCopy();assertEquals("error",normalized.remove("severity").getAsString());selected.add(normalized);
            }
            assertEquals(f.get("name").toString(),new com.google.gson.Gson().toJsonTree(java),selected);
        }
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/rust-token-stream.tsv"),report);
    }
    private String rustProbe() {
        return """
            mod generated;
            use std::io::{self,BufRead};
            use unlaxer_runtime::{json_string,lexing::{Mode,Options}};
            fn main() {
                for line in io::stdin().lock().lines() {
                    let line=line.unwrap();let (flags,encoded)=line.split_once(':').unwrap();
                    let bytes=(0..encoded.len()).step_by(2).map(|i|u8::from_str_radix(&encoded[i..i+2],16).unwrap()).collect();
                    let source=String::from_utf8(bytes).unwrap();let mode=[Mode::Direct,Mode::TriviaCache,Mode::TokensLazy,Mode::TokensEager][usize::from(flags.as_bytes()[0]-b'0')];
                    let mut out=generated::parser::parse_with_lexing(&source,Options{mode,preserve_trivia:flags.as_bytes()[1]==b'1'}).unwrap();
                    let ast=if out.succeeded {generated::mapper::map(out.tree.as_ref().unwrap()).unwrap().canonical_json()} else {"null".into()};
                    let captures=if out.succeeded {out.tree.as_ref().unwrap().nodes[out.tree.as_ref().unwrap().root].captures.iter().map(|c|format!(r#"{{"name":{},"span":[{},{}]}}"#,json_string(c.name),c.span.start,c.span.end)).collect::<Vec<_>>().join(",")} else {String::new()};
                    let lexemes=out.session.lexemes().iter().map(|e|format!(r#"{{"kind":{},"name":{},"span":[{},{}],"text":{}}}"#,json_string(e.kind),json_string(e.name),e.span.start,e.span.end,json_string(out.session.text(e)))).collect::<Vec<_>>().join(",");
                    println!(r#"{{"accepted":{},"consumed":{},"matched":{},"farthest":{},"ast":{},"captures":[{}],"lexemes":[{}]}}"#,out.succeeded,out.consumed,out.matched,out.farthest,ast,captures,lexemes);
                }
            }
            """;
    }
    @Test public void reportMeasuredWorkAndAllocation() throws Exception {
        assumeTrue("enable with -DtokenStreamBench=true -DrustConformance=true",Boolean.getBoolean("tokenStreamBench") && Boolean.getBoolean("rustConformance"));
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        assumeTrue("JVM thread allocation counter required",bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().getId();
        var grammar=UBNFMapper.parse(Files.readString(repo.resolve("spec-corpus/token-stream/benchmark.ubnf"))).grammars().get(0);
        var report=new ArrayList<>(List.of("host\tcase\tmode\tsource_cp\titerations\tnanoseconds_per_parse\tallocated_bytes_per_parse\tterminal_evaluations\ttrivia_evaluations\tretained_entries\tpeak_requested_bytes"));
        record Case(String name,String source,int iterations) {}
        var cases=List.of(new Case("short","a:b;",100),new Case("dense","a:b;".repeat(100),10),new Case("comments",("alpha /*"+" note ".repeat(64)+"*/ : beta ;\n").repeat(100),5));
        try(var loader=compileJava(grammar)) {
            var method=loader.loadClass(PACKAGE+".InputParsers").getMethod("parseWithLexing",String.class,Lexing.Options.class);
            for(var c:cases) {
                var counts=new java.util.EnumMap<Lexing.Mode,Lexing.Metrics>(Lexing.Mode.class);
                for(var mode:Lexing.Mode.values()) {
                    var options=new Lexing.Options(mode,false);
                    for(int i=0;i<5;i++) assertTrue(((Lexing.Outcome)method.invoke(null,c.source(),options)).succeeded());
                    long nanos=0,bytes=0;Lexing.Metrics metrics=null;
                    for(int i=0;i<c.iterations();i++) {
                        long before=bean.getThreadAllocatedBytes(thread),start=System.nanoTime();
                        var out=(Lexing.Outcome)method.invoke(null,c.source(),options);
                        nanos+=System.nanoTime()-start;bytes+=bean.getThreadAllocatedBytes(thread)-before;
                        assertTrue(out.succeeded());metrics=out.session().metrics();
                    }
                    counts.put(mode,metrics);
                    report.add("java\t"+c.name()+"\t"+mode+"\t"+c.source().codePointCount(0,c.source().length())+"\t"+c.iterations()+"\t"+nanos/c.iterations()+"\t"+bytes/c.iterations()+"\t"+metrics.terminalEvaluations()+"\t"+metrics.triviaEvaluations()+"\t"+metrics.retainedEntries()+"\tNA");
                }
                assertEquals(counts.get(Lexing.Mode.DIRECT).terminalEvaluations(),counts.get(Lexing.Mode.TRIVIA_CACHE).terminalEvaluations());
                assertTrue(counts.get(Lexing.Mode.TRIVIA_CACHE).triviaEvaluations()<=counts.get(Lexing.Mode.DIRECT).triviaEvaluations());
                assertEquals(counts.get(Lexing.Mode.TOKENS_LAZY).terminalEvaluations(),counts.get(Lexing.Mode.TOKENS_EAGER).terminalEvaluations());
            }
        }
        Path folder=temporary.newFolder().toPath(), generated=Files.createDirectories(folder.resolve("generated"));
        for(var file:new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()),file.content());
        Files.copy(repo.resolve("spec-corpus/token-stream/benchmark.rs"),folder.resolve("main.rs"));
        for(String crate:List.of("unlaxer_runtime","unlaxer_alloc_audit")) {
            String directory=crate.replace('_','-');
            success(run(List.of("rustc","--edition=2021","-O","--crate-type=rlib","--crate-name="+crate,repo.resolve("rust/"+directory+"/src/lib.rs").toString(),"-o",folder.resolve("lib"+crate+".rlib").toString()),"",false));
        }
        success(run(List.of("rustc","--edition=2021","-O","--extern","unlaxer_runtime="+folder.resolve("libunlaxer_runtime.rlib"),"--extern","unlaxer_alloc_audit="+folder.resolve("libunlaxer_alloc_audit.rlib"),folder.resolve("main.rs").toString(),"-o",folder.resolve("benchmark").toString()),"",false));
        var rust=run(List.of(folder.resolve("benchmark").toString()),"",false);success(rust);report.addAll(rust.output().lines().toList());
        assertEquals(25,report.size());
        Files.createDirectories(Path.of("target"));Files.write(Path.of("target/token-stream-benchmark.tsv"),report);
    }
    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var names=new java.util.HashMap<String,String>();
        for(var rule:grammar.rules()) {
            var plan=new CaptureBindingPlan(rule);
            for(var a:rule.annotations()) if(a instanceof org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation m)
                for(String name:m.paramNames()) for(var site:plan.sites(name)) names.put(site.id(),name);
        }
        captureNames=names;
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(
                new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            sources.add(generator.generate(grammar));
        }
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/"
                + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return source.source();
                }
            }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("tests require a JDK, not a JRE", compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath",
                    System.getProperty("java.class.path") + File.pathSeparator, "-d", output.toString()),
                null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JsonArray prefix(Parser parser, String input) throws Exception {
        var result = new JsonArray();
        try (var context = new ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
            result.add(parser.parse(context).isSucceeded());
            result.add(context.getConsumedPosition().value());
            result.add(context.getMatchedPosition().value());
        }
        return result;
    }

    private JsonObject canonical(Object ast, Object mapped) throws Exception {
        var result = new JsonObject();
        result.addProperty("type", ast.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class)
            .invoke(mapped, ast)).orElseThrow();
        var position = new JsonArray();
        position.add(span[0]);
        position.add(span[1]);
        result.add("span", position);
        var fields = new JsonObject();
        for (var component : ast.getClass().getRecordComponents()) {
            fields.add(component.getName(), canonicalValue(component.getAccessor().invoke(ast), mapped));
        }
        result.add("fields", fields);
        return result;
    }

    private JsonElement canonicalValue(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonicalValue(optional.orElse(null), mapped);
        if (value instanceof List<?> list) {
            var result = new JsonArray();
            for (Object item : list) result.add(canonicalValue(item, mapped));
            return result;
        }
        return canonical(value, mapped);
    }

    private record ProcessResult(int code, String output) {}

    private ProcessResult run(List<String> command, String input, boolean withoutJava) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/nonexistent-unlaxer-java");
        }
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                terminateProcessTree(process);
                fail("process timed out: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) terminateProcessTree(process);
        }
    }

    private void terminateProcessTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--) descendants.get(i).destroy();
        process.destroy();
        process.waitFor(2, TimeUnit.SECONDS);
        for (int i = descendants.size() - 1; i >= 0; i--) {
            ProcessHandle descendant = descendants.get(i);
            if (descendant.isAlive()) descendant.destroyForcibly();
        }
        if (process.isAlive()) {
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }

    private void success(ProcessResult result) {
        assertEquals(result.output(), 0, result.code());
    }
}
