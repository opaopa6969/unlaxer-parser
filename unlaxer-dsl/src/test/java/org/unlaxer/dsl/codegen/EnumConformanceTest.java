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

/**
 * Typed enum values: independent generated parser/mapper/evaluator oracle through both real
 * generators.
 */
public class EnumConformanceTest {
    @org.junit.Before
    public void requiresJava21() {
        assumeTrue(
                "Enum generation/evaluator contract requires JDK21",
                Runtime.version().feature() >= 21);
    }

    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    private Path fixture(String name) {
        return repo.resolve("spec-corpus/enums/" + name);
    }

    private JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(fixture("generated-cases.json")))
                .getAsJsonArray();
    }

    private List<String> invalid() throws Exception {
        return JsonParser.parseString(Files.readString(fixture("invalid-cases.json")))
                .getAsJsonArray()
                .asList()
                .stream()
                .map(e -> e.getAsJsonObject().get("grammar").getAsString())
                .toList();
    }

    @Test
    public void generatedJavaMatchesIndependentOracle() throws Exception {
        var report = new ArrayList<>(List.of("grammar\tcase\tconfiguration\tresult"));
        for (var element : corpus()) {
            var entry = element.getAsJsonObject();
            var grammar = UBNFMapper.parse(entry.get("grammar").getAsString()).grammars().get(0);
            assertTrue(
                    entry.get("name").getAsString(),
                    GrammarValidator.validateWithoutClassLoading(grammar).stream()
                            .noneMatch(i -> i.severity().equals("ERROR")));
            try (var loader = compileJava(grammar, entry)) {
                verifyJava(entry, loader, null, report);
            }
        }
        javaEnumEvolution();
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/enum-java.tsv"), report);
    }

    @Test
    public void invalidEnumsAreRejectedBeforeEmission() throws Exception {
        for (String source : invalid()) {
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            assertTrue(
                    source,
                    GrammarValidator.validateWithoutClassLoading(grammar).stream()
                            .anyMatch(issue -> issue.code().startsWith("E-ENUM")));
            for (CodeGenerator generator :
                    List.of(
                            new ASTGenerator(),
                            new MapperGenerator(),
                            new EvaluatorGenerator(21))) {
                var error =
                        assertThrows(
                                IllegalArgumentException.class, () -> generator.generate(grammar));
                assertTrue(error.getMessage(), error.getMessage().contains("E-ENUM"));
            }
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> new RustBackend().generate(grammar));
            assertTrue(error.getMessage(), error.getMessage().contains("E-ENUM"));
            var report = org.unlaxer.dsl.PortabilityCheck.check(source);
            assertFalse(source, report.portable());
            assertTrue(source, report.diagnostics().stream().allMatch(d -> d.span() != null));
        }
    }

    @Test
    public void bothRustFrontendsCompileAndMatchIndependentOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) {
            System.out.println(
                    "[assumption] EnumConformanceTest requires -DrustConformance=true and"
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
            comparePortability(generator, source, true);
            var javaHost = new RustBackend().generate(grammar);
            assertEquals(5, javaHost.size());
            for (var file : javaHost) {
                assertArrayEquals(
                        entry.get("name") + ": " + file.relativePath(),
                        file.content().getBytes(StandardCharsets.UTF_8),
                        Files.readAllBytes(generated.resolve(file.relativePath())));
            }
            var impact =
                    org.unlaxer.dsl.impact.ApiImpact.compare(
                            "rust",
                            entry.get("grammar").getAsString(),
                            entry.get("grammar").getAsString());
            assertTrue(impact.toJson(), impact.ok());
            String nativeImpact =
                    run(
                            List.of(
                                    generator.toString(),
                                    "impact",
                                    "--target",
                                    "rust",
                                    "--before",
                                    source.toString(),
                                    "--after",
                                    source.toString()),
                            "",
                            true,
                            true);
            assertEquals(
                    entry.get("name").getAsString() + " API schema",
                    JsonParser.parseString(impact.toJson()),
                    JsonParser.parseString(nativeImpact));
            Files.writeString(directory.resolve("main.rs"), rustProbe(entry));
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
            try (var loader = compileJava(grammar, entry)) {
                verifyJava(entry, loader, nativeLines, report);
            }
        }
        rustEnumEvolution(generator, runtime);
        int index = 0;
        for (String invalid : invalid()) {
            Path source = temporary.getRoot().toPath().resolve("invalid-" + index++ + ".ubnf");
            Files.writeString(source, invalid);
            String error =
                    run(
                            List.of(
                                    generator.toString(),
                                    "generate",
                                    "--grammar",
                                    source.toString(),
                                    "--output",
                                    temporary
                                            .getRoot()
                                            .toPath()
                                            .resolve("invalid-output")
                                            .toString()),
                            "",
                            true,
                            false);
            assertTrue(error, error.contains("E-ENUM"));
            comparePortability(generator, source, false);
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/enum-conformance.tsv"), report);
    }

    private void verifyJava(
            JsonObject entry, URLClassLoader loader, List<String> nativeLines, List<String> report)
            throws Exception {
        String name = entry.get("name").getAsString();
        Parser parser =
                (Parser)
                        loader.loadClass("enumprobe." + name + "Parsers")
                                .getMethod("getRootParser")
                                .invoke(null);
        Class<?> mapper = loader.loadClass("enumprobe." + name + "Mapper");
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
                        actual.add("eval", JsonNull.INSTANCE);
                        actual.add("enums", new JsonArray());
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
                            actual.add("ast", canonical(ast, mapped));
                            mapper.getMethod("parse", String.class).invoke(null, input);
                            assertEquals(
                                    "retained enum wrapper/source map",
                                    actual.get("ast"),
                                    canonical(ast, mapped));
                            actual.addProperty(
                                    "eval",
                                    (Integer)
                                            loader.loadClass("enumprobe.Semantics")
                                                    .getMethod(
                                                            "run",
                                                            loader.loadClass(
                                                                    "enumprobe." + name + "AST"))
                                                    .invoke(null, ast));
                            var enums = new JsonArray();
                            enumTokens(rootToken, entry, enums, input);
                            actual.add("enums", enums);
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
        result.add("ast", row.get("ast"));
        result.add("eval", row.get("eval"));
        result.add("enums", row.get("enums"));
        result.add("diagnostic", row.get("diagnostic"));
        return result;
    }

    private URLClassLoader compileJava(GrammarDecl grammar, JsonObject entry) throws Exception {
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator :
                List.of(
                        new ParserGenerator(),
                        new ASTGenerator(),
                        new MapperGenerator(),
                        new EvaluatorGenerator(21))) {
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
        String semanticSource = javaSemantics(entry);
        units.add(
                new SimpleJavaFileObject(
                        URI.create("string:///enumprobe/Semantics.java"),
                        JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignored) {
                        return semanticSource;
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

    private String rustProbe(JsonObject entry) {
        return rustSemantics(entry)
                + "\n"
                + """
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
                                    Ok(tree) => { let ast=generated::mapper::map(&tree).unwrap(); print!("{}",ast.canonical_json());
                                        print!(r#", "eval":{},"enums":["#,generated::evaluator::evaluate(&ast,&mut Semantics));
                                        let mut tokens=Vec::new(); enum_tokens(&tree,tree.root,&mut tokens); print!("{}",tokens.join(","));
                                        print!(r#"], "diagnostic":null"#); }
                                    Err(diagnostic) => { print!(r#"null,"eval":null,"enums":[],"diagnostic":{{"kind":"{}","offset":{}}}"#,diagnostic.kind,diagnostic.offset); }
                                }
                                println!("}}");
                            }
                        }
                    }
                }
                """;
    }

    @Test
    public void deepEnumAliasesHaveABoundedGeneratorDiagnostic() {
        var source =
                new StringBuilder(
                        "grammar Deep { @enum Mode ::= 'sync'; @root @mapping(Value,params=[value])"
                                + " Root ::= R0 @value; ");
        for (int index = 0; index < 200; index++)
            source.append("R").append(index).append(" ::= R").append(index + 1).append(";");
        source.append("R200 ::= Mode; }");
        var grammar = UBNFMapper.parse(source.toString()).grammars().get(0);
        var error =
                assertThrows(
                        IllegalArgumentException.class, () -> new ASTGenerator().generate(grammar));
        assertTrue(
                error.getMessage(),
                error.getMessage().contains("enum value analysis exceeds 128 rules"));
    }

    private void comparePortability(Path generator, Path path, boolean portable) throws Exception {
        String source = Files.readString(path);
        var java = org.unlaxer.dsl.PortabilityCheck.check(source);
        var nativeReport =
                JsonParser.parseString(
                                run(
                                        List.of(
                                                generator.toString(),
                                                "check",
                                                "--target",
                                                "rust",
                                                "--grammar",
                                                path.toString(),
                                                "--format",
                                                "json"),
                                        "",
                                        true,
                                        portable))
                        .getAsJsonObject();
        assertEquals(source, portable, java.portable());
        assertEquals(source, java.portable(), nativeReport.get("portable").getAsBoolean());
        assertEquals(java.structure(), nativeReport.get("structure").getAsString());
        var diagnostics = nativeReport.getAsJsonArray("diagnostics");
        assertEquals(java.diagnostics().size(), diagnostics.size());
        for (int index = 0; index < diagnostics.size(); index++) {
            var expected = java.diagnostics().get(index);
            var actual = diagnostics.get(index).getAsJsonObject();
            assertEquals(expected.code(), actual.get("code").getAsString());
            assertEquals(expected.subject(), actual.get("subject").getAsString());
            var span = actual.getAsJsonObject("span");
            assertNotNull(expected.span());
            assertEquals(expected.span().start(), span.get("start").getAsInt());
            assertEquals(expected.span().end(), span.get("end").getAsInt());
            if (expected.code().equals("P-STRUCTURE")) {
                assertEquals(0, span.get("start").getAsInt());
                assertEquals(source.codePointCount(0, source.length()), span.get("end").getAsInt());
            } else {
                int start = span.get("start").getAsInt();
                int end = span.get("end").getAsInt();
                assertTrue(
                        source,
                        start >= 0
                                && start < end
                                && end <= source.codePointCount(0, source.length()));
                String located =
                        source.substring(
                                source.offsetByCodePoints(0, start),
                                source.offsetByCodePoints(0, end));
                if (expected.code().equals("P-EMPTY-LITERAL")) {
                    assertEquals("''", located);
                } else {
                    assertTrue(expected.code() + ": " + located, located.startsWith("@" + expected.subject()));
                }
            }
        }
        assertEquals(source, Files.readString(path));
    }

    private void javaEnumEvolution() throws Exception {
        String source =
                corpus().get(0)
                        .getAsJsonObject()
                        .get("grammar")
                        .getAsString()
                        .replace("'auto'", "'auto'|'later'");
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var ast = new ASTGenerator().generate(grammar);
        var units = new ArrayList<JavaFileObject>();
        for (var file :
                List.of(
                        ast,
                        new CodeGenerator.GeneratedSource(
                                "enumprobe",
                                "StaleConsumer",
                                "package enumprobe; class StaleConsumer { int"
                                        + " choose(EnumScalarAST.Mode value) {return switch(value)"
                                        + " {case SYNC -> 1;case AUTO -> 2;};}}"))) {
            units.add(
                    new SimpleJavaFileObject(
                            URI.create("string:///enumprobe/" + file.className() + ".java"),
                            JavaFileObject.Kind.SOURCE) {
                        @Override
                        public CharSequence getCharContent(boolean ignored) {
                            return file.source();
                        }
                    });
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean ok =
                    compiler.getTask(
                                    null,
                                    manager,
                                    diagnostics,
                                    List.of(
                                            "--release",
                                            "21",
                                            "-d",
                                            temporary.newFolder().toString()),
                                    null,
                                    units)
                            .call();
            assertFalse("new enum constant must invalidate stale Java switch", ok);
            assertTrue(
                    diagnostics.getDiagnostics().toString(),
                    diagnostics.getDiagnostics().stream()
                            .anyMatch(d -> d.getCode().contains("not.exhaustive")));
        }
    }

    private void rustEnumEvolution(Path generator, Path runtime) throws Exception {
        var directory = temporary.newFolder().toPath();
        Path source = directory.resolve("evolution.ubnf");
        Files.writeString(
                source,
                corpus().get(0)
                        .getAsJsonObject()
                        .get("grammar")
                        .getAsString()
                        .replace("'auto'", "'auto'|'later'"));
        run(
                List.of(
                        generator.toString(),
                        "generate",
                        "--grammar",
                        source.toString(),
                        "--output",
                        directory.resolve("generated").toString()),
                "",
                true,
                true);
        Files.writeString(
                directory.resolve("main.rs"),
                "mod generated; fn choose(value:&generated::ast::Mode)->i32 {match value"
                    + " {generated::ast::Mode::SYNC=>1,generated::ast::Mode::AUTO=>2}} fn main() {}"
                    + " ");
        var error =
                run(
                        List.of(
                                "rustc",
                                "--edition=2021",
                                "--extern",
                                "unlaxer_runtime=" + runtime,
                                directory.resolve("main.rs").toString(),
                                "-o",
                                directory.resolve("probe").toString()),
                        "",
                        false,
                        false);
        assertTrue(error, error.contains("E0004"));
    }

    private JsonElement canonical(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Enum<?> e) {
            assertTrue(
                    "enum singletons must not carry occurrence spans",
                    ((Optional<?>)
                                    mapped.getClass()
                                            .getMethod("sourceSpanOf", Object.class)
                                            .invoke(mapped, e))
                            .isEmpty());
            return new JsonPrimitive(e.name());
        }
        if (value instanceof Optional<?> o)
            return o.isPresent() ? canonical(o.get(), mapped) : JsonNull.INSTANCE;
        if (value instanceof List<?> list) {
            var result = new JsonArray();
            for (var item : list) result.add(canonical(item, mapped));
            return result;
        }
        var result = new JsonObject();
        result.addProperty("type", value.getClass().getSimpleName());
        int[] span =
                (int[])
                        ((Optional<?>)
                                        mapped.getClass()
                                                .getMethod("sourceSpanOf", Object.class)
                                                .invoke(mapped, value))
                                .orElseThrow();
        var positions = new JsonArray();
        positions.add(span[0]);
        positions.add(span[1]);
        result.add("span", positions);
        var fields = new JsonObject();
        for (var component : value.getClass().getRecordComponents())
            fields.add(
                    component.getName(), canonical(component.getAccessor().invoke(value), mapped));
        result.add("fields", fields);
        return result;
    }

    private void enumTokens(
            org.unlaxer.Token token, JsonObject entry, JsonArray result, String input) {
        for (var field : entry.getAsJsonArray("enumRules")) {
            String name = field.getAsString();
            if (token.parser.getClass().getSimpleName().equals(name + "Parser")) {
                var item = new JsonObject();
                item.addProperty("rule", name);
                item.addProperty("text", token.source.sourceAsString());
                int start = token.source.offsetFromRoot().value();
                var span = new JsonArray();
                span.add(start);
                span.add(start + token.source.codePointLength().value());
                item.add("span", span);
                int end = start + token.source.codePointLength().value();
                var snapshot = new DocumentSnapshot("fixture", 1, input);
                var utf16 = new JsonArray();
                utf16.add(snapshot.utf16(start));
                utf16.add(snapshot.utf16(end));
                item.add("utf16", utf16);
                var utf8 = new JsonArray();
                utf8.add(snapshot.utf8(start));
                utf8.add(snapshot.utf8(end));
                item.add("utf8", utf8);
                result.add(item);
                break;
            }
        }
        for (var child : token.getOriginalChildren()) enumTokens(child, entry, result, input);
    }

    private String javaSemantics(JsonObject entry) {
        String name = entry.get("name").getAsString();
        String root = entry.get("rootMapping").getAsString();
        var terms = new ArrayList<String>();
        for (var item : entry.getAsJsonArray("fields")) {
            var field = item.getAsJsonObject();
            String value = "node." + field.get("name").getAsString() + "()";
            if (field.has("type") && field.get("type").getAsString().equals("text")) {
                terms.add(value + ".codePointCount(0," + value + ".length())");
                continue;
            }
            terms.add(
                    switch (field.get("cardinality").getAsString()) {
                        case "optional" -> value + ".map(v -> v.ordinal()+1).orElse(0)";
                        case "many" -> value + ".stream().mapToInt(v -> v.ordinal()+1).sum()";
                        default -> value + ".ordinal()+1";
                    });
        }
        return "package enumprobe; public class Semantics extends "
                + name
                + "Evaluator<Integer> { protected Integer eval"
                + root
                + "("
                + name
                + "AST."
                + root
                + " node) { return "
                + String.join(" + ", terms)
                + "; } public static int run("
                + name
                + "AST node) {return new Semantics().eval(node);} }";
    }

    private String rustSemantics(JsonObject entry) {
        String method =
                "eval_"
                        + entry.get("rootMapping")
                                .getAsString()
                                .replaceAll("([a-z])([A-Z])", "$1_$2")
                                .toLowerCase(Locale.ROOT);
        var params = new ArrayList<String>();
        var terms = new ArrayList<String>();
        var matches = new java.util.LinkedHashSet<String>();
        var grammar = UBNFMapper.parse(entry.get("grammar").getAsString()).grammars().get(0);
        for (var field : entry.getAsJsonArray("fields")) {
            var f = field.getAsJsonObject();
            String name = f.get("name").getAsString();
            if (f.has("type") && f.get("type").getAsString().equals("text")) {
                params.add(name + ":&str");
                terms.add(name + ".chars().count() as i32");
                continue;
            }
            String type = f.get("enum").getAsString();
            String card = f.get("cardinality").getAsString();
            params.add(
                    name
                            + ":"
                            + switch (card) {
                                case "optional" -> "Option<&generated::ast::" + type + ">";
                                case "many" -> "&[generated::ast::" + type + "]";
                                default -> "&generated::ast::" + type;
                            });
            terms.add(
                    switch (card) {
                        case "optional" -> name + ".map_or(0,|v|*v as i32+1)";
                        case "many" -> name + ".iter().map(|v|*v as i32+1).sum::<i32>()";
                        default -> "(*" + name + " as i32+1)";
                    });
        }
        entry.getAsJsonArray("enumRules").forEach(value -> matches.add(value.getAsString()));
        var checks = new ArrayList<String>();
        for (String type : matches) {
            int id =
                    java.util.stream.IntStream.range(0, grammar.rules().size())
                            .filter(i -> grammar.rules().get(i).name().equals(type))
                            .findFirst()
                            .orElseThrow();
            checks.add(
                    "        "
                            + id
                            + " => out.push(format!(r#\"{{\"rule\":\""
                            + type
                            + "\",\"text\":{},\"span\":[{},{}],\"utf16\":[{},{}],\"utf8\":[{},{}]}}\"#,quoted(tree.text(node.span)),node.span.start,node.span.end,snapshot.utf16(node.span.start).unwrap(),snapshot.utf16(node.span.end).unwrap(),snapshot.utf8(node.span.start).unwrap(),snapshot.utf8(node.span.end).unwrap())),");
        }
        return "struct Semantics; impl generated::evaluator::Semantics for Semantics {type"
                + " Output=i32; fn "
                + method
                + "(&mut self,"
                + String.join(",", params)
                + ",_span:unlaxer_runtime::Span)->i32 {"
                + String.join("+", terms)
                + "} }\n"
                + "fn enum_tokens(tree:&unlaxer_runtime::Tree,id:usize,out:&mut Vec<String>) {let"
                + " node=&tree.nodes[id];let"
                + " snapshot=unlaxer_runtime::source::Snapshot::new(\"fixture\",1,&tree.source).unwrap();match"
                + " node.rule {\n"
                + String.join("\n", checks)
                + "\n _=>(),} for child in &node.children {enum_tokens(tree,*child,out);} }\n";
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
