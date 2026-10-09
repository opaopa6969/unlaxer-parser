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

/** Java/generated-Rust/native-frontend contract for explicit name-predicate choice profile. */
public class NamePredicateConformanceTest {
    private static final String PACKAGE = "org.example.namepredicate";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void namePredicatesPreserveIndependentOracles() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",
            Boolean.getBoolean("rustConformance"));

        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib",
            "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
            "-o", runtime.toString()), "", false));

        Path nativeTarget = temporary.getRoot().toPath().resolve("native-target");
        success(run(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--target-dir",
            nativeTarget.toString()), "", false));
        Path nativeGenerator = nativeTarget.resolve("debug/unlaxer").toAbsolutePath();

        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/name-predicate");
        JsonArray corpus = JsonParser.parseString(
            Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of(
            "fixture\tcase\tinput_json\texpected_prefix\tjava_prefix\texpected_ast\tjava_ast\trust"));

        int fixtureIndex = 0;
        for (JsonElement fixtureElement : corpus) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String source = fixture.get("grammar").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            List<RustBackend.GeneratedFile> javaFrontend = new RustBackend().generate(grammar);
            assertEquals(name + " Java frontend Rust file count", 5, javaFrontend.size());

            Path fixtureDir = temporary.getRoot().toPath().resolve("fixture-" + fixtureIndex++);
            Files.createDirectories(fixtureDir);
            Path ubnf = fixtureDir.resolve(grammar.name() + ".ubnf");
            Files.writeString(ubnf, source);
            Path nativeGenerated = fixtureDir.resolve("native-generated");
            success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", ubnf.toString(),
                "--output", nativeGenerated.toString()), "", true));
            for (var file : javaFrontend) {
                assertArrayEquals(name + " native frontend byte parity: " + file.relativePath(),
                    file.content().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(nativeGenerated.resolve(file.relativePath())));
            }

            Path generated = Files.createDirectory(fixtureDir.resolve("generated"));
            for (var file : javaFrontend) {
                Files.writeString(generated.resolve(file.relativePath()), file.content());
            }
            JsonArray cases = fixture.getAsJsonArray("cases");
            Files.writeString(fixtureDir.resolve("main.rs"), rustProbe(cases));
            success(run(List.of("rustc", "--edition=2021", "--extern",
                "unlaxer_runtime=" + runtime, fixtureDir.resolve("main.rs").toString(), "-o",
                fixtureDir.resolve("probe").toString()), "", false));

            String framed = String.join("\n", cases.asList().stream()
                .map(row -> HexFormat.of().formatHex(row.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
            ProcessResult rustResult = run(List.of(fixtureDir.resolve("probe").toString()), framed, false);
            success(rustResult);
            List<String> rustLines = rustResult.output().lines().toList();
            assertEquals(name + " Rust result count", cases.size(), rustLines.size());

            try (URLClassLoader loader = compileJava(grammar)) {
                Class<?> parsers = loader.loadClass(PACKAGE + "." + grammar.name() + "Parsers");
                Parser parser = (Parser) parsers.getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + grammar.name() + "Mapper");
                Object retainedMap=null; JsonElement retainedAst=null;
                for (int i = 0; i < cases.size(); i++) {
                    JsonObject row = cases.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String context = name + "/" + row.get("id").getAsString();
                    JsonObject rust = JsonParser.parseString(rustLines.get(i)).getAsJsonObject();

                    var snapshots = snapshots(row);
                    assertEquals(context + " independent name CP span", (row.has("nameSpan") ? row.get("nameSpan") : JsonNull.INSTANCE), nameSpan(parser,input,snapshots));
                    assertEquals(context + " Rust name CP span", (row.has("nameSpan") ? row.get("nameSpan") : JsonNull.INSTANCE), rust.get("nameSpan"));
                    JsonArray javaPrefix = prefix(parser, input, snapshots, org.unlaxer.context.ParseOptions.DEFAULT);
                    assertEquals(context + " Java memo parity", javaPrefix, prefix(parser, input, snapshots,
                        org.unlaxer.context.ParseOptions.withMemoization(org.unlaxer.context.Memoization.SAFE_FAILURES)));
                    assertEquals(context + " independent prefix cursor oracle", row.get("prefix"), javaPrefix);
                    assertEquals(context + " Java/Rust prefix cursor parity", javaPrefix, rust.get("prefix"));

                    boolean accepted = row.get("accepted").getAsBoolean();
                    Optional<?> diagnostic =
                        (Optional<?>) mapper.getMethod("diagnoseWithNameSnapshots", String.class, List.class, org.unlaxer.context.ParseOptions.class).invoke(null, input, snapshots, org.unlaxer.context.ParseOptions.DEFAULT);
                    for(var options:List.of(
                            org.unlaxer.context.ParseOptions.DEFAULT.withDiagnostics(org.unlaxer.context.ParseOptions.Diagnostics.DETAILED_ON_FAILURE),
                            org.unlaxer.context.ParseOptions.withMemoization(org.unlaxer.context.Memoization.SAFE_FAILURES))) {
                        assertEquals(context + " Java diagnostic retry/memo parity", diagnostic,
                            mapper.getMethod("diagnoseWithNameSnapshots",String.class,List.class,org.unlaxer.context.ParseOptions.class).invoke(null,input,snapshots,options));
                    }
                    assertEquals(context + " Java full-input acceptance", accepted, diagnostic.isEmpty());
                    assertEquals(context + " Rust full-input acceptance", accepted,
                        !rust.get("ast").isJsonNull());

                    if (row.has("diagnostic")) {
                        JsonObject expectedDiagnostic = row.getAsJsonObject("diagnostic");
                        Object actualDiagnostic = diagnostic.orElseThrow();
                        assertEquals(context + " Java diagnostic kind", expectedDiagnostic.get("kind").getAsString(),
                            actualDiagnostic.getClass().getMethod("kind").invoke(actualDiagnostic));
                        assertEquals(context + " Java diagnostic CP offset", expectedDiagnostic.get("offset").getAsInt(),
                            actualDiagnostic.getClass().getMethod("offset").invoke(actualDiagnostic));
                        assertTrue(context + " Java diagnostic expected", ((List<?>) actualDiagnostic.getClass()
                            .getMethod("expected").invoke(actualDiagnostic)).contains(expectedDiagnostic.get("expected").getAsString()));
                        var rustDiagnostic = rust.getAsJsonObject("diagnostic");
                        assertEquals(context + " Rust diagnostic kind", expectedDiagnostic.get("kind"), rustDiagnostic.get("kind"));
                        assertEquals(context + " Rust diagnostic CP offset", expectedDiagnostic.get("offset"), rustDiagnostic.get("offset"));
                        assertTrue(context + " Rust diagnostic expected", rustDiagnostic.getAsJsonArray("expected").asList()
                            .contains(expectedDiagnostic.get("expected")));
                    }
                    JsonElement expected = JsonNull.INSTANCE;
                    JsonElement javaAst = JsonNull.INSTANCE;
                    if (accepted) {
                        expected = row.get("ast");
                        Object mapped = mapper.getMethod("parseWithNameSnapshotsAndSourceMap", String.class, List.class, org.unlaxer.context.ParseOptions.class).invoke(null, input, snapshots, org.unlaxer.context.ParseOptions.DEFAULT);
                        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                        javaAst = canonical(ast, mapped);
                        assertEquals(context + " Java AST all fields/node spans", expected, javaAst);
                        assertEquals(context + " Rust AST all fields/node spans", expected, rust.get("ast"));
                        if(retainedMap!=null) assertEquals(context+" previous source map remains frozen",retainedAst,
                            canonical(retainedMap.getClass().getMethod("ast").invoke(retainedMap),retainedMap));
                        retainedMap=mapped; retainedAst=javaAst;
                    }
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input")
                        + "\t" + row.get("prefix") + "\t" + javaPrefix + "\t" + expected + "\t"
                        + javaAst + "\t" + rust);
                }
            }
        }

        JsonArray invalid = JsonParser.parseString(Files.readString(fixtures.resolve("invalid.json"))).getAsJsonArray();
        for (var element : invalid) {
            JsonObject row = element.getAsJsonObject();
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            String name = row.get("name").getAsString();
            assertTrue(name + " independent invalid code", GrammarValidator.validateWithoutClassLoading(grammar)
                .stream().anyMatch(issue -> issue.code().equals(row.get("code").getAsString())));
            assertThrows(name + " Java generator refusal", IllegalArgumentException.class,
                () -> new ParserGenerator().generate(grammar));
            assertThrows(name + " Java frontend Rust lowering refusal", IllegalArgumentException.class,
                () -> new RustBackend().generate(grammar));
            Path source = temporary.getRoot().toPath().resolve("invalid-" + name + ".ubnf");
            Files.writeString(source, row.get("grammar").getAsString());
            var nativeResult = run(List.of(nativeGenerator.toString(), "generate", "--grammar", source.toString(),
                "--output", temporary.getRoot().toPath().resolve("invalid-output").toString()), "", true);
            assertNotEquals(name + " native generation refusal", 0, nativeResult.code());
            assertTrue(name + ": " + nativeResult.output(), nativeResult.output()
                .contains(row.get("nativeMessage").getAsString()));
        }

        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve("rust-name-predicate.tsv"), report, StandardCharsets.UTF_8);
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
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

    private List<org.unlaxer.context.NameSnapshot> snapshots(JsonObject row) {
        if(!row.has("snapshot") || row.get("snapshot").isJsonNull()) return List.of();
        var value=row.getAsJsonObject("snapshot");
        var names=new java.util.HashMap<String,org.unlaxer.context.NameSnapshot.Kind>();
        for(var name:value.getAsJsonArray("types")) names.put(name.getAsString(),org.unlaxer.context.NameSnapshot.Kind.TYPE);
        for(var name:value.getAsJsonArray("values")) names.put(name.getAsString(),org.unlaxer.context.NameSnapshot.Kind.VALUE);
        return List.of(new org.unlaxer.context.NameSnapshot("cxx23",value.get("version").getAsString(),names));
    }
    private JsonElement nameSpan(Parser parser,String input,List<org.unlaxer.context.NameSnapshot> snapshots) {
        try(var context=ParseContext.withNameSnapshots(org.unlaxer.StringSource.createRootSource(input),snapshots,org.unlaxer.context.ParseOptions.DEFAULT)) {
            parser.parse(context);
            var failure=org.unlaxer.context.NameResolution.failure(context);
            if(failure.isEmpty()) return JsonNull.INSTANCE;
            var span=new JsonArray(); span.add(failure.get().start()); span.add(failure.get().end()); return span;
        }
    }
    private JsonArray prefix(Parser parser, String input, List<org.unlaxer.context.NameSnapshot> snapshots,
            org.unlaxer.context.ParseOptions options) {
        var result = new JsonArray();
        try (var context = ParseContext.withNameSnapshots(org.unlaxer.StringSource.createRootSource(input), snapshots, options)) {
            var parsed = parser.parse(context);
            result.add(parsed.isSucceeded());
            if (parsed.isSucceeded()) {
                String prefixText = input.substring(0, input.offsetByCodePoints(0, context.getConsumedPosition().value()));
                assertEquals("raw selected Java CST source", prefixText, parsed.getConsumed().source.toString());
            }
            result.add(context.getConsumedPosition().value()); result.add(context.getMatchedPosition().value());
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

    private String rustProbe(JsonArray cases) {
        StringBuilder snapshots = new StringBuilder();
        int index=0;
        for(var rowValue:cases) {
            var row=rowValue.getAsJsonObject(); snapshots.append(index++).append(" => ");
            if(!row.has("snapshot") || row.get("snapshot").isJsonNull()) {snapshots.append("vec![],\n");continue;}
            var value=row.getAsJsonObject("snapshot");
            snapshots.append("vec![unlaxer_runtime::names::Snapshot::new(\"cxx23\", ").append(value.get("version")).append(", [");
            for(var name:value.getAsJsonArray("types")) snapshots.append("(").append(name).append(".to_owned(),unlaxer_runtime::names::Kind::Type),");
            for(var name:value.getAsJsonArray("values")) snapshots.append("(").append(name).append(".to_owned(),unlaxer_runtime::names::Kind::Value),");
            snapshots.append("].into()).unwrap()],\n");
        }
        return """
            mod generated;
            use std::io::{self, BufRead};
            fn main() {
                for (index,line) in io::stdin().lock().lines().enumerate() {
                    let encoded = line.unwrap();
                    let bytes = (0..encoded.len()).step_by(2)
                        .map(|i| u8::from_str_radix(&encoded[i..i + 2], 16).unwrap()).collect();
                    let input = String::from_utf8(bytes).unwrap();
                    let snapshots=match index { %s _=>unreachable!() };
                    let mut context = unlaxer_runtime::ParseContext::with_name_snapshots(&input,&snapshots,unlaxer_runtime::ParseOptions::default()).unwrap();
                    let prefix = generated::parser::parse_context(&mut context);
                    let prefix_ok = prefix.is_ok();
                    if let Ok(matched) = prefix {
                        let expected_text: String = input.chars().take(context.position()).collect();
                        assert_eq!(context.text(matched.span), Some(expected_text.as_str()));
                    }
                    print!(r#"{{\"prefix\":[{},{},{}],\"ast\":"#,
                        prefix_ok, context.position(), context.matched_position());
                    match generated::parser::parse_tree_detailed_with_name_snapshots(&input,&snapshots,unlaxer_runtime::ParseOptions::default()) {
                        Ok(tree) => {
                            let ast = generated::mapper::map(&tree).unwrap();
                            drop(tree);
                            print!("{}", ast.canonical_json());
                        }
                        Err(_) => print!("null"),
                    }
                    let diagnostic = match generated::parser::parse_tree_detailed_with_name_snapshots(&input,&snapshots,unlaxer_runtime::ParseOptions::default()) {
                        Ok(_) => "null".to_owned(), Err(error) => error.canonical_json(),
                    };
                    for options in [
                        unlaxer_runtime::ParseOptions::default().with_diagnostics(unlaxer_runtime::Diagnostics::DetailedOnFailure),
                        unlaxer_runtime::ParseOptions::with_memoization(unlaxer_runtime::Memoization::SafeFailures),
                    ] {
                        let repeated=generated::parser::parse_tree_detailed_with_name_snapshots(&input,&snapshots,options);
                        let repeated_diagnostic=match &repeated { Ok(_)=>"null".to_owned(),Err(error)=>error.canonical_json() };
                        assert_eq!(diagnostic,repeated_diagnostic);
                        if let Ok(tree)=repeated {
                            let reference=generated::parser::parse_tree_detailed_with_name_snapshots(&input,&snapshots,unlaxer_runtime::ParseOptions::default()).unwrap();
                            assert_eq!(generated::mapper::map(&tree).unwrap().canonical_json(),generated::mapper::map(&reference).unwrap().canonical_json());
                        }
                    }
                    print!(r#",\"diagnostic\":{}"#, diagnostic);
                    match context.name_failure() {
                        Some(error)=>print!(r#",\"nameSpan\":[{},{}]"#,error.span.start,error.span.end),
                        None=>print!(r#",\"nameSpan\":null"#),
                    }
                    let mut memo = unlaxer_runtime::ParseContext::with_name_snapshots(&input,&snapshots,
                        unlaxer_runtime::ParseOptions::with_memoization(unlaxer_runtime::Memoization::SafeFailures)).unwrap();
                    let memo_ok = generated::parser::parse_context(&mut memo).is_ok();
                    assert_eq!((prefix_ok, context.position(), context.matched_position()),
                        (memo_ok, memo.position(), memo.matched_position()));
                    println!("}}");
                }
            }
            """.formatted(snapshots);
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
