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
import org.unlaxer.context.Memoization;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.elementary.*;

/** #809: a native generated Chain failure changes the policy read by the next parser. */
public class ChainResetAfterFailureConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();

    private static final List<String> GRAMMARS = List.of(
            "grammar.ubnf", "local-trivia.ubnf", "named-trivia.ubnf", "lexical-context.ubnf",
            "scope-effects.ubnf", "backref.ubnf");

    /** Rust rule-level failure hits for three calls; Java has 2 except lexical context (0). */
    private static int rustFailureHits(String file) {
        // Rust CaptureEquality (@backref) is not memo-safe, so the rule is never cached.
        return file.equals("backref.ubnf") ? 0 : 2;
    }

    private GrammarDecl grammar(String file) throws Exception {
        return UBNFMapper.parse(Files.readString(repo.resolve(
                "conformance/chain-reset-after-failure/" + file))).grammars().get(0);
    }

    private static Parser follower(String kind) {
        Parser child = switch (kind) {
            case "lookahead" -> new MatchOnly(new WordParser("b"));
            case "negative" -> new Not(new WordParser("a"));
            case "until" -> new WildCardStringTerminatorParser("b");
            case "empty" -> new EmptyParser();
            case "diagnostic" -> new NonOrdered(new MatchOnly(new WordParser("b")),
                    new MatchOnly(new WordParser("x")));
            default -> throw new AssertionError(kind);
        };
        return new NonOrdered(child);
    }

    @Test public void generatedJavaMatchesSharedFailureAndFollowerOracle() throws Exception {
        for (String file : GRAMMARS) try (var loader = compileJava(grammar(file))) {
            Parser parser = (Parser) loader.loadClass("chainreset.ChainResetParsers")
                    .getMethod("getRootParser").invoke(null);
            for (String row : Files.readAllLines(repo.resolve("conformance/chain-reset-after-failure/cases.tsv"))) {
                if (row.startsWith("#")) continue;
                String[] c = row.split("\t");
                for (var memo : Memoization.values()) for (var mode : ParseOptions.Diagnostics.values()) {
                    for (boolean reset : List.of(false, true)) {
                        String label = file + "/" + row + "/" + memo + "/" + mode + "/reset=" + reset;
                        try (var context = ParseContext.withOptions(StringSource.createRootSource(c[1]),
                                ParseOptions.withMemoization(memo).withDiagnostics(mode))) {
                            assertTrue(new MatchOnly(new WordParser(c[2])).parse(context).isSucceeded());
                            for (boolean retryReset : List.of(reset, reset, !reset)) {
                                context.getCurrent().setResetMatchedWithConsumed(retryReset);
                                assertTrue(label, parser.parse(context).isFailed());
                                assertFalse(label, context.getCurrent().isResetMatchedWithConsumed());
                            }
                            if (memo == Memoization.SAFE_FAILURES) {
                                // Java Lexing.withContext changes the state epoch on every entry/exit.
                                assertEquals(label, file.equals("lexical-context.ubnf") ? 0 : 2,
                                        context.getPackratMemoTable().failureHits());
                            }
                            boolean success = follower(c[3]).parse(context).isSucceeded();
                            assertEquals(label, Boolean.parseBoolean(c[4]), success);
                            assertEquals(label, Integer.parseInt(c[5]), context.position());
                            assertEquals(label, Integer.parseInt(c[6]), context.matchedPosition());
                            assertEquals(label, mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE ? 0
                                    : Integer.parseInt(c[7]), context.getParseFailureDiagnostics().getFarthestOffset());
                            assertFalse(label, context.getCurrent().isResetMatchedWithConsumed());
                            assertEquals(label, 1, context.getTokenStack().size());
                            if (c[3].equals("diagnostic") && mode != ParseOptions.Diagnostics.DETAILED_ON_FAILURE)
                                assertEquals(label, List.of("'x'"), context.getParseFailureDiagnostics().getExpectedParsers());
                        }
                    }
                }
            }
        }
    }

    @Test public void enclosingBoundariesRestoreTheOwnerPolicy() throws Exception {
        for (String file : GRAMMARS) try (var loader = compileJava(grammar(file))) {
            Parser parser = (Parser) loader.loadClass("chainreset.ChainResetParsers")
                    .getMethod("getRootParser").invoke(null);
            for (var memo : Memoization.values()) for (String boundary : List.of("choice", "non-ordered", "transaction")) {
                try (var context = ParseContext.withOptions(StringSource.createRootSource("ab"),
                        ParseOptions.withMemoization(memo))) {
                    if (!boundary.equals("non-ordered"))
                        assertTrue(new MatchOnly(new WordParser("a")).parse(context).isSucceeded());
                    if (boundary.equals("transaction")) {
                        context.beginPreservingMatched(parser);
                        assertTrue(parser.parse(context).isFailed());
                        assertFalse(context.getCurrent().isResetMatchedWithConsumed());
                        context.rollback(parser);
                    } else {
                        Parser wrapped = boundary.equals("choice")
                                ? new Choice(parser, follower("lookahead"))
                                : new NonOrdered(new MatchOnly(new WordParser("a")), parser,
                                        new MatchOnly(new WordParser("b")));
                        assertTrue(boundary + "/" + memo, wrapped.parse(context).isFailed());
                    }
                    assertEquals(boundary, 0, context.position());
                    assertEquals(boundary, boundary.equals("non-ordered") ? 0 : 1, context.matchedPosition());
                    assertTrue(boundary, context.getCurrent().isResetMatchedWithConsumed());
                    assertEquals(boundary, 1, context.getParseFailureDiagnostics().getFarthestOffset());
                }
            }
        }
    }

    /** Commit and Label call their child in the owner's transaction, as a rule reference does. */
    @Test public void raisingWrappersKeepTheFailedChainPolicy() {
        for (var memo : Memoization.values()) for (String kind : List.of("commit", "label")) {
            Parser chain = new Chain(new WordParser("a"), new WordParser("z"));
            Parser parser = kind.equals("commit") ? new Commit(chain) : new Label("L", chain);
            try (var context = ParseContext.withOptions(StringSource.createRootSource("ab"),
                    ParseOptions.withMemoization(memo))) {
                assertTrue(new MatchOnly(new WordParser("a")).parse(context).isSucceeded());
                context.getCurrent().setResetMatchedWithConsumed(true);
                assertTrue(kind, parser.parse(context).isFailed());
                assertFalse(kind, context.getCurrent().isResetMatchedWithConsumed());
                assertTrue(kind, follower("lookahead").parse(context).isSucceeded());
                assertEquals(kind, 0, context.position());
                assertEquals(kind, 2, context.matchedPosition());
            }
        }
    }

    @Test public void bothRustHostsMatchSharedFailureAndFollowerOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] ChainResetAfterFailureConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path runtime = temporary.getRoot().toPath().resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
                repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
                "-p", "unlaxer-generator"));
        for (String file : GRAMMARS) {
            Path dir = temporary.newFolder().toPath();
            Path nativeOutput = dir.resolve("native");
            run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar",
                    repo.resolve("conformance/chain-reset-after-failure/" + file).toString(),
                    "--output", nativeOutput.toString()));
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
                            oracle::check_with_failure_hits(generated::parser::rules(), %d);
                            oracle::check_with_failure_hits(generated::parser::mapping_grammar().unwrap().to_vec(), %d);
                        }
                        """.formatted(host, repo.resolve("rust/unlaxer-runtime/tests/support/chain_reset_after_failure.rs"),
                        rustFailureHits(file), rustFailureHits(file)));
                run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime,
                        main.toString(), "-o", binary.toString()));
                run(List.of(binary.toString()));
            }
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

    private void run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            assertTrue("timeout: " + command, process.waitFor(180, TimeUnit.SECONDS));
            assertEquals(command + "\n" + Files.readString(log), 0, process.exitValue());
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
