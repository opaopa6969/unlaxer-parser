package org.unlaxer.dsl;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.google.gson.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.impact.ApiImpact;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.ASTGenerator;
import org.unlaxer.dsl.codegen.EvaluatorGenerator;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Frozen pre-evolution consumers are compiled against both real generated APIs. */
public class ApiImpactConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private record Run(int code, String out, String err) {}
    private record Compilation(boolean ok, String codes, long nanos) {}

    @Test public void reportsLocationsAndRealCompilerObligationsAgree() throws Exception {
        boolean enabled = Boolean.getBoolean("rustConformance");
        if (!enabled) System.out.println("[assumption] ApiImpactConformanceTest requires -DrustConformance=true");
        assumeTrue("requires real Rust compiler and native host", enabled);
        Path cwd = temporary.newFolder().toPath();
        Files.writeString(cwd.resolve("handwritten.rs"), "// do not rewrite\n");
        Run build = run(List.of("cargo", "build", "--locked", "--manifest-path",
            repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), cwd, false);
        assertEquals(build.err(), 0, build.code());
        Path nativeHost = repo.resolve("rust/target/debug/unlaxer");
        invalidReports(nativeHost, cwd);
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        Run library = run(List.of("rustc", "--edition=2021", "--crate-name=unlaxer_runtime", "--crate-type=rlib",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()), cwd, false);
        assertEquals(library.err(), 0, library.code());
        Path fixtures = repo.resolve("spec-corpus/api-impact");
        JsonArray corpus = JsonParser.parseString(Files.readString(fixtures.resolve("cases.json"))).getAsJsonArray();
        List<String> reports = new ArrayList<>(List.of("fixture\tline_endings\ttarget\tchanges\treport_json"));
        List<String> compiles = new ArrayList<>(List.of(
            "fixture\ttarget\tconsumer\tbaseline_ok\tafter_ok\tupdated_consumer_ok\tdiagnostic_codes\tbefore_bytes\tafter_bytes\tbaseline_compile_ns\tafter_compile_ns"));
        for (var entry : corpus) {
            JsonObject fixture = entry.getAsJsonObject();
            String name = fixture.get("name").getAsString();
            String before = Files.readString(fixtures.resolve(fixture.get("before").getAsString()));
            String after = Files.readString(fixtures.resolve(fixture.get("after").getAsString()));
            for (String ending : List.of("LF", "CRLF")) {
                String oldSource = ending.equals("LF") ? before : before.replace("\n", "\r\n");
                String newSource = ending.equals("LF") ? after : after.replace("\n", "\r\n");
                Path oldFile = temporary.newFile().toPath(), newFile = temporary.newFile().toPath();
                Files.writeString(oldFile, oldSource); Files.writeString(newFile, newSource);
                for (String target : List.of("java", "rust")) {
                    String label = name + "/" + ending + "/" + target;
                    ApiImpact.Report report = ApiImpact.compare(target, oldSource, newSource);
                    assertTrue(label + report.toJson(), report.ok());
                    assertEquals(label, strings(fixture.getAsJsonArray(target + "Changes")),
                        report.changes().stream().map(c -> c.kind() + ":" + c.subject()).toList());
                    assertEquals(label, report.toJson(), ApiImpact.compare(target, oldSource, newSource).toJson());
                    locations(oldSource, target, report.before()); locations(newSource, target, report.after());
                    for (var change : report.changes()) {
                        if (change.beforeGenerated() != null) location(generated(target, oldSource), change.beforeGenerated());
                        if (change.afterGenerated() != null) location(generated(target, newSource), change.afterGenerated());
                    }
                    String[] options = {"impact", "--target", target, "--before", oldFile.toString(),
                        "--after", newFile.toString(), "--format", "json"};
                    Run java = javaRun(options);
                    assertEquals(label + java.err(), 0, java.code()); assertEquals("", java.err());
                    JsonElement expected = JsonParser.parseString(report.toJson());
                    assertEquals(label, expected, JsonParser.parseString(java.out()));
                    if (target.equals("rust")) {
                        var args = new ArrayList<>(List.of(nativeHost.toString())); args.addAll(List.of(options));
                        Run rust = run(args, cwd, true);
                        assertEquals(label + rust.err() + rust.out(), 0, rust.code()); assertEquals("", rust.err());
                        assertEquals(label, expected, JsonParser.parseString(rust.out()));
                        assertEquals(label, rust.out(), run(args, cwd, true).out());
                    }
                    reports.add(name + "\t" + ending + "\t" + target + "\t" + report.changes().size() + "\t" + report.toJson());
                }
                assertEquals(oldSource, Files.readString(oldFile)); assertEquals(newSource, Files.readString(newFile));
            }
            for (String target : List.of("java", "rust")) {
                var beforeSchema = ApiImpact.schema(target, before);
                var afterSchema = ApiImpact.schema(target, after);
                Map<String, String> oldGenerated = generated(target, before), newGenerated = generated(target, after);
                for (String consumer : List.of("Semantics", "Consumer")) {
                    String frozen = target.equals("java") ? javaConsumer(beforeSchema, fixture, consumer)
                        : rustConsumer(beforeSchema, fixture, consumer);
                    Compilation baseline = compile(target, oldGenerated, frozen, runtime);
                    Compilation candidate = compile(target, newGenerated, frozen, runtime);
                    String updated = target.equals("java") ? javaConsumer(afterSchema, fixture, consumer)
                        : rustConsumer(afterSchema, fixture, consumer);
                    Compilation repaired = compile(target, newGenerated, updated, runtime);
                    String label = name + "/" + target + "/" + consumer;
                    assertTrue(label + ": baseline must compile, " + baseline.codes(), baseline.ok());
                    assertTrue(label + ": new API with updated consumer must compile, " + repaired.codes(), repaired.ok());
                    boolean expected = fixture.getAsJsonObject("legacy" + consumer).get(target).getAsBoolean();
                    assertEquals(label + ": " + candidate.codes(), expected, candidate.ok());
                    if (!expected) assertFalse(label, candidate.codes().isEmpty());
                    compiles.add(name + "\t" + target + "\t" + consumer + "\t" + baseline.ok() + "\t"
                        + candidate.ok() + "\t" + repaired.ok() + "\t" + candidate.codes() + "\t" + bytes(oldGenerated) + "\t" + bytes(newGenerated)
                        + "\t" + baseline.nanos() + "\t" + candidate.nanos());
                }
            }
        }
        assertEquals("// do not rewrite\n", Files.readString(cwd.resolve("handwritten.rs")));
        try (var files = Files.list(cwd)) { assertEquals("impact must not write files", 1, files.count()); }
        Files.write(Path.of("target/api-impact-conformance.tsv"), reports);
        Files.write(Path.of("target/api-impact-compile.tsv"), compiles);
        assertEquals(1 + corpus.size() * 4, reports.size()); assertEquals(1 + corpus.size() * 4, compiles.size());
    }

    private static List<String> strings(JsonArray array) { return array.asList().stream().map(JsonElement::getAsString).toList(); }
    private void invalidReports(Path binary, Path cwd) throws Exception {
        String valid = "grammar G { @root @mapping(Value, params=[value]) Root ::= 'x' @value; }";
        Path before = temporary.newFile().toPath(), after = temporary.newFile().toPath();
        for (String invalid : List.of("", "grammar Broken {", valid + " trailing",
                "grammar Broken { @root Root ::= Missing; }",
                "grammar Unsupported { @root @doc('unsupported') Root ::= 'x'; }")) {
            for (String side : List.of("before", "after", "both")) {
                Files.writeString(before, side.equals("after") ? valid : invalid);
                Files.writeString(after, side.equals("before") ? valid : invalid);
                String[] options = {"impact", "--target", "rust", "--before", before.toString(), "--after", after.toString()};
                var args = new ArrayList<>(List.of(binary.toString())); args.addAll(List.of(options));
                Run java = javaRun(options), rust = run(args, cwd, true);
                assertEquals(java.out() + java.err(), 3, java.code()); assertEquals(rust.out() + rust.err(), 3, rust.code());
                assertEquals("", java.err()); assertEquals("", rust.err());
                var javaJson = JsonParser.parseString(java.out()).getAsJsonObject();
                var rustJson = JsonParser.parseString(rust.out()).getAsJsonObject();
                for (var json : List.of(javaJson, rustJson)) {
                    assertFalse(json.get("ok").getAsBoolean()); assertFalse(json.get("hasChanges").getAsBoolean());
                    assertTrue(json.getAsJsonArray("changes").isEmpty());
                    assertEquals(side.equals("both") ? 2 : 1, json.getAsJsonArray("diagnostics").size());
                    for (var diagnostic : json.getAsJsonArray("diagnostics")) {
                        assertFalse(diagnostic.getAsJsonObject().get("message").getAsString().isEmpty());
                        diagnostic.getAsJsonObject().remove("message");
                    }
                }
                assertEquals("failure codes/side/remaining schema must match", javaJson, rustJson);
            }
        }
        for (String[] options : List.of(new String[] {"impact"},
                new String[] {"impact", "--target", "rust", "--before", "x", "--after", "y", "--output", "z"},
                new String[] {"impact", "--target", "rust", "--before", "x", "--after", "y", "--format", "xml"},
                new String[] {"impact", "--target", "rust", "--before", "x", "--after", "y", "--after", "z"})) {
            var args = new ArrayList<>(List.of(binary.toString())); args.addAll(List.of(options));
            for (Run result : List.of(javaRun(options), run(args, cwd, true))) {
                assertEquals(2, result.code()); assertEquals("", result.out()); assertFalse(result.err().isEmpty());
            }
        }
        String missing = cwd.resolve("missing.ubnf").toString();
        assertEquals(4, javaRun("impact", "--target", "rust", "--before", missing, "--after", missing).code());
        assertEquals(4, run(List.of(binary.toString(), "impact", "--target", "rust", "--before", missing, "--after", missing), cwd, true).code());
    }
    private static long bytes(Map<String, String> files) {
        return files.values().stream().mapToLong(s -> s.getBytes(StandardCharsets.UTF_8).length).sum();
    }
    private static Map<String, String> generated(String target, String source) {
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        Map<String, String> files = new LinkedHashMap<>();
        if (target.equals("rust")) {
            for (var file : new RustBackend().generate(grammar)) files.put(file.relativePath(), file.content());
        } else {
            for (var file : List.of(new ASTGenerator().generate(grammar), new EvaluatorGenerator(17).generate(grammar)))
                files.put(file.packageName().replace('.', '/') + "/" + file.className() + ".java", file.source());
        }
        return files;
    }
    private static void locations(String source, String target, ApiImpact.Snapshot schema) {
        Map<String, String> files = generated(target, source);
        for (var node : schema.nodes()) {
            assertFalse(node.name(), node.origins().isEmpty()); origins(source, node.origins());
            assertTrue(location(files, node.generated()).contains(node.name()));
            for (var field : node.fields()) {
                String declaration = location(files, field.generated());
                assertTrue(declaration, declaration.contains(field.name())); assertTrue(declaration, declaration.contains(field.type()));
            }
        }
        for (var method : schema.methods()) {
            assertFalse(method.name(), method.origins().isEmpty()); origins(source, method.origins());
            assertTrue(location(files, method.generated()).contains(method.name() + "("));
        }
    }
    private static void origins(String source, List<ApiImpact.Origin> origins) {
        for (var origin : origins) {
            String rule = slice(source, origin.span());
            assertTrue(rule, rule.matches("(?s).*\\b" + origin.rule() + "\\s*::=.*"));
            assertTrue(rule, rule.startsWith("@") || rule.startsWith(origin.rule())); assertTrue(rule, rule.endsWith(";"));
            int utf16Start = source.indexOf(rule);
            assertEquals("origin must use code points, not UTF-16", source.codePointCount(0, utf16Start), origin.span().start());
        }
    }
    private static String location(Map<String, String> files, ApiImpact.Location location) {
        assertTrue(location.path(), files.containsKey(location.path()));
        String source = files.get(location.path()); String declaration = slice(source, location.span());
        assertFalse(location.toString(), declaration.isEmpty());
        int offset = source.offsetByCodePoints(0, location.span().start());
        assertEquals(source.substring(0, offset).chars().filter(c -> c == '\n').count() + 1, location.line());
        assertEquals(source.codePointCount(source.lastIndexOf('\n', offset - 1) + 1, offset) + 1, location.column());
        return declaration;
    }
    private static String slice(String source, ApiImpact.Span span) {
        return source.substring(source.offsetByCodePoints(0, span.start()), source.offsetByCodePoints(0, span.end()));
    }

    private static String javaConsumer(ApiImpact.Snapshot before, JsonObject fixture, String consumer) {
        String ast = before.astType().substring(before.astType().lastIndexOf('.') + 1);
        String evaluator = before.evaluatorType().substring(before.evaluatorType().lastIndexOf('.') + 1);
        StringBuilder source = new StringBuilder("package org.example.impact; import java.util.*; import ")
            .append(before.astType()).append(".*;\nclass Legacy");
        if (consumer.equals("Semantics")) {
            source.append(" extends ").append(evaluator).append("<Integer> {\n");
            for (var method : before.methods()) {
                source.append("@Override protected ").append(method.returnType().replace("T", "Integer")).append(' ')
                    .append(method.name()).append('(').append(String.join(",", method.parameters().stream()
                        .map(p -> p.type() + " " + p.name()).toList())).append(") { return 0; }\n");
            }
        } else {
            var node = before.nodes().stream().filter(n -> n.name().equals(fixture.get("node").getAsString())).findFirst().orElseThrow();
            source.append(" { static void consume(").append(ast).append('.').append(node.name()).append(" node) {\n");
            if (fixture.get("consumer").getAsString().equals("field")) {
                var field = node.fields().stream().filter(f -> f.name().equals("value")).findFirst().orElseThrow();
                source.append(field.type()).append(" value = node.value();\n");
            } else {
                source.append("new ").append(ast).append('.').append(node.name()).append('(')
                    .append(String.join(",", node.fields().stream().map(f -> javaValue(f.type())).toList())).append(");\n");
            }
            source.append("}\n");
        }
        return source.append("}\n").toString();
    }
    private static String javaValue(String type) {
        return switch (type) { case "int", "long", "double", "float", "short", "byte" -> "0";
            case "String" -> "\"text\""; case "boolean" -> "false"; default -> "null"; };
    }
    private static String rustConsumer(ApiImpact.Snapshot before, JsonObject fixture, String consumer) {
        StringBuilder source = new StringBuilder("#![allow(dead_code, unused_imports, unused_variables)]\nmod generated;\n"
            + "use generated::ast::*; use unlaxer_runtime::Span;\n");
        if (consumer.equals("Semantics")) {
            source.append("struct Legacy; impl generated::evaluator::Semantics for Legacy { type Output = ();\n");
            for (var method : before.methods()) source.append("fn ").append(method.name()).append('(')
                .append(String.join(",", method.parameters().stream().map(p -> p.name().equals("self")
                    ? "&mut self" : "r#" + p.name() + ": " + p.type()).toList())).append(") -> Self::Output { () }\n");
            source.append("}\n");
        } else {
            var node = before.nodes().stream().filter(n -> n.name().equals(fixture.get("node").getAsString())).findFirst().orElseThrow();
            if (fixture.get("consumer").getAsString().equals("field")) {
                var field = node.fields().stream().filter(f -> f.name().equals("value")).findFirst().orElseThrow();
                source.append("fn consume(node: &Ast) { match node { Ast::r#").append(node.name())
                    .append(" { r#value, .. } => { let _: &").append(field.type()).append(" = value; }, _ => {} } }\n");
            } else {
                source.append("fn consume() { let _ = Ast::r#").append(node.name()).append(" { span: Span { start: 0, end: 0 },")
                    .append(String.join(",", node.fields().stream().map(f -> "r#" + f.name() + ": Default::default()").toList()))
                    .append(" }; }\n");
            }
        }
        return source.append("fn main() {}\n").toString();
    }

    private Compilation compile(String target, Map<String, String> generated, String legacy, Path runtime) throws Exception {
        Path directory = temporary.newFolder().toPath(); long started = System.nanoTime();
        if (target.equals("java")) {
            var compiler = ToolProvider.getSystemJavaCompiler();
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            var sources = new LinkedHashMap<>(generated); sources.put("org/example/impact/Legacy.java", legacy);
            List<JavaFileObject> units = new ArrayList<>();
            for (var source : sources.entrySet()) units.add(new SimpleJavaFileObject(URI.create("string:///" + source.getKey()),
                    JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return source.getValue(); }
            });
            try (var files = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                boolean ok = compiler.getTask(new StringWriter(), files, diagnostics,
                    List.of("--release", "17", "-proc:none", "-d", directory.toString()), null, units).call();
                String codes = String.join(",", diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR).map(javax.tools.Diagnostic::getCode).distinct().sorted().toList());
                return new Compilation(ok, codes, System.nanoTime() - started);
            }
        }
        for (var file : generated.entrySet()) {
            Path path = directory.resolve("generated").resolve(file.getKey()); Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        Files.writeString(directory.resolve("main.rs"), legacy);
        Run result = run(List.of("rustc", "--edition=2021", "--error-format=json", "--emit=metadata", "--extern",
            "unlaxer_runtime=" + runtime, directory.resolve("main.rs").toString(), "-o", directory.resolve("probe.rmeta").toString()), directory, false);
        List<String> codes = new ArrayList<>();
        for (String line : result.err().lines().toList()) {
            JsonObject diagnostic = JsonParser.parseString(line).getAsJsonObject();
            if (diagnostic.has("level") && diagnostic.get("level").getAsString().equals("error") && !diagnostic.get("code").isJsonNull())
                codes.add(diagnostic.getAsJsonObject("code").get("code").getAsString());
        }
        return new Compilation(result.code() == 0, String.join(",", codes.stream().distinct().sorted().toList()), System.nanoTime() - started);
    }
    private static Run javaRun(String... options) throws Exception {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream(); int code;
        try (var stdout = new PrintStream(out, true, StandardCharsets.UTF_8); var stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = CodegenMain.run(options, stdout, stderr);
        }
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
    private Run run(List<String> command, Path cwd, boolean noJava) throws Exception {
        Path stdout = temporary.newFile().toPath(), stderr = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        if (noJava) { builder.environment().put("PATH", ""); builder.environment().put("JAVA_HOME", "/nonexistent-java"); }
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            assertTrue("timeout: " + command, process.waitFor(60, TimeUnit.SECONDS));
            return new Run(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
