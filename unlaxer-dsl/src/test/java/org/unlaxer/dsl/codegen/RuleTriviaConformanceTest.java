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
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/** Java/generated-Rust/native-frontend contract for rule-scoped trivia policy. */
public class RuleTriviaConformanceTest {
    private static final String PACKAGE = "org.example.ruletrivia";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void ruleTriviaPoliciesPreserveCursorsAcceptanceAstAndSpans() throws Exception {
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

        JsonArray corpus = JsonParser.parseString(Files.readString(fixtureCorpus())).getAsJsonArray();
        var report = new ArrayList<>(List.of(
            "fixture\tcase\tinput_json\texpected_prefix\tjava_prefix\texpected_ast\tjava_ast\trust\tjava_diagnostic"));
        var nativeReport = new ArrayList<>(report);

        int fixtureIndex = 0;
        for (JsonElement fixtureElement : corpus) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String source = fixture.get("grammar").getAsString();
            Path fixtureDir = temporary.getRoot().toPath().resolve("fixture-" + fixtureIndex++);
            Files.createDirectories(fixtureDir);
            Path ubnf = fixtureDir.resolve("root.ubnf");
            Files.writeString(ubnf, source);
            if (fixture.has("modules")) for (var entry : fixture.getAsJsonObject("modules").entrySet())
                Files.writeString(fixtureDir.resolve(entry.getKey()), entry.getValue().getAsString());
            if (fixture.has("manifest")) {
                Path manifest = fixtureDir.resolve("ubnf.json");
                Files.writeString(manifest, fixture.get("manifest").toString());
                org.unlaxer.dsl.bootstrap.UBNFPackageResolver.resolve(manifest);
                String javaLock = Files.readString(fixtureDir.resolve("ubnf.lock.json"));
                success(run(List.of(nativeGenerator.toString(), "deps", "resolve", "--manifest", manifest.toString()), "", true));
                assertEquals(name + " exact package identity/hash lock parity", javaLock,
                    Files.readString(fixtureDir.resolve("ubnf.lock.json")));
            }
            GrammarDecl grammar = org.unlaxer.dsl.bootstrap.UBNFModuleLoader.load(ubnf).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            List<RustBackend.GeneratedFile> javaFrontend = new RustBackend().generate(grammar);
            assertEquals(name + " Java frontend Rust file count", 5, javaFrontend.size());

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
            Files.writeString(fixtureDir.resolve("main.rs"), rustProbe());
            success(run(List.of("rustc", "--edition=2021", "--extern",
                "unlaxer_runtime=" + runtime, fixtureDir.resolve("main.rs").toString(), "-o",
                fixtureDir.resolve("probe").toString()), "", false));
            Files.writeString(fixtureDir.resolve("native_main.rs"), rustProbe().replace(
                "mod generated;", "#[path = \"native-generated/mod.rs\"] mod generated;"));
            success(run(List.of("rustc", "--edition=2021", "--extern",
                "unlaxer_runtime=" + runtime, fixtureDir.resolve("native_main.rs").toString(), "-o",
                fixtureDir.resolve("native-probe").toString()), "", false));

            JsonArray cases = fixture.getAsJsonArray("cases");
            String framed = String.join("\n", cases.asList().stream()
                .map(row -> HexFormat.of().formatHex(row.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
            ProcessResult rustResult = run(List.of(fixtureDir.resolve("probe").toString()), framed, false);
            success(rustResult);
            List<String> rustLines = rustResult.output().lines().toList();
            assertEquals(name + " Rust result count", cases.size(), rustLines.size());
            ProcessResult nativeRustResult = run(
                List.of(fixtureDir.resolve("native-probe").toString()), framed, false);
            success(nativeRustResult);
            List<String> nativeRustLines = nativeRustResult.output().lines().toList();
            assertEquals(name + " native-host Rust result count", cases.size(), nativeRustLines.size());
            assertEquals(name + " independently executed Rust hosts", rustLines, nativeRustLines);

            try (URLClassLoader loader = compileJava(grammar)) {
                Class<?> parsers = loader.loadClass(PACKAGE + "." + grammar.name() + "Parsers");
                Parser parser = (Parser) parsers.getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + grammar.name() + "Mapper");
                for (int i = 0; i < cases.size(); i++) {
                    JsonObject row = cases.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String context = name + "/" + row.get("id").getAsString();
                    JsonObject rust = JsonParser.parseString(rustLines.get(i)).getAsJsonObject();
                    JsonObject nativeRust = JsonParser.parseString(nativeRustLines.get(i)).getAsJsonObject();

                    JsonArray javaPrefix = prefix(parser, input, row);
                    assertEquals(context + " independent prefix cursor oracle", row.get("prefix"), javaPrefix);
                    assertEquals(context + " Java/Rust prefix cursor parity", javaPrefix, rust.get("prefix"));
                    assertEquals(context + " native-host independent prefix cursor oracle",
                        row.get("prefix"), nativeRust.get("prefix"));
                    if (row.has("memoHitsAtLeast")) {
                        for (int hit = 0; hit < 2; hit++) assertTrue(context + " Rust actual memo hits",
                            rust.getAsJsonArray("memoHits").get(hit).getAsInt()
                                >= row.getAsJsonArray("memoHitsAtLeast").get(hit).getAsInt());
                    }

                    boolean accepted = row.get("accepted").getAsBoolean();
                    Optional<?> diagnostic =
                        (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    for (var memo : Memoization.values()) for (var policy : ParseOptions.Diagnostics.values()) {
                        Optional<?> configured = (Optional<?>) mapper.getMethod("diagnose", String.class, ParseOptions.class)
                            .invoke(null, input, ParseOptions.withMemoization(memo).withDiagnostics(policy));
                        assertEquals(context + " Java diagnostic " + memo + "/" + policy, diagnostic, configured);
                    }
                    assertEquals(context + " Java full-input acceptance", accepted, diagnostic.isEmpty());
                    assertEquals(context + " Rust full-input acceptance", accepted,
                        !rust.get("ast").isJsonNull());
                    assertEquals(context + " native-host full-input acceptance", accepted,
                        !nativeRust.get("ast").isJsonNull());

                    if (row.has("diagnostic")) {
                        Object failure = diagnostic.orElseThrow();
                        JsonArray javaDiagnostic = new JsonArray();
                        javaDiagnostic.add((String) failure.getClass().getMethod("kind").invoke(failure));
                        javaDiagnostic.add((Integer) failure.getClass().getMethod("offset").invoke(failure));
                        assertEquals(context + " independent diagnostic category/location", row.get("diagnostic"), javaDiagnostic);
                        assertEquals(context + " Java/Rust diagnostic category/location", javaDiagnostic, rust.get("diagnostic"));
                        assertEquals(context + " native-host independent diagnostic category/location",
                            row.get("diagnostic"), nativeRust.get("diagnostic"));
                        if (row.has("expectedHints")) {
                            JsonObject hints = row.getAsJsonObject("expectedHints");
                            assertEquals(context + " Java native hints", hints.get("java"),
                                new com.google.gson.Gson().toJsonTree(
                                    failure.getClass().getMethod("expected").invoke(failure)));
                            var rustHints = new JsonArray();
                            for (var hex : rust.getAsJsonArray("expectedHex")) rustHints.add(new String(
                                HexFormat.of().parseHex(hex.getAsString()), StandardCharsets.UTF_8));
                            assertEquals(context + " Rust native hints", hints.get("rust"), rustHints);
                        }
                    }
                    JsonElement expected = JsonNull.INSTANCE;
                    JsonElement javaAst = JsonNull.INSTANCE;
                    if (accepted) {
                        expected = row.get("ast");
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                        javaAst = canonical(ast, mapped);
                        assertEquals(context + " Java AST all fields/node spans", expected, javaAst);
                        if (explicitClassicMapping()) {
                            try (var classic = new ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                                var parsed = parser.parse(classic, org.unlaxer.TokenKind.consumed, false);
                                assertTrue(context, parsed.isSucceeded());
                                assertArrayEquals(context + " explicit Classic root raw UTF-8",
                                    input.getBytes(StandardCharsets.UTF_8),
                                    parsed.getRootToken().source.sourceAsString().getBytes(StandardCharsets.UTF_8));
                                Object direct = mapper.getMethod("mapParsedTokenWithSourceMap", org.unlaxer.Token.class).invoke(null, parsed.getRootToken());
                                Object directAst = direct.getClass().getMethod("ast").invoke(direct);
                                assertEquals(context + " explicit Classic AST/source map", expected, canonical(directAst,direct));
                            }
                        }
                        assertEquals(context + " Rust AST all fields/node spans", expected, rust.get("ast"));
                        assertEquals(context + " native-host independent AST all fields/node spans",
                            expected, nativeRust.get("ast"));
                    }
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input")
                        + "\t" + row.get("prefix") + "\t" + javaPrefix + "\t" + expected + "\t"
                        + javaAst + "\t" + rust + "\t" + diagnostic);
                    nativeReport.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input")
                        + "\t" + row.get("prefix") + "\t" + javaPrefix + "\t" + expected + "\t"
                        + javaAst + "\t" + nativeRust + "\t" + diagnostic);
                }
            }
        }

        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve(reportName() + ".tsv"), report, StandardCharsets.UTF_8);
        Files.write(target.resolve(reportName() + "-native.tsv"), nativeReport, StandardCharsets.UTF_8);
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

