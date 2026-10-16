package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;
import org.unlaxer.source.DocumentSnapshot;

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

/** REGEX: independent lexical/coordinate oracle through both real generators. */
public class RegexGeneratedConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    private Path fixture(String name) {
        return repo.resolve("spec-corpus/regex-integration/" + name);
    }

    private JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(fixture("generated-cases.json")))
                .getAsJsonArray();
    }

    @org.junit.Before
    public void java21Baseline() {
        assumeTrue(Runtime.version().feature() >= 21);
    }

    @Test
    public void generatedJavaMatchesIndependentOracle() throws Exception {
        var report = new ArrayList<>(List.of("grammar\tcase\tconfiguration\tresult"));
        for (var element : corpus()) {
            var entry = element.getAsJsonObject();
            var grammar = UBNFMapper.parse(entry.get("grammar").getAsString()).grammars().get(0);
            try (var loader = compileJava(grammar)) {
                verifyJava(entry, loader, null, report);
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/regex-generated-java.tsv"), report);
    }

    @Test
    public void bothRustFrontendsCompileAndMatchIndependentOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println(
                    "[assumption] RegexGeneratedConformanceTest requires -DrustConformance=true and"
                            + " rustc/cargo");
        }
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        "--crate-type=rlib",
                        "--crate-name=unlaxer_runtime",
                        repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(),
                        "-o",
                        runtime.toString()),
                "",
                false,
                true);
        Path nativeTarget = temporary.getRoot().toPath().resolve("native-target");
        run(
                List.of(
                        "cargo",
                        "build",
                        "--locked",
                        "--manifest-path",
                        repo.resolve("rust/Cargo.toml").toString(),
                        "-p",
                        "unlaxer-generator",
                        "--target-dir",
                        nativeTarget.toString()),
                "",
                false,
                true);
        Path generator = nativeTarget.resolve("debug/unlaxer").toAbsolutePath();
        var report = new ArrayList<>(List.of("grammar\tcase\tconfiguration\tresult"));
        for (var element : corpus()) {
            var entry = element.getAsJsonObject();
            GrammarDecl grammar =
                    UBNFMapper.parse(entry.get("grammar").getAsString()).grammars().get(0);
            Path directory = temporary.newFolder().toPath();
            Path source = directory.resolve("grammar.ubnf");
            Files.writeString(source, entry.get("grammar").getAsString());
            assertTrue(
                    org.unlaxer.dsl.PortabilityCheck.check(entry.get("grammar").getAsString())
                            .portable());
            var readiness =
                    JsonParser.parseString(
                                    run(
                                            List.of(
                                                    generator.toString(),
                                                    "check",
                                                    "--target",
                                                    "rust",
                                                    "--grammar",
                                                    source.toString(),
                                                    "--format",
                                                    "json"),
                                            "",
                                            true,
                                            true))
                            .getAsJsonObject();
            assertTrue(readiness.toString(), readiness.get("portable").getAsBoolean());
            assertTrue(readiness.getAsJsonArray("diagnostics").isEmpty());
            Path generated = directory.resolve("generated");
            run(
                    List.of(
                            generator.toString(),
                            "generate",
                            "--grammar",
                            source.toString(),
                            "--output",
                            generated.toString()),
                    "",
                    true,
                    true);
            var javaHost = new RustBackend().generate(grammar);
            assertEquals(5, javaHost.size());
            for (var file : javaHost) {
                assertArrayEquals(
                        entry.get("name") + ": " + file.relativePath(),
                        file.content().getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(generated.resolve(file.relativePath())));
            }
            Files.writeString(directory.resolve("main.rs"), rustProbe());
            Path binary = directory.resolve("probe");
            run(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            "--extern",
                            "unlaxer_runtime=" + runtime,
                            directory.resolve("main.rs").toString(),
                            "-o",
                            binary.toString()),
                    "",
                    false,
                    true);
            String input =
                    String.join(
                                    "\n",
                                    entry.getAsJsonArray("cases").asList().stream()
                                            .map(
                                                    row ->
                                                            HexFormat.of()
                                                                    .formatHex(
                                                                            row.getAsJsonObject()
                                                                                    .get("input")
                                                                                    .getAsString()
                                                                                    .getBytes(
                                                                                            StandardCharsets
                                                                                                    .UTF_8)))
                                            .toList())
                            + "\n";
            List<String> nativeLines =
                    run(List.of(binary.toString()), input, true, true).lines().toList();
            assertEquals(entry.getAsJsonArray("cases").size() * 6, nativeLines.size());
            try (var loader = compileJava(grammar)) {
                verifyJava(entry, loader, nativeLines, report);
            }
        }
        Files.createDirectories(Path.of("target"));
        verifySelfContainedPlayground(generator);
        Files.write(Path.of("target/regex-generated-conformance.tsv"), report);
    }

    private void verifySelfContainedPlayground(Path generator) throws Exception {
        var fixture = corpus().get(0).getAsJsonObject();
        Path work = temporary.newFolder().toPath(),
                source = work.resolve("grammar.ubnf"),
                project = work.resolve("project");
        Files.writeString(source, fixture.get("grammar").getAsString());
        run(
                List.of(
                        generator.toString(),
                        "playground",
                        "--grammar",
                        source.toString(),
                        "--output",
                        project.toString()),
                "",
                true,
                true);
        var javaHost = org.unlaxer.dsl.codegen.rust.PlaygroundGenerator.generate(source);
        for (var file : javaHost.entrySet())
            assertArrayEquals(
                    file.getKey(),
                    file.getValue().getBytes(StandardCharsets.UTF_8),
                    Files.readAllBytes(project.resolve(file.getKey())));
        try (var files = Files.walk(project)) {
            assertEquals(javaHost.size(), files.filter(Files::isRegularFile).count());
        }
        Path library = work.resolve("libunlaxer_runtime.rlib");
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        "--crate-type=rlib",
                        "--crate-name=unlaxer_runtime",
                        project.resolve("runtime/src/lib.rs").toString(),
                        "-o",
                        library.toString()),
                "",
                false,
                true);
        Path probe = project.resolve("src/main.rs"), binary = work.resolve("probe");
        Files.writeString(probe, rustProbe());
        run(
                List.of(
                        "rustc",
                        "--edition=2021",
                        probe.toString(),
                        "--extern",
                        "unlaxer_runtime=" + library,
                        "-o",
                        binary.toString()),
                "",
                false,
                true);
        var actual = run(List.of(binary.toString()), "616263\n", true, true).lines().toList();
        assertEquals(6, actual.size());
        var expected = expected(fixture.getAsJsonArray("cases").get(0).getAsJsonObject());
        for (String observation : actual)
            assertEquals(expected, JsonParser.parseString(observation));
    }

    private void verifyJava(
            JsonObject entry, URLClassLoader loader, List<String> nativeLines, List<String> report)
            throws Exception {
        String name = entry.get("name").getAsString();
        Parser parser =
                (Parser)
                        loader.loadClass("regex.probe." + name + "Parsers")
                                .getMethod("getRootParser")
                                .invoke(null);
        Class<?> mapper = loader.loadClass("regex.probe." + name + "Mapper");
        int nativeIndex = 0;
        for (var element : entry.getAsJsonArray("cases")) {
            var row = element.getAsJsonObject();
            String input = row.get("input").getAsString();
            var expected = expected(row);
            for (Memoization memo : Memoization.values()) {
                for (ParseOptions.Diagnostics diagnostics : ParseOptions.Diagnostics.values()) {
                    var options = ParseOptions.withMemoization(memo).withDiagnostics(diagnostics);
                    var actual = new JsonObject();
                    try (var context =
                            ParseContext.withOptions(
                                    StringSource.createRootSource(input), options)) {
                        var parsed = parser.parse(context);
                        var prefix = new JsonArray();
                        prefix.add(parsed.isSucceeded());
                        prefix.add(context.getConsumedPosition().value());
                        prefix.add(context.getMatchedPosition().value());
                        actual.add("prefix", prefix);
                        assertEquals(
                                name + "/" + row.get("id") + " prefix", row.get("prefix"), prefix);
                        int consumed = context.getConsumedPosition().value();
                        var snapshot = new DocumentSnapshot("fixture", 1, input);
                        var coordinates = new JsonArray();
                        coordinates.add(consumed);
                        coordinates.add(snapshot.utf16(consumed));
                        coordinates.add(snapshot.utf8(consumed));
                        actual.add("coordinates", coordinates);
                        actual.add(
                                "text",
                                parsed.isSucceeded()
                                        ? new JsonPrimitive(
                                                parsed.getRootToken(false).source.sourceAsString())
                                        : JsonNull.INSTANCE);
                        actual.add("ast", JsonNull.INSTANCE);
                        actual.add("evaluation", JsonNull.INSTANCE);
                        if (row.get("accepted").getAsBoolean()) {
                            var rootToken =
                                    context.getCurrent().getTokens().stream()
                                            .filter(token -> token.parser == parser)
                                            .findFirst()
                                            .orElseThrow(
                                                    () ->
                                                            new AssertionError(
                                                                    "missing committed generated"
                                                                            + " root"));
                            var mapped =
                                    mapper.getMethod(
                                                    "mapParsedTokenWithSourceMap",
                                                    org.unlaxer.Token.class)
                                            .invoke(null, rootToken);
                            var ast = mapped.getClass().getMethod("ast").invoke(mapped);
                            var span =
                                    (int[])
                                            ((Optional<?>)
                                                            mapped.getClass()
                                                                    .getMethod(
                                                                            "sourceSpanOf",
                                                                            Object.class)
                                                                    .invoke(mapped, ast))
                                                    .orElseThrow();
                            var canonical = new JsonObject();
                            canonical.addProperty("type", "Value");
                            var positions = new JsonArray();
                            positions.add(span[0]);
                            positions.add(span[1]);
                            canonical.add("span", positions);
                            var fields = new JsonObject();
                            fields.addProperty(
                                    "text", (String) ast.getClass().getMethod("text").invoke(ast));
                            canonical.add("fields", fields);
                            actual.add("ast", canonical);
                            Object evaluated =
                                    loader.loadClass("regex.probe." + name + "Evaluation")
                                            .getMethod(
                                                    "evaluate",
                                                    loader.loadClass("regex.probe." + name + "AST"))
                                            .invoke(null, ast);
                            actual.addProperty("evaluation", (String) evaluated);
                        }
                        if (!parsed.isSucceeded()) {
                            assertTrue(context.getCurrent().getTokens().isEmpty());
                            assertFalse(parser.parse(context).isSucceeded());
                            assertEquals(0, context.getConsumedPosition().value());
                            assertEquals(0, context.getMatchedPosition().value());
                            assertTrue(context.getCurrent().getTokens().isEmpty());
                        }
                    }
                    var diagnostic =
                            (Optional<?>)
                                    mapper.getMethod("diagnose", String.class, ParseOptions.class)
                                            .invoke(null, input, options);
                    if (diagnostic.isPresent()) {
                        Object value = diagnostic.orElseThrow();
                        var detail = new JsonObject();
                        detail.addProperty(
                                "kind", (String) value.getClass().getMethod("kind").invoke(value));
                        detail.addProperty(
                                "offset",
                                (Integer) value.getClass().getMethod("offset").invoke(value));
                        actual.add("diagnostic", detail);
                    } else actual.add("diagnostic", JsonNull.INSTANCE);
                    String label =
                            name
                                    + "/"
                                    + row.get("id").getAsString()
                                    + "/"
                                    + memo
                                    + "/"
                                    + diagnostics;
                    assertEquals(label + " independent Java oracle", expected, actual);
                    if (nativeLines != null) {
                        assertEquals(
                                label + " independent Rust oracle",
                                expected,
                                JsonParser.parseString(nativeLines.get(nativeIndex++)));
                    }
                    report.add(
                            name
                                    + "\t"
                                    + row.get("id").getAsString()
                                    + "\t"
                                    + memo
                                    + "/"
                                    + diagnostics
                                    + "\t"
                                    + actual);
                }
            }
        }
    }

    private JsonObject expected(JsonObject row) {
        var result = new JsonObject();
        result.add("prefix", row.get("prefix"));
        result.add("coordinates", row.get("coordinates"));
        int end = row.getAsJsonArray("prefix").get(1).getAsInt();
        String input = row.get("input").getAsString();
        result.add(
                "text",
                row.getAsJsonArray("prefix").get(0).getAsBoolean()
                        ? new JsonPrimitive(input.substring(0, input.offsetByCodePoints(0, end)))
                        : JsonNull.INSTANCE);
        result.add("ast", JsonNull.INSTANCE);
        result.add("evaluation", JsonNull.INSTANCE);
        if (row.get("accepted").getAsBoolean()) {
            var ast = new JsonObject();
            ast.addProperty("type", "Value");
            var span = new JsonArray();
            span.add(0);
            span.add(end);
            ast.add("span", span);
            var fields = new JsonObject();
            fields.add("text", row.get("value"));
            ast.add("fields", fields);
            result.add("ast", ast);
            result.add("evaluation", row.get("value"));
        }
        result.add("diagnostic", row.get("diagnostic"));
        return result;
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator :
                List.of(
                        new ParserGenerator(),
                        new ASTGenerator(),
                        new MapperGenerator(),
                        new EvaluatorGenerator())) {
            var source = generator.generate(grammar);
            units.add(
                    new SimpleJavaFileObject(
                            URI.create(
                                    "string:///"
                                            + source.packageName().replace('.', '/')
                                            + "/"
                                            + source.className()
                                            + ".java"),
                            JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                            return source.source();
                        }
                    });
        }
        String evaluator =
                "package regex.probe; public final class "
                        + grammar.name()
                        + "Evaluation extends "
                        + grammar.name()
                        + "Evaluator<String> { protected String evalValue("
                        + grammar.name()
                        + "AST.Value node) { return node.text(); } public static String evaluate("
                        + grammar.name()
                        + "AST node) { return new "
                        + grammar.name()
                        + "Evaluation().eval(node); } }";
        units.add(
                new SimpleJavaFileObject(
                        URI.create("string:///regex/probe/" + grammar.name() + "Evaluation.java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignored) {
                        return evaluator;
                    }
                });
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled =
                    compiler.getTask(
                                    null,
                                    manager,
                                    diagnostics,
                                    List.of(
                                            "--release",
                                            "21",
                                            "-classpath",
                                            System.getProperty("java.class.path"),
                                            "-d",
                                            output.toString()),
                                    null,
                                    units)
                            .call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }

    private String rustProbe() {
        return """
        mod generated;
        use std::io::{self, BufRead};
        use unlaxer_runtime::{Diagnostics, Memoization, ParseContext, ParseOptions};
        fn quoted(text: &str) -> String {
            let mut out = String::new(); out.push(char::from(34));
            for ch in text.chars() {
                if ch == char::from(34) || ch == char::from(92) {
                    out.push(char::from(92)); out.push(ch);
                } else if ch <= char::from(31) {
                    out.push(char::from(92)); out.push('u');
                    out.push_str(&format!("{:04x}",ch as u32));
                } else { out.push(ch); }
            }
            out.push(char::from(34)); out
        }
        struct Values;
        impl generated::evaluator::Semantics for Values {
            type Output=String;
            fn eval_value(&mut self, text:&str, _:unlaxer_runtime::Span)->String { text.into() }
        }
        fn main() {
            for row in io::stdin().lock().lines() {
                let hex = row.unwrap();
                let bytes = (0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i+2],16).unwrap()).collect();
                let input = String::from_utf8(bytes).unwrap();
                for memo in [Memoization::Off, Memoization::SafeFailures] {
                    for diagnostics in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
                        let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                        let mut context = ParseContext::with_options(&input, options);
                        let matched = generated::parser::parse_context(&mut context);
                        let ok = matched.is_ok();
                        let point = context.position();
                        let prefix: String = input.chars().take(point).collect();
                        let snapshot = unlaxer_runtime::source::Snapshot::new("fixture",1,&input).unwrap();
                        let text = match matched {
                            Ok(value) => { assert_eq!(context.text(value.span),Some(prefix.as_str())); quoted(&prefix) }
                            Err(_) => {
                                assert!(context.node(0).is_none());
                                assert!(generated::parser::parse_context(&mut context).is_err());
                                assert_eq!((context.position(),context.matched_position()),(0,0));
                                assert!(context.node(0).is_none());
                                "null".into()
                            }
                        };
                        print!(r#"{{"prefix":[{},{},{}],"coordinates":[{},{},{}],"text":{},"ast":"#,
                            ok,point,context.matched_position(),point,snapshot.utf16(point).unwrap(),snapshot.utf8(point).unwrap(),text);
                        match generated::parser::parse_tree_detailed_with_options(&input,options) {
                            Ok(tree) => { let ast=generated::mapper::map(&tree).unwrap(); print!("{}",ast.canonical_json()); print!(r#", "evaluation":{}, "diagnostic":null"#, quoted(&generated::evaluator::evaluate(&ast,&mut Values))); }
                            Err(diagnostic) => { print!(r#"null,"evaluation":null,"diagnostic":{{"kind":"{}","offset":{}}}"#,diagnostic.kind,diagnostic.offset); }
                        }
                        println!("}}");
                    }
                }
            }
        }
        """;
    }

    private String run(List<String> command, String input, boolean withoutJava, boolean success)
            throws Exception {
        Path log = temporary.newFile().toPath();
        var builder =
                new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (withoutJava) {
            builder.environment().put("PATH", "");
            builder.environment().put("JAVA_HOME", "/missing-java");
        }
        Process process = builder.start();
        try {
            try (var stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
            }
            assertTrue("timeout: " + command, process.waitFor(180, TimeUnit.SECONDS));
            String result = Files.readString(log);
            if (success) assertEquals(result, 0, process.exitValue());
            else assertNotEquals(result, 0, process.exitValue());
            return result;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
