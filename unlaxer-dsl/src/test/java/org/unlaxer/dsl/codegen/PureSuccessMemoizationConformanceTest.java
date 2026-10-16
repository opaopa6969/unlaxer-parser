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

/** Handwritten typed-AST/coordinate oracle and real pure-success hits through both generators. */
public class PureSuccessMemoizationConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    private Path fixture(String name) {
        return repo.resolve("conformance/pure-success-memo/" + name);
    }

    private JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(fixture("generated-cases.json")))
                .getAsJsonArray();
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
        Files.write(Path.of("target/pure-success-memo-java.tsv"), report);
    }

    @Test
    public void bothRustFrontendsCompileAndMatchIndependentOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println(
                    "[assumption] PureSuccessMemoizationConformanceTest requires"
                        + " -DrustConformance=true and rustc/cargo");
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
                                                                                            .get(
                                                                                                    "input")
                                                                                            .getAsString()
                                                                                            .getBytes(
                                                                                                    StandardCharsets
                                                                                                            .UTF_8))
                                                                    + "\t"
                                                                    + row.getAsJsonObject()
                                                                            .get("successHit")
                                                                            .getAsString())
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
        Files.write(Path.of("target/pure-success-memo-conformance.tsv"), report);
    }

    private void verifyJava(
            JsonObject entry, URLClassLoader loader, List<String> nativeLines, List<String> report)
            throws Exception {
        String name = entry.get("name").getAsString();
        Parser parser =
                (Parser)
                        loader.loadClass("memo.probe." + name + "Parsers")
                                .getMethod("getRootParser")
                                .invoke(null);
        Class<?> mapper = loader.loadClass("memo.probe." + name + "Mapper");
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
                        if (memo == Memoization.SAFE_FAILURES
                                && row.get("successHit").getAsBoolean()) {
                            assertTrue(
                                    name
                                            + "/"
                                            + row.get("id")
                                            + " requires a real Java success hit",
                                    context.getPackratMemoTable().successHits() > 0);
                        }
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
                            Object item = ast.getClass().getMethod("item").invoke(ast);
                            var itemSpan =
                                    (int[])
                                            ((Optional<?>)
                                                            mapped.getClass()
                                                                    .getMethod(
                                                                            "sourceSpanOf",
                                                                            Object.class)
                                                                    .invoke(mapped, item))
                                                    .orElseThrow();
                            var child = new JsonObject();
                            child.addProperty("type", "Item");
                            var childRange = new JsonArray();
                            childRange.add(itemSpan[0]);
                            childRange.add(itemSpan[1]);
                            child.add("span", childRange);
                            var childFields = new JsonObject();
                            if (row.get("hasText").getAsBoolean())
                                childFields.addProperty(
                                        "text",
                                        (String) item.getClass().getMethod("text").invoke(item));
                            child.add("fields", childFields);
                            var fields = new JsonObject();
                            fields.add("item", child);
                            canonical.add("fields", fields);
                            actual.add("ast", canonical);
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
        if (row.get("accepted").getAsBoolean()) {
            var ast = new JsonObject();
            ast.addProperty("type", "Value");
            var span = new JsonArray();
            span.add(0);
            span.add(end);
            ast.add("span", span);
            var item = new JsonObject();
            item.addProperty("type", "Item");
            var itemSpan = new JsonArray();
            itemSpan.add(0);
            itemSpan.add(row.get("itemEnd"));
            item.add("span", itemSpan);
            var itemFields = new JsonObject();
            if (row.get("hasText").getAsBoolean()) itemFields.add("text", row.get("item"));
            item.add("fields", itemFields);
            var fields = new JsonObject();
            fields.add("item", item);
            ast.add("fields", fields);
            result.add("ast", ast);
        }
        result.add("diagnostic", row.get("diagnostic"));
        return result;
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator :
                List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
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
        fn main() {
            for row in io::stdin().lock().lines() {
                let row = row.unwrap();
                let mut cells = row.split(char::from(9));
                let hex = cells.next().unwrap();
                let require_hit = cells.next().unwrap() == "true";
                let bytes = (0..hex.len()).step_by(2).map(|i| u8::from_str_radix(&hex[i..i+2],16).unwrap()).collect();
                let input = String::from_utf8(bytes).unwrap();
                for memo in [Memoization::Off, Memoization::SafeFailures] {
                    for diagnostics in [Diagnostics::Auto, Diagnostics::Detailed, Diagnostics::DetailedOnFailure] {
                        let options = ParseOptions::with_memoization(memo).with_diagnostics(diagnostics);
                        let mut context = ParseContext::with_options(&input, options);
                        let matched = generated::parser::parse_context(&mut context);
                        if memo == Memoization::SafeFailures && require_hit {
                            assert!(context.memoized_success_hits() > 0, "expected actual Rust success replay for {input:?}/{diagnostics:?}");
                        }
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
                            Ok(tree) => { let ast=generated::mapper::map(&tree).unwrap(); print!("{}",ast.canonical_json()); print!(r#", "diagnostic":null"#); }
                            Err(diagnostic) => { print!(r#"null,"diagnostic":{{"kind":"{}","offset":{}}}"#,diagnostic.kind,diagnostic.offset); }
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
            assertTrue("timeout: " + command, process.waitFor(60, TimeUnit.SECONDS));
            String result = Files.readString(log);
            if (success) assertEquals(result, 0, process.exitValue());
            else assertNotEquals(result, 0, process.exitValue());
            return result;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