    private JsonArray prefix(Parser parser, String input, JsonObject row) throws Exception {
        JsonArray expected = null;
        for (var memo : Memoization.values()) for (var policy : ParseOptions.Diagnostics.values()) {
            var result = new JsonArray();
            try (var context = ParseContext.withOptions(org.unlaxer.StringSource.createRootSource(input),
                    ParseOptions.withMemoization(memo).withDiagnostics(policy))) {
                result.add(parser.parse(context).isSucceeded());
                result.add(context.getConsumedPosition().value());
                result.add(context.getMatchedPosition().value());
                if (row.has("memoHitsAtLeast") && memo == Memoization.SAFE_FAILURES) {
                    var hits = row.getAsJsonArray("memoHitsAtLeast");
                    assertTrue(input + " Java failure memo hit " + policy,
                        context.getPackratMemoTable().failureHits() >= hits.get(0).getAsInt());
                    assertTrue(input + " Java success memo hit " + policy,
                        context.getPackratMemoTable().successHits() >= hits.get(1).getAsInt());
                }
            }
            if (expected == null) expected = result;
            else assertEquals("Java prefix " + memo + "/" + policy + ": " + input, expected, result);
        }
        return expected;
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

    protected String rustProbe() {
        return """
            mod generated;
            use std::io::{self, BufRead};
            fn main() {
                for line in io::stdin().lock().lines() {
                    let encoded = line.unwrap();
                    let bytes = (0..encoded.len()).step_by(2)
                        .map(|i| u8::from_str_radix(&encoded[i..i + 2], 16).unwrap()).collect();
                    let input = String::from_utf8(bytes).unwrap();
                    let mut context = unlaxer_runtime::ParseContext::new(&input);
                    let prefix_ok = generated::parser::parse_context(&mut context).is_ok();
                    let options = unlaxer_runtime::ParseOptions::with_memoization(unlaxer_runtime::Memoization::SafeFailures);
                    let mut memo_context = unlaxer_runtime::ParseContext::with_options(&input, options);
                    let memo_ok = generated::parser::parse_context(&mut memo_context).is_ok();
                    assert_eq!((prefix_ok, context.position(), context.matched_position()),
                        (memo_ok, memo_context.position(), memo_context.matched_position()));
                    let mut hits = [usize::MAX; 2];
                    for memo in [unlaxer_runtime::Memoization::Off, unlaxer_runtime::Memoization::SafeFailures] {
                        for policy in [unlaxer_runtime::Diagnostics::Detailed,
                            unlaxer_runtime::Diagnostics::DetailedOnFailure, unlaxer_runtime::Diagnostics::Auto] {
                            let configured = unlaxer_runtime::ParseOptions::with_memoization(memo).with_diagnostics(policy);
                            let mut probe = unlaxer_runtime::ParseContext::with_options(&input, configured);
                            let ok = generated::parser::parse_context(&mut probe).is_ok();
                            if memo == unlaxer_runtime::Memoization::SafeFailures {
                                hits[0] = hits[0].min(probe.memoized_failure_hits());
                                hits[1] = hits[1].min(probe.memoized_success_hits());
                            }
                            assert_eq!((prefix_ok, context.position(), context.matched_position()),
                                (ok, probe.position(), probe.matched_position()), "prefix: {memo:?}/{policy:?}: {input}");
                            assert_eq!(generated::parser::parse_tree_detailed(&input).err(),
                                generated::parser::parse_tree_detailed_with_options(&input, configured).err(),
                                "diagnostic: {memo:?}/{policy:?}: {input}");
                        }
                    }
                    print!(r#"{{\"prefix\":[{},{},{}],\"memoHits\":{:?},\"ast\":"#,
                        prefix_ok, context.position(), context.matched_position(), hits);
                    match generated::parser::parse_tree_detailed(&input) {
                        Ok(tree) => {
                            let ast = generated::mapper::map(&tree).unwrap();
                            drop(tree);
                            let canonical = ast.canonical_json();
                            let memo_tree = generated::parser::parse_tree_detailed_with_options(&input, options).unwrap();
                            assert_eq!(canonical, generated::mapper::map(&memo_tree).unwrap().canonical_json());
                            print!("{}", canonical);
                        }
                        Err(error) => {
                            assert_eq!(error, generated::parser::parse_tree_detailed_with_options(&input, options).unwrap_err());
                            print!(r#"null,\"diagnostic\":[\"{}\",{}]"#, error.kind, error.offset);
                            let hints: Vec<String> = error.expected.iter().map(|hint|
                                hint.as_bytes().iter().map(|byte| format!("{byte:02x}")).collect()).collect();
                            print!(r#",\"expectedHex\":{:?}"#, hints);
                        }
                    }
                    println!("}}");
                }
            }
            """;
    }

    protected boolean explicitClassicMapping() { return false; }

    /** Base name of the required CI artifacts written under {@code target/}. */
    protected String reportName() { return "rust-rule-trivia"; }

    protected Path fixtureCorpus() {
        return repo.resolve("unlaxer-dsl/src/test/resources/rule-trivia/corpus.json");
    }

    private record ProcessResult(int code, String output) {}

    private ProcessResult run(List<String> command, String input, boolean withoutJava) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = org.unlaxer.dsl.TestProcesses.builder(command).redirectErrorStream(true).redirectOutput(log.toFile());
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
