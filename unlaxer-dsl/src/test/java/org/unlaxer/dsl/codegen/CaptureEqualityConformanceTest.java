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
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.EmptyParser;

/** Generated Java/Rust contract for scope-less capture equality and scoped references. */
public class CaptureEqualityConformanceTest {
    private static final String PACKAGE = "org.example.captureequality";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void captureEqualityPreservesSyntaxAstAndTransactionalDiagnostics() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println(
            "[assumption] CaptureEqualityConformanceTest requires -DrustConformance=true");
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
            repo.resolve("spec-corpus/capture-equality/corpus.json"))).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of(
            "fixture\tcase\tinput_json\tprefix\tsyntax\tstate\tast\trust"));
        int fixtureIndex = 0;
        for (JsonElement fixtureElement : corpus) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(fixture.get("grammar").getAsString()).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            List<RustBackend.GeneratedFile> javaFrontend = new RustBackend().generate(grammar);
            assertEquals(name + " five Rust modules", 5, javaFrontend.size());
            Path directory = temporary.getRoot().toPath().resolve("fixture-" + fixtureIndex++);
            Files.createDirectories(directory);
            Path ubnf = directory.resolve(name + ".ubnf");
            Files.writeString(ubnf, fixture.get("grammar").getAsString());
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
            String framed = String.join("\n", cases.asList().stream().map(item -> {
                JsonObject row = item.getAsJsonObject();
                return HexFormat.of().formatHex(row.get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8)) + "\t" + row.has("parentRollback");
            }).toList()) + "\n";
            ProcessResult rustResult = run(List.of(directory.resolve("probe").toString()), framed, false);
            success(rustResult);
            List<String> rustLines = rustResult.output().lines().toList();
            assertEquals(name + " Rust probe rows", cases.size(), rustLines.size());
            try (URLClassLoader loader = compileJava(grammar)) {
                Parser parser = (Parser) loader.loadClass(PACKAGE + "." + name + "Parsers")
                    .getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + name + "Mapper");
                for (int i = 0; i < cases.size(); i++) {
                    JsonObject row = cases.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String label = name + "/" + row.get("id").getAsString();
                    JsonObject rust = JsonParser.parseString(rustLines.get(i)).getAsJsonObject();
                    JsonArray javaPrefix = new JsonArray();
                    JsonObject javaState;
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        javaPrefix.add(parser.parse(context).isSucceeded());
                        javaPrefix.add(context.getConsumedPosition().value());
                        javaPrefix.add(context.getMatchedPosition().value());
                        javaState = state(context);
                    }
                    assertEquals(label + " Java prefix", row.get("prefix"), javaPrefix);
                    assertEquals(label + " Rust prefix", row.get("prefix"), rust.get("prefix"));
                    JsonObject expectedState = expectedState(row);
                    assertEquals(label + " Java semantic state", expectedState, javaState);
                    assertEquals(label + " Rust semantic state", expectedState, rust.get("state"));
                    JsonArray javaPolicies = policiesJava(parser, input);
                    assertEquals(label + " Java semantic state across diagnostic/memo policies",
                        expectedPolicies(row), javaPolicies);
                    assertEquals(label + " Rust semantic state across diagnostic/memo policies",
                        expectedPolicies(row), rust.get("policies"));
                    boolean syntax = row.get("syntax").getAsBoolean();
                    Optional<?> diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class)
                        .invoke(null, input);
                    assertEquals(label + " Java full-input syntax", syntax, diagnostic.isEmpty());
                    assertEquals(label + " Rust full-input syntax", syntax, rust.get("syntax").getAsBoolean());
                    assertEquals(label + " Java full-input diagnostic policy/memo syntax",
                        expectedJavaFullPolicies(row), fullPoliciesJava(mapper, input));
                    assertEquals(label + " Rust full-input diagnostic policy/memo semantic state",
                        expectedFullPolicies(row), rust.get("fullPolicies"));
                    String mapping = row.get("mapping").getAsString();
                    assertEquals(label + " Rust mapping outcome", mapping,
                        rust.get("mapping").getAsString());
                    if (mapping.equals("ok")) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                        assertEquals(label + " Java AST and spans", row.get("ast"), canonical(ast, mapped));
                        assertEquals(label + " Rust AST and spans", row.get("ast"), rust.get("ast"));
                        assertEquals(label + " Rust owned tree state", expectedState,
                            rust.get("treeState"));
                    } else if (mapping.equals("failure")) {
                        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                            () -> mapper.getMethod("parse", String.class).invoke(null, input));
                        assertTrue(label + " explicit Java mapping failure",
                            failure.getCause() instanceof IllegalArgumentException);
                        assertTrue(label + " no Rust AST", rust.get("ast").isJsonNull());
                        assertEquals(label + " Rust owned tree state", expectedState,
                            rust.get("treeState"));
                    } else {
                        assertTrue(label + " no Rust AST", rust.get("ast").isJsonNull());
                        assertTrue(label + " no Rust tree", rust.get("treeState").isJsonNull());
                    }
                    if (row.has("parentRollback")) {
                        assertEquals(label + " Java enclosing rollback", emptyState(),
                            rollbackJava(parser, input));
                        assertEquals(label + " Rust enclosing rollback", emptyState(),
                            rust.get("rollback"));
                    }
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input")
                        + "\t" + row.get("prefix") + "\t" + row.get("syntax") + "\t"
                        + expectedState + "\t" + row.get("ast") + "\t" + rust);
                }
            }
        }
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve("rust-capture-equality.tsv"), report, StandardCharsets.UTF_8);
    }

    private JsonObject expectedState(JsonObject row) {
        JsonObject result = new JsonObject();
        result.add("declarations", row.get("declarations"));
        result.add("references", row.get("references"));
        result.add("diagnostics", row.get("diagnostics"));
        return result;
    }

    private JsonObject emptyState() {
        JsonObject result = new JsonObject();
        result.add("declarations", new JsonArray());
        result.add("references", new JsonArray());
        result.add("diagnostics", new JsonArray());
        return result;
    }

    private JsonObject rollbackJava(Parser parser, String input) throws Exception {
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            Parser parent = new EmptyParser();
            context.begin(parent);
            assertTrue(parser.parse(context).isSucceeded());
            context.rollback(parent);
            assertEquals(0, context.getConsumedPosition().value());
            assertEquals(0, context.getMatchedPosition().value());
            return state(context);
        }
    }

    private JsonArray expectedPolicies(JsonObject row) {
        JsonArray result = new JsonArray();
        for (String memo : List.of("off", "safe")) {
            for (String diagnostics : List.of("auto", "detailed", "onFailure")) {
                JsonObject item = new JsonObject();
                item.addProperty("memo", memo);
                item.addProperty("diagnostics", diagnostics);
                item.add("prefix", row.get("prefix"));
                item.add("state", expectedState(row));
                result.add(item);
            }
        }
        return result;
    }

    private JsonArray expectedFullPolicies(JsonObject row) {
        JsonArray result = new JsonArray();
        for (String memo : List.of("off", "safe")) {
            for (String diagnostics : List.of("auto", "detailed", "onFailure")) {
                JsonObject item = new JsonObject();
                item.addProperty("memo", memo);
                item.addProperty("diagnostics", diagnostics);
                item.addProperty("syntax", row.get("syntax").getAsBoolean());
                item.add("state", row.get("syntax").getAsBoolean()
                    ? expectedState(row) : JsonNull.INSTANCE);
                result.add(item);
            }
        }
        return result;
    }

    private JsonArray expectedJavaFullPolicies(JsonObject row) {
        JsonArray result = new JsonArray();
        for (JsonElement element : expectedFullPolicies(row)) {
            JsonObject full = element.getAsJsonObject();
            JsonObject item = new JsonObject();
            item.add("memo", full.get("memo"));
            item.add("diagnostics", full.get("diagnostics"));
            item.add("syntax", full.get("syntax"));
            result.add(item);
        }
        return result;
    }

    private JsonArray fullPoliciesJava(Class<?> mapper, String input) throws Exception {
        JsonArray result = new JsonArray();
        for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
            for (ParseOptions.Diagnostics diagnostics : List.of(ParseOptions.Diagnostics.AUTO,
                    ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE)) {
                ParseOptions options = ParseOptions.withMemoization(memo).withDiagnostics(diagnostics);
                Optional<?> parseDiagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class,
                    ParseOptions.class).invoke(null, input, options);
                JsonObject item = new JsonObject();
                item.addProperty("memo", memo == Memoization.OFF ? "off" : "safe");
                item.addProperty("diagnostics", switch (diagnostics) {
                    case AUTO -> "auto";
                    case DETAILED -> "detailed";
                    case DETAILED_ON_FAILURE -> "onFailure";
                });
                item.addProperty("syntax", parseDiagnostic.isEmpty());
                // The Java full-input diagnostic entry point exposes syntax diagnostics only.
                // The same options are checked against semantic state via policiesJava.
                result.add(item);
            }
        }
        return result;
    }

    private JsonArray policiesJava(Parser parser, String input) throws Exception {
        JsonArray result = new JsonArray();
        for (Memoization memo : List.of(Memoization.OFF, Memoization.SAFE_FAILURES)) {
            for (ParseOptions.Diagnostics diagnostics : List.of(ParseOptions.Diagnostics.AUTO,
                    ParseOptions.Diagnostics.DETAILED, ParseOptions.Diagnostics.DETAILED_ON_FAILURE)) {
                ParseOptions options = ParseOptions.withMemoization(memo).withDiagnostics(diagnostics);
                try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                    JsonObject item = new JsonObject();
                    item.addProperty("memo", memo == Memoization.OFF ? "off" : "safe");
                    item.addProperty("diagnostics", switch (diagnostics) {
                        case AUTO -> "auto";
                        case DETAILED -> "detailed";
                        case DETAILED_ON_FAILURE -> "onFailure";
                    });
                    JsonArray prefix = new JsonArray();
                    prefix.add(parser.parse(context).isSucceeded());
                    prefix.add(context.getConsumedPosition().value());
                    prefix.add(context.getMatchedPosition().value());
                    item.add("prefix", prefix);
                    item.add("state", state(context));
                    result.add(item);
                }
            }
        }
        return result;
    }

    private JsonObject state(ParseContext context) {
        JsonObject result = new JsonObject();
        JsonArray declarations = new JsonArray();
        for (var value : ScopeStore.getAllDeclarations(context)) {
            JsonObject item = new JsonObject();
            item.addProperty("name", value.name());
            item.addProperty("sourceOffset", value.sourceOffset());
            declarations.add(item);
        }
        result.add("declarations", declarations);
        JsonArray references = new JsonArray();
        for (var value : ScopeStore.getAllReferences(context)) {
            JsonObject item = new JsonObject();
            item.addProperty("name", value.name());
            item.addProperty("offset", value.offset());
            item.addProperty("length", value.length());
            references.add(item);
        }
        result.add("references", references);
        JsonArray diagnostics = new JsonArray();
        for (var value : ScopeStore.getDiagnostics(context)) {
            JsonObject item = new JsonObject();
            item.addProperty("message", value.message());
            item.addProperty("offset", value.offset());
            item.addProperty("length", value.length());
            item.addProperty("severity", value.severity().name());
            diagnostics.add(item);
        }
        result.add("diagnostics", diagnostics);
        return result;
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(),
                new MapperGenerator())) sources.add(generator.generate(grammar));
        var units = sources.stream().map(source -> new SimpleJavaFileObject(
            URI.create("string:///" + source.packageName().replace('.', '/') + "/"
                + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source.source(); }
        }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("JDK required", compiler);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
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
        JsonArray pair = new JsonArray();
        pair.add(span[0]); pair.add(span[1]); node.add("span", pair);
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
            use unlaxer_runtime::{json_string, ScopeStore, Severity, Diagnostics, Memoization, ParseOptions};
            fn state(scope: &ScopeStore) -> String {
                let declarations = scope.all_declarations().iter().map(|value| format!(
                    r#"{{"name":{},"sourceOffset":{}}}"#, json_string(&value.name),
                    value.source_offset)).collect::<Vec<_>>().join(",");
                let references = scope.all_references().iter().map(|value| format!(
                    r#"{{"name":{},"offset":{},"length":{}}}"#, json_string(&value.name),
                    value.offset, value.length)).collect::<Vec<_>>().join(",");
                let diagnostics = scope.diagnostics().iter().map(|value| {
                    let severity = match value.severity { Severity::Error => "ERROR",
                        Severity::Warning => "WARNING", Severity::Info => "INFO",
                        Severity::Hint => "HINT" };
                    format!(r#"{{"message":{},"offset":{},"length":{},"severity":{}}}"#,
                        json_string(&value.message), value.offset, value.length, json_string(severity))
                }).collect::<Vec<_>>().join(",");
                format!(r#"{{"declarations":[{}],"references":[{}],"diagnostics":[{}]}}"#,
                    declarations, references, diagnostics)
            }
            fn main() {
                for line in io::stdin().lock().lines() {
                    let framed = line.unwrap();
                    let (encoded, rollback_requested) = framed.split_once(char::from(9)).unwrap();
                    let bytes = (0..encoded.len()).step_by(2)
                        .map(|i| u8::from_str_radix(&encoded[i..i + 2], 16).unwrap()).collect();
                    let input = String::from_utf8(bytes).unwrap();
                    let mut context = unlaxer_runtime::ParseContext::new(&input);
                    let prefix_ok = generated::parser::parse_context(&mut context).is_ok();
                    let prefix = format!("[{},{},{}]", prefix_ok, context.position(),
                        context.matched_position());
                    let prefix_state = state(context.scopes());
                    let mut policy_rows = Vec::new();
                    for memo in [Memoization::Off, Memoization::SafeFailures] {
                        for diagnostics in [Diagnostics::Auto, Diagnostics::Detailed,
                                Diagnostics::DetailedOnFailure] {
                            let options = ParseOptions::with_memoization(memo)
                                .with_diagnostics(diagnostics);
                            let mut ctx = unlaxer_runtime::ParseContext::with_options(&input, options);
                            let ok = generated::parser::parse_context(&mut ctx).is_ok();
                            let memo_name = if memo == Memoization::Off { "off" } else { "safe" };
                            let diagnostics_name = match diagnostics {
                                Diagnostics::Auto => "auto", Diagnostics::Detailed => "detailed",
                                Diagnostics::DetailedOnFailure => "onFailure" };
                            policy_rows.push(format!(concat!(r#"{{"memo":"{}","diagnostics":"{}","#,
                                r#""prefix":[{},{},{}],"state":{}}}"#), memo_name,
                                diagnostics_name, ok, ctx.position(), ctx.matched_position(),
                                state(ctx.scopes())));
                        }
                    }
                    let policies = policy_rows.join(",");
                    let mut full_policy_rows = Vec::new();
                    for memo in [Memoization::Off, Memoization::SafeFailures] {
                        for diagnostics in [Diagnostics::Auto, Diagnostics::Detailed,
                                Diagnostics::DetailedOnFailure] {
                            let options = ParseOptions::with_memoization(memo)
                                .with_diagnostics(diagnostics);
                            let full = generated::parser::parse_tree_detailed_with_options(&input, options);
                            let (ok, full_state) = match full {
                                Ok(tree) => (true, state(tree.scopes())),
                                Err(_) => (false, "null".to_owned()),
                            };
                            let memo_name = if memo == Memoization::Off { "off" } else { "safe" };
                            let diagnostics_name = match diagnostics {
                                Diagnostics::Auto => "auto", Diagnostics::Detailed => "detailed",
                                Diagnostics::DetailedOnFailure => "onFailure" };
                            full_policy_rows.push(format!(concat!(r#"{{"memo":"{}","diagnostics":"{}","#,
                                r#""syntax":{},"state":{}}}"#), memo_name, diagnostics_name,
                                ok, full_state));
                        }
                    }
                    let full_policies = full_policy_rows.join(",");
                    let (syntax, mapping, ast, tree_state) = match generated::parser::parse_tree_detailed(&input) {
                        Ok(tree) => {
                            let tree_state = state(tree.scopes());
                            match generated::mapper::map(&tree) {
                                Ok(ast) => (true, "ok", ast.canonical_json(), tree_state),
                                Err(_) => (true, "failure", "null".to_owned(), tree_state),
                            }
                        }
                        Err(_) => (false, "none", "null".to_owned(), "null".to_owned()),
                    };
                    let rollback = if rollback_requested == "true" {
                        let mut rollback_context = unlaxer_runtime::ParseContext::new(&input);
                        let _: Result<(), unlaxer_runtime::ParseError> = rollback_context.transaction(|ctx| {
                            generated::parser::parse_context(ctx)?;
                            Err(ctx.error("forced parent rollback"))
                        });
                        assert_eq!(rollback_context.position(), 0);
                        assert_eq!(rollback_context.matched_position(), 0);
                        state(rollback_context.scopes())
                    } else { "null".to_owned() };
                    println!(concat!(r#"{{"prefix":{},"state":{},"policies":[{}],"fullPolicies":[{}],"#,
                        r#""syntax":{},"mapping":"{}","ast":{},"treeState":{},"rollback":{}}}"#),
                        prefix, prefix_state, policies, full_policies, syntax, mapping, ast,
                        tree_state, rollback);
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
                process.destroyForcibly();
                fail("process timed out: " + command);
            }
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
    private void success(ProcessResult result) { assertEquals(result.output(), 0, result.code()); }
}
