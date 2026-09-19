package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Opt-in locally, mandatory in Rust CI. Runs both implementations; no mocked compiler failures. */
public class RustConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String PACKAGE = "org.example.evolution.";
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    @Test public void capturePlacementAndTransparentCollectionsAreExecutable() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        record Case(String body, String extra, String input, String field) {}
        String item0 = "{\"type\":\"Item\",\"span\":[0,1],\"fields\":{}}";
        String item1 = "{\"type\":\"Item\",\"span\":[1,2],\"fields\":{}}";
        String itemRule = "@mapping(Item) Item ::= 'x';";
        for (Case example : List.of(
            new Case("[ 'x' @value ]", "", "", "null"),
            new Case("[ 'x' @value ]", "", "x", "\"x\""),
            new Case("('x' @value | 'y')", "", "y", "null"),
            new Case("{ 'x' @value }", "", "xx", "[\"x\",\"x\"]"),
            new Case("'x' @value 'y' @value", "", "xy", "[\"x\",\"y\"]"),
            new Case("{ ('x' @value 'y') }", "", "xyxy", "[\"x\",\"x\"]"),
            new Case("Parts @value", "Parts ::= { Item }; " + itemRule, "xx", "[" + item0 + "," + item1 + "]"),
            new Case("Maybe @value", "Maybe ::= [ Item ]; " + itemRule, "", "null"),
            new Case("Maybe @value", "Maybe ::= [ Item ]; " + itemRule, "x", item0),
            new Case("(Item Item) @value", itemRule, "xx", "[" + item0 + "," + item1 + "]"),
            new Case("('x' 'y') @value", "", "xy", "\"xy\""),
            new Case("'x'{0} @value", "", "", "[]"),
            new Case("'x'{2} @value", "", "xx", "[\"x\",\"x\"]"))) {
            var grammar = UBNFMapper.parse("grammar G { @root @mapping(Case, params=[value]) Root ::= " + example.body() + "; " + example.extra() + " }")
                .grammars().get(0);
            Path dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(dir.resolve("generated"));
            for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
            Files.writeString(dir.resolve("main.rs"), """
                mod generated;
                use std::io::{self, BufRead};
                fn main() {
                    let input = io::stdin().lock().lines().next().unwrap().unwrap();
                    let tree = generated::parser::parse_tree(&input).unwrap();
                    let ast = generated::mapper::map(&tree).unwrap();
                    drop(tree);
                    println!("{}", ast.canonical_json());
                }
                """);
            success(rustCompile(dir, library));
            var actual = run(List.of(dir.resolve("probe").toString()), example.input() + "\n"); success(actual);
            String expected = "{\"type\":\"Case\",\"span\":[0," + example.input().length() + "],\"fields\":{\"value\":" + example.field() + "}}";
            assertEquals(example.toString(), JsonParser.parseString(expected), JsonParser.parseString(actual.output()));
            if (example.body().equals("[ 'x' @value ]")) {
                Files.writeString(dir.resolve("main.rs"), """
                    mod generated;
                    struct Stale;
                    impl generated::evaluator::Semantics for Stale {
                        type Output = ();
                        fn eval_case(&mut self, _: &str, _: unlaxer_runtime::Span) {}
                    }
                    fn main() {}
                    """);
                var stale = rustCompile(dir, library);
                assertNotEquals(stale.output(), 0, stale.code());
                assertTrue(stale.output(), stale.output().contains("E0053"));
            }
        }
    }

    @Test public void cardinalityPreservesOptionalListsSpansAndEvaluation() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        Path fixtures = repo.resolve("unlaxer-dsl/src/test/resources/cardinality");
        String source = Files.readString(fixtures.resolve("Cardinality.ubnf"));
        var corpus = JsonParser.parseString(Files.readString(fixtures.resolve("corpus.json"))).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of("mode\tinput_json\tjava\trust"));
        for (String mode : List.of("zero", "plus", "bounded", "unbounded", "separated")) {
            String quantifier = switch (mode) {
                case "zero" -> "{ Item }";
                case "plus" -> "Item+";
                case "bounded" -> "Item{1,2}";
                case "unbounded" -> "Item{1,}";
                default -> "Item % ','";
            };
            var grammar = UBNFMapper.parse(source.replace("{ Item }", quantifier)).grammars().get(0);
            Path dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(dir.resolve("generated"));
            for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
            Files.writeString(dir.resolve("main.rs"), Files.readString(fixtures.resolve("probe.rs.txt")));
            success(rustCompile(dir, library));
            var actual = run(List.of(dir.resolve("probe").toString()), String.join("\n", corpus.asList().stream()
                .map(row -> row.getAsJsonObject().get("input").getAsString()).toList()) + "\n");
            success(actual);
            var lines = actual.output().lines().toList();
            assertEquals(corpus.size(), lines.size());
            try (var loader = compileJava(grammar, List.of())) {
                var mapper = loader.loadClass("org.example.cardinality.CardinalityMapper");
                for (int i = 0; i < corpus.size(); i++) {
                    var row = corpus.get(i).getAsJsonObject();
                    String input = row.get("input").getAsString();
                    String context = mode + " " + input;
                    var rust = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    boolean accepted = row.getAsJsonArray("modes").asList().contains(new JsonPrimitive(mode));
                    assertEquals(context, accepted, rust.get("ok").getAsBoolean());
                    var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, input);
                    assertEquals(context + " Java", accepted, diagnostic.isEmpty());
                    JsonElement java = JsonNull.INSTANCE;
                    if (accepted) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, input);
                        java = canonical(mapped.getClass().getMethod("ast").invoke(mapped), mapped);
                        assertEquals(context + " Rust oracle", cardinalityAst(row.getAsJsonObject("expected"), input, false), rust.get("ast"));
                        // Known Java capture-selection bugs, tracked separately; never copy them into Rust.
                        assertEquals(context + " Java known difference", cardinalityAst(row.getAsJsonObject("javaKnown"), input, !mode.equals("zero")), java);
                        assertNotEquals(context + " Java capture bug must remain explicit", java, rust.get("ast"));
                        assertEquals(context, row.get("value").getAsDouble(), rust.get("value").getAsDouble(), 0.0);
                    }
                    report.add(mode + "\t" + row.get("input") + "\t" + java + "\t" + rust);
                }
            }
        }
        Files.write(Path.of("target/rust-cardinality.tsv"), report, StandardCharsets.UTF_8);
    }

    private JsonObject cardinalityAst(JsonObject expected, String input, boolean emptyJavaList) {
        var fields = new JsonObject();
        fields.add("head", cardinalityItem(expected.get("head")));
        var items = new JsonArray();
        if (!emptyJavaList) for (var item : expected.getAsJsonArray("items")) items.add(cardinalityItem(item));
        fields.add("values", items);
        fields.add("tail", expected.get("tail"));
        var flags = new JsonArray();
        expected.get("flags").getAsString().chars().forEach(c -> flags.add(String.valueOf((char) c)));
        fields.add("flags", flags);
        var ast = new JsonObject();
        ast.addProperty("type", "Container");
        var span = new JsonArray(); span.add(0); span.add(input.codePointCount(0, input.length()));
        ast.add("span", span); ast.add("fields", fields);
        return ast;
    }

    private JsonElement cardinalityItem(JsonElement description) {
        if (description.isJsonNull()) return description;
        var item = description.getAsJsonArray();
        var ast = new JsonObject(); ast.addProperty("type", "Item");
        var span = new JsonArray(); span.add(item.get(1)); span.add(item.get(2)); ast.add("span", span);
        var fields = new JsonObject(); fields.add("value", item.get(0)); ast.add("fields", fields);
        return ast;
    }

    @Test public void diagnosticEdgeCasesIncludeAnExplicitBackendDifference() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        var grammar = UBNFMapper.parse(fixture("3/Evolution.ubnf")).grammars().get(0);
        Path dir = temporary.newFolder().toPath();
        Path generated = Files.createDirectory(dir.resolve("generated"));
        for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
        Files.writeString(dir.resolve("main.rs"), """
            mod generated;
            use std::io::{self, BufRead};
            fn main() {
                for line in io::stdin().lock().lines() {
                    let error = generated::parser::parse_tree_detailed(&line.unwrap()).unwrap_err();
                    println!("{}", error.canonical_json());
                }
            }
            """);
        success(rustCompile(dir, library));
        var corpus = JsonParser.parseString(fixture("diagnostics.json")).getAsJsonArray();
        var actual = run(List.of(dir.resolve("probe").toString()), String.join("\n", corpus.asList().stream()
            .map(row -> row.getAsJsonObject().get("input").getAsString()).toList()) + "\n");
        success(actual);
        var lines = actual.output().lines().toList();
        assertEquals(corpus.size(), lines.size());
        var report = new ArrayList<>(List.of("input_json\tjava_diagnostic\trust_diagnostic"));
        try (var loader = compileJava(grammar, 3)) {
            for (int i = 0; i < corpus.size(); i++) {
                var row = corpus.get(i).getAsJsonObject();
                String input = row.get("input").getAsString();
                var java = javaDiagnostic(loader, input);
                var rust = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                report.add(row.get("input") + "\t" + java + "\t" + rust);
                assertEquals(input, row.get("kind"), java.get("kind"));
                assertEquals(input, row.get("kind"), rust.get("kind"));
                assertEquals(input + " Java", row.get("javaOffset"), java.get("offset"));
                assertEquals(input + " Rust", row.get("rustOffset"), rust.get("offset"));
                assertFalse(input, java.getAsJsonArray("expected").isEmpty());
                assertFalse(input, rust.getAsJsonArray("expected").isEmpty());
                if (row.get("kind").getAsString().equals("trailing_input")) {
                    assertEquals(input, java.get("expected"), rust.get("expected"));
                }
            }
        }
        Files.write(Path.of("target/rust-diagnostics-edge.tsv"), report, StandardCharsets.UTF_8);
    }

    @Test public void sameGrammarPreservesAstSpansResultsAndEvolutionObligations() throws Exception {
        assumeTrue("enable with -DrustConformance=true (requires rustc)", Boolean.getBoolean("rustConformance"));
        Path library = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        success(run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
            repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", library.toString()), ""));
        var corpus = JsonParser.parseString(fixture("conformance.json")).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of("stage\tcases\taccepted\tevaluated\tstale_dispatch\tmissing_semantics"));
        List<String> diagnosticReport = new ArrayList<>(List.of("stage\tinput_json\tjava_diagnostic\trust_diagnostic"));
        String previousDispatch = null;
        for (int stage = 0; stage < 4; stage++) {
            var grammar = UBNFMapper.parse(fixture(stage + "/Evolution.ubnf")).grammars().get(0);
            Path dir = temporary.newFolder().toPath();
            Path generated = Files.createDirectory(dir.resolve("generated"));
            for (var file : new RustBackend().generate(grammar)) Files.writeString(generated.resolve(file.relativePath()), file.content());
            String dispatch = Files.readString(generated.resolve("evaluator.rs"));
            if (stage >= 2) {
                Files.writeString(generated.resolve("evaluator.rs"), previousDispatch);
                Files.writeString(dir.resolve("main.rs"), "mod generated; fn main() {}\n");
                var stale = rustCompile(dir, library);
                assertNotEquals(stale.output(), 0, stale.code());
                assertTrue(stale.output(), stale.output().contains("E0004"));
                Files.writeString(generated.resolve("evaluator.rs"), dispatch);
                Files.writeString(dir.resolve("semantics.rs"), semantics(stage - 1));
                Files.writeString(dir.resolve("main.rs"), "mod generated; mod semantics; fn main() {}\n");
                var missing = rustCompile(dir, library);
                assertNotEquals(missing.output(), 0, missing.code());
                assertTrue(missing.output(), missing.output().contains("E0046"));
                assertTrue(missing.output(), missing.output().contains(stage == 2 ? "eval_negation" : "eval_conditional"));
            }
            Files.writeString(dir.resolve("main.rs"), Files.readString(repo.resolve("rust/examples/evolution/src/main.rs"))
                .replace("use unlaxer_evolution_example::{generated, semantics::Calculator};",
                    "mod generated; mod semantics; use semantics::Calculator;"));
            if (stage == 1) {
                Files.writeString(dir.resolve("semantics.rs"), semantics(0));
                success(rustCompile(dir, library));
                var negative = run(List.of(dir.resolve("probe").toString()), "2*3\n");
                success(negative);
                assertTrue(JsonParser.parseString(negative.output()).getAsJsonObject().get("evaluationError").getAsBoolean());
            }
            Files.writeString(dir.resolve("semantics.rs"), semantics(stage));
            success(rustCompile(dir, library));
            String input = String.join("\n", corpus.asList().stream().map(JsonElement::getAsString).toList()) + "\n";
            var actual = run(List.of(dir.resolve("probe").toString()), input);
            success(actual);
            var lines = actual.output().lines().toList();
            assertEquals(corpus.size(), lines.size());
            int accepted = 0, evaluated = 0;
            try (var loader = compileJava(grammar, stage)) {
                for (int i = 0; i < corpus.size(); i++) {
                    String source = corpus.get(i).getAsString();
                    var expected = javaResult(loader, source);
                    var rust = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    String context = "stage=" + stage + " input=" + source;
                    assertEquals(context, expected.get("ok"), rust.get("ok"));
                    if (expected.get("ok").getAsBoolean()) {
                        accepted++;
                        assertEquals(context, expected.get("ast"), rust.get("ast"));
                        assertEquals(context, expected.get("evaluationError"), rust.get("evaluationError"));
                        if (expected.has("value")) {
                            evaluated++;
                            assertEquals(context, expected.get("value").getAsDouble(), rust.get("value").getAsDouble(), 1e-12);
                        }
                    } else {
                        int offset = rust.get("offset").getAsInt();
                        assertTrue(context, offset >= 0 && offset <= source.codePointCount(0, source.length()));
                        assertFalse(context, rust.getAsJsonArray("expected").isEmpty());
                        var javaDiagnostic = expected.getAsJsonObject("diagnostic");
                        var rustDiagnostic = rust.getAsJsonObject("diagnostic");
                        diagnosticReport.add(stage + "\t" + corpus.get(i) + "\t" + javaDiagnostic + "\t" + rustDiagnostic);
                        assertEquals(context, javaDiagnostic.get("kind"), rustDiagnostic.get("kind"));
                        assertEquals(context, javaDiagnostic.get("offset"), rustDiagnostic.get("offset"));
                        if (javaDiagnostic.get("kind").getAsString().equals("trailing_input")) {
                            assertEquals(context, javaDiagnostic.get("expected"), rustDiagnostic.get("expected"));
                        }
                    }
                }
            }
            report.add(stage + "\t" + corpus.size() + "\t" + accepted + "\t" + evaluated + "\t"
                + (stage >= 2 ? "E0004\tE0046" : "not-applicable\tnot-applicable"));
            previousDispatch = dispatch;
        }
        Files.write(Path.of("target/rust-conformance.tsv"), report, StandardCharsets.UTF_8);
        Files.write(Path.of("target/rust-diagnostics.tsv"), diagnosticReport, StandardCharsets.UTF_8);
        report.forEach(System.out::println);
        // Committed standalone example must exactly match the generator, without requiring Java to run.
        var finalGrammar = UBNFMapper.parse(fixture("3/Evolution.ubnf")).grammars().get(0);
        for (var file : new RustBackend().generate(finalGrammar)) assertEquals(file.relativePath(), file.content(),
            Files.readString(repo.resolve("rust/examples/evolution/src/generated").resolve(file.relativePath())));
    }

    private String fixture(String path) throws Exception {
        try (var input = getClass().getResourceAsStream("/evolution/" + path)) {
            assertNotNull(path, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private URLClassLoader compileJava(org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl grammar, int stage) throws Exception {
        return compileJava(grammar, List.of(new GeneratedSource("org.example.evolution", "Calculator", fixture(stage + "/Calculator.java.txt"))));
    }

    private URLClassLoader compileJava(org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl grammar, List<GeneratedSource> extra) throws Exception {
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator())) {
            sources.add(generator.generate(grammar));
        }
        sources.addAll(extra);
        var units = sources.stream().map(s -> new SimpleJavaFileObject(
            URI.create("string:///" + s.packageName().replace('.', '/') + "/" + s.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return s.source(); }
            }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                null, units).call();
            assertTrue(diagnostics.getDiagnostics().toString(), compiled);
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }

    private JsonObject javaResult(ClassLoader loader, String source) throws Exception {
        var result = new JsonObject();
        Object mapped;
        try { mapped = loader.loadClass(PACKAGE + "EvolutionMapper").getMethod("parseWithSourceMap", String.class).invoke(null, source); }
        catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof IllegalArgumentException)) throw error;
            result.addProperty("ok", false);
            result.add("diagnostic", javaDiagnostic(loader, source));
            return result;
        }
        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
        assertTrue(((Optional<?>) loader.loadClass(PACKAGE + "EvolutionMapper")
            .getMethod("diagnose", String.class).invoke(null, source)).isEmpty());
        result.addProperty("ok", true);
        result.add("ast", canonical(ast, mapped));
        Object calculator = loader.loadClass(PACKAGE + "Calculator").getConstructor().newInstance();
        try {
            double value = (Double) calculator.getClass().getMethod("eval", loader.loadClass(PACKAGE + "EvolutionAST")).invoke(calculator, ast);
            if (Double.isFinite(value)) result.addProperty("value", value);
            else result.addProperty("evaluationError", true);
        } catch (InvocationTargetException error) {
            if (!(error.getCause() instanceof IllegalArgumentException)) throw error;
            result.addProperty("evaluationError", true);
        }
        return result;
    }

    private JsonObject javaDiagnostic(ClassLoader loader, String source) throws Exception {
        Object diagnostic = ((Optional<?>) loader.loadClass(PACKAGE + "EvolutionMapper")
            .getMethod("diagnose", String.class).invoke(null, source)).orElseThrow();
        var json = new JsonObject();
        for (var component : diagnostic.getClass().getRecordComponents()) {
            json.add(component.getName(), new Gson().toJsonTree(component.getAccessor().invoke(diagnostic)));
        }
        return json;
    }

    private JsonObject canonical(Object ast, Object mapped) throws Exception {
        var result = new JsonObject();
        result.addProperty("type", ast.getClass().getSimpleName());
        int[] span = (int[]) ((Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, ast)).orElseThrow();
        var position = new JsonArray(); position.add(span[0]); position.add(span[1]); result.add("span", position);
        var fields = new JsonObject();
        for (var component : ast.getClass().getRecordComponents()) {
            Object value = component.getAccessor().invoke(ast);
            fields.add(component.getName(), canonicalValue(value, mapped));
        }
        result.add("fields", fields);
        return result;
    }

    private JsonElement canonicalValue(Object value, Object mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Optional<?> optional) return canonicalValue(optional.orElse(null), mapped);
        if (value instanceof List<?> list) {
            JsonArray result = new JsonArray();
            for (Object item : list) result.add(canonicalValue(item, mapped));
            return result;
        }
        return canonical(value, mapped);
    }

    private record ProcessResult(int code, String output) {}
    private ProcessResult run(List<String> command, String input) throws Exception {
        Path log = temporary.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try (var stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            assertTrue(command.toString(), process.waitFor(45, TimeUnit.SECONDS));
            return new ProcessResult(process.exitValue(), Files.readString(log));
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private ProcessResult rustCompile(Path directory, Path library) throws Exception {
        return run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + library,
            directory.resolve("main.rs").toString(), "-o", directory.resolve("probe").toString()), "");
    }
    private void success(ProcessResult result) { assertEquals(result.output(), 0, result.code()); }

    private String semantics(int stage) {
        return """
            use crate::generated::{ast::Ast, evaluator::{evaluate, Semantics}};
            use unlaxer_runtime::Span;
            pub struct Calculator;
            impl Semantics for Calculator {
                type Output = Result<f64, String>;
                fn eval_number(&mut self, value: &str, _: Span) -> Self::Output {
                    value.parse::<f64>().map_err(|error| error.to_string())
                }
                fn eval_binary(&mut self, left: &Ast, op: &str, right: &Ast, _: Span) -> Self::Output {
                    let left = evaluate(left, self)?; let right = evaluate(right, self)?;
                    match op { "+" => Ok(left + right),
            """ + (stage >= 1 ? "\"*\" => Ok(left * right),\n" : "") + "_ => Err(op.to_owned()) } }\n"
            + (stage >= 2 ? """
                fn eval_negation(&mut self, value: &Ast, _: Span) -> Self::Output { Ok(-evaluate(value, self)?) }
                """ : "")
            + (stage >= 3 ? """
                fn eval_conditional(&mut self, condition: &Ast, then_expr: &Ast, else_expr: &Ast, _: Span) -> Self::Output {
                    if evaluate(condition, self)? != 0.0 { evaluate(then_expr, self) } else { evaluate(else_expr, self) }
                }
                """ : "") + "}\n";
    }
}
