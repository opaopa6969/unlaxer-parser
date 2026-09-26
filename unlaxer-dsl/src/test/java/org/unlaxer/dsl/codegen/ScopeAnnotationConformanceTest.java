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
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.dsl.runtime.ScopeStore.ReferenceInfo;
import org.unlaxer.dsl.runtime.ScopeStore.SymbolDiagnostic;
import org.unlaxer.dsl.runtime.ScopeStore.SymbolInfo;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.EmptyParser;

/** Generated Java/Rust contract for transactional scope annotations and capture sites. */
public class ScopeAnnotationConformanceTest {
    private static final String PACKAGE = "org.example.scopeannotations";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void generatedScopeEffectsPreserveMetadataEventsAstAndRollback() throws Exception {
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

        JsonArray corpus = JsonParser.parseString(Files.readString(repo.resolve(
            "unlaxer-dsl/src/test/resources/scope-annotations/corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of(
            "fixture\tcase\tinput_json\texpected_prefix\tjava_prefix\texpected_scope\tjava_scope\texpected_ast\tjava_ast\trust"));

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
            Files.writeString(fixtureDir.resolve("main.rs"), rustProbe(fixture));
            success(run(List.of("rustc", "--edition=2021", "--extern",
                "unlaxer_runtime=" + runtime, fixtureDir.resolve("main.rs").toString(), "-o",
                fixtureDir.resolve("probe").toString()), "", false));

            JsonArray cases = fixture.getAsJsonArray("cases");
            String framed = String.join("\n", cases.asList().stream().map(element -> {
                JsonObject row = element.getAsJsonObject();
                String input = HexFormat.of().formatHex(
                    row.get("input").getAsString().getBytes(StandardCharsets.UTF_8));
                return input + "\t" + row.has("parentRollback");
            }).toList()) + "\n";
            ProcessResult rustResult = run(List.of(fixtureDir.resolve("probe").toString()), framed, false);
            success(rustResult);
            List<String> rustLines = rustResult.output().lines().toList();
            assertEquals(name + " Rust result count", cases.size(), rustLines.size());

            try (URLClassLoader loader = compileJava(grammar)) {
                Class<?> parsers = loader.loadClass(PACKAGE + "." + grammar.name() + "Parsers");
                Parser parser = (Parser) parsers.getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + grammar.name() + "Mapper");
                assertEquals(name + " Java generated rule-effects metadata", fixture.get("metadata"),
                    javaMetadata(parsers, fixture.getAsJsonArray("metadata")));
                for (int i = 0; i < cases.size(); i++) {
                    JsonObject row = cases.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String context = name + "/" + row.get("id").getAsString();
                    JsonObject rust = JsonParser.parseString(rustLines.get(i)).getAsJsonObject();

                    JavaParse java = parseJava(parser, input, fixture.getAsJsonArray("queries"),
                        row.has("parentRollback"));
                    assertEquals(context + " independent prefix cursor oracle", row.get("prefix"),
                        java.prefix());
                    assertEquals(context + " Java/Rust prefix cursor parity", java.prefix(),
                        rust.get("prefix"));
                    assertEquals(context + " Java scope event oracle", row.get("scope"), java.scope());
                    assertEquals(context + " Rust scope event oracle", row.get("scope"),
                        rust.get("scope"));
                    assertEquals(context + " generated rule-effects metadata", fixture.get("metadata"),
                        rust.get("metadata"));

                    boolean accepted = row.get("accepted").getAsBoolean();
                    Optional<?> diagnostic =
                        (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    assertEquals(context + " Java full-input acceptance", accepted, diagnostic.isEmpty());
                    assertEquals(context + " Rust full-input acceptance", accepted,
                        !rust.get("ast").isJsonNull());
                    assertEquals(context + " owned Tree scope snapshot", accepted ? row.get("scope")
                        : JsonNull.INSTANCE, rust.get("treeScope"));

                    JsonElement expectedAst = accepted ? row.get("ast") : JsonNull.INSTANCE;
                    JsonElement javaAst = JsonNull.INSTANCE;
                    if (accepted) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class)
                            .invoke(null, input);
                        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                        javaAst = canonical(ast, mapped);
                        assertEquals(context + " Java AST all fields/node spans", expectedAst, javaAst);
                        assertEquals(context + " Rust AST all fields/node spans", expectedAst,
                            rust.get("ast"));
                    }

                    JsonElement expectedRollback = JsonNull.INSTANCE;
                    if (row.has("parentRollback")) {
                        expectedRollback = rollbackOracle(fixture.getAsJsonArray("queries"));
                        assertEquals(context + " Java enclosing transaction rollback", expectedRollback,
                            java.rollback());
                    }
                    assertEquals(context + " Rust enclosing transaction rollback", expectedRollback,
                        rust.get("rollback"));
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input")
                        + "\t" + row.get("prefix") + "\t" + java.prefix() + "\t" + row.get("scope")
                        + "\t" + java.scope() + "\t" + expectedAst + "\t" + javaAst + "\t" + rust);
                }
            }
        }

        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve("rust-scope-annotations.tsv"), report, StandardCharsets.UTF_8);
    }

    private JavaParse parseJava(Parser parser, String input, JsonArray queries,
            boolean parentRollback) throws Exception {
        JsonArray prefix;
        JsonObject scope;
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            Parsed parsed = parser.parse(context);
            prefix = prefix(parsed, context);
            scope = javaScope(context, queries);
        }
        JsonElement rollback = JsonNull.INSTANCE;
        if (parentRollback) {
            try (var context = new ParseContext(StringSource.createRootSource(input))) {
                Parser parent = new EmptyParser();
                context.begin(parent);
                assertTrue("fixture root succeeds before forced parent rollback",
                    parser.parse(context).isSucceeded());
                context.rollback(parent);
                var value = new JsonObject();
                var cursor = new JsonArray();
                cursor.add(context.getConsumedPosition().value());
                cursor.add(context.getMatchedPosition().value());
                value.add("cursor", cursor);
                value.add("scope", javaScope(context, queries));
                rollback = value;
            }
        }
        return new JavaParse(prefix, scope, rollback);
    }

    private JsonArray prefix(Parsed parsed, ParseContext context) {
        var result = new JsonArray();
        result.add(parsed.isSucceeded());
        result.add(context.getConsumedPosition().value());
        result.add(context.getMatchedPosition().value());
        return result;
    }

    private JsonObject javaScope(ParseContext context, JsonArray queries) {
        var result = new JsonObject();
        result.addProperty("depth", ScopeStore.currentScopeDepth(context));
        var resolved = new JsonArray();
        for (JsonElement queryElement : queries) {
            String query = queryElement.getAsString();
            var item = new JsonObject();
            item.addProperty("query", query);
            item.add("symbol", ScopeStore.resolve(context, query)
                .<JsonElement>map(this::symbol).orElse(JsonNull.INSTANCE));
            resolved.add(item);
        }
        result.add("resolved", resolved);
        result.add("declarations", symbols(ScopeStore.getAllDeclarations(context)));
        result.add("references", references(ScopeStore.getAllReferences(context)));
        result.add("diagnostics", diagnostics(ScopeStore.getDiagnostics(context)));
        return result;
    }

    private JsonObject rollbackOracle(JsonArray queries) {
        var result = new JsonObject();
        var cursor = new JsonArray();
        cursor.add(0);
        cursor.add(0);
        result.add("cursor", cursor);
        var scope = new JsonObject();
        scope.addProperty("depth", 0);
        var resolved = new JsonArray();
        for (JsonElement queryElement : queries) {
            var item = new JsonObject();
            item.addProperty("query", queryElement.getAsString());
            item.add("symbol", JsonNull.INSTANCE);
            resolved.add(item);
        }
        scope.add("resolved", resolved);
        scope.add("declarations", new JsonArray());
        scope.add("references", new JsonArray());
        scope.add("diagnostics", new JsonArray());
        result.add("scope", scope);
        return result;
    }

    private JsonArray javaMetadata(Class<?> parsers, JsonArray expected) throws Exception {
        var result = new JsonArray();
        for (JsonElement expectedElement : expected) {
            String rule = expectedElement.getAsJsonObject().get("rule").getAsString();
            var item = new JsonObject();
            item.addProperty("rule", rule);
            Optional<?> scope = optionalMetadata(parsers, "getScopeTreeMode", rule);
            item.add("scope", scope.<JsonElement>map(value -> new JsonPrimitive(value.toString()))
                .orElse(JsonNull.INSTANCE));
            Optional<?> declaration = optionalMetadata(parsers, "getDeclaresSpec", rule);
            if (declaration.isPresent()) {
                Object value = declaration.orElseThrow();
                var declares = new JsonObject();
                declares.addProperty("symbol", value.getClass().getMethod("symbolCapture").invoke(value)
                    .toString());
                Optional<?> description = (Optional<?>) value.getClass().getMethod("description")
                    .invoke(value);
                declares.add("description", description
                    .<JsonElement>map(text -> new JsonPrimitive(text.toString()))
                    .orElse(JsonNull.INSTANCE));
                item.add("declares", declares);
            } else {
                item.add("declares", JsonNull.INSTANCE);
            }
            Optional<?> backref = optionalMetadata(parsers, "getBackrefName", rule);
            item.add("backref", backref.<JsonElement>map(value -> new JsonPrimitive(value.toString()))
                .orElse(JsonNull.INSTANCE));
            result.add(item);
        }
        return result;
    }

    private Optional<?> optionalMetadata(Class<?> parsers, String method, String rule)
            throws Exception {
        try {
            return (Optional<?>) parsers.getMethod(method, String.class).invoke(null, rule);
        } catch (NoSuchMethodException absentForGrammar) {
            return Optional.empty();
        }
    }

    private JsonArray symbols(List<SymbolInfo> values) {
        var result = new JsonArray();
        values.forEach(value -> result.add(symbol(value)));
        return result;
    }

    private JsonObject symbol(SymbolInfo value) {
        var result = new JsonObject();
        result.addProperty("name", value.name());
        result.addProperty("sourceOffset", value.sourceOffset());
        return result;
    }

    private JsonArray references(List<ReferenceInfo> values) {
        var result = new JsonArray();
        for (ReferenceInfo value : values) {
            var item = new JsonObject();
            item.addProperty("name", value.name());
            item.addProperty("offset", value.offset());
            item.addProperty("length", value.length());
            result.add(item);
        }
        return result;
    }

    private JsonArray diagnostics(List<SymbolDiagnostic> values) {
        var result = new JsonArray();
        for (SymbolDiagnostic value : values) {
            var item = new JsonObject();
            item.addProperty("message", value.message());
            item.addProperty("offset", value.offset());
            item.addProperty("length", value.length());
            item.addProperty("severity", value.severity().name());
            result.add(item);
        }
        return result;
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
        var compilerDiagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(compilerDiagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, compilerDiagnostics,
                List.of("--release", "17", "-classpath",
                    System.getProperty("java.class.path") + File.pathSeparator, "-d", output.toString()),
                null, units).call();
            assertTrue(compilerDiagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
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

    private String rustProbe(JsonObject fixture) {
        String queries = fixture.getAsJsonArray("queries").asList().stream()
            .map(value -> rustString(value.getAsString())).reduce((a, b) -> a + "," + b).orElse("");
        return """
            mod generated;
            use std::io::{self, BufRead};
            use unlaxer_runtime::{json_string, Expr, ScopeMode, ScopeStore, Severity};

            fn symbol(value: &unlaxer_runtime::SymbolInfo) -> String {
                format!(r#"{{\"name\":{},\"sourceOffset\":{}}}"#,
                    json_string(&value.name), value.source_offset)
            }
            fn scope_state(scope: &ScopeStore, queries: &[&str]) -> String {
                let resolved = queries.iter().map(|query| format!(
                    r#"{{\"query\":{},\"symbol\":{}}}"#, json_string(query),
                    scope.resolve(query).map(symbol).unwrap_or_else(|| "null".to_owned())))
                    .collect::<Vec<_>>().join(",");
                let declarations = scope.all_declarations().iter().map(symbol)
                    .collect::<Vec<_>>().join(",");
                let references = scope.all_references().iter().map(|value| format!(
                    r#"{{\"name\":{},\"offset\":{},\"length\":{}}}"#,
                    json_string(&value.name), value.offset, value.length))
                    .collect::<Vec<_>>().join(",");
                let diagnostics = scope.diagnostics().iter().map(|value| {
                    let severity = match value.severity { Severity::Error => "ERROR",
                        Severity::Warning => "WARNING", Severity::Info => "INFO",
                        Severity::Hint => "HINT" };
                    format!(r#"{{\"message\":{},\"offset\":{},\"length\":{},\"severity\":{}}}"#,
                        json_string(&value.message), value.offset, value.length, json_string(severity))
                }).collect::<Vec<_>>().join(",");
                format!(concat!(r#"{{\"depth\":{},\"resolved\":[{}],"#,
                    r#"\"declarations\":[{}],\"references\":[{}],\"diagnostics\":[{}]}}"#),
                    scope.current_scope_depth(), resolved, declarations, references, diagnostics)
            }
            fn metadata() -> String {
                let values = generated::parser::rules().into_iter().filter_map(|rule| {
                    let Expr::RuleEffects { effects, .. } = rule.expression else { return None; };
                    let scope = match effects.scope_mode { Some(ScopeMode::Lexical) => r#""lexical""#,
                        Some(ScopeMode::Dynamic) => r#""dynamic""#, None => "null" };
                    let declares = effects.declares.map(|value| format!(
                        r#"{{\"symbol\":{},\"description\":{}}}"#, json_string(value.symbol_capture),
                        value.description.map(json_string).unwrap_or_else(|| "null".to_owned())))
                        .unwrap_or_else(|| "null".to_owned());
                    let backref = effects.backref.map(json_string)
                        .unwrap_or_else(|| "null".to_owned());
                    Some(format!(concat!(r#"{{\"rule\":{},\"scope\":{},"#,
                        r#"\"declares\":{},\"backref\":{}}}"#),
                        json_string(rule.name), scope, declares, backref))
                }).collect::<Vec<_>>().join(",");
                format!("[{}]", values)
            }
            fn main() {
                let queries = [__QUERIES__];
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
                    let scope = scope_state(context.scopes(), &queries);
                    let (ast, tree_scope) = match generated::parser::parse_tree_detailed(&input) {
                        Ok(tree) => {
                            let tree_scope = scope_state(tree.scopes(), &queries);
                            let ast = generated::mapper::map(&tree).unwrap();
                            drop(tree);
                            (ast.canonical_json(), tree_scope)
                        }
                        Err(_) => ("null".to_owned(), "null".to_owned()),
                    };
                    let rollback = if rollback_requested == "true" {
                        let mut rollback_context = unlaxer_runtime::ParseContext::new(&input);
                        let _: Result<(), unlaxer_runtime::ParseError> = rollback_context.transaction(|ctx| {
                            generated::parser::parse_context(ctx)?;
                            Err(ctx.error("forced parent rollback"))
                        });
                        format!(r#"{{\"cursor\":[{},{}],\"scope\":{}}}"#,
                            rollback_context.position(), rollback_context.matched_position(),
                            scope_state(rollback_context.scopes(), &queries))
                    } else { "null".to_owned() };
                    println!(concat!(r#"{{\"prefix\":{},\"scope\":{},\"treeScope\":{},"#,
                        r#"\"metadata\":{},\"ast\":{},\"rollback\":{}}}"#),
                        prefix, scope, tree_scope, metadata(), ast, rollback);
                }
            }
            """.replace("__QUERIES__", queries);
    }

    private String rustString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private record JavaParse(JsonArray prefix, JsonObject scope, JsonElement rollback) {}
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
