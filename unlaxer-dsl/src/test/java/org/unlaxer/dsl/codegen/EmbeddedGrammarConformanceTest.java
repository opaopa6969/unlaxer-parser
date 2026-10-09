package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.source.*;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.*;

public class EmbeddedGrammarConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private final Path fixtures = repo.resolve("docs/fixtures/embedded-grammars");
    private static final List<String> NAMES = List.of("FormulaInfo", "TinyExpression", "Java");
    private GrammarDecl grammar(String name) throws Exception {
        String source = Files.readString(fixtures.resolve(name + ".ubnf"));
        assertTrue(PortabilityCheck.check(source).toString(), PortabilityCheck.check(source).portable());
        var grammar = UBNFMapper.parse(source).grammars().get(0);
        GrammarValidator.validateOrThrow(grammar);
        return grammar;
    }
    private URLClassLoader compile() throws Exception {
        var units = new ArrayList<JavaFileObject>();
        for (String name : NAMES) {
            var generated = new ParserGenerator().generate(grammar(name));
            units.add(new SimpleJavaFileObject(URI.create("string:///" + generated.packageName().replace('.', '/') + "/" + generated.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return generated.source(); }
            });
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        Path output = temporary.newFolder().toPath();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()), null, units).call());
        }
        return new URLClassLoader(new URL[]{output.toUri().toURL()}, getClass().getClassLoader());
    }
    private static Language language(String id, String grammar, String entry) { return new Language(id, "example", "1", grammar, entry); }
    @Test public void generatedJavaParsersCreateThreeLayerRegionsFromSharedCorpus() throws Exception {
        try (var loader = compile()) {
            var grammars = new ArrayList<EmbeddedLanguages.Grammar>();
            for (String name : NAMES) grammars.add((EmbeddedLanguages.Grammar) loader.loadClass("org.example.embedded." + name + "Parsers").getMethod("embeddedGrammar").invoke(null));
            Language root = language("formula", "FormulaInfo", "Document");
            for (String line : Files.readAllLines(fixtures.resolve("cases.tsv"))) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("\t");
                var providers = new HashMap<Language, EmbeddedLanguages.Grammar>();
                providers.put(root, grammars.get(0)); providers.put(language("tiny", "TinyExpression", "Expression"), grammars.get(1));
                if (!fields[1].equals("missing")) providers.put(language("java", "Java", "CompilationUnit"), grammars.get(2));
                var host = new DocumentSnapshot("host", 7, fields[2]);
                var result = EmbeddedLanguages.parse(host, root, providers, 8, 32);
                String actual = result.regions().stream().map(r -> r.language().grammar() + ":" + r.parseState() + ":" + r.full().start() + ":" + r.full().end() + ":" + r.body().start() + ":" + r.body().end()).collect(java.util.stream.Collectors.joining(","));
                assertEquals(fields[0], fields[3], actual);
                for (var region : result.regions()) {
                    assertEquals(host.slice(region.body()), region.sourceMap().output().text());
                    var mapped = region.sourceMap().edit(new Span(0, region.sourceMap().output().length()));
                    assertEquals(host, mapped.snapshot()); assertEquals(region.body(), mapped.span());
                }
                if (fields[0].equals("complete")) {
                    var tree = result.tree();
                    assertEquals("FormulaInfo", tree.at(2).language().grammar());
                    assertEquals("TinyExpression", tree.at(4).language().grammar());
                    assertEquals("Java", tree.at(5).language().grammar());
                    assertEquals("TinyExpression", tree.at(36).language().grammar());
                    assertEquals("FormulaInfo", tree.at(38).language().grammar());
                    assertNull(tree.at(40));
                    assertThrows(IllegalArgumentException.class, () -> EmbeddedLanguages.parse(host, root, providers, 2, 32));
                    assertThrows(IllegalArgumentException.class, () -> EmbeddedLanguages.parse(host, root, providers, 8, 2));
                }
            }
            assertEquals(State.PARTIAL, grammars.get(2).parse("RecoverableUnit", new DocumentSnapshot("child", 7, "bad;")).state());
            var child = new DocumentSnapshot("child", 7, "{}");
            assertEquals(State.UNSUPPORTED, grammars.get(2).parse("Missing", child).state());
            assertEquals(State.COMPLETE, grammars.get(2).parse("Block", child).state());
        }
    }
    @Test public void nativeAndJavaRustEmissionMatchAndExecuteTheSameIndependentCorpus() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] EmbeddedGrammarConformanceTest requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path output = temporary.newFolder().toPath();
        run(List.of("cargo", "build", "--locked", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"));
        String[] modules = {"formula", "tiny", "java"};
        for (int i = 0; i < NAMES.size(); i++) {
            Path target = output.resolve(modules[i]);
            run(List.of(repo.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar", fixtures.resolve(NAMES.get(i) + ".ubnf").toString(), "--output", target.toString()));
            for (var file : new RustBackend().generate(grammar(NAMES.get(i)))) assertEquals(file.relativePath(), file.content(), Files.readString(target.resolve(file.relativePath())));
        }
        Path runtime = output.resolve("libunlaxer_runtime.rlib");
        run(List.of("rustc", "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        Files.copy(fixtures.resolve("probe.rs"), output.resolve("main.rs"));
        run(List.of("rustc", "--edition=2021", "--extern", "unlaxer_runtime=" + runtime, output.resolve("main.rs").toString(), "-o", output.resolve("probe").toString()));
        run(List.of(output.resolve("probe").toString(), fixtures.resolve("cases.tsv").toString()));
    }
    @Test public void malformedDeclarationsAreRejectedByBothJavaGenerationPaths() throws Exception {
        for (String line : Files.readAllLines(fixtures.resolve("invalid.tsv"))) {
            if (line.startsWith("#")) continue;
            var fields = line.split("\t"); var grammar = UBNFMapper.parse(fields[1]).grammars().get(0);
            assertTrue(fields[0], GrammarValidator.validate(grammar).stream().anyMatch(i -> i.code().equals("E-EMBEDDING")));
            assertThrows(fields[0], IllegalArgumentException.class, () -> new ParserGenerator().generate(grammar));
            assertThrows(fields[0], IllegalArgumentException.class, () -> new RustBackend().generate(grammar));
        }
    }
    private void run(List<String> arguments) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout: " + arguments); }
        assertEquals(arguments + "\n" + Files.readString(log), 0, process.exitValue());
    }
}
