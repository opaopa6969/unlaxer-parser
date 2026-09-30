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
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.parser.Parser;

/** Independent AST, CST and parser-state oracles for the @skip projection boundary. */
public class SkipMappingConformanceTest {
    private static final String PACKAGE = "org.example.skipmapping";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void generatedJavaAndRustRespectSkipProjectionAndSyntax() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println("[assumption] SkipMappingConformanceTest requires -DrustConformance=true");
        }
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
        JsonArray corpus = JsonParser.parseString(Files.readString(
            repo.resolve("spec-corpus/skip-mapping/corpus.json"))).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of(
            "fixture\tcase\tinput_json\tprefix\tsyntax\tmapping\tast\trust"));
        int fixtureIndex = 0;
        for (JsonElement fixtureElement : corpus) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            System.out.println("[skip-mapping] " + name);
            String source = fixture.get("grammar").getAsString();
            GrammarDecl grammar = UBNFMapper.parse(source).grammars().get(0);
            GrammarValidator.validateOrThrow(grammar);
            List<RustBackend.GeneratedFile> javaFrontend = new RustBackend().generate(grammar);
            assertEquals(name + " emitted files", 5, javaFrontend.size());
            Path directory = temporary.getRoot().toPath().resolve("fixture-" + fixtureIndex++);
            Files.createDirectories(directory);
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
            String framed = String.join("\n", cases.asList().stream().map(item ->
                HexFormat.of().formatHex(item.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
            ProcessResult rustResult = run(List.of(directory.resolve("probe").toString()), framed, false);
            success(rustResult);
            List<String> rustLines = rustResult.output().lines().toList();
            assertEquals(name + " probe rows", cases.size(), rustLines.size());
            try (URLClassLoader loader = compileJava(grammar)) {
                System.out.println("[skip-mapping] compiled " + name);
                Parser parser = (Parser) loader.loadClass(PACKAGE + "." + name + "Parsers")
                    .getMethod("getRootParser").invoke(null);
                System.out.println("[skip-mapping] parser " + name);
                Class<?> mapper = loader.loadClass(PACKAGE + "." + name + "Mapper");
                for (int i = 0; i < cases.size(); i++) {
                    JsonObject row = cases.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String label = name + "/" + row.get("id").getAsString();
                    System.out.println("[skip-mapping] case " + label);
                    JsonObject rust = JsonParser.parseString(rustLines.get(i)).getAsJsonObject();
                    JsonArray javaPrefix = new JsonArray();
                    List<Token> javaTokens = new ArrayList<>();
                    int declarations;
                    int references;
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        var parsed = parser.parse(context);
                        javaPrefix.add(parsed.isSucceeded());
                        javaPrefix.add(context.getConsumedPosition().value());
                        javaPrefix.add(context.getMatchedPosition().value());
                        declarations = ScopeStore.getAllDeclarations(context).size();
                        references = ScopeStore.getAllReferences(context).size();
                        // Choice returns its selected child's Parsed; the committed CST retains
                        // the grammar rule boundary required by generated mappers.
                        if (parsed.isSucceeded()) {
                            for (Token token : context.getCurrent().getTokens()) {
                                javaTokens.addAll(token.flattenDepth(Token.ChildrenKind.original));
                            }
                        }
                    }
                    assertEquals(label + " Java prefix", row.get("prefix"), javaPrefix);
                    assertEquals(label + " Rust prefix", row.get("prefix"), rust.get("prefix"));
                    assertEquals(label + " Java declarations", row.get("declarations").getAsInt(), declarations);
                    assertEquals(label + " Java references", row.get("references").getAsInt(), references);
                    assertEquals(label + " Rust declarations", row.get("declarations"), rust.get("declarations"));
                    assertEquals(label + " Rust references", row.get("references"), rust.get("references"));
                    boolean syntax = row.get("syntax").getAsBoolean();
                    Optional<?> diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class)
                        .invoke(null, input);
                    assertEquals(label + " Java syntax", syntax, diagnostic.isEmpty());
                    assertEquals(label + " Rust syntax", syntax, rust.get("syntax").getAsBoolean());
                    if (syntax && row.has("cst")) {
                        JsonObject expectedCst = row.getAsJsonObject("cst");
                        JsonObject actualCst = rust.getAsJsonObject("cst");
                        for (var entry : expectedCst.entrySet()) {
                            String rule = entry.getKey();
                            long actualJava = javaTokens.stream().filter(token ->
                                token.parser.getClass().getSimpleName().equals(rule + "Parser")).count();
                            assertEquals(label + " Java CST retained " + rule,
                                entry.getValue().getAsLong(), actualJava);
                            assertEquals(label + " Rust CST retained " + rule, entry.getValue(),
                                actualCst.get(rule));
                        }
                    }
                    String outcome = row.get("mapping").getAsString();
                    assertEquals(label + " Rust mapping", outcome, rust.get("mapping").getAsString());
                    if (outcome.equals("ok")) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                        assertEquals(label + " Java AST and spans", row.get("ast"), canonical(ast, mapped));
                        assertEquals(label + " Rust AST and spans", row.get("ast"), rust.get("ast"));
                        if (row.has("captureSpans")) {
                            for (var entry : row.getAsJsonObject("captureSpans").entrySet()) {
                                Object captured = ast.getClass().getMethod(entry.getKey()).invoke(ast);
                                int[] span = (int[]) ((Optional<?>) mapped.getClass()
                                    .getMethod("sourceSpanOf", Object.class).invoke(mapped, captured))
                                    .orElseThrow();
                                JsonArray actual = new JsonArray();
                                actual.add(span[0]); actual.add(span[1]);
                                assertEquals(label + " Java capture span " + entry.getKey(),
                                    entry.getValue(), actual);
                                assertEquals(label + " Rust capture span " + entry.getKey(),
                                    entry.getValue(), rust.getAsJsonObject("captureSpans")
                                        .get(entry.getKey()));
                            }
                        }
                    } else if (outcome.equals("failure")) {
                        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                            () -> mapper.getMethod("parse", String.class).invoke(null, input));
                        assertTrue(label + " explicit Java mapping failure",
                            failure.getCause() instanceof IllegalArgumentException);
                        assertTrue(label + " absent mapped root",
                            failure.getCause().getMessage().contains("No mapped node"));
                        assertTrue(label + " no Rust AST", rust.get("ast").isJsonNull());
                        Token committedRoot = javaTokens.stream()
                            .filter(token -> token.parser == parser).findFirst().orElseThrow();
                        for (String method : List.of("mapParsedTree", "mapSubtreeTree")) {
                            InvocationTargetException retainedFailure = assertThrows(label + " " + method,
                                InvocationTargetException.class,
                                () -> mapper.getMethod(method, Token.class).invoke(null, committedRoot));
                            assertTrue(retainedFailure.getCause() instanceof IllegalArgumentException);
                            assertTrue(retainedFailure.getCause().getMessage().contains("No mapped node"));
                        }
                    } else {
                        assertTrue(label + " no AST", rust.get("ast").isJsonNull());
                    }
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input")
                        + "\t" + row.get("prefix") + "\t" + row.get("syntax") + "\t"
                        + row.get("mapping") + "\t" + row.get("ast") + "\t" + rust);
                }
            }
        }
        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve("rust-skip-mapping.tsv"), report, StandardCharsets.UTF_8);
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(),
                new MapperGenerator(), new EvaluatorGenerator(17))) sources.add(generator.generate(grammar));
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
            use unlaxer_runtime::json_string;
            fn main() {
                for line in io::stdin().lock().lines() {
                    let encoded = line.unwrap();
                    let bytes = (0..encoded.len()).step_by(2)
                        .map(|i| u8::from_str_radix(&encoded[i..i + 2], 16).unwrap()).collect();
                    let input = String::from_utf8(bytes).unwrap();
                    let mut context = unlaxer_runtime::ParseContext::new(&input);
                    let prefix_ok = generated::parser::parse_context(&mut context).is_ok();
                    let declarations = context.scopes().all_declarations().len();
                    let references = context.scopes().all_references().len();
                    print!(r#"{{"prefix":[{},{},{}],"declarations":{},"references":{},"#,
                        prefix_ok, context.position(), context.matched_position(), declarations, references);
                    match generated::parser::parse_tree_detailed(&input) {
                        Ok(tree) => {
                            let capture_spans = tree.nodes[tree.root].captures.iter().map(|capture|
                                format!("{}:[{},{}]", json_string(capture.name), capture.span.start,
                                    capture.span.end)).collect::<Vec<_>>().join(",");
                            print!(r#""syntax":true,"captureSpans":{{{}}},"cst":{{"#, capture_spans);
                            let rules = generated::parser::rules();
                            for (index, rule) in rules.iter().enumerate() {
                                if index != 0 { print!(","); }
                                let count = tree.nodes.iter().filter(|node| node.rule == index).count();
                                print!("{}:{}", json_string(rule.name), count);
                            }
                            print!(r#"}},"#);
                            match generated::mapper::map(&tree) {
                                Ok(ast) => print!(r#""mapping":"ok","ast":{}"#, ast.canonical_json()),
                                Err(_) => print!(r#""mapping":"failure","ast":null"#),
                            }
                        }
                        Err(_) => print!(r#""syntax":false,"captureSpans":null,"cst":null,"mapping":"none","ast":null"#),
                    }
                    println!("}}");
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
