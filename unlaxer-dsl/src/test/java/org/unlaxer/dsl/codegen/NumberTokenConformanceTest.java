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
import java.util.HexFormat;
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

/** #752: generated Number bindings, using the independent low-level Java diagnostic oracle. */
public class NumberTokenConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private static final String[][] SHAPES = {
        {"number", "Num"}, {"optional", "[Num]"}, {"choice", "'x' | Num"},
        {"after-optional", "['x'] Num"}, {"tail", "Num ['e']"}
    };

    private static GrammarDecl grammar(String body) {
        return UBNFMapper.parse(source(body)).grammars().get(0);
    }

    private static String source(String body) {
        return "grammar NumberDiagnostic { @package: numberprobe @whitespace: none "
                + "token VALUE = org.unlaxer.parser.elementary.NumberParser "
                + "@root @mapping(Node) Root ::= " + body + "; Num ::= VALUE; }";
    }

    @Test public void generatedJavaMatchesTheDiagnosticOracle() throws Exception {
        for (String[] shape : SHAPES) try (var loader = compileJava(grammar(shape[1]))) {
            Parser parser = (Parser) loader.loadClass("numberprobe.NumberDiagnosticParsers")
                    .getMethod("getRootParser").invoke(null);
            for (String row : Files.readAllLines(repo.resolve("conformance/number-token/diagnostics.tsv"))) {
                if (!row.startsWith(shape[0] + "\t")) continue;
                String[] c = row.split("\t");
                String input = new String(HexFormat.of().parseHex(c[1]), StandardCharsets.UTF_8);
                for (var mode : ParseOptions.Diagnostics.values()) {
                    var options = ParseOptions.withMemoization(Memoization.valueOf(c[2])).withDiagnostics(mode);
                    try (var context = ParseContext.withOptions(StringSource.createRootSource(input), options)) {
                        boolean success = parser.parse(context).isSucceeded();
                        var failure = context.getParseFailureDiagnostics();
                        boolean deferred = mode == ParseOptions.Diagnostics.DETAILED_ON_FAILURE;
                        assertEquals(row, Boolean.parseBoolean(c[3]), success);
                        assertEquals(row, Integer.parseInt(c[4]), context.getConsumedPosition().value());
                        assertEquals(row, Integer.parseInt(c[5]), context.getMatchedPosition().value());
                        assertEquals(row, deferred ? 0 : Integer.parseInt(c[6]), failure.getFarthestOffset());
                        assertEquals(row, deferred ? "" : c[7], String.join(",", failure.getExpectedParsers()));
                        assertTrue(row, context.getRuleStack().isEmpty());
                    }
                }
            }
        }
    }

    @Test public void bothRustHostsMatchTheDiagnosticOracle() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] NumberTokenConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path root = temporary.getRoot().toPath();
        Path runtime = root.resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime",
                repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(),
                "-p", "unlaxer-generator"));
        for (String[] shape : SHAPES) {
            Path dir = temporary.newFolder().toPath();
            Path ubnf = dir.resolve("number.ubnf");
            Files.writeString(ubnf, source(shape[1]));
            Path nativeOutput = dir.resolve("native");
            run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar", ubnf.toString(),
                    "--output", nativeOutput.toString()));
            Path javaOutput = Files.createDirectory(dir.resolve("java"));
            for (var file : new RustBackend().generate(grammar(shape[1]))) {
                assertEquals(file.relativePath(), file.content(), Files.readString(nativeOutput.resolve(file.relativePath())));
                Files.writeString(javaOutput.resolve(file.relativePath()), file.content());
            }
            // Compile and execute each host independently. The helper also wraps the generated Num
            // rule in negative PEG assertions, which cannot currently be expressed in UBNF.
            for (String host : List.of("java", "native")) {
                Path main = dir.resolve(host + "_main.rs"), binary = dir.resolve(host + "_probe");
                Files.writeString(main, """
                        #![allow(dead_code)]
                        use unlaxer_runtime::*;
                        #[path = "%s/mod.rs"] mod generated;
                        #[path = "%s"] mod diagnostics;
                        fn main() {
                            for mapping in [false, true] {
                                diagnostics::check(|shape| {
                                    let generated = if mapping {
                                        generated::parser::mapping_grammar().unwrap().to_vec()
                                    } else { generated::parser::rules() };
                                    assert_eq!(generated[0].name, "Root");
                                    assert_eq!(generated[1].name, "Num");
                                    if shape == "%s" { generated } else {
                                        let mut rules = diagnostics::rules(shape);
                                        rules[1].expression = generated[1].expression.clone();
                                        rules
                                    }
                                }, false, |context| context.failure().expected);
                            }
                        }
                        """.formatted(host, repo.resolve("rust/unlaxer-runtime/tests/support/number_token_diagnostics.rs"), shape[0]));
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
