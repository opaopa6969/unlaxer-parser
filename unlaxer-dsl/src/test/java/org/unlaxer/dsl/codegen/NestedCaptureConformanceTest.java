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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.dsl.runtime.ScopeStore.SymbolInfo;
import org.unlaxer.parser.Parser;

/** Java/Rust contract for nested capture completion, cardinality, boundaries, and spans. */
public class NestedCaptureConformanceTest {
    private static final String PACKAGE = "org.example.nestedcaptures";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void nestedCapturesKeepCompletionOrderAndSemanticCardinality() throws Exception {
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
            "unlaxer-dsl/src/test/resources/nested-captures/corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of(
            "fixture\tcase\tinput_json\texpected_ast\tjava_ast\texpected_captures\trust_captures\texpected_declarations\tjava_declarations\trust\tjava_field_types\trust_field_type_constraints\texpected_value_spans\tjava_value_spans\tjava_retained_value_spans"));
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
            for (var file : javaFrontend) Files.writeString(
                generated.resolve(file.relativePath()), file.content());
            Files.writeString(fixtureDir.resolve("main.rs"), rustProbe(fixture));
            success(run(List.of("rustc", "--edition=2021", "--extern",
                "unlaxer_runtime=" + runtime, fixtureDir.resolve("main.rs").toString(), "-o",
                fixtureDir.resolve("probe").toString()), "", false));

            JsonArray cases = fixture.getAsJsonArray("cases");
            String framed = String.join("\n", cases.asList().stream().map(element -> HexFormat.of()
                .formatHex(element.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
            ProcessResult rustResult = run(List.of(fixtureDir.resolve("probe").toString()), framed, false);
            success(rustResult);
            List<String> rustLines = rustResult.output().lines().toList();
            assertEquals(name + " Rust result count", cases.size(), rustLines.size());

            try (URLClassLoader loader = compileJava(grammar)) {
                JsonObject javaFieldTypes = assertJavaFieldTypes(name, fixture.getAsJsonObject("javaFieldTypes"), grammar, loader);
                Class<?> parsers = loader.loadClass(PACKAGE + "." + grammar.name() + "Parsers");
                Parser parser = (Parser) parsers.getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + grammar.name() + "Mapper");
                for (int i = 0; i < cases.size(); i++) {
                    JsonObject row = cases.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String context = name + "/" + row.get("id").getAsString();
                    JsonObject rust = JsonParser.parseString(rustLines.get(i)).getAsJsonObject();

                    Optional<?> diagnostic =
                        (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    assertTrue(context + " Java full-input acceptance", diagnostic.isEmpty());
                    assertEquals(context + " Rust AST contract", row.get("ast"), rust.get("ast"));
                    assertEquals(context + " Rust capture text/code-point spans", row.get("captures"),
                        rust.get("captures"));
                    JsonElement expectedValueSpans = row.has("valueSpans")
                        ? row.get("valueSpans") : new JsonArray();
                    assertEquals(context + " Rust owned mixed-value spans after Tree drop",
                        expectedValueSpans, rust.get("valueSpans"));
                    Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                    Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                    JsonObject javaAst = canonical(ast, mapped);
                    assertEquals(context + " Java AST contract", row.get("ast"), javaAst);
                    JsonArray javaValueSpans = javaValueSpans(fixture, ast, mapped);
                    assertEquals(context + " Java mixed-value source-map spans", expectedValueSpans, javaValueSpans);
                    if (fixture.has("valueSpanField")) {
                        // A later parse clears the mapper's live identity map, not this owned snapshot.
                        String nextInput = cases.get((i + 1) % cases.size()).getAsJsonObject()
                            .get("input").getAsString();
                        mapper.getMethod("parse", String.class).invoke(null, nextInput);
                    }
                    JsonArray retainedValueSpans = javaValueSpans(fixture, ast, mapped);
                    assertEquals(context + " Java retained mixed-value spans after another parse",
                        expectedValueSpans, retainedValueSpans);

                    JsonArray expectedDeclarations = row.has("declarations")
                        ? row.getAsJsonArray("declarations") : new JsonArray();
                    JsonArray javaDeclarations = declarations(parser, input);
                    assertEquals(context + " Java scope events", expectedDeclarations, javaDeclarations);
                    assertEquals(context + " Rust Tree-owned scope events", expectedDeclarations,
                        rust.get("declarations"));
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input")
                        + "\t" + row.get("ast") + "\t" + javaAst + "\t" + row.get("captures")
                        + "\t" + rust.get("captures") + "\t" + expectedDeclarations + "\t"
                        + javaDeclarations + "\t" + rust + "\t" + javaFieldTypes + "\t"
                        + fixture.get("rustFieldTypes") + "\t" + expectedValueSpans + "\t"
                        + javaValueSpans + "\t" + retainedValueSpans);
                }
            }
        }
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve("rust-nested-captures.tsv"), report, StandardCharsets.UTF_8);
    }

    private JsonArray declarations(Parser parser, String input) throws Exception {
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            assertTrue(parser.parse(context).isSucceeded());
            int end = input.codePointCount(0, input.length());
            assertEquals(end, context.getConsumedPosition().value());
            assertEquals(end, context.getMatchedPosition().value());
            var result = new JsonArray();
            for (SymbolInfo value : ScopeStore.getAllDeclarations(context)) {
                var item = new JsonObject();
                item.addProperty("name", value.name());
                item.addProperty("sourceOffset", value.sourceOffset());
                result.add(item);
            }
            return result;
        }
    }

    private JsonObject assertJavaFieldTypes(String context, JsonObject expected, GrammarDecl grammar,
            URLClassLoader loader) throws Exception {
        var types = new JsonObject();
        for (var record : expected.entrySet()) {
            Class<?> type = loader.loadClass(PACKAGE + "." + grammar.name() + "AST$" + record.getKey());
            var actual = new JsonObject();
            for (var component : type.getRecordComponents()) {
                actual.addProperty(component.getName(), javaTypeName(component.getGenericType()));
            }
            assertEquals(context + " Java declared field types: " + record.getKey(), record.getValue(), actual);
            types.add(record.getKey(), actual);
        }
        return types;
    }

    private String javaTypeName(Type type) {
        if (type instanceof Class<?> concrete) return concrete.getSimpleName();
        if (type instanceof ParameterizedType parameterized) {
            return javaTypeName(parameterized.getRawType()) + "<"
                + java.util.Arrays.stream(parameterized.getActualTypeArguments()).map(this::javaTypeName)
                    .collect(java.util.stream.Collectors.joining(",")) + ">";
        }
        throw new AssertionError("Unexpected generated field type: " + type);
    }

    private JsonArray javaValueSpans(JsonObject fixture, Object ast, Object mapped) throws Exception {
        var result = new JsonArray();
        if (!fixture.has("valueSpanField")) return result;
        var values = new ArrayList<>();
        values.add(ast.getClass().getMethod(fixture.get("valueSpanField").getAsString()).invoke(ast));
        values.addAll((List<?>) ast.getClass().getMethod(fixture.get("valueSpanListField").getAsString()).invoke(ast));
        for (Object value : values) {
            int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class)
                .invoke(mapped, value)).orElseThrow(() -> new AssertionError(
                    fixture.get("name").getAsString() + " missing Java source span for mixed value: " + value));
            var position = new JsonArray();
            position.add(span[0]);
            position.add(span[1]);
            result.add(position);
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
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath",
                    System.getProperty("java.class.path") + File.pathSeparator, "-d", output.toString()),
                null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
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
        String names = fixture.getAsJsonArray("captureNames").asList().stream()
            .map(value -> rustString(value.getAsString())).reduce((a, b) -> a + "," + b).orElse("");
        String valueSpans = fixture.has("valueSpanField") ? """
            fn value_spans(ast: &generated::ast::Ast) -> String {
                match ast {
                    generated::ast::Ast::r#Root { r#__FIELD__, r#__LIST__, .. } => {
                        let mut spans = vec![r#__FIELD__.span()];
                        spans.extend(r#__LIST__.iter().map(|value| value.span()));
                        format!("[{}]", spans.iter().map(|span| format!("[{},{}]", span.start, span.end))
                            .collect::<Vec<_>>().join(","))
                    }
                    _ => "[]".to_owned(),
                }
            }
            """.replace("__FIELD__", fixture.get("valueSpanField").getAsString())
                .replace("__LIST__", fixture.get("valueSpanListField").getAsString()) : """
            fn value_spans(_: &generated::ast::Ast) -> String { "[]".to_owned() }
            """;
        var fieldTypes = new StringBuilder("fn assert_field_types(ast: &generated::ast::Ast) {\n"
            + "use generated::ast::*;\nmatch ast {\n");
        for (var variant : fixture.getAsJsonObject("rustFieldTypes").entrySet()) {
            JsonObject fields = variant.getValue().getAsJsonObject();
            String bindings = fields.keySet().stream().map(name -> "r#" + name)
                .collect(java.util.stream.Collectors.joining(", "));
            fieldTypes.append("Ast::r#").append(variant.getKey()).append(" { ").append(bindings)
                .append(", .. } => {\n");
            for (var field : fields.entrySet()) {
                fieldTypes.append("let _: &").append(field.getValue().getAsString()).append(" = r#")
                    .append(field.getKey()).append(";\n");
            }
            fieldTypes.append("}\n");
        }
        fieldTypes.append("}\n}\n");
        return """
            mod generated;
            use std::io::{self, BufRead};
            use unlaxer_runtime::{json_string, Tree};
            fn captures(tree: &Tree, names: &[&str]) -> String {
                let values = tree.nodes.iter().flat_map(|node| node.captures.iter())
                    .filter(|capture| names.contains(&capture.name)).map(|capture| format!(
                        r#"{{\"name\":{},\"span\":[{},{}],\"text\":{}}}"#,
                        json_string(capture.name), capture.span.start, capture.span.end,
                        json_string(tree.text(capture.span))))
                    .collect::<Vec<_>>().join(",");
                format!("[{}]", values)
            }
            fn declarations(tree: &Tree) -> String {
                let values = tree.scopes().all_declarations().iter().map(|value| format!(
                    r#"{{\"name\":{},\"sourceOffset\":{}}}"#,
                    json_string(&value.name), value.source_offset)).collect::<Vec<_>>().join(",");
                format!("[{}]", values)
            }
            __VALUE_SPANS__
            __FIELD_TYPES__
            fn main() {
                let names = [__NAMES__];
                for line in io::stdin().lock().lines() {
                    let encoded = line.unwrap();
                    let bytes = (0..encoded.len()).step_by(2)
                        .map(|i| u8::from_str_radix(&encoded[i..i + 2], 16).unwrap()).collect();
                    let input = String::from_utf8(bytes).unwrap();
                    let mut context = unlaxer_runtime::ParseContext::new(&input);
                    generated::parser::parse_context(&mut context).unwrap();
                    assert_eq!(context.position(), input.chars().count());
                    assert_eq!(context.matched_position(), input.chars().count());
                    let tree = generated::parser::parse_tree_detailed(&input).unwrap();
                    let capture_json = captures(&tree, &names);
                    let declaration_json = declarations(&tree);
                    let ast = generated::mapper::map(&tree).unwrap();
                    drop(tree);
                    assert_field_types(&ast);
                    println!(concat!(r#"{{\"ast\":{},\"captures\":{},"#,
                        r#"\"valueSpans\":{},\"declarations\":{}}}"#),
                        ast.canonical_json(), capture_json, value_spans(&ast), declaration_json);
                }
            }
            """.replace("__NAMES__", names).replace("__VALUE_SPANS__", valueSpans)
                .replace("__FIELD_TYPES__", fieldTypes);
    }

    private String rustString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
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
