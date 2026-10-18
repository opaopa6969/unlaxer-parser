package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import com.google.gson.*;

import org.junit.*;
import org.unlaxer.StringSource;
import org.unlaxer.context.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.tools.*;

/** Handwritten values, mapping-time failures, and CP spans shared by both generator hosts. */
public class NestedTypeofConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("spec-corpus/nested-typeof");

    private JsonArray corpus(String name) throws Exception {
        return JsonParser.parseString(Files.readString(FIXTURES.resolve(name + "-cases.json")))
                .getAsJsonArray();
    }

    @Before
    public void baseline() {
        Assume.assumeTrue("Java21 baseline", Runtime.version().feature() >= 21);
    }

    @Test
    public void javaCompileMapAndEvaluateAgainstIndependentValuesAndFailureSpans()
            throws Exception {
        var report = new ArrayList<String>(List.of("fixture\tinput\tmemo\tdiagnostics\tactual"));
        for (var fixture : corpus("generated")) {
            var row = fixture.getAsJsonObject();
            var grammar = UBNFMapper.parse(row.get("grammar").getAsString()).grammars().get(0);
            assertTrue(
                    row + "\n" + GrammarValidator.validateWithoutClassLoading(grammar),
                    GrammarValidator.validateWithoutClassLoading(grammar).stream()
                            .noneMatch(issue -> issue.severity().equals("ERROR")));
            var units = new ArrayList<JavaFileObject>();
            for (CodeGenerator generator :
                    List.of(
                            new ParserGenerator(),
                            new ASTGenerator(),
                            new MapperGenerator(),
                            new EvaluatorGenerator(21))) {
                var source = generator.generate(grammar);
                units.add(source(source.packageName() + "." + source.className(), source.source()));
            }
            units.add(
                    source(
                            "typeofprobe.Probe",
                            "package typeofprobe; public class Probe extends"
                                + " TypeofEvaluator<Integer> { public static Integer run(TypeofAST"
                                + " node){return new Probe().eval(node);} "
                                    + row.get("javaMethods").getAsString()
                                    + " }"));
            Path classes = temporary.newFolder().toPath();
            var compiler = ToolProvider.getSystemJavaCompiler();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
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
                                                classes.toString()),
                                        null,
                                        units)
                                .call();
                assertTrue(row.get("name") + "\n" + diagnostics.getDiagnostics(), compiled);
            }
            try (var loader =
                    new URLClassLoader(
                            new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
                var mapper = loader.loadClass("typeofprobe.TypeofMapper");
                var parser =
                        (Parser)
                                loader.loadClass("typeofprobe.TypeofParsers")
                                        .getMethod("getRootParser")
                                        .invoke(null);
                var evaluator =
                        loader.loadClass("typeofprobe.Probe")
                                .getMethod("run", loader.loadClass("typeofprobe.TypeofAST"));
                var retained = new ArrayList<Runnable>();
                for (var item : row.getAsJsonArray("cases")) {
                    var input = item.getAsJsonObject();
                    String text = input.get("input").getAsString();
                    for (var memo : Memoization.values())
                        for (var mode : ParseOptions.Diagnostics.values()) {
                            JsonObject actual;
                            try (var context =
                                    ParseContext.withOptions(
                                            StringSource.createRootSource(text),
                                            ParseOptions.withMemoization(memo)
                                                    .withDiagnostics(mode))) {
                                var parsed = parser.parse(context);
                                if (parsed.isFailed()) {
                                    assertEquals(0, context.getConsumedPosition().value());
                                    assertEquals(0, context.getMatchedPosition().value());
                                    assertTrue(context.getCurrent().getTokens().isEmpty());
                                    boolean reset=context.getCurrent().isResetMatchedWithConsumed();
                                    assertTrue(parser.parse(context).isFailed());
                                    assertEquals(0,context.position());assertEquals(0,context.matchedPosition());
                                    assertEquals(reset,context.getCurrent().isResetMatchedWithConsumed());
                                    assertEquals(1,context.getTokenStack().size());assertTrue(context.getCurrent().getTokens().isEmpty());
                                    actual = new JsonObject();
                                    actual.addProperty("status", "syntax");
                                    actual.addProperty(
                                            "offset",
                                            context.getParseFailureDiagnostics()
                                                    .getFarthestOffset());
                                    assertTrue(new org.unlaxer.parser.elementary.WordParser(text).parse(context).isSucceeded());
                                    assertEquals(text.codePointCount(0,text.length()),context.position());
                                } else {
                                    assertEquals(
                                            text.codePointCount(0, text.length()),
                                            context.getConsumedPosition().value());
                                    try {
                                        Object mapped =
                                                mapper.getMethod(
                                                                "mapParsedTokenWithSourceMap",
                                                                org.unlaxer.Token.class)
                                                        .invoke(
                                                                null,
                                                                context
                                                                        .getCurrent()
                                                                        .getTokens()
                                                                        .stream()
                                                                        .filter(
                                                                                token ->
                                                                                        token.parser
                                                                                                == parser)
                                                                        .findFirst()
                                                                        .orElseThrow());
                                        Object ast =
                                                mapped.getClass().getMethod("ast").invoke(mapped);
                                        actual = new JsonObject();
                                        actual.addProperty("status", "ok");
                                        actual.add("value", observe(ast, mapped));
                                        actual.addProperty(
                                                "eval", (Integer) evaluator.invoke(null, ast));
                                        JsonElement observation = observe(ast, mapped);
                                        var token =
                                                context.getCurrent().getTokens().stream()
                                                        .filter(t -> t.parser == parser)
                                                        .findFirst()
                                                        .orElseThrow();
                                        var preferred =
                                                mapper.getMethod(
                                                                "mapParsedTree",
                                                                org.unlaxer.Token.class)
                                                        .invoke(null, token);
                                        var selected =
                                                preferred
                                                        .getClass()
                                                        .getMethod("selectDefault")
                                                        .invoke(preferred);
                                        var sourceMap =
                                                selected.getClass()
                                                        .getMethod("sourceMap")
                                                        .invoke(selected);
                                        var preferredAst =
                                                sourceMap
                                                        .getClass()
                                                        .getMethod("ast")
                                                        .invoke(sourceMap);
                                        assertEquals(observation, observe(preferredAst, sourceMap));
                                        retained.add(
                                                () -> {
                                                    try {
                                                        assertEquals(
                                                                observation, observe(ast, mapped));
                                                        assertEquals(
                                                                observation,
                                                                observe(preferredAst, sourceMap));
                                                    } catch (Exception failure) {
                                                        throw new AssertionError(failure);
                                                    }
                                                });
                                    } catch (InvocationTargetException failure) {
                                        assertTrue(
                                                failure.getCause().toString(),
                                                failure.getCause()
                                                        instanceof IllegalArgumentException);
                                        actual = new JsonObject();
                                        actual.addProperty("status", "map_error");
                                        actual.addProperty(
                                                "message", failure.getCause().getMessage());
                                        assertEquals(
                                                text.codePointCount(0, text.length()),
                                                context.getConsumedPosition().value());
                                        assertFalse(context.getCurrent().getTokens().isEmpty());
                                        var token = context.getCurrent().getTokens().stream().filter(t -> t.parser == parser).findFirst().orElseThrow();
                                        var retried = assertThrows(InvocationTargetException.class, () -> mapper.getMethod("mapParsedTokenWithSourceMap", org.unlaxer.Token.class).invoke(null, token));
                                        assertEquals(failure.getCause().getMessage(), retried.getCause().getMessage());
                                    }
                                }
                            }
                            assertEquals(
                                    row.get("name") + "/" + input + "/" + memo + "/" + mode,
                                    input.get("expected"),
                                    actual);
                            report.add(
                                    row.get("name")
                                            + "\t"
                                            + input.get("input")
                                            + "\t"
                                            + memo
                                            + "\t"
                                            + mode
                                            + "\t"
                                            + actual);
                        }
                }
                retained.forEach(Runnable::run);
            }
        }
        Files.write(Path.of("target/nested-typeof-java.tsv"), report);
    }

    private JsonElement observe(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Enum<?> item) return new JsonPrimitive(item.name());
        if (value instanceof Optional<?> item)
            return item.isPresent() ? observe(item.get(), mapped) : JsonNull.INSTANCE;
        if (value instanceof List<?> values) {
            var array = new JsonArray();
            for (Object item : values) array.add(observe(item, mapped));
            return array;
        }
        int[] offsets =
                (int[])
                        ((Optional<?>)
                                        mapped.getClass()
                                                .getMethod("sourceSpanOf", Object.class)
                                                .invoke(mapped, value))
                                .orElseThrow();
        var span = new JsonArray();
        span.add(offsets[0]);
        span.add(offsets[1]);
        var result = new JsonObject();
        if (value.getClass().getSimpleName().equals("EnumValue")) {
            var item = (Enum<?>) value.getClass().getMethod("value").invoke(value);
            result.addProperty("enum", item.getDeclaringClass().getSimpleName());
            result.addProperty("value", item.name());
            result.add("span", span);
            return result;
        }
        if (value.getClass().getName().contains("$NestedValue$")) {
            Object payload = value.getClass().getMethod("value").invoke(value);
            if (payload instanceof Enum<?> item) {
                result.addProperty("enum", item.getDeclaringClass().getSimpleName());
                result.addProperty("value", item.name());
                result.add("span", span);
                return result;
            }
            return observe(payload, mapped);
        }
        String name = value.getClass().getName().split("TypeofAST\\$")[1].replace("$", "__");
        result.addProperty("type", name);
        result.add("span", span);
        var fields = new JsonObject();
        for (var field : value.getClass().getRecordComponents())
            fields.add(field.getName(), observe(field.getAccessor().invoke(value), mapped));
        result.add("fields", fields);
        return result;
    }

    @Test
    public void bothRustHostsEmitIdenticalCompilableMappingTimeChecks() throws Exception {
        Assume.assumeTrue(
                "Rust oracle requires -DrustConformance=true",
                Boolean.getBoolean("rustConformance"));
        var report = new ArrayList<String>(List.of("fixture\tinput\thost\tmode\tactual"));
        for (var fixture : corpus("generated")) {
            var row = fixture.getAsJsonObject();
            String source = row.get("grammar").getAsString();
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            assertTrue(
                    row + "\n" + org.unlaxer.dsl.PortabilityCheck.check(source),
                    org.unlaxer.dsl.PortabilityCheck.check(source).portable());
            Path dir = temporary.newFolder().toPath(),
                    generated = Files.createDirectories(dir.resolve("generated"));
            Path grammarFile = dir.resolve("fixture.ubnf");
            Files.writeString(grammarFile, source);
            Path nativeOut = dir.resolve("native");
            run(nativeCommand(grammarFile, nativeOut), "", 0);
            for (var file : new RustBackend().generate(grammar)) {
                assertEquals(
                        row.get("name") + "/" + file.relativePath(),
                        file.content(),
                        Files.readString(nativeOut.resolve(file.relativePath())));
                Files.writeString(generated.resolve(file.relativePath()), file.content());
            }
            Files.writeString(
                    dir.resolve("main.rs"),
                    Files.readString(FIXTURES.resolve("probe.rs.txt"))
                            .replace("/*METHODS*/", row.get("rustMethods").getAsString()));
            Files.writeString(
                    dir.resolve("Cargo.toml"),
                    "[package]\n"
                            + "name=\"typeof-probe\"\n"
                            + "version=\"0.1.0\"\n"
                            + "edition=\"2021\"\n"
                            + "[[bin]]\n"
                            + "name=\"probe\"\n"
                            + "path=\"main.rs\"\n"
                            + "[dependencies]\n"
                            + "unlaxer-runtime={path=\""
                            + ROOT.resolve("rust/unlaxer-runtime")
                            + "\"}\nserde_json=\"1\"\n");
            for (Path hostOutput : List.of(generated, nativeOut)) {
                String host=hostOutput.equals(generated)?"java":"native";
                Path consumer=Files.createDirectories(dir.resolve(host+"-consumer"));
                Path consumerGenerated=Files.createDirectories(consumer.resolve("generated"));
                Files.copy(dir.resolve("main.rs"),consumer.resolve("main.rs"));
                Files.copy(dir.resolve("Cargo.toml"),consumer.resolve("Cargo.toml"));
                for(String file:List.of("ast.rs","parser.rs","mapper.rs","evaluator.rs","mod.rs"))
                    Files.copy(hostOutput.resolve(file),consumerGenerated.resolve(file));
                Path target=ROOT.resolve("rust/target/nested-typeof-probe-"+host);
                run(
                        List.of(
                                "cargo",
                                "build",
                                "--quiet",
                                "--offline",
                                "--manifest-path",
                                consumer.resolve("Cargo.toml").toString(),
                                "--target-dir",
                                target.toString()),
                        "",
                        0);
                String inputs =
                        String.join(
                                        "\n",
                                        row.getAsJsonArray("cases").asList().stream()
                                                .map(
                                                        item ->
                                                                HexFormat.of()
                                                                        .formatHex(
                                                                                item.getAsJsonObject()
                                                                                        .get(
                                                                                                "input")
                                                                                        .getAsString()
                                                                                        .getBytes(
                                                                                                StandardCharsets
                                                                                                        .UTF_8)))
                                                .toList())
                                + "\n";
                var lines =
                        run(List.of(target.resolve("debug/probe").toString()), inputs, 0)
                                .lines()
                                .toList();
                assertEquals(row.getAsJsonArray("cases").size() * 6, lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    var input = row.getAsJsonArray("cases").get(i / 6).getAsJsonObject();
                    assertEquals(
                            row.get("name") + "/" + input + "/" + (i % 6),
                            input.get("expected"),
                            JsonParser.parseString(lines.get(i)));
                    report.add(
                            row.get("name")
                                    + "\t"
                                    + input.get("input")
                                    + "\t"
                                    + host
                                    + "\t"
                                    + (i % 6)
                                    + "\t"
                                    + lines.get(i));
                }
            }
        }
        Files.write(Path.of("target/nested-typeof-conformance.tsv"), report);
    }

    @Test
    public void bothHostsRejectUnknownOrUnboundConstraintsBeforeGeneration() throws Exception {
        for (var item : corpus("invalid")) {
            var row = item.getAsJsonObject();
            String text = row.get("grammar").getAsString(), code = row.get("code").getAsString();
            var grammar = UBNFMapper.parse(text).grammars().get(0);
            assertTrue(
                    row + "\n" + GrammarValidator.validateWithoutClassLoading(grammar),
                    GrammarValidator.validateWithoutClassLoading(grammar).stream()
                            .anyMatch(issue -> issue.code().equals(code)));
            for (CodeGenerator generator :
                    List.of(
                            new ParserGenerator(),
                            new ASTGenerator(),
                            new MapperGenerator(),
                            new EvaluatorGenerator(21))) {
                // The standalone syntax-only generator does not validate AST family schemas.
                if (code.equals("E-COMMONFIELD-MISMATCH") && generator instanceof ParserGenerator)
                    continue;
                assertTrue(
                        row.toString(),
                        assertThrows(
                                        IllegalArgumentException.class,
                                        () -> generator.generate(grammar))
                                .getMessage()
                                .contains(code));
            }
            assertTrue(
                    row.toString(),
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> new RustBackend().generate(grammar))
                            .getMessage()
                            .contains(code));
            if (Boolean.getBoolean("rustConformance")) {
                Path dir = temporary.newFolder().toPath(), source = dir.resolve("invalid.ubnf");
                Files.writeString(source, text);
                String failure = run(nativeCommand(source, dir.resolve("out")), "", 1);
                assertTrue(row + "\n" + failure, failure.contains(code));
                assertFalse(Files.exists(dir.resolve("out/mapper.rs")));
            }
        }
    }

    @Test
    public void nestedSchemaIncludesPublicShapesAndHidesComparisonLocals() throws Exception {
        for (String name :
                List.of("lists", "private-forward", "private-only-mixed", "shared-contributors")) {
            var row =
                    corpus("generated").asList().stream()
                            .map(JsonElement::getAsJsonObject)
                            .filter(value -> value.get("name").getAsString().equals(name))
                            .findFirst()
                            .orElseThrow();
            String after = row.get("grammar").getAsString();
            // Compare the schema to itself: type constraints must not introduce private AST fields.
            for (String target : List.of("java", "rust")) {
                var report = org.unlaxer.dsl.impact.ApiImpact.compare(target, after, after);
                assertTrue(report.toJson(), report.ok());
                assertTrue(report.changes().isEmpty());
                var pair =
                        report.before().nodes().stream()
                                .filter(node -> node.name().equals("Pair"))
                                .findFirst()
                                .orElseThrow();
                var names = pair.fields().stream().map(field -> field.name()).toList();
                for (var field : pair.fields()) {
                    String expectedType = name.equals("private-forward") ? "String" : target.equals("java") ? "List<String>" : "Vec<String>";
                    assertEquals(name + "/" + target, expectedType, field.type());
                }
                if (name.equals("private-only-mixed")) {
                    var integer = report.before().nodes().stream().filter(node -> node.name().equals("NestedValue.Integer")).findFirst().orElseThrow();
                    assertEquals(target.equals("java") ? "int" : "i32", integer.fields().get(0).type());
                }

                assertEquals(
                        name,
                        name.equals("private-forward")
                                ? List.of("left")
                                : name.equals("private-only-mixed")
                                        ? List.of()
                                        : List.of("left", "right"),
                        names);
            }
            if (Boolean.getBoolean("rustConformance")) {
                Path dir = temporary.newFolder().toPath(), source = dir.resolve("schema.ubnf");
                Files.writeString(source, after);
                var command = new ArrayList<>(nativeCommand(source, dir.resolve("out")));
                command.subList(command.indexOf("generate"), command.size()).clear();
                command.addAll(
                        List.of(
                                "impact",
                                "--target",
                                "rust",
                                "--before",
                                source.toString(),
                                "--after",
                                source.toString()));
                assertEquals(
                        JsonParser.parseString(
                                org.unlaxer.dsl.impact.ApiImpact.compare("rust", after, after)
                                        .toJson()),
                        JsonParser.parseString(run(command, "", 0)));
            }
        }
    }

    private List<String> nativeCommand(Path source, Path output) {
        return List.of(
                "cargo",
                "run",
                "--quiet",
                "--locked",
                "--offline",
                "--manifest-path",
                ROOT.resolve("rust/Cargo.toml").toString(),
                "-p",
                "unlaxer-generator",
                "--bin",
                "unlaxer",
                "--",
                "generate",
                "--target",
                "rust",
                "--grammar",
                source.toString(),
                "--output",
                output.toString());
    }

    private String run(List<String> command, String input, int expected) throws Exception {
        Path output = temporary.newFile().toPath();
        var builder =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile());
        builder.environment().put("CARGO_INCREMENTAL", "0");
        var process = builder.start();
        try {
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(command.toString(), process.waitFor(180, TimeUnit.SECONDS));
            String result = Files.readString(output);
            if (expected == 0) assertEquals(command + "\n" + result, 0, process.exitValue());
            else assertNotEquals(command + "\n" + result, 0, process.exitValue());
            return result;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private JavaFileObject source(String name, String body) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return body;
            }
        };
    }
}
