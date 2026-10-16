package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import com.google.gson.*;

import org.junit.*;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.parser.Parser;

import java.net.*;
import java.nio.file.*;
import java.util.*;

import javax.tools.*;

/** Handwritten values and occurrence spans, independent of generated AST snapshots. */
public class MixedEnumConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Path FIXTURES = Path.of("../spec-corpus/mixed-enums");

    @Before
    public void baseline() {
        org.junit.Assume.assumeTrue("Java21 baseline", Runtime.version().feature() >= 21);
    }

    @Test
    public void javaMixedEnumValuesRetainIdentityAndOccurrences() throws Exception {
        var report =
                new ArrayList<String>(List.of("grammarHash\tinput\tmemo\tdiagnostics\tresult"));
        for (var fixture : fixtures()) {
            var grammar = UBNFMapper.parse(fixture.grammar()).grammars().get(0);
            var units = new ArrayList<JavaFileObject>();
            for (CodeGenerator generator :
                    List.of(
                            new ParserGenerator(),
                            new ASTGenerator(),
                            new MapperGenerator(),
                            new EvaluatorGenerator(21),
                            new DAPGenerator())) {
                var source = generator.generate(grammar);
                units.add(source(source.packageName() + "." + source.className(), source.source()));
            }
            String probe =
                    Files.readString(
                            FIXTURES.resolve(
                                    fixture.root() ? "root-probe.java.txt" : "probe.java.txt"));
            if (!fixture.root() && !EnumSchema.rootEnums(grammar).isEmpty())
                probe =
                        probe.replace(
                                "    private JsonElement value",
                                Files.readString(FIXTURES.resolve("root-methods.java.txt"))
                                        + "    private JsonElement value");
            if (fixture.scalar())
                probe =
                        probe.replace(
                                "node.head().map(this::value).orElse(JsonNull.INSTANCE)",
                                "value(node.head())");
            units.add(
                    source(
                            "org.example.mixedenum.DapProbe",
                            "package org.example.mixedenum; public final class DapProbe extends"
                                + " MixedEnumsDebugAdapter {}"));
            units.add(source("org.example.mixedenum.Probe", probe));
            Path classes = temporary.newFolder().toPath();
            var compiler = ToolProvider.getSystemJavaCompiler();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
                assertTrue(
                        diagnostics.getDiagnostics().toString(),
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
                                .call());
            }
            try (var loader =
                    new URLClassLoader(
                            new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
                var mapper = loader.loadClass("org.example.mixedenum.MixedEnumsMapper");
                var parse = mapper.getMethod("parseWithSourceMap", String.class);
                var parser =
                        (Parser)
                                loader.loadClass("org.example.mixedenum.MixedEnumsParsers")
                                        .getMethod("getRootParser")
                                        .invoke(null);
                var evaluator =
                        loader.loadClass("org.example.mixedenum.Probe")
                                .getMethod(
                                        "run",
                                        loader.loadClass("org.example.mixedenum.MixedEnumsAST"));
                var retained = new ArrayList<Map.Entry<Object, JsonObject>>();
                for (var element : fixture.cases()) {
                    var row = element.getAsJsonObject();
                    String input = row.get("input").getAsString();
                    for (Memoization memo : Memoization.values())
                        for (ParseOptions.Diagnostics diagnosticMode :
                                ParseOptions.Diagnostics.values()) {
                            report.add(
                                    fixture.grammar().hashCode()
                                            + "\t"
                                            + row.get("input")
                                            + "\t"
                                            + memo
                                            + "\t"
                                            + diagnosticMode
                                            + "\tchecked");
                            var options =
                                    ParseOptions.withMemoization(memo)
                                            .withDiagnostics(diagnosticMode);
                            try (var context =
                                    ParseContext.withOptions(
                                            org.unlaxer.StringSource.createRootSource(input),
                                            options)) {
                                var parsed = parser.parse(context);
                                boolean accepted =
                                        parsed.isSucceeded()
                                                && context.getConsumedPosition().value()
                                                        == input.codePointCount(0, input.length());
                                assertEquals(row.toString(), !row.has("failure"), accepted);
                                if (!parsed.isSucceeded()) {
                                    assertEquals(0, context.getConsumedPosition().value());
                                    assertTrue(context.getCurrent().getTokens().isEmpty());
                                    assertFalse(parser.parse(context).isSucceeded());
                                }
                                if (accepted) {
                                    var token =
                                            context.getCurrent().getTokens().stream()
                                                    .filter(t -> t.parser == parser)
                                                    .findFirst()
                                                    .orElseThrow();
                                    Object mapped =
                                            mapper.getMethod(
                                                            "mapParsedTokenWithSourceMap",
                                                            org.unlaxer.Token.class)
                                                    .invoke(null, token);
                                    verify(mapped, row);
                                    var expected = row.deepCopy();
                                    expected.remove("input");
                                    if (expected.has("root"))
                                        trimSpan(expected.getAsJsonArray("root"));
                                    else {
                                        if (!expected.get("head").isJsonNull())
                                            trimSpan(expected.getAsJsonArray("head"));
                                        for (var value : expected.getAsJsonArray("values"))
                                            trimSpan(value.getAsJsonArray());
                                    }
                                    assertEquals(
                                            expected, evaluator.invoke(null, field(mapped, "ast")));
                                    retained.add(Map.entry(mapped, row));
                                }
                            }
                        }
                    if (!row.has("failure")) verifyDap(loader, input, row);
                    if (row.has("failure"))
                        assertThrows(
                                java.lang.reflect.InvocationTargetException.class,
                                () -> parse.invoke(null, input));
                }
                var executor = java.util.concurrent.Executors.newFixedThreadPool(4);
                try {
                    var calls =
                            new ArrayList<
                                    java.util.concurrent.Callable<Map.Entry<Object, JsonObject>>>();
                    for (var element : fixture.cases()) {
                        var row = element.getAsJsonObject();
                        if (!row.has("failure"))
                            calls.add(
                                    () ->
                                            Map.entry(
                                                    parse.invoke(
                                                            null, row.get("input").getAsString()),
                                                    row));
                    }
                    for (var future : executor.invokeAll(calls)) retained.add(future.get());
                } finally {
                    executor.shutdownNow();
                }
                for (var entry : retained) verify(entry.getKey(), entry.getValue());
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/mixed-enum-java.tsv"), report);
    }

    @Test
    public void bothRustHostsExecuteMixedEnumOracle() throws Exception {
        org.junit.Assume.assumeTrue(
                "requires -DrustConformance=true", Boolean.getBoolean("rustConformance"));
        var report = new ArrayList<String>(List.of("grammarHash\tinput\tconfiguration\tresult"));
        for (var fixture : fixtures()) {
            Path repo = Path.of("..").toAbsolutePath().normalize(),
                    dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectories(dir.resolve("generated"));
            var grammar = UBNFMapper.parse(fixture.grammar()).grammars().get(0);
            Path grammarFile = dir.resolve("fixture.ubnf");
            Files.writeString(grammarFile, fixture.grammar());
            Path nativeOut = dir.resolve("native");
            run(
                    List.of(
                            "cargo",
                            "run",
                            "--quiet",
                            "--locked",
                            "--manifest-path",
                            repo.resolve("rust/Cargo.toml").toString(),
                            "-p",
                            "unlaxer-generator",
                            "--bin",
                            "unlaxer",
                            "--",
                            "generate",
                            "--target",
                            "rust",
                            "--grammar",
                            grammarFile.toString(),
                            "--output",
                            nativeOut.toString(),
                            "--dap"),
                    "");
            for (var file :
                    new org.unlaxer.dsl.codegen.rust.RustBackend().generateWithDap(grammar)) {
                assertEquals(
                        file.relativePath(),
                        file.content(),
                        Files.readString(nativeOut.resolve(file.relativePath())));
                Files.writeString(generated.resolve(file.relativePath()), file.content());
            }
            String probe =
                    Files.readString(
                            FIXTURES.resolve(
                                    fixture.root() ? "root-probe.rs.txt" : "probe.rs.txt"));
            if (!fixture.root() && !EnumSchema.rootEnums(grammar).isEmpty())
                probe =
                        probe.replace(
                                "type Output=String;",
                                "type Output=String;\n"
                                        + Files.readString(
                                                FIXTURES.resolve("root-methods.rs.txt")));
            if (fixture.scalar())
                probe =
                        probe.replace("head:Option<&AstValue>", "head:&AstValue")
                                .replace(
                                        "head.map(value).unwrap_or(\"null\".into())",
                                        "value(head)");
            probe += Files.readString(FIXTURES.resolve("dap-probe.rs.txt"));
            Files.writeString(dir.resolve("main.rs"), probe);
            Files.writeString(
                    dir.resolve("Cargo.toml"),
                    """
                    [package]
                    name="mixed-enum-probe"
                    version="0.1.0"
                    edition="2021"
                    [[bin]]
                    name="probe"
                    path="main.rs"
                    [dependencies]
                    unlaxer-runtime={path="%s"}
                    unlaxer-dap={path="%s"}
                    """
                            .formatted(
                                    repo.resolve("rust/unlaxer-runtime"),
                                    repo.resolve("rust/unlaxer-dap")));
            Path target = repo.resolve("rust/target/mixed-enum-probe");
            run(
                    List.of(
                            "cargo",
                            "build",
                            "--quiet",
                            "--offline",
                            "--manifest-path",
                            dir.resolve("Cargo.toml").toString(),
                            "--target-dir",
                            target.toString()),
                    "");
            var corpus = fixture.cases();
            String inputs =
                    String.join(
                                    "\n",
                                    corpus.asList().stream()
                                            .map(
                                                    row ->
                                                            HexFormat.of()
                                                                    .formatHex(
                                                                            row.getAsJsonObject()
                                                                                    .get("input")
                                                                                    .getAsString()
                                                                                    .getBytes(
                                                                                            java.nio
                                                                                                    .charset
                                                                                                    .StandardCharsets
                                                                                                    .UTF_8)))
                                            .toList())
                            + "\n";
            var lines =
                    run(List.of(target.resolve("debug/probe").toString()), inputs).lines().toList();
            assertEquals(corpus.size() * 6, lines.size());
            for (int i = 0; i < lines.size(); i++) {
                report.add(
                        fixture.grammar().hashCode()
                                + "\t"
                                + corpus.get(i / 6).getAsJsonObject().get("input")
                                + "\t"
                                + (i % 6)
                                + "\tchecked");
                var expected = corpus.get(i / 6).getAsJsonObject().deepCopy();
                expected.remove("input");
                if (!expected.has("failure"))
                    expected.add("dap", expectedDap(corpus.get(i / 6).getAsJsonObject()));
                assertEquals(
                        corpus.get(i / 6).toString(),
                        expected,
                        JsonParser.parseString(lines.get(i)));
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.write(Path.of("target/mixed-enum-rust.tsv"), report);
    }

    private JsonArray expectedDap(JsonObject row) {
        var result = new JsonArray();
        String input = row.get("input").getAsString();
        if (row.has("root")) {
            var root = row.getAsJsonArray("root");
            result.add(
                    dapStep(
                            root.get(1).getAsString()
                                    + (root.get(0).getAsString().equals("enum") ? "Node" : ""),
                            root.get(root.size() - 2).getAsInt(),
                            root.get(root.size() - 1).getAsInt()));
        } else {
            result.add(dapStep("Values", 0, input.codePointCount(0, input.length())));
            var children = new JsonArray();
            if (!row.get("head").isJsonNull()) children.add(row.get("head"));
            children.addAll(row.getAsJsonArray("values"));
            for (var value : children) {
                var parts = value.getAsJsonArray();
                if (parts.get(0).getAsString().equals("node"))
                    result.add(
                            dapStep(
                                    parts.get(1).getAsString(),
                                    parts.get(parts.size() - 2).getAsInt(),
                                    parts.get(parts.size() - 1).getAsInt()));
            }
        }
        return result;
    }

    private JsonArray dapStep(String label, int start, int end) {
        var result = new JsonArray();
        result.add(label);
        result.add(start);
        result.add(end);
        return result;
    }

    private void verifyDap(ClassLoader loader, String input, JsonObject row) throws Exception {
        var adapter =
                loader.loadClass("org.example.mixedenum.DapProbe").getConstructor().newInstance();
        var type = adapter.getClass().getSuperclass();
        var source = type.getDeclaredField("sourceContent");
        source.setAccessible(true);
        source.set(adapter, input);
        var collect = type.getDeclaredMethod("collectAstSteps");
        collect.setAccessible(true);
        collect.invoke(adapter);
        var error = type.getDeclaredField("astStepError");
        error.setAccessible(true);
        assertEquals(input, "", error.get(adapter));
        var labels = type.getDeclaredField("astNodeTypes");
        labels.setAccessible(true);
        var spans = type.getDeclaredField("astNodeSpans");
        spans.setAccessible(true);
        var names = (List<?>) labels.get(adapter);
        var positions = (List<?>) spans.get(adapter);
        var actual = new JsonArray();
        for (int i = 0; i < names.size(); i++) {
            int[] position = (int[]) positions.get(i);
            actual.add(dapStep(names.get(i).toString(), position[0], position[1]));
        }
        assertEquals(input, expectedDap(row), actual);
    }

    @Test
    public void javaTypeofUsesUnderlyingEnumIdentity() throws Exception {
        String source =
                """
                grammar MixedEnums {
                  @package: org.example.mixedenum
                  @whitespace: none
                  @enum Mode ::= 'sync' | 'auto';
                  @enum Heat ::= 'hot' | 'cold';
                  Item ::= Mode | Heat | 'text';
                  @root @mapping(Pair,params=[left,right])
                  Root ::= Item @left '=' @typeof(left) Item @right;
                }
                """;
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator :
                List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            var generated = generator.generate(grammar);
            units.add(
                    source(
                            generated.packageName() + "." + generated.className(),
                            generated.source()));
        }
        Path classes = temporary.newFolder().toPath();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var errors = new java.io.StringWriter();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(
                    errors.toString(),
                    compiler.getTask(
                                    errors,
                                    manager,
                                    null,
                                    List.of(
                                            "--release",
                                            "21",
                                            "-classpath",
                                            System.getProperty("java.class.path"),
                                            "-d",
                                            classes.toString()),
                                    null,
                                    units)
                            .call());
        }
        try (var loader =
                new URLClassLoader(
                        new URL[] {classes.toUri().toURL()}, getClass().getClassLoader())) {
            var parse =
                    loader.loadClass("org.example.mixedenum.MixedEnumsMapper")
                            .getMethod("parse", String.class);
            for (String input : List.of("sync=auto", "hot=cold", "text=text"))
                assertNotNull(parse.invoke(null, input));
            for (String input : List.of("sync=hot", "hot=sync", "text=auto", "sync=text")) {
                var error =
                        assertThrows(
                                java.lang.reflect.InvocationTargetException.class,
                                () -> parse.invoke(null, input));
                assertTrue(
                        error.getCause().toString(),
                        error.getCause().getMessage().contains("@typeof constraint violated"));
            }
        }
        assertTrue(org.unlaxer.dsl.PortabilityCheck.check(source).portable());
    }

    @Test
    public void wideningEnumFieldsIsVisibleToImpactConsumers() throws Exception {
        String before =
                Files.readString(FIXTURES.resolve("values.ubnf"))
                        .replace("Item ::= Mode | Heat | Leaf | 'text';", "Item ::= Mode;");
        String after = Files.readString(FIXTURES.resolve("values.ubnf"));
        for (String target : List.of("java", "rust")) {
            var report = org.unlaxer.dsl.impact.ApiImpact.compare(target, before, after);
            assertTrue(report.toJson(), report.ok());
            for (String field : List.of("Values.head", "Values.values"))
                assertTrue(
                        report.toJson(),
                        report.changes().stream()
                                .anyMatch(
                                        change ->
                                                change.kind().equals("FIELD_TYPE_CHANGED")
                                                        && change.subject().equals(field)));
            if (target.equals("rust") && Boolean.getBoolean("rustConformance")) {
                Path directory = temporary.newFolder().toPath();
                Path old = directory.resolve("before.ubnf"), next = directory.resolve("after.ubnf");
                Files.writeString(old, before);
                Files.writeString(next, after);
                String nativeReport =
                        run(
                                List.of(
                                        "cargo",
                                        "run",
                                        "--quiet",
                                        "--locked",
                                        "--manifest-path",
                                        Path.of("../rust/Cargo.toml")
                                                .toAbsolutePath()
                                                .normalize()
                                                .toString(),
                                        "-p",
                                        "unlaxer-generator",
                                        "--bin",
                                        "unlaxer",
                                        "--",
                                        "impact",
                                        "--target",
                                        "rust",
                                        "--before",
                                        old.toString(),
                                        "--after",
                                        next.toString()),
                                "");
                assertEquals(
                        JsonParser.parseString(report.toJson()),
                        JsonParser.parseString(nativeReport));
            }
        }
    }

    private record Fixture(String grammar, JsonArray cases, boolean root) {
        Fixture(String grammar, JsonArray cases) {
            this(grammar, cases, false);
        }

        boolean scalar() {
            return grammar.contains("Root ::= Item @head");
        }
    }

    private List<Fixture> fixtures() throws Exception {
        String source = Files.readString(FIXTURES.resolve("values.ubnf"));
        var corpus =
                JsonParser.parseString(Files.readString(FIXTURES.resolve("cases.json")))
                        .getAsJsonArray();
        var result = new ArrayList<Fixture>();
        for (var item :
                JsonParser.parseString(Files.readString(FIXTURES.resolve("manifest.json")))
                        .getAsJsonArray()) {
            var row = item.getAsJsonObject();
            var cases = new JsonArray();
            for (var index : row.getAsJsonArray("cases")) cases.add(corpus.get(index.getAsInt()));
            result.add(
                    new Fixture(
                            source.replace(
                                    "Item ::= Mode | Heat | Leaf | 'text';",
                                    "Item ::= " + row.get("item").getAsString() + ";"),
                            cases));
        }
        var scalarCases = new JsonArray();
        for (var row : corpus)
            if (row.getAsJsonObject().has("failure")
                    || !row.getAsJsonObject().get("head").isJsonNull()) scalarCases.add(row);
        result.add(
                new Fixture(
                        source.replace("Root ::= [Item] @head", "Root ::= Item @head"),
                        scalarCases));
        var compositeCases = new JsonArray();
        for (var row : corpus)
            if (!row.getAsJsonObject().get("input").getAsString().equals("sync"))
                compositeCases.add(row);
        for (var row :
                JsonParser.parseString(Files.readString(FIXTURES.resolve("root-cases.json")))
                        .getAsJsonArray()) {
            if (row.getAsJsonObject().has("root")) compositeCases.add(row);
        }
        result.add(
                new Fixture(
                        source.replace("@root @mapping(Values", "@mapping(Values")
                                .replace("Root ::= [Item]", "ValuesRoot ::= [Item]")
                                .replace(
                                        "  Item ::=",
                                        "  @root Root ::= ValuesRoot | Mode | Heat | Leaf;\n"
                                                + "  Item ::="),
                        compositeCases));
        String shared = Files.readString(FIXTURES.resolve("shared.ubnf"));
        var sharedCases =
                JsonParser.parseString(Files.readString(FIXTURES.resolve("shared-cases.json")))
                        .getAsJsonArray();
        result.add(new Fixture(shared, sharedCases));
        var sharedLines = new ArrayList<>(shared.lines().toList());
        int first = 0;
        while (!sharedLines.get(first).contains("@mapping(Values")) first++;
        Collections.reverse(sharedLines.subList(first, sharedLines.size() - 1));
        result.add(new Fixture(String.join("\n", sharedLines), sharedCases));
        result.add(
                new Fixture(
                        Files.readString(FIXTURES.resolve("roots.ubnf")),
                        JsonParser.parseString(
                                        Files.readString(FIXTURES.resolve("root-cases.json")))
                                .getAsJsonArray(),
                        true));
        return result;
    }

    private String run(List<String> command, String input) throws Exception {
        Path output = temporary.newFile().toPath();
        var process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            process.getOutputStream()
                    .write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().close();
            assertTrue(
                    command.toString(),
                    process.waitFor(180, java.util.concurrent.TimeUnit.SECONDS));
            String text = Files.readString(output);
            assertEquals(command + "\n" + text, 0, process.exitValue());
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void verify(Object mapped, JsonObject row) throws Exception {
        Object ast = field(mapped, "ast");
        if (row.has("root")) {
            assertEquals(row.toString(), row.get("root"), observe(ast, mapped));
            return;
        }
        Object first = field(ast, "head");
        Optional<?> head = first instanceof Optional<?> optional ? optional : Optional.of(first);
        assertEquals(
                row.toString(),
                row.get("head"),
                head.isEmpty() ? JsonNull.INSTANCE : observe(head.get(), mapped));
        var values = new JsonArray();
        for (var value : (List<?>) field(ast, "values")) values.add(observe(value, mapped));
        assertEquals(row.toString(), row.get("values"), values);
    }

    private void trimSpan(JsonArray value) {
        value.remove(value.size() - 1);
        value.remove(value.size() - 1);
    }

    private JsonArray observe(Object value, Object mapped) throws Exception {
        int[] span =
                (int[])
                        ((Optional<?>)
                                        mapped.getClass()
                                                .getMethod("sourceSpanOf", Object.class)
                                                .invoke(mapped, value))
                                .orElseThrow();
        var result = new JsonArray();
        if (value instanceof String text) {
            result.add("text");
            result.add(text);
        } else if ((value.getClass().getSimpleName().equals("EnumValue")
                || value.getClass().getSimpleName().equals("ModeNode")
                || value.getClass().getSimpleName().equals("HeatNode"))) {
            var item = (Enum<?>) field(value, "value");
            result.add("enum");
            result.add(item.getDeclaringClass().getSimpleName());
            result.add((String) item.getClass().getMethod("text").invoke(item));
        } else {
            result.add("node");
            result.add(value.getClass().getSimpleName());
            result.add((String) field(value, "text"));
        }
        result.add(span[0]);
        result.add(span[1]);
        return result;
    }

    private Object field(Object value, String name) throws Exception {
        return value.getClass().getMethod(name).invoke(value);
    }

    private JavaFileObject source(String name, String text) {
        return new SimpleJavaFileObject(
                URI.create("string:///" + name.replace('.', '/') + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignored) {
                return text;
            }
        };
    }
}
