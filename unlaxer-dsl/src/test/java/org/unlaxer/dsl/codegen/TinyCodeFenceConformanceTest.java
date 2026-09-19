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
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

/**
 * End-to-end contract for tinyexpression's real opening and closing code-fence parsers.
 *
 * <p>The pinned downstream classes are supplied separately instead of being a Maven test
 * dependency, so a same-named local class cannot silently replace the oracle. The corpus fixes
 * code-point cursors and AST values independently. In particular, generated mapper text is
 * stripped: line endings on headers and edge whitespace on bodies are intentionally absent from
 * the expected field values while node spans retain the complete consumed source.</p>
 */
public class TinyCodeFenceConformanceTest {
    private static final String START_PARSER =
        "org.unlaxer.tinyexpression.parser.javalang.CodeStartParser";
    private static final String END_PARSER =
        "org.unlaxer.tinyexpression.parser.javalang.CodeEndParser";
    private static final String PACKAGE = "org.example.tinyfence";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void realTinyParsersJavaRustAndNativeFrontendsShareOneCorpus() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc/cargo)",
            Boolean.getBoolean("rustConformance"));
        String configured = System.getProperty("tinyexpression.classes");
        assumeTrue("set -Dtinyexpression.classes=<pinned tinyexpression target/classes>",
            configured != null && !configured.isBlank());

        Path tinyClasses = Path.of(configured).toAbsolutePath().normalize();
        assertTrue("tinyexpression.classes must be a directory: " + tinyClasses,
            Files.isDirectory(tinyClasses));
        for (String parser : List.of(START_PARSER, END_PARSER)) {
            assertTrue("real tiny parser is missing from " + tinyClasses,
                Files.isRegularFile(tinyClasses.resolve(parser.replace('.', '/') + ".class")));
        }
        assertRealOracle(tinyClasses);

        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib",
            "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
            "-o", runtime.toString()), "", false));

        Path nativeTarget = temporary.getRoot().toPath().resolve("native-target");
        success(run(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--target-dir",
            nativeTarget.toString()), "", false));
        Path nativeGenerator = nativeTarget.resolve("debug/unlaxer");

        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/tiny-code-fence");
        JsonArray corpus = JsonParser.parseString(
            Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();
        var report = new ArrayList<>(List.of(
            "fixture\tcase\tinput_json\texpected_prefix\tjava_prefix\texpected_ast\tjava_ast\trust"));

        int fixtureIndex = 0;
        for (JsonElement fixtureElement : corpus) {
            JsonObject fixture = fixtureElement.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String source = grammar(fixture);
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            var javaRust = new RustBackend().generate(grammar);
            assertEquals(name + " Java Rust backend file count", 5, javaRust.size());

            Path fixtureDir = temporary.getRoot().toPath().resolve("fixture-" + fixtureIndex++);
            Files.createDirectories(fixtureDir);
            Path ubnf = fixtureDir.resolve("TinyFence.ubnf");
            Files.writeString(ubnf, source);
            Path nativeGenerated = fixtureDir.resolve("native-generated");
            ProcessResult nativeResult = run(List.of(nativeGenerator.toString(), "generate", "--grammar",
                ubnf.toString(), "--output", nativeGenerated.toString()), "", true);
            success(nativeResult);
            for (var file : javaRust) {
                assertArrayEquals(name + " native frontend byte parity: " + file.relativePath(),
                    file.content().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(nativeGenerated.resolve(file.relativePath())));
            }

            Path javaGenerated = Files.createDirectory(fixtureDir.resolve("generated"));
            for (var file : javaRust) {
                Files.writeString(javaGenerated.resolve(file.relativePath()), file.content());
            }
            Files.writeString(fixtureDir.resolve("main.rs"), rustProbe());
            success(run(List.of("rustc", "--edition=2021", "--extern",
                "unlaxer_runtime=" + runtime, fixtureDir.resolve("main.rs").toString(), "-o",
                fixtureDir.resolve("probe").toString()), "", false));

            JsonArray cases = fixture.getAsJsonArray("cases");
            String framed = String.join("\n", cases.asList().stream()
                .map(row -> HexFormat.of().formatHex(row.getAsJsonObject().get("input").getAsString()
                    .getBytes(StandardCharsets.UTF_8))).toList()) + "\n";
            ProcessResult rustResult = run(List.of(fixtureDir.resolve("probe").toString()), framed, false);
            success(rustResult);
            List<String> rustLines = rustResult.output().lines().toList();
            assertEquals(name + " Rust result count", cases.size(), rustLines.size());

            try (URLClassLoader loader = compileJava(grammar, tinyClasses)) {
                assertLoadedFromConfiguredOracle(loader, tinyClasses);
                Class<?> parsers = loader.loadClass(PACKAGE + ".TinyFenceParsers");
                Parser parser = (Parser) parsers.getMethod("getRootParser").invoke(null);
                Class<?> mapper = loader.loadClass(PACKAGE + ".TinyFenceMapper");

                for (int i = 0; i < cases.size(); i++) {
                    JsonObject row = cases.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String context = name + "/" + row.get("id").getAsString();
                    JsonObject rust = JsonParser.parseString(rustLines.get(i)).getAsJsonObject();

                    JsonArray javaPrefix = prefix(parser, input);
                    assertEquals(context + " independent prefix cursor oracle", row.get("prefix"), javaPrefix);
                    assertEquals(context + " Java/Rust prefix cursor parity", javaPrefix, rust.get("prefix"));

                    boolean accepted = row.get("accepted").getAsBoolean();
                    Optional<?> diagnostic =
                        (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    assertEquals(context + " Java full-input acceptance", accepted, diagnostic.isEmpty());
                    assertEquals(context + " Rust full-input acceptance", accepted,
                        !rust.get("ast").isJsonNull());

                    JsonElement expected = JsonNull.INSTANCE;
                    JsonElement javaAst = JsonNull.INSTANCE;
                    if (accepted) {
                        expected = expectedAst(row);
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                        javaAst = canonical(ast, mapped);
                        assertEquals(context + " Java AST all fields/node spans", expected, javaAst);
                        assertEquals(context + " Rust AST all fields/node spans", expected, rust.get("ast"));
                    }
                    report.add(name + "\t" + row.get("id").getAsString() + "\t" + row.get("input")
                        + "\t" + row.get("prefix") + "\t" + javaPrefix + "\t" + expected + "\t"
                        + javaAst + "\t" + rust);
                }
            }
        }

        Path target = Path.of("target");
        Files.createDirectories(target);
        Files.write(target.resolve("rust-tiny-code-fence.tsv"), report, StandardCharsets.UTF_8);
    }

    private String grammar(JsonObject fixture) {
        String params = String.join(", ", fixture.getAsJsonArray("params").asList().stream()
            .map(JsonElement::getAsString).toList());
        String mapping = params.isEmpty() ? "@mapping(Fence)" : "@mapping(Fence, params=[" + params + "])";
        return "grammar TinyFence { @package: " + PACKAGE + " "
            + (fixture.has("whitespace") ? "@whitespace: javaStyle " : "")
            + "token START = " + START_PARSER + "\n"
            + "token CLOSE = " + END_PARSER + "\n"
            + "token BODY = UNTIL('```')\n"
            + "token CHAR = ANY\n"
            + "token END = EOF\n"
            + (fixture.has("extra") ? fixture.get("extra").getAsString() : "") + "\n"
            + "@root " + mapping + " Root ::= "
            + fixture.get("body").getAsString() + "; }";
    }

    private void assertRealOracle(Path tinyClasses) throws Exception {
        try (var loader = new URLClassLoader(new URL[]{tinyClasses.toUri().toURL()},
                getClass().getClassLoader())) {
            for (String parserName : List.of(START_PARSER, END_PARSER)) {
                Class<?> parser = Class.forName(parserName, true, loader);
                assertEquals("the configured real class must win over any parent/test double",
                    tinyClasses.toRealPath(), codeSource(parser));
                assertEquals("org.unlaxer.parser.combinator.LazyChain", parser.getSuperclass().getName());
                assertTrue(parser.getConstructor().newInstance() instanceof Parser);
            }
        }
    }

    private void assertLoadedFromConfiguredOracle(ClassLoader loader, Path tinyClasses) throws Exception {
        for (String parserName : List.of(START_PARSER, END_PARSER)) {
            Class<?> parser = Class.forName(parserName, true, loader);
            assertEquals("generated Java parser must bind to configured tinyexpression target/classes",
                tinyClasses.toRealPath(), codeSource(parser));
        }
    }

    private Path codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
    }

    private URLClassLoader compileJava(org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl grammar,
        Path tinyClasses) throws Exception {
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            sources.add(generator.generate(grammar));
        }
        String parserSource = sources.get(0).source();
        for (String parser : List.of(START_PARSER, END_PARSER)) {
            assertTrue("generated parser must reference the exact downstream FQN " + parser + ":\n"
                + parserSource, parserSource.contains("extends " + parser + " "));
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
        String classpath = System.getProperty("java.class.path") + File.pathSeparator + tinyClasses;
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT,
                StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", classpath, "-d",
                    output.toString()), null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL(), tinyClasses.toUri().toURL()},
            getClass().getClassLoader());
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

    private JsonObject expectedAst(JsonObject row) {
        var expected = new JsonObject();
        expected.addProperty("type", "Fence");
        expected.add("span", row.get("span"));
        expected.add("fields", row.get("fields"));
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

    private String rustProbe() {
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
                    print!(r#"{{"prefix":[{},{},{}],"ast":"#,
                        prefix_ok, context.position(), context.matched_position());
                    match generated::parser::parse_tree_detailed(&input) {
                        Ok(tree) => {
                            let ast = generated::mapper::map(&tree).unwrap();
                            drop(tree);
                            print!("{}", ast.canonical_json());
                        }
                        Err(_) => print!("null"),
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
