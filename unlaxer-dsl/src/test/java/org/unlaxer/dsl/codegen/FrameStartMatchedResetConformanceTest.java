package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.StringSource;
import org.unlaxer.TokenKind;
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;

/** #829: ordinary frame entry after a public lookahead on the same context. */
public class FrameStartMatchedResetConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private final Path corpus = repo.resolve("conformance/chain-reset-after-failure");

    private GrammarDecl grammar() throws Exception {
        return UBNFMapper.parse(Files.readString(corpus.resolve("frame-start.ubnf"))).grammars().get(0);
    }

    private List<String> oracle() throws Exception {
        return Files.readAllLines(corpus.resolve("frame-start.tsv")).stream()
                .filter(row -> !row.startsWith("#") && !row.isBlank()).toList();
    }

    /** Exact, separately inventoried existing differences; never suppress a whole observation. */
    private List<String> rustOracle() throws Exception {
        var rows = new java.util.LinkedHashMap<String, String[]>();
        for (String row : oracle()) {
            String[] cells = row.split("\t");
            rows.put(String.join("\t", java.util.Arrays.copyOf(cells, 5)), cells);
        }
        var seen = new java.util.HashSet<String>();
        for (String row : Files.readAllLines(corpus.resolve("frame-start-differences.tsv"))) {
            if (row.startsWith("#") || row.isBlank()) continue;
            String[] diff = row.split("\t");
            assertEquals(13, diff.length);
            String key = String.join("\t", java.util.Arrays.copyOf(diff, 5));
            assertTrue("duplicate difference " + key, seen.add(key));
            String[] expected = rows.get(key);
            assertNotNull(key, expected);
            // #827 permits host-dependent hit accounting only, never diagnostic differences.
            assertEquals(key + "/diagnostic parity", diff[5], diff[6]);
            assertEquals(key + "/follower diagnostic parity", diff[11], diff[12]);
            int[] columns = {9, 10, 11, 16};
            for (int i = 0; i < columns.length; i++) {
                assertEquals(key + "/Java column " + columns[i], expected[columns[i]], diff[5 + 2 * i]);
                expected[columns[i]] = diff[6 + 2 * i];
            }
        }
        return rows.values().stream().map(cells -> String.join("\t", cells)).toList();
    }

    private static Parser wrap(Parser parser, String boundary) {
        return switch (boundary) {
            case "direct" -> parser;
            case "nonordered" -> new NonOrdered(parser);
            case "lookahead" -> new MatchOnly(parser);
            case "choice" -> new Choice(parser, new WordParser("fallback"));
            case "optional" -> new org.unlaxer.parser.combinator.Optional(parser);
            case "repeat" -> new ZeroOrMore(parser);
            case "no-attempt" -> new Repeat(parser, 0, 0);
            case "invalid-bounds" -> new Repeat(parser, 2, 1);
            case "zero-check" -> new Repeat(parser, 0, 0) {
                @Override public boolean isZeroMatchCheck() { return true; }
            };
            case "invert" -> new NotPropagatableSource(parser);
            case "recovery" -> new SyncPointRecoveryParser(parser, ";");
            case "predictive" -> new PredictiveChoice(List.of(ChoicePredictor.any(), ChoicePredictor.any()),
                    parser, new WordParser("fallback"));
            case "until-frame" -> new WildCardStringTerminatorParser("b");
            case "longest" -> new LongestChoice(parser, new WordParser("fallback"));
            case "unique" -> new UniqueLongestChoice(parser, new WordParser("fallback"));
            case "capture-scope" -> new CaptureScope(parser);
            case "capture" -> new NamedCapture("value", parser);
            case "catch" -> new CatchLabels(new Label("L", parser), parser, "L");
            case "empty" -> new EmptyParser();
            // Negative controls: these Java wrappers do not begin a transaction.
            case "commit" -> new Commit(parser);
            case "label" -> new Label("L", parser);
            case "stop-consume" -> new DoConsumePropagationStopper(parser);
            case "stop-invert" -> new InvertMatchPropagationStopper(parser);
            case "positive" -> new MatchOnly(new WordParser("b"));
            case "negative" -> new Not(new WordParser("b"));
            // #838 nested frames: rule and non-rule frames, and failures after a successful Not.
            case "lookahead-longest" -> new MatchOnly(new LongestChoice(parser, new WordParser("fallback")));
            case "unique-lookahead" -> new UniqueLongestChoice(new MatchOnly(parser), new WordParser("fallback"));
            case "lookahead-negative" -> new MatchOnly(new Not(new WordParser("b")));
            case "longest-negative" -> new LongestChoice(new Not(new WordParser("b")), parser);
            case "negative-then" -> new Chain(new Not(new WordParser("b")), parser);
            case "choice-negative-seq" -> new Choice(new Chain(new Not(new WordParser("b")), new WordParser("zz")), parser);
            default -> throw new AssertionError(boundary);
        };
    }

    @Test public void generatedJavaMatchesFrameStartOracle() throws Exception {
        var actual = new ArrayList<String>();
        GrammarDecl grammar = grammar();
        Files.createDirectories(Path.of("target/frame-start-java"));
        Files.writeString(Path.of("target/frame-start-java/parser.java"), new ParserGenerator().generate(grammar).source());
        try (var loader = compileJava(grammar)) {
            for (String name : List.of("Root", "PlainTok", "PlainGroup", "PlainSeq", "Longest", "Unique", "Predictive")) {
                Parser parser = (Parser) loader.loadClass("framestart.FrameStartParsers$" + name + "Parser")
                        .getConstructor().newInstance();
                Class<?> expected = switch (name) {
                    case "PlainSeq" -> LazyChain.class;
                    case "Longest" -> LazyLongestChoice.class;
                    case "Unique" -> LazyUniqueLongestChoice.class;
                    case "Predictive" -> LazyPredictiveChoice.class;
                    default -> LazyChoice.class;
                };
                assertTrue(name + " generated frame", expected.isInstance(parser));
            }
            for (String row : Files.readAllLines(corpus.resolve("frame-start-inputs.tsv"))) {
                if (row.startsWith("#") || row.isBlank()) continue;
                String[] c = row.split("\t");
                Parser generated = (Parser) loader.loadClass("framestart.FrameStartParsers$" + c[1] + "Parser")
                        .getConstructor().newInstance();
                if (c[1].equals("Root")) generated = (Parser) loader.loadClass("framestart.FrameStartParsers")
                        .getMethod("getRootParser").invoke(null);
                Parser parser = wrap(generated, c[2]);
                for (boolean classic : List.of(false, true)) for (boolean initial : List.of(false, true))
                    for (var memo : Memoization.values()) for (var mode : ParseOptions.Diagnostics.values()) {
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(c[3].equals("-") ? "" : c[3]),
                            ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                        if (!c[4].equals("-")) assertTrue(new MatchOnly(new WordParser(c[4])).parse(context).isSucceeded());
                        String observation = null;
                        for (int attempt = 0; attempt < 3; attempt++) {
                            context.getCurrent().setResetMatchedWithConsumed(initial);
                            boolean success = classic || c[2].equals("until-frame")
                                    ? parser.parse(context, TokenKind.consumed, false).isSucceeded()
                                    : parser.parse(context).isSucceeded();
                            String next = success + "\t" + context.position() + "\t" + context.matchedPosition()
                                    + "\t" + context.getCurrent().isResetMatchedWithConsumed();
                            if (observation != null) assertEquals(row + "/retry", observation, next);
                            observation = next;
                            if (success) break;
                        }
                        String before = observation + "\t" + context.getParseFailureDiagnostics().getFarthestOffset()
                                + "\t" + (context.getPackratMemoTable() == null ? 0 : context.getPackratMemoTable().failureHits())
                                + "\t" + (context.getPackratMemoTable() == null ? 0 : context.getPackratMemoTable().successHits());
                        boolean follower = new NonOrdered(new MatchOnly(new WordParser("b"))).parse(context).isSucceeded();
                        new NonOrdered(new MatchOnly(new WordParser("x"))).parse(context);
                        actual.add(c[0] + "\t" + classic + "\t" + initial + "\t" + memo.ordinal() + "\t" + mode.ordinal()
                                + "\t" + before + "\t" + follower + "\t" + context.position() + "\t" + context.matchedPosition()
                                + "\t" + context.getCurrent().isResetMatchedWithConsumed()
                                + "\t" + context.getParseFailureDiagnostics().getFarthestOffset());
                        assertEquals(row, 1, context.getTokenStack().size());
                    }
                }
            }
        }
        Files.write(Path.of("target/frame-start-java.tsv"), actual);
        assertObservations("Java", oracle(), actual);
    }

    @Test public void bothGeneratedRustHostsMatchFrameStartOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] FrameStartMatchedResetConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
                repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
                "-p", "unlaxer-generator"));
        Path dir = temporary.newFolder().toPath();
        Path nativeOutput = dir.resolve("native");
        run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar",
                corpus.resolve("frame-start.ubnf").toString(), "--output", nativeOutput.toString()));
        Path javaOutput = Files.createDirectory(dir.resolve("java"));
        for (var generated : new RustBackend().generate(grammar())) {
            assertEquals(generated.relativePath(), generated.content(), Files.readString(nativeOutput.resolve(generated.relativePath())));
            Files.writeString(javaOutput.resolve(generated.relativePath()), generated.content());
        }
        Files.createDirectories(Path.of("target/frame-start-rust"));
        Files.copy(nativeOutput.resolve("parser.rs"), Path.of("target/frame-start-rust/parser.rs"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        for (String host : List.of("java", "native")) {
            Path main = dir.resolve(host + "_main.rs"), binary = dir.resolve(host + "_probe");
            Files.writeString(main, """
                    #![allow(dead_code)]
                    #[path = "%s/mod.rs"] mod generated;
                    #[path = "%s"] mod oracle;
                    fn main() {
                        oracle::report(generated::parser::rules());
                        oracle::report(generated::parser::mapping_grammar().unwrap().to_vec());
                    }
                    """.formatted(host, repo.resolve("rust/unlaxer-runtime/tests/support/frame_start_matched_reset.rs")));
            run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime,
                    main.toString(), "-o", binary.toString()));
            List<String> actual = run(List.of(binary.toString())).lines().toList();
            Files.write(Path.of("target/frame-start-" + host + "-rust.tsv"), actual);
            var expected = new ArrayList<String>();
            // Both ordinary and mapping graphs use the Java execution oracle.
            for (int graph = 0; graph < 2; graph++) expected.addAll(rustOracle());
            assertObservations(host, expected, actual);
        }
    }

    private static void assertObservations(String label, List<String> expected, List<String> actual) {
        MemoHitAccountingAssertions.assertMemoTransparency(label, actual);
        assertEquals(label + " observation count", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            String key = String.join("/", java.util.Arrays.copyOf(expected.get(i).split("\t"), 5));
            assertEquals(label + " row " + i + " " + key, expected.get(i), actual.get(i));
        }
    }

    private URLClassLoader compileJava(GrammarDecl grammar) throws Exception {
        var units = new ArrayList<JavaFileObject>();
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            var source = generator.generate(grammar);
            units.add(new SimpleJavaFileObject(URI.create("string:///" + source.packageName().replace('.', '/') + "/"
                    + source.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignore) { return source.source(); }
            });
        }
        Path output = temporary.newFolder().toPath();
        var compiler = org.unlaxer.dsl.Java21TestSupport.compiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                    List.of("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()),
                    null, units).call());
        }
        return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
    }

    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue("timeout: " + command, process.waitFor(180, TimeUnit.SECONDS));
            assertEquals(command + "\n" + Files.readString(log), 0, process.exitValue());
            return Files.readString(log);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
