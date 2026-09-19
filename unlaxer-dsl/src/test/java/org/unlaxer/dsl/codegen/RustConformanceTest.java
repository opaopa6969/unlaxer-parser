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
        var sources = new ArrayList<GeneratedSource>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator(), new EvaluatorGenerator())) {
            sources.add(generator.generate(grammar));
        }
        sources.add(new GeneratedSource("org.example.evolution", "Calculator", fixture(stage + "/Calculator.java.txt")));
        var units = sources.stream().map(s -> new SimpleJavaFileObject(
            URI.create("string:///" + s.packageName().replace('.', '/') + "/" + s.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return s.source(); }
            }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--enable-preview", "--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                null, units).call());
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
            if (value instanceof String text) fields.addProperty(component.getName(), text);
            else fields.add(component.getName(), canonical(value, mapped));
        }
        result.add("fields", fields);
        return result;
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
