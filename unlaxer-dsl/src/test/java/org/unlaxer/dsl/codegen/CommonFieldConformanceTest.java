package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.tools.*;

/** Independent source/accessor oracle; both real hosts compile the exact same Rust modules. */
public class CommonFieldConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    protected JsonArray corpus(String name) throws Exception {
        return JsonParser.parseString(
                        Files.readString(
                                repo.resolve("spec-corpus/common-fields/" + name + "-cases.json")))
                .getAsJsonArray();
    }

    @Test
    public void javaTypedAccessorsAndFailures() throws Exception {
        for (var item : corpus("generated")) {
            var entry = item.getAsJsonObject();
            var grammar = UBNFMapper.parse(entry.get("grammar").getAsString()).grammars().get(0);
            assertTrue(
                    GrammarValidator.validateWithoutClassLoading(grammar).toString(),
                    GrammarValidator.validateWithoutClassLoading(grammar).stream()
                            .noneMatch(i -> i.severity().equals("ERROR")));
            try (var loader = compile(grammar, entry.get("java").getAsString())) {
                var parser =
                        (Parser)
                                loader.loadClass("commonprobe." + grammar.name() + "Parsers")
                                        .getMethod("getRootParser")
                                        .invoke(null);
                var mapper = loader.loadClass("commonprobe." + grammar.name() + "Mapper");
                var probe = loader.loadClass("commonprobe.Probe");
                for (var row : entry.getAsJsonArray("cases")) {
                    String input = row.getAsJsonArray().get(0).getAsString();
                    try (var context = new ParseContext(StringSource.createRootSource(input))) {
                        var parsed = parser.parse(context);
                        assertTrue(input, parsed.isSucceeded());
                        var rootToken =
                                context.getCurrent().getTokens().stream()
                                        .filter(token -> token.parser == parser)
                                        .findFirst()
                                        .orElseThrow();
                        var mapped =
                                mapper.getMethod(
                                                "mapParsedTokenWithSourceMap",
                                                org.unlaxer.Token.class)
                                        .invoke(null, rootToken);
                        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                        var expectedSpan = row.getAsJsonArray().get(2).getAsJsonArray();
                        assertEquals(
                                expectedSpan.get(1).getAsInt(),
                                context.getConsumedPosition().value());
                        int[] span =
                                (int[])
                                        ((Optional<?>)
                                                        mapped.getClass()
                                                                .getMethod(
                                                                        "sourceSpanOf",
                                                                        Object.class)
                                                                .invoke(mapped, ast))
                                                .orElseThrow();
                        assertArrayEquals(
                                new int[] {
                                    expectedSpan.get(0).getAsInt(), expectedSpan.get(1).getAsInt()
                                },
                                span);
                        if (entry.has("mixed"))
                            verifyOccurrences(
                                    mapped, ast, row.getAsJsonArray().get(3).getAsJsonArray());
                        assertEquals(
                                input,
                                row.getAsJsonArray().get(1).getAsString(),
                                probe.getMethod(
                                                "eval",
                                                loader.loadClass(
                                                        "commonprobe."
                                                                + grammar.name()
                                                                + "AST$Node"))
                                        .invoke(null, ast));
                    }
                }
                for (String invalid : List.of("", "?"))
                    try (var context = new ParseContext(StringSource.createRootSource(invalid))) {
                        assertFalse(parser.parse(context).isSucceeded());
                        assertEquals(0, context.getConsumedPosition().value());
                    }
            }
        }
        for (var item : corpus("invalid")) {
            var grammar =
                    UBNFMapper.parse(item.getAsJsonObject().get("grammar").getAsString())
                            .grammars()
                            .get(0);
            assertTrue(
                    GrammarValidator.validateWithoutClassLoading(grammar).stream()
                            .anyMatch(i -> i.code().equals("E-COMMONFIELD-MISMATCH")));
            for (CodeGenerator generator :
                    List.of(new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator(21)))
                assertTrue(
                        assertThrows(
                                        IllegalArgumentException.class,
                                        () -> generator.generate(grammar))
                                .getMessage()
                                .contains("E-COMMONFIELD"));
            assertTrue(
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> new RustBackend().generate(grammar))
                            .getMessage()
                            .contains("E-COMMONFIELD"));
            var portability =
                    org.unlaxer.dsl.PortabilityCheck.check(
                            item.getAsJsonObject().get("grammar").getAsString());
            assertFalse(portability.portable());
            assertTrue(portability.diagnostics().stream().allMatch(d -> d.span() != null));
            var expected = item.getAsJsonObject().getAsJsonObject("error");
            assertEquals(item.toString(), 1, portability.diagnostics().size());
            var diagnostic = portability.diagnostics().get(0);
            assertEquals(expected.get("code").getAsString(), diagnostic.code());
            assertEquals(
                    expected.getAsJsonArray("span").get(0).getAsInt(), diagnostic.span().start());
            assertEquals(
                    expected.getAsJsonArray("span").get(1).getAsInt(), diagnostic.span().end());
        }
    }

    @Test
    public void bothHostsCompileAndEvaluate() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println(
                    "[assumption] CommonFieldConformanceTest requires -DrustConformance=true");
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
        Path target = repo.resolve("rust/target");
        run(
                List.of(
                        "cargo",
                        "build",
                        "--locked",
                        "--manifest-path",
                        repo.resolve("rust/Cargo.toml").toString(),
                        "-p",
                        "unlaxer-generator"),
                "",
                false,
                true);
        Path generator = target.resolve("debug/unlaxer");
        for (var item : corpus("generated")) {
            var entry = item.getAsJsonObject();
            Path dir = temporary.newFolder().toPath();
            Path source = dir.resolve("fixture.ubnf");
            Files.writeString(source, entry.get("grammar").getAsString());
            Path output = dir.resolve("generated");
            run(
                    List.of(
                            generator.toString(),
                            "generate",
                            "--grammar",
                            source.toString(),
                            "--output",
                            output.toString()),
                    "",
                    true,
                    true);
            var grammar = UBNFMapper.parse(entry.get("grammar").getAsString()).grammars().get(0);
            for (var file : new RustBackend().generate(grammar))
                assertEquals(
                        file.relativePath(),
                        file.content(),
                        Files.readString(output.resolve(file.relativePath())));
            String selfImpact =
                    org.unlaxer.dsl.impact.ApiImpact.compare(
                                    "rust",
                                    entry.get("grammar").getAsString(),
                                    entry.get("grammar").getAsString())
                            .toJson();
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
                    "API schema " + grammar.name(),
                    JsonParser.parseString(selfImpact),
                    JsonParser.parseString(nativeImpact));
            StringBuilder probe = new StringBuilder("mod generated; fn main(){\n");
            for (var row : entry.getAsJsonArray("cases")) {
                probe.append("{let tree=generated::parser::parse_tree(")
                        .append(row.getAsJsonArray().get(0))
                        .append(
                                ").unwrap();let ast=generated::mapper::map(&tree).unwrap();let"
                                        + " view=ast.common_node().unwrap();let value=")
                        .append(entry.get("rust").getAsString())
                        .append(";assert_eq!([ast.span().start,ast.span().end],")
                        .append(row.getAsJsonArray().get(2))
                        .append(");assert_eq!(value,")
                        .append(row.getAsJsonArray().get(1))
                        .append(");");
                if (entry.has("mixed"))
                    for (var occurrence : row.getAsJsonArray().get(3).getAsJsonArray()) {
                        var expectedOccurrence = occurrence.getAsJsonArray();
                        String field = expectedOccurrence.get(0).getAsString();
                        String access =
                                field.equals("value")
                                        ? "view.value"
                                        : field.equals("maybe")
                                                ? "view.maybe.as_ref().unwrap()"
                                                : "&view.many[" + expectedOccurrence.get(1).getAsInt() + "]";
                        probe.append("{let occurrence=")
                                .append(access)
                                .append(
                                        ";assert_eq!([occurrence.span().start,occurrence.span().end],[")
                                .append(expectedOccurrence.get(2).getAsInt())
                                .append(",")
                                .append(expectedOccurrence.get(3).getAsInt())
                                .append("]);}\n");
                    }
                probe.append("}\n");
            }
            probe.append(
                    "assert!(generated::parser::parse_tree(\"\").is_err());assert!(generated::parser::parse_tree(\"?\").is_err());}\n");
            Files.writeString(dir.resolve("main.rs"), probe);
            Path binary = dir.resolve("probe");
            run(
                    List.of(
                            "rustc",
                            "--edition=2021",
                            "--extern",
                            "unlaxer_runtime=" + runtime,
                            dir.resolve("main.rs").toString(),
                            "-o",
                            binary.toString()),
                    "",
                    false,
                    true);
            run(List.of(binary.toString()), "", true, true);
        }
        for (var item : corpus("invalid")) {
            Path dir = temporary.newFolder().toPath();
            Path source = dir.resolve("fixture.ubnf");
            Files.writeString(source, item.getAsJsonObject().get("grammar").getAsString());
            assertTrue(
                    run(
                                    List.of(
                                            generator.toString(),
                                            "generate",
                                            "--grammar",
                                            source.toString(),
                                            "--output",
                                            dir.resolve("out").toString()),
                                    "",
                                    true,
                                    false)
                            .contains("E-COMMONFIELD"));
        }
    }

    private void verifyOccurrences(Object mapped, Object ast, JsonArray expected) throws Exception {
        var identities = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        for (var occurrence : expected) {
            var item = occurrence.getAsJsonArray();
            String field = item.get(0).getAsString();
            Object value = ast.getClass().getMethod(field).invoke(ast);
            if (field.equals("maybe")) value = ((Optional<?>) value).orElseThrow();
            if (field.equals("many")) value = ((List<?>) value).get(item.get(1).getAsInt());
            assertEquals("EnumValue", value.getClass().getSimpleName());
            assertTrue("distinct occurrence wrapper", identities.add(value));
            int[] span =
                    (int[])
                            ((Optional<?>)
                                            mapped.getClass()
                                                    .getMethod("sourceSpanOf", Object.class)
                                                    .invoke(mapped, value))
                                    .orElseThrow();
            assertArrayEquals(new int[] {item.get(2).getAsInt(), item.get(3).getAsInt()}, span);
        }
    }

    private URLClassLoader compile(GrammarDecl grammar, String expression) throws Exception {
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator :
                List.of(
                        new ParserGenerator(),
                        new ASTGenerator(),
                        new MapperGenerator(),
                        new EvaluatorGenerator(21))) {
            var source = generator.generate(grammar);
            units.add(unit(source.packageName() + "." + source.className(), source.source()));
        }
        units.add(
                unit(
                        "commonprobe.Probe",
                        "package commonprobe; public class Probe { public static String eval("
                                + grammar.name()
                                + "AST.Node node) { "
                                + expression
                                + " } }"));
        Path output = temporary.newFolder().toPath();
        var compiler = org.unlaxer.dsl.Java21TestSupport.compiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
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

    private JavaFileObject unit(String name, String source) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignored) {
                return source;
            }
        };
    }

    private String run(List<String> command, String input, boolean withoutJava, boolean success)
            throws Exception {
        Path log = temporary.newFile().toPath();
        var builder =
                org.unlaxer.dsl.TestProcesses.builder(command).redirectErrorStream(true).redirectOutput(log.toFile());
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
