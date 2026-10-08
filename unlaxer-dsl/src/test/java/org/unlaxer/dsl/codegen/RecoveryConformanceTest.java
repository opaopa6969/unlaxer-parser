package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
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
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.RecoveryDiagnostic;

/** Recovery is successful syntax with explicit error regions, not an evaluable typed AST. */
public class RecoveryConformanceTest {
    private static final String PACKAGE = "org.example.recovery";
    private static final String MESSAGE = "syntax error: skipped to sync point";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void generatedRecoveryPreservesRegionsStateAndMappingBoundary() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println(
            "[assumption] RecoveryConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib",
            "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
            "-o", runtime.toString()), "", false));
        Path nativeTarget = temporary.getRoot().toPath().resolve("native-target");
        success(run(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--target-dir",
            nativeTarget.toString()), "", false));
        Path nativeGenerator = nativeTarget.resolve("debug/unlaxer").toAbsolutePath();
        JsonArray corpus = JsonParser.parseString(Files.readString(
            repo.resolve("spec-corpus/recovery/corpus.json"))).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of("fixture\tcase\tmemo\tdiagnostics\tinput_json\texpected\tjava_actual\trust_actual\tfailure_expected\tjava_failure\trust_failure"));
        int index = 0;
        for (JsonElement fixtureElement : corpus) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String source = fixture.get("grammar").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            var javaFrontend = new RustBackend().generate(grammar);
            assertEquals(name + " Rust module count", 5, javaFrontend.size());
            Path directory = Files.createDirectory(temporary.getRoot().toPath().resolve("fixture-" + index++));
            Path ubnf = directory.resolve(name + ".ubnf");
            Files.writeString(ubnf, source);
            Path nativeGenerated = directory.resolve("native-generated");
            success(run(List.of(nativeGenerator.toString(), "generate", "--grammar", ubnf.toString(),
                "--output", nativeGenerated.toString()), "", true));
            Path generated = Files.createDirectory(directory.resolve("generated"));
            for (var file : javaFrontend) {
                assertArrayEquals(name + "/" + file.relativePath(),
                    file.content().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(nativeGenerated.resolve(file.relativePath())));
                Files.writeString(generated.resolve(file.relativePath()), file.content());
            }
            Files.writeString(directory.resolve("main.rs"), rustProbe());
            success(run(List.of("rustc", "--edition=2021", "--extern",
                "unlaxer_runtime=" + runtime, directory.resolve("main.rs").toString(),
                "-o", directory.resolve("probe").toString()), "", false));
            JsonArray cases = fixture.getAsJsonArray("cases");
            String framed = String.join("\n", cases.asList().stream().map(item -> HexFormat.of()
                .formatHex(item.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
            ProcessResult rustResult = run(List.of(directory.resolve("probe").toString()), framed, true);
            success(rustResult);
            List<String> rustLines = rustResult.output().lines().toList();
            assertEquals(name + " probe rows", cases.size(), rustLines.size());
            try (URLClassLoader loader = compileJava(grammar)) {
                Parser parser = (Parser) loader.loadClass(PACKAGE + "." + name + "Parsers")
                    .getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + name + "Mapper");
                for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
                    JsonObject row = cases.get(caseIndex).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String label = name + "/" + row.get("id").getAsString();
                    JsonArray rustPolicies = JsonParser.parseString(rustLines.get(caseIndex)).getAsJsonArray();
                    assertEquals(label + " policy count", 6, rustPolicies.size());
                    JsonObject oracle = expected(row);
                    int policyIndex = 0;
                    for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
                        for (ParseOptions.Diagnostics diagnostics : List.of(ParseOptions.Diagnostics.AUTO,
                                ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE)) {
                            String policy = label + "/" + memo + "/" + diagnostics;
                            ParseOptions options = ParseOptions.withMemoization(memo).withDiagnostics(diagnostics);
                            JsonObject java = javaResult(parser, mapper, input, options);
                            JsonObject rust = rustPolicies.get(policyIndex++).getAsJsonObject();
                            JsonElement javaFailure = java.remove("failureDetail");
                            JsonElement rustFailure = rust.remove("failureDetail");
                            if (row.has("failureDetail")) {
                                assertEquals(policy + " Java original failure", row.get("failureDetail"), javaFailure);
                                assertEquals(policy + " Rust original failure", row.get("failureDetail"), rustFailure);
                            }
                            assertEquals(policy + " Java", oracle, java);
                            assertEquals(policy + " Rust", oracle, rust);
                            report.add(name + "\t" + row.get("id").getAsString() + "\t" + memo + "\t"
                                + diagnostics + "\t" + row.get("input") + "\t" + oracle + "\t" + java + "\t" + rust
                                + "\t" + row.get("failureDetail") + "\t" + javaFailure + "\t" + rustFailure);
                        }
                    }
                }
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/rust-recovery.tsv"), report, StandardCharsets.UTF_8);
    }

    private JsonObject expected(JsonObject row) {
        JsonObject expected = new JsonObject();
        JsonArray prefix = row.getAsJsonArray("prefix");
        expected.add("prefix", prefix);
        JsonArray recoveries = new JsonArray();
        for (var span : row.getAsJsonArray("recoveries")) {
            JsonObject recovery = new JsonObject();
            recovery.add("span", span);
            recovery.addProperty("message", MESSAGE);
            recoveries.add(recovery);
        }
        expected.add("recoveries", recoveries);
        expected.add("declarations", row.has("declarations") ? row.get("declarations") : new JsonArray());
        expected.add("references", new JsonArray());
        expected.add("semanticDiagnostics", new JsonArray());
        expected.addProperty("mapping", !prefix.get(0).getAsBoolean() ? "parse_failed"
            : recoveries.isEmpty() ? "mapped" : "recovered");
        expected.add("ast", row.has("ast") ? row.get("ast") : JsonNull.INSTANCE);
        String input = row.get("input").getAsString();
        boolean complete = prefix.get(0).getAsBoolean()
            && prefix.get(1).getAsInt() == input.codePointCount(0, input.length());
        String kind = !prefix.get(0).getAsBoolean() ? "syntax" : !complete ? "trailing_input"
            : recoveries.isEmpty() ? null : "recovery";
        expected.addProperty("fullKind", kind);
        expected.add("ownedRecoveries", complete ? recoveries : JsonNull.INSTANCE);
        return expected;
    }

    private JsonObject javaResult(Parser parser, Class<?> mapper, String input, ParseOptions options) throws Exception {
        JsonObject result = new JsonObject();
        try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
            var parsed = parser.parse(context);
            JsonArray prefix = new JsonArray();
            prefix.add(parsed.isSucceeded());
            prefix.add(context.getConsumedPosition().value());
            prefix.add(context.getMatchedPosition().value());
            result.add("prefix", prefix);
            JsonArray recoveries = recoveryJson(RecoveryDiagnostic.from(context));
            result.add("recoveries", recoveries);
            JsonArray declarations = new JsonArray();
            for (var value : ScopeStore.getAllDeclarations(context)) {
                JsonObject declaration = new JsonObject();
                declaration.addProperty("name", value.name());
                declaration.addProperty("sourceOffset", value.sourceOffset());
                declarations.add(declaration);
            }
            result.add("declarations", declarations);
            JsonArray references = new JsonArray();
            for (var value : ScopeStore.getAllReferences(context)) {
                JsonObject reference = new JsonObject();
                reference.addProperty("name", value.name());
                reference.addProperty("offset", value.offset());
                reference.addProperty("length", value.length());
                references.add(reference);
            }
            result.add("references", references);
            JsonArray semanticDiagnostics = new JsonArray();
            for (var value : ScopeStore.getDiagnostics(context)) semanticDiagnostics.add(value.message());
            result.add("semanticDiagnostics", semanticDiagnostics);
            result.add("ast", JsonNull.INSTANCE);
            boolean complete = parsed.isSucceeded() && context.getConsumedPosition().value()
                == input.codePointCount(0, input.length());
            result.add("ownedRecoveries", JsonNull.INSTANCE);
            if (parsed.isSucceeded()) {
                // Keep the committed root, including a generated recovery wrapper.
                // Parsed.getRootToken may strip a rule needed by typed mapping.
                assertEquals("one committed root", 1, context.getCurrent().getTokens().size());
                Token token = context.getCurrent().getTokens().get(0);
                if (!recoveries.isEmpty()) {
                    expectRecoveryRejection(mapper, "mapParsedToken", new Class<?>[]{Token.class}, token);
                    expectRecoveryRejection(mapper, "mapParsedTree", new Class<?>[]{Token.class}, token);
                    expectRecoveryRejection(mapper, "mapSubtreeToken", new Class<?>[]{Token.class}, token);
                    expectRecoveryRejection(mapper, "mapSubtreeTree", new Class<?>[]{Token.class}, token);
                    if (complete) expectRecoveryRejection(mapper, "parseWithOptions",
                        new Class<?>[]{String.class, ParseOptions.class}, input, options);
                    result.addProperty("mapping", "recovered");
                } else {
                    Object mapped = mapper.getMethod("mapParsedTokenWithSourceMap", Token.class).invoke(null, token);
                    Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                    result.add("ast", canonical(ast, mapped));
                    result.addProperty("mapping", "mapped");
                }
                if (complete) result.add("ownedRecoveries", recoveryJson(RecoveryDiagnostic.from(token)));
            } else result.addProperty("mapping", "parse_failed");
        }
        Optional<?> diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class, ParseOptions.class)
            .invoke(null, input, options);
        result.addProperty("fullKind", diagnostic.isEmpty() ? null
            : (String) diagnostic.get().getClass().getMethod("kind").invoke(diagnostic.get()));
        result.add("failureDetail", JsonNull.INSTANCE);
        if (diagnostic.isPresent()) {
            Object value = diagnostic.get();
            JsonObject failure = new JsonObject();
            failure.addProperty("offset", (Integer) value.getClass().getMethod("offset").invoke(value));
            failure.add("expected", new Gson().toJsonTree(value.getClass().getMethod("expected").invoke(value)));
            result.add("failureDetail", failure);
        }
        return result;
    }

    private void expectRecoveryRejection(Class<?> mapper, String method, Class<?>[] types, Object... args) throws Exception {
        try {
            mapper.getMethod(method, types).invoke(null, args);
            fail(method + " must reject recovered syntax");
        } catch (InvocationTargetException exception) {
            assertTrue(method + ": " + exception.getCause(), exception.getCause() instanceof IllegalArgumentException);
            assertTrue(method + ": " + exception.getCause(),
                exception.getCause().getMessage().contains("cannot map recovered syntax"));
        }
    }

    private JsonArray recoveryJson(List<RecoveryDiagnostic> values) {
        JsonArray result = new JsonArray();
        for (var value : values) {
            JsonObject item = new JsonObject();
            JsonArray span = new JsonArray();
            span.add(value.start()); span.add(value.end()); item.add("span", span);
            item.addProperty("message", value.message()); result.add(item);
        }
        return result;
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator()))
            sources.add(generator.generate(grammar));
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/"
                + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
        }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("JDK required", compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path")
                    + File.pathSeparator, "-d", output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JsonElement canonical(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonical(optional.orElse(null), mapped);
        if (value instanceof List<?> values) {
            JsonArray array = new JsonArray();
            for (Object item : values) array.add(canonical(item, mapped));
            return array;
        }
        JsonObject node = new JsonObject();
        node.addProperty("type", value.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class)
            .invoke(mapped, value)).orElseThrow();
        JsonArray pair = new JsonArray(); pair.add(span[0]); pair.add(span[1]); node.add("span", pair);
        JsonObject fields = new JsonObject();
        for (var component : value.getClass().getRecordComponents()) fields.add(component.getName(),
            canonical(component.getAccessor().invoke(value), mapped));
        node.add("fields", fields);
        return node;
    }

    private String rustProbe() {
        return """
            mod generated;
            use std::io::{self, BufRead};
            use unlaxer_runtime::{json_string, Diagnostics, Memoization, ParseOptions, RecoveryDiagnostic};
            fn recoveries(values: &[RecoveryDiagnostic]) -> String {
                let items = values.iter().map(|v| format!(r#"{{"span":[{},{}],"message":{}}}"#,
                    v.span.start, v.span.end, json_string(&v.message))).collect::<Vec<_>>().join(",");
                format!("[{}]", items)
            }
            fn main() {
                for line in io::stdin().lock().lines() {
                    let encoded = line.unwrap();
                    let bytes = (0..encoded.len()).step_by(2)
                        .map(|i| u8::from_str_radix(&encoded[i..i+2], 16).unwrap()).collect();
                    let input = String::from_utf8(bytes).unwrap();
                    let mut policies = Vec::new();
                    for memo in [Memoization::Off, Memoization::SafeFailures] {
                        for diagnostics in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
                            let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                            let mut context = unlaxer_runtime::ParseContext::with_options(&input, options);
                            let parsed = generated::parser::parse_context(&mut context);
                            let recovered = recoveries(context.recoveries());
                            let mut ast = "null".to_owned();
                            let mapping = match &parsed {
                                Ok(value) => {
                                    let tree = context.tree(value.root_node().unwrap()).unwrap();
                                    match generated::mapper::map(&tree) {
                                        Ok(value) => { assert!(tree.recoveries().is_empty());
                                            ast = value.canonical_json(); "mapped" },
                                        Err(error) => { assert!(!tree.recoveries().is_empty(), "{error}");
                                            assert!(error.contains("cannot map recovered syntax"), "{error}"); "recovered" }
                                    }
                                },
                                Err(_) => "parse_failed"
                            };
                            let declarations = context.scopes().all_declarations().iter().map(|value|
                                format!(r#"{{"name":{},"sourceOffset":{}}}"#, json_string(&value.name), value.source_offset))
                                .collect::<Vec<_>>().join(",");
                            let references = context.scopes().all_references().iter().map(|value|
                                format!(r#"{{"name":{},"offset":{},"length":{}}}"#, json_string(&value.name), value.offset, value.length))
                                .collect::<Vec<_>>().join(",");
                            let semantic = context.scopes().diagnostics().iter().map(|value| json_string(&value.message))
                                .collect::<Vec<_>>().join(",");
                            let (full_kind, owned, failure) = match generated::parser::parse_tree_detailed_with_options(&input, options) {
                                Ok(tree) => (if tree.recoveries().is_empty() { "null".to_owned() }
                                    else { json_string("recovery") }, recoveries(tree.recoveries()), "null".to_owned()),
                                Err(error) => (json_string(error.kind), "null".to_owned(), format!(r#"{{"offset":{},"expected":[{}]}}"#,
                                    error.offset, error.expected.iter().map(|v| json_string(v)).collect::<Vec<_>>().join(",")))
                            };
                            policies.push(format!(concat!(r#"{{"prefix":[{},{},{}],"recoveries":{},"declarations":[{}],"references":[{}],"semanticDiagnostics":[{}],"mapping":{},"ast":{},"fullKind":{},"ownedRecoveries":{},"failureDetail":{}}}"#),
                                parsed.is_ok(), context.position(), context.matched_position(), recovered,
                                declarations, references, semantic, json_string(mapping), ast, full_kind, owned, failure));
                        }
                    }
                    println!("[{}]", policies.join(","));
                }
            }
            """;
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
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly(); fail("process timed out: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private void success(ProcessResult result) { assertEquals(result.output(), 0, result.code()); }
}
