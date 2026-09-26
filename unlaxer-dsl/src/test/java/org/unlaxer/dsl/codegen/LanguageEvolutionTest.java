package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;

/** Reproducible, bounded change experiment. Times are machine time, never developer effort. */
public class LanguageEvolutionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String PACKAGE = "org.example.evolution";
    private static final List<CodeGenerator> GENERATORS = List.of(new ParserGenerator(), new ASTGenerator(),
        new MapperGenerator(), GeneratedJavaRelease.evaluatorGenerator(), new LSPGenerator(), new LSPLauncherGenerator(),
        new DAPGenerator(), new DAPLauncherGenerator());

    private String fixture(int stage, String file) throws Exception {
        try (var input = getClass().getResourceAsStream("/evolution/" + stage + "/" + file)) {
            assertNotNull(file, input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private List<GeneratedSource> generate(int stage) throws Exception {
        var grammar = UBNFMapper.parse(fixture(stage, "Evolution.ubnf")).grammars().get(0);
        return GENERATORS.stream().map(g -> g.generate(grammar)).toList();
    }

    private record Compilation(boolean success, String diagnostics, Path directory) {}

    private Compilation compile(List<GeneratedSource> generated, String calculator, String probe) throws Exception {
        var sources = new ArrayList<>(generated);
        if (calculator != null) sources.add(new GeneratedSource(PACKAGE, "Calculator", calculator));
        if (probe != null) sources.add(new GeneratedSource(PACKAGE, "Probe", probe));
        var units = sources.stream().map(s -> new SimpleJavaFileObject(
            URI.create("string:///" + s.packageName().replace('.', '/') + "/" + s.className() + ".java"),
            JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return s.source(); }
            }).toList();
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path directory = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                List.of("--release", GeneratedJavaRelease.EVALUATOR_RELEASE_OPTION, "-classpath", System.getProperty("java.class.path"),
                    "-d", directory.toString()), null, units).call();
            String text = diagnostics.getDiagnostics().stream()
                .map(d -> d.getCode() + ": " + d.getMessage(Locale.ROOT)).reduce("", (a, b) -> a + "\n" + b);
            return new Compilation(success, text, directory);
        }
    }

    private URLClassLoader load(Compilation result) throws Exception {
        assertTrue(result.diagnostics(), result.success());
        return new URLClassLoader(new URL[]{result.directory().toUri().toURL()}, getClass().getClassLoader());
    }

    @Test
    public void evolutionDetectsMissingNodesButNotStringOperators() throws Exception {
        String[] inputs = {"2+3", "2*3", "neg(2*3)", "if(0,neg(2*3),4+5)"};
        double[] expected = {5, 6, -6, 9};
        List<String> report = new ArrayList<>();
        report.add("stage\thandwritten_files_changed\tlines_added\tlines_removed\tgenerated_files_changed\tgenerated_files\tmachine_ms");
        List<GeneratedSource> previous = null;
        for (int stage = 0; stage < 4; stage++) {
            long start = System.nanoTime();
            var generated = generate(stage);
            if (stage >= 2) {
                var stale = new ArrayList<>(generated);
                stale.set(3, previous.get(3)); // new AST + old dispatch
                var staleResult = compile(stale, null, null);
                if (GeneratedJavaRelease.sealedSwitchDispatch()) {
                    assertFalse(staleResult.diagnostics(), staleResult.success());
                    assertTrue(staleResult.diagnostics(), staleResult.diagnostics().contains("compiler.err.not.exhaustive"));
                } else {
                    // --java-release 17: instanceof dispatch has no compile-time exhaustiveness (#311);
                    // EvaluatorVariantRuntimeTest checks that the unknown node fails at run time.
                    assertTrue(staleResult.diagnostics(), staleResult.success());
                }
                var missing = compile(generated, fixture(stage - 1, "Calculator.java.txt"), null);
                assertFalse(missing.diagnostics(), missing.success());
                assertTrue(missing.diagnostics(), missing.diagnostics().contains(stage == 2 ? "evalNegation" : "evalConditional"));
                assertTrue(missing.diagnostics(), missing.diagnostics().contains("compiler.err.does.not.override.abstract"));
            }
            if (stage == 1) {
                var old = compile(generated, fixture(0, "Calculator.java.txt"), null);
                try (var loader = load(old)) {
                    try {
                        evaluate(loader, inputs[stage]);
                        fail("String-valued operators are not exhaustiveness checked");
                    } catch (java.lang.reflect.InvocationTargetException error) {
                        assertTrue(error.getCause() instanceof IllegalArgumentException);
                    }
                }
            }
            var compiled = compile(generated, fixture(stage, "Calculator.java.txt"), PROBE);
            try (var loader = load(compiled)) {
                for (int earlier = 0; earlier <= stage; earlier++) {
                    assertEquals(expected[earlier], evaluate(loader, inputs[earlier]), 0.0);
                }
                loader.loadClass(PACKAGE + ".Probe").getMethod("check", String.class).invoke(null, inputs[stage]);
            }
            int added = 0, removed = 0, files = 0, generatedChanged = 0;
            for (String name : List.of("Evolution.ubnf", "Calculator.java.txt")) {
                String before = stage == 0 ? "" : fixture(stage - 1, name);
                String after = fixture(stage, name);
                if (!before.equals(after)) files++;
                int[] delta = lineDelta(before, after);
                added += delta[0]; removed += delta[1];
            }
            for (int i = 0; i < generated.size(); i++) {
                if (previous == null || !previous.get(i).source().equals(generated.get(i).source())) generatedChanged++;
            }
            report.add(stage + "\t" + files + "\t" + added + "\t" + removed + "\t" + generatedChanged + "\t"
                + generated.size() + "\t" + (System.nanoTime() - start) / 1_000_000);
            previous = generated;
        }
        Path output = Path.of("target/language-evolution.tsv");
        Files.createDirectories(output.getParent());
        Files.write(output, report, StandardCharsets.UTF_8);
        report.forEach(System.out::println);
    }

    private double evaluate(ClassLoader loader, String input) throws Exception {
        var ast = loader.loadClass(PACKAGE + ".EvolutionMapper").getMethod("parse", String.class).invoke(null, input);
        var calculator = loader.loadClass(PACKAGE + ".Calculator").getConstructor().newInstance();
        return (Double) calculator.getClass().getMethod("eval", loader.loadClass(PACKAGE + ".EvolutionAST"))
            .invoke(calculator, ast);
    }

    // LCS line edit counts, including blank lines; no inferred development-time estimates.
    private static int[] lineDelta(String before, String after) {
        var a = before.lines().toList(); var b = after.lines().toList();
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) for (int j = 1; j <= b.size(); j++) {
            lcs[i][j] = a.get(i - 1).equals(b.get(j - 1)) ? lcs[i - 1][j - 1] + 1
                : Math.max(lcs[i - 1][j], lcs[i][j - 1]);
        }
        int common = lcs[a.size()][b.size()];
        return new int[]{b.size() - common, a.size() - common};
    }

    private static final String PROBE = """
        package org.example.evolution;
        import static org.junit.Assert.*;
        import java.util.*;
        import org.eclipse.lsp4j.*;
        import org.eclipse.lsp4j.debug.services.*;
        public class Probe {
            public static void check(String input) throws Exception {
                var mapped = EvolutionMapper.parseWithSourceMap(input);
                var calculator = new Calculator();
                var visited = new ArrayList<EvolutionAST>();
                calculator.setDebugStrategy(new EvolutionEvaluator.StepCounterStrategy((step, node) -> {
                    assertTrue(mapped.sourceSpanOf(node).isPresent());
                    visited.add(node);
                }));
                calculator.eval(mapped.ast());
                assertFalse(visited.isEmpty());
                int[] saved = mapped.sourceSpanOf(mapped.ast()).orElseThrow();
                assertTrue(EvolutionMapper.diagnose(input).isEmpty());
                var trailing = EvolutionMapper.diagnose("1e+").orElseThrow();
                assertEquals("trailing_input", trailing.kind());
                assertEquals(1, trailing.offset());
                assertEquals(List.of("end of input"), trailing.expected());
                assertEquals(3, trailing.farthestOffset());
                assertTrue(trailing.farthestExpected().contains("DigitParser"));
                assertThrows(UnsupportedOperationException.class, () -> trailing.expected().add("changed"));
                assertThrows(UnsupportedOperationException.class, () -> trailing.farthestExpected().clear());
                var syntax = EvolutionMapper.diagnose("").orElseThrow();
                assertEquals("syntax", syntax.kind());
                assertEquals(0, syntax.offset());
                assertEquals(syntax.expected().stream().sorted().toList(), syntax.expected());
                var unicodeError = EvolutionMapper.diagnose("1/*😀*/x").orElseThrow();
                assertEquals("trailing_input", unicodeError.kind());
                assertEquals(6, unicodeError.offset());
                // diagnose parses only: it must leave both snapshot and legacy latest-map lookup intact.
                assertArrayEquals(saved, EvolutionMapper.sourceSpanOf(mapped.ast()).orElseThrow());
                assertArrayEquals(saved, mapped.sourceSpanOf(mapped.ast()).orElseThrow());
                var mutable = new ArrayList<>(List.of("original"));
                var copy = new EvolutionMapper.ParseDiagnostic("syntax", 0, mutable, 0, mutable);
                mutable.clear();
                assertEquals(List.of("original"), copy.expected());
                assertEquals(List.of("original"), copy.farthestExpected());
                EvolutionMapper.parse("999");
                assertArrayEquals(saved, mapped.sourceSpanOf(mapped.ast()).orElseThrow());
                saved[0] = -100;
                assertTrue(mapped.sourceSpanOf(mapped.ast()).orElseThrow()[0] >= 0);
                assertTrue(mapped.sourceSpanOf(new Object()).isEmpty());
                var repeated = EvolutionMapper.parseWithSourceMap("1+1");
                var binary = (EvolutionAST.Binary) repeated.ast();
                assertEquals(binary.left(), binary.right());
                assertNotSame(binary.left(), binary.right());
                assertArrayEquals(new int[]{0,1}, repeated.sourceSpanOf(binary.left()).orElseThrow());
                assertArrayEquals(new int[]{2,3}, repeated.sourceSpanOf(binary.right()).orElseThrow());
                // Root span includes the non-BMP comment: start and end must use one unit.
                String unicode = "1/*😀*/+1";
                var unicodeMapped = EvolutionMapper.parseWithSourceMap(unicode);
                assertArrayEquals(new int[]{0,8}, unicodeMapped.sourceSpanOf(unicodeMapped.ast()).orElseThrow());
                var unicodeBinary = (EvolutionAST.Binary) unicodeMapped.ast();
                assertArrayEquals(new int[]{7,8}, unicodeMapped.sourceSpanOf(unicodeBinary.right()).orElseThrow());
                try (var context = new org.unlaxer.context.ParseContext(org.unlaxer.StringSource.createRootSource(input))) {
                    var parsed = EvolutionParsers.getRootParser().parse(context);
                    var remapped = EvolutionMapper.mapParsedTokenWithSourceMap(parsed.getRootToken(true));
                    EvolutionMapper.parse("888");
                    assertTrue(remapped.sourceSpanOf(remapped.ast()).isPresent());
                }
                var server = new EvolutionLanguageServer() {};
                server.initialize(new InitializeParams()).get();
                var document = new TextDocumentItem("file:///evolution.calc", "evolution", 1, input);
                server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(document));
                var completions = server.getTextDocumentService().completion(new CompletionParams(
                    new TextDocumentIdentifier(document.getUri()), new Position(0, 0))).get();
                var items = completions.isLeft() ? completions.getLeft() : completions.getRight().getItems();
                if (input.startsWith("neg")) assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("neg")));
                if (input.startsWith("if")) assertTrue(items.stream().anyMatch(i -> i.getLabel().equals("if")));
                server.shutdown().get();
                var adapter = new EvolutionDebugAdapter() {};
                var reasons = new ArrayList<String>();
                adapter.connect(new IDebugProtocolClient() {
                    public void stopped(org.eclipse.lsp4j.debug.StoppedEventArguments event) { reasons.add(event.getReason()); }
                });
                java.nio.file.Path program = java.nio.file.Files.createTempFile("evolution-", ".calc");
                try {
                    java.nio.file.Files.writeString(program, input);
                    adapter.initialize(new org.eclipse.lsp4j.debug.InitializeRequestArguments()).get();
                    adapter.launch(Map.of("program", program.toString(), "steppingMode", "ast", "stopOnEntry", true)).get();
                    adapter.configurationDone(new org.eclipse.lsp4j.debug.ConfigurationDoneArguments()).get();
                    assertTrue(reasons.contains("entry"));
                    var args = new org.eclipse.lsp4j.debug.StackTraceArguments();
                    args.setThreadId(1);
                    var frames = adapter.stackTrace(args).get().getStackFrames();
                    assertTrue(frames.length > 0);
                    assertTrue(frames[0].getName().contains(mapped.ast().getClass().getSimpleName()));
                    adapter.next(new org.eclipse.lsp4j.debug.NextArguments()).get();
                    assertTrue(reasons.contains("step"));
                } finally {
                    java.nio.file.Files.deleteIfExists(program);
                }
            }
        }
        """;
}
