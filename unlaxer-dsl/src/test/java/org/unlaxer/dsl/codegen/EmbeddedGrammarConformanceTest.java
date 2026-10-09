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
    @Test public void generatedEditorRegionsFollowOriginalSnapshotEdits() throws Exception {
        try (var loader = compile()) {
            var javaGrammar = (EmbeddedLanguages.Grammar) loader.loadClass("org.example.embedded.JavaParsers").getMethod("embeddedGrammar").invoke(null);
            var root = language("formula", "FormulaInfo", "Document");
            int version = 0; EmbeddedLanguages.Result previous = null;
            for (String line : Files.readAllLines(fixtures.resolve("editor.tsv"))) {
                String[] fields = line.split("\t", -1);
                var options = fields[1].equals("limit") ? new org.unlaxer.editor.EditorCst.Options(0, 0) : org.unlaxer.editor.EditorCst.Options.defaults();
                var formula = (EmbeddedLanguages.Grammar) loader.loadClass("org.example.embedded.FormulaInfoParsers").getMethod("embeddedEditorGrammar", List.class, org.unlaxer.editor.EditorCst.Options.class).invoke(null, List.of(fields[1].equals("synthetic") ? "x}F" : "}F"), options);
                var tiny = (EmbeddedLanguages.Grammar) loader.loadClass("org.example.embedded.TinyExpressionParsers").getMethod("embeddedEditorGrammar", List.class, org.unlaxer.editor.EditorCst.Options.class).invoke(null, List.of("]T"), options);
                var providers = new HashMap<Language, EmbeddedLanguages.Grammar>();
                providers.put(root, formula); providers.put(language("tiny", "TinyExpression", "Expression"), tiny);
                if (!fields[1].equals("missing")) providers.put(language("java", "Java", "CompilationUnit"), javaGrammar);
                var host = new DocumentSnapshot("host", ++version, fields[2]);
                var result = EmbeddedLanguages.parse(host, root, providers, 8, 32);
                String actual = result.regions().stream().map(r -> r.language().grammar() + ":" + r.parseState() + ":" + r.full().start() + ":" + r.full().end() + ":" + r.body().start() + ":" + r.body().end()).collect(java.util.stream.Collectors.joining(","));
                assertEquals(fields[0], fields[3], actual);
                for (var region : result.regions()) {
                    assertEquals(host.slice(region.body()), region.sourceMap().output().text());
                    assertEquals(version, region.sourceMap().output().version());
                    assertEquals(region.body(), region.sourceMap().edit(new Span(0, region.sourceMap().output().length())).span());
                    assertEquals(0, region.sourceMap().cursor(new SegmentSourceMap.Location(host, new Span(region.body().start(), region.body().start()))).orElseThrow());
                }
                if (previous != null) {
                    var old = previous; var project = new LanguageQueries.Project("project", version, Map.of(host.uri(), host), Map.of());
                    assertThrows(IllegalArgumentException.class, () -> new LanguageQueries(old.tree(), project, Map.of()));
                }
                assertOwnership(fields[0], fields[1], result);
                previous = result;
            }
        }
    }
    private void assertOwnership(String name, String mode, EmbeddedLanguages.Result result) throws Exception {
        var host = result.snapshot(); var tree = result.tree();
        var project = new LanguageQueries.Project("project", host.version(), Map.of(host.uri(), host), Map.of());
        var providers = new HashMap<Language, LanguageQueries.Provider>();
        for (var region : result.regions()) {
            if (mode.equals("missing") && region.language().id().equals("java")) continue;
            providers.put(region.language(), new LanguageQueries.Provider() {
                @Override public Set<Operation> capabilities() { return Set.of(Operation.COMPLETION); }
                @Override public LanguageQueries.Response query(LanguageQueries.Request request) {
                    var source = request.region().sourceMap().output();
                    var point = new SegmentSourceMap.Location(source, new Span(request.cursor(), request.cursor()));
                    return new LanguageQueries.Response(source, project.id(), project.version(), State.PARTIAL, List.of(
                        new LanguageQueries.Item("x", "boundary", List.of(point), List.of(new LanguageQueries.TextEdit(point, "x")))));
                }
            });
        }
        var queries = new LanguageQueries(tree, project, providers);
        for (String row : Files.readAllLines(fixtures.resolve("ownership.tsv"))) {
            String[] fields = row.split("\t"); if (!fields[0].equals(name)) continue;
            int cursor = Integer.parseInt(fields[1]); var owner = tree.at(cursor);
            assertEquals(name + ":" + cursor, fields[2], owner == null ? "NONE" : owner.language().grammar());
            String open = result.openEnds().stream().sorted().collect(java.util.stream.Collectors.joining(","));
            assertEquals(name, fields[3].equals("-") ? "" : fields[3], open);
            var response = queries.query(host, project, cursor, Operation.COMPLETION, Map.of());
            assertEquals(name, State.valueOf(fields[4]), response.state());
            if (response.state() == State.PARTIAL) {
                assertEquals(new Span(cursor, cursor), response.items().get(0).edits().get(0).span());
                assertEquals(new Span(cursor, cursor), response.items().get(0).locations().get(0).location().span());
                assertEquals(host.slice(new Span(0, cursor)) + "x" + host.slice(new Span(cursor, host.length())),
                    tree.apply(host, host.version() + 1, response.items().get(0).edits()).text());
            }
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
        run(List.of(output.resolve("probe").toString(), fixtures.resolve("cases.tsv").toString(), fixtures.resolve("editor.tsv").toString(), fixtures.resolve("ownership.tsv").toString()));
    }
    @Test public void generatedPlaygroundUsesActualPartialRegionRegistry() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] embedded Playground requires -DrustConformance=true and wasm32 target");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path output = temporary.newFolder().toPath();
        for (var file : org.unlaxer.dsl.codegen.rust.PlaygroundGenerator.generate(fixtures.resolve("FormulaInfoPlayground.ubnf")).entrySet()) {
            Path target = output.resolve(file.getKey()); Files.createDirectories(target.getParent()); Files.writeString(target, file.getValue());
        }
        for (String name : List.of("TinyExpression", "Java")) {
            Path module = output.resolve(name.equals("Java") ? "src/java" : "src/tiny");
            for (var file : new RustBackend().generate(grammar(name))) {
                Path target = module.resolve(file.relativePath()); Files.createDirectories(target.getParent()); Files.writeString(target, file.content());
            }
        }
        Files.copy(fixtures.resolve("region_adapter.rs"), output.resolve("src/region_adapter.rs"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        run(List.of("node", output.resolve("build.mjs").toString()));
        run(List.of("node", fixtures.resolve("playground-probe.mjs").toString(), output.resolve("public/language.wasm").toString(), fixtures.resolve("editor.tsv").toString(), fixtures.resolve("ownership.tsv").toString()));
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
