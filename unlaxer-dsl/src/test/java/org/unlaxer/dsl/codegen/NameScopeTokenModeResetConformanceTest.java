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
import java.util.Map;
import org.unlaxer.TokenKind;
import org.unlaxer.context.NameSnapshot;
import org.unlaxer.context.NameResolution;
import java.util.concurrent.TimeUnit;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
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
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;

/** #814: generated name frames and Classic rule calls share one owner-policy oracle. */
public class NameScopeTokenModeResetConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private final Path corpus = repo.resolve("conformance/chain-reset-after-failure");
    private static final List<NameSnapshot.Requirement> REQUIREMENTS =
            List.of(new NameSnapshot.Requirement("names", "v1"));

    private List<String[]> cases() throws Exception {
        return Files.readAllLines(corpus.resolve("owner-reset.tsv")).stream()
                .filter(row -> !row.startsWith("#") && !row.isBlank())
                .map(row -> row.split("\t")).toList();
    }

    private GrammarDecl grammar(String file) throws Exception {
        return UBNFMapper.parse(Files.readString(corpus.resolve(file))).grammars().get(0);
    }

    private static String observation(String id, boolean success, ParseContext context) {
        return id + "\t" + success + "\t" + context.getCurrent().isResetMatchedWithConsumed()
                + "\t" + context.position() + "\t" + context.matchedPosition();
    }

    private static String expected(String[] c) {
        return c[0] + "\t" + String.join("\t", java.util.Arrays.copyOfRange(c, 7, 14));
    }

    @Test public void generatedJavaMatchesOwnerResetOracle() throws Exception {
        var actual = new ArrayList<String>();
        var expected = new ArrayList<String>();
        for (String file : cases().stream().map(c -> c[1]).distinct().toList()) {
            GrammarDecl grammar = grammar(file);
            // Retain the generated parser for review of the real Java boundary.
            Files.createDirectories(Path.of("target/owner-reset-java"));
            Files.writeString(Path.of("target/owner-reset-java/" + file + ".java"),
                    new ParserGenerator().generate(grammar).source());
            try (var loader = compileJava(grammar)) {
                String prefix = file.startsWith("name-") ? "namereset.NameReset" : "chainreset.ChainReset";
                Parser root = (Parser) loader.loadClass(prefix + "Parsers").getMethod("getRootParser").invoke(null);
                for (String[] c : cases()) {
                    if (!c[1].equals(file)) continue;
                    Parser parser = c[2].equals("predicate")
                            ? (Parser) loader.loadClass(prefix + "Parsers$RootNamePredicateParser")
                                    .getConstructor().newInstance() : root;
                    for (var memo : Memoization.values()) for (var mode : ParseOptions.Diagnostics.values()) {
                        var snapshots = List.of(new NameSnapshot("names", "v1", Map.of("a",
                                c[5].equals("type") ? NameSnapshot.Kind.TYPE : NameSnapshot.Kind.VALUE)));
                        try (var context = ParseContext.withNameSnapshots(StringSource.createRootSource(c[3]),
                                snapshots, ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                            var frame = c[2].equals("predicate") ? NameResolution.begin(context, REQUIREMENTS) : null;
                            try {
                                assertTrue(new MatchOnly(new WordParser(c[4])).parse(context).isSucceeded());
                                int count = Boolean.parseBoolean(c[7]) ? 1 : 3;
                                String before = null;
                                for (int attempt = 0; attempt < count; attempt++) {
                                    context.getCurrent().setResetMatchedWithConsumed(Boolean.parseBoolean(c[6]));
                                    // Rust's Rule reference and its generated body both correspond
                                    // to this Java LazyChain parser; neither adds an owner frame.
                                    boolean success = c[2].startsWith("classic-")
                                            ? parser.parse(context, TokenKind.consumed, false).isSucceeded()
                                            : parser.parse(context).isSucceeded();
                                    String observed = observation(c[0], success, context);
                                    if (before != null) assertEquals(c[0] + "/retry/" + memo, before, observed);
                                    before = observed;
                                }
                                if (memo == Memoization.SAFE_FAILURES && file.equals("grammar.ubnf")
                                        && c[2].equals("classic-rule"))
                                    assertEquals(c[0], 2, context.getPackratMemoTable().failureHits());
                                boolean follower = new NonOrdered(new MatchOnly(new WordParser("b")))
                                        .parse(context).isSucceeded();
                                actual.add(before + "\t" + follower + "\t" + context.position() + "\t" + context.matchedPosition());
                                expected.add(expected(c));
                                assertEquals(c[0], 1, context.getTokenStack().size());
                            } finally {
                                if (frame != null) NameResolution.end(context, frame);
                            }
                        }
                    }
                }
            }
        }
        Files.write(Path.of("target/owner-reset-java.tsv"), actual);
        assertObservations("generated Java", expected, actual);
    }

    @Test public void bothGeneratedRustHostsMatchOwnerResetOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] NameScopeTokenModeResetConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
                repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
                "-p", "unlaxer-generator"));
        var actual = new ArrayList<String>();
        var expected = new ArrayList<String>();
        for (String file : cases().stream().map(c -> c[1]).distinct().toList()) {
            Path dir = temporary.newFolder().toPath();
            Path nativeOutput = dir.resolve("native");
            run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar",
                    corpus.resolve(file).toString(), "--output", nativeOutput.toString()));
            Path javaOutput = Files.createDirectory(dir.resolve("java"));
            for (var generated : new RustBackend().generate(grammar(file))) {
                assertEquals(generated.relativePath(), generated.content(), Files.readString(nativeOutput.resolve(generated.relativePath())));
                Files.writeString(javaOutput.resolve(generated.relativePath()), generated.content());
            }
            for (String host : List.of("java", "native")) {
                Path main = dir.resolve(host + "_main.rs"), binary = dir.resolve(host + "_probe");
                Files.writeString(main, """
                        #![allow(dead_code)]
                        #[path = "%s/mod.rs"] mod generated;
                        #[path = "%s"] mod oracle;
                        fn main() {
                            oracle::report("%s", generated::parser::rules());
                            oracle::report("%s", generated::parser::mapping_grammar().unwrap().to_vec());
                        }
                        """.formatted(host, repo.resolve("rust/unlaxer-runtime/tests/support/name_scope_token_mode_reset.rs"), file, file));
                run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime,
                        main.toString(), "-o", binary.toString()));
                actual.addAll(run(List.of(binary.toString())).lines().toList());
                for (int mapping = 0; mapping < 2; mapping++) for (String[] c : cases()) {
                    if (!c[1].equals(file)) continue;
                    for (int memo = 0; memo < 2; memo++) for (int diagnostic = 0; diagnostic < 3; diagnostic++)
                        expected.add(expected(c));
                }
            }
        }
        Files.write(Path.of("target/owner-reset-rust.tsv"), actual);
        assertObservations("both generated Rust hosts", expected, actual);
    }

    private static void assertObservations(String label, List<String> expected, List<String> actual) {
        assertEquals(label + " observation count", expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++)
            assertEquals(label + " observation " + i, expected.get(i), actual.get(i));
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
