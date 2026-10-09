package org.unlaxer.dsl.provider;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.source.*;
import org.unlaxer.source.LanguageRegions.Operation;
import org.unlaxer.source.ProviderProtocol.*;

/** Both hosts run three real, pinned compilers against independent shared observations. */
public class ExternalProviderConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private final Path fixture = repo.resolve("docs/fixtures/language-providers");
    private static final Set<Operation> OPERATIONS = Set.of(Operation.PARSE, Operation.VALIDATE, Operation.HOVER, Operation.DEFINITION, Operation.COMPLETION, Operation.FORMAT);
    private String java() { return Path.of(System.getProperty("java.home"), "bin", "java").toString(); }
    private String classpath() { return System.getProperty("java.class.path"); }
    private String typescript() { return repo.resolve("unlaxer-dsl/ubnf-vscode/node_modules/typescript/lib/typescript.js").toString(); }
    private List<String> command(String provider, String rustc) {
        return switch (provider) {
            case "javac" -> List.of(java(), "-cp", classpath(), "org.unlaxer.dsl.provider.JavacProvider");
            case "typescript" -> List.of("python3", repo.resolve("scripts/language-providers/provider.py").toString(), "typescript", typescript());
            case "rustc" -> List.of("python3", repo.resolve("scripts/language-providers/provider.py").toString(), "rust", rustc);
            default -> throw new IllegalArgumentException(provider);
        };
    }
    @Test public void realJavaTypescriptRustAdaptersMatchBothHosts() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] ExternalProviderConformanceTest requires -DrustConformance=true and pinned tools");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        assertTrue("run npm ci --ignore-scripts in unlaxer-dsl/ubnf-vscode", Files.isRegularFile(Path.of(typescript())));
        assertTrue("pin JDK 21.0.9", Runtime.version().toString().startsWith("21.0.9"));
        String rustc = run(List.of("rustup", "which", "rustc", "--toolchain", "1.85.0")).trim();
        Path directory = temporary.newFolder().toPath();
        run(List.of("python3", repo.resolve("scripts/language-providers/fixture_wire.py").toString(), fixture.resolve("cases.json").toString(), directory.toString()));
        Path marker = prepareProcessorSentinel(directory);
        var reports = new ArrayList<String>();
        for (String expected : Files.readAllLines(directory.resolve("expected.tsv"))) {
            String name = expected.split("\t")[0];
            String wire = Files.readString(directory.resolve(name + ".wire"));
            Request request = ProviderProtocol.readRequest(wire);
            assertEquals("canonical " + name, wire, ProviderProtocol.encode(request).text());
            Response response = new ProviderProcess(command(request.provider().id(), rustc), request.provider(), OPERATIONS, Duration.ofSeconds(30)).invoke(request);
            if (name.equals("rust-macro-origin")) { assertEquals(1, response.diagnostics().size()); assertEquals(2, response.diagnostics().get(0).locations().size()); }
            String actual = observation(name, response);
            assertFalse("annotation processor executed", Files.exists(marker));
            assertEquals(name, expected, actual);
            assertFalse(response.capabilities().contains("EXECUTE_USER_CODE"));
            reports.add(actual);
        }
        Path runtime = directory.resolve("libunlaxer_runtime.rlib");
        run(List.of(rustc, "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        run(List.of(rustc, "--edition=2021", "--extern", "unlaxer_runtime=" + runtime, fixture.resolve("probe.rs").toString(), "-o", directory.resolve("probe").toString()));
        String rust = run(List.of(directory.resolve("probe").toString(), directory.toString(), repo.toString(), java(), classpath(), typescript(), rustc));
        assertEquals(reports, rust.lines().toList());
        assertFalse("annotation processor executed by Rust host", Files.exists(marker));
        embeddedDiagnosticsAndForeignDefinitions(directory, rustc, runtime);
        completionEditsReachHost(directory, rustc);
        Files.write(Path.of("target/external-provider-conformance.tsv"), reports);
    }
    private void completionEditsReachHost(Path directory, String rustc) throws Exception {
        Request base = ProviderProtocol.readRequest(Files.readString(directory.resolve("ts-completion.wire")));
        var snapshot = new DocumentSnapshot(base.snapshot().uri(), 7, base.snapshot().text() + " ");
        var host = new DocumentSnapshot("file:///host.formula", 7, "😀[" + snapshot.text() + "]");
        var body = new DocumentSnapshot.Span(2, host.length() - 1);
        var map = new SegmentSourceMap(snapshot, List.of(new SegmentSourceMap.Segment(new DocumentSnapshot.Span(0, snapshot.length()), SegmentSourceMap.Kind.COPY, new SegmentSourceMap.Location(host, body))));
        var region = new LanguageRegions.Region("ts", null, base.language(), new DocumentSnapshot.Span(0, host.length()), body, map, LanguageRegions.State.PARTIAL);
        var project = new LanguageQueries.Project("project", 12, Map.of(host.uri(), host), Map.of());
        var process = new ProviderProcess(command("typescript", rustc), base.provider(), OPERATIONS, Duration.ofSeconds(30));
        var queries = new LanguageQueries(new LanguageRegions(host, List.of(region)), project, Map.of(base.language(), process));
        var result = queries.query(host, project, 22, Operation.COMPLETION, Map.of("prefix", "alp"));
        assertEquals(LanguageRegions.State.PARTIAL, result.state()); assertEquals(1, result.items().size());
        var edit = result.items().get(0).edits().get(0);
        assertEquals(new DocumentSnapshot.Span(19, 22), edit.span()); assertEquals("alpha", edit.replacement());
        assertEquals("alp", host.slice(edit.span()));
    }
    private static LanguageRegions.Language language(String id, String grammar, String entry) { return new LanguageRegions.Language(id, "example", "1", grammar, entry); }
    private void embeddedDiagnosticsAndForeignDefinitions(Path directory, String rustc, Path runtime) throws Exception {
        Path embedded = repo.resolve("docs/fixtures/embedded-grammars");
        var units = new ArrayList<javax.tools.JavaFileObject>();
        for (String name : List.of("FormulaInfo", "TinyExpression")) {
            var grammar = org.unlaxer.dsl.bootstrap.UBNFMapper.parse(Files.readString(embedded.resolve(name + ".ubnf"))).grammars().get(0);
            var file = new org.unlaxer.dsl.codegen.ParserGenerator().generate(grammar);
            units.add(new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///" + file.className() + ".java"), javax.tools.JavaFileObject.Kind.SOURCE) {
                public CharSequence getCharContent(boolean ignored) { return file.source(); }
            });
            Path module = Files.createDirectory(directory.resolve(name.equals("FormulaInfo") ? "formula" : "tiny"));
            for (var rustFile : new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar)) {
                Path target = module.resolve(rustFile.relativePath()); Files.createDirectories(target.getParent()); Files.writeString(target, rustFile.content());
            }
        }
        Path classes = Files.createDirectory(directory.resolve("embedded-classes"));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-proc:none", "-classpath", classpath(), "-d", classes.toString()), null, units).call());
        }
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var formula = (EmbeddedLanguages.Grammar) loader.loadClass("org.example.embedded.FormulaInfoParsers").getMethod("embeddedGrammar").invoke(null);
            var tiny = (EmbeddedLanguages.Grammar) loader.loadClass("org.example.embedded.TinyExpressionParsers").getMethod("embeddedGrammar").invoke(null);
            for (String name : List.of("java-type", "java-definition")) {
                Request base = ProviderProtocol.readRequest(Files.readString(directory.resolve(name + ".wire")));
                var process = new ProviderProcess(command("javac", rustc), base.provider(), OPERATIONS, Duration.ofSeconds(30));
                var host = new DocumentSnapshot("file:///host.formula", 7, "😀F{T[" + base.snapshot().text() + "]T}F");
                var docs = new HashMap<>(base.project().documents()); docs.put(host.uri(), host);
                var project = new LanguageQueries.Project("project", 12, docs, Map.of());
                var root = language("formula", "FormulaInfo", "Document"); var javaLanguage = language("java", "Java", "CompilationUnit");
                var result = EmbeddedLanguages.parse(host, root, Map.of(root, formula, language("tiny", "TinyExpression", "Expression"), tiny, javaLanguage, process.grammar(javaLanguage, project, Map.of())), 8, 32);
                assertEquals(3, result.regions().size()); assertTrue(result.regions().stream().allMatch(r -> r.parseState() == LanguageRegions.State.COMPLETE));
                var region = result.regions().get(2); assertEquals(5, region.body().start());
                if (name.equals("java-type")) {
                    Request request = new Request(name, base.provider(), javaLanguage, region.id(), region.sourceMap().output(), project, Operation.VALIDATE, 0, Map.of(), false);
                    Response response = process.invoke(request); assertEquals(Status.DIAGNOSTICS, response.status());
                    var mapped = ProviderProtocol.mapDiagnostics(response, region.sourceMap());
                    assertEquals(1, mapped.size()); assertEquals(1, mapped.get(0).locations().size());
                    var location = mapped.get(0).locations().get(0); assertTrue(location.exact()); assertEquals(host, location.location().snapshot());
                    assertEquals(new DocumentSnapshot.Span(35, 36), location.location().span()); assertEquals("1", host.slice(location.location().span()));
                    var queries = new LanguageQueries(result.tree(), project, Map.of(javaLanguage, process));
                    var typed = queries.diagnosticsAll(host, project, Map.of()).stream().filter(r -> r.region().equals(region.id())).findFirst().orElseThrow();
                    assertEquals(LanguageRegions.State.PARTIAL, typed.state()); assertEquals(mapped, typed.diagnostics());
                    assertEquals("ERROR", typed.diagnostics().get(0).severity());
                    var generic = queries.query(host, project, region.body().start(), Operation.VALIDATE, Map.of());
                    assertEquals(1, generic.items().size()); assertEquals(mapped.get(0).code(), generic.items().get(0).label());
                    assertEquals(mapped.get(0).locations(), generic.items().get(0).locations());
                    var anchor = new SegmentSourceMap.Location(host, new DocumentSnapshot.Span(0, 1));
                    for (var kind : List.of(SegmentSourceMap.Kind.GENERATED, SegmentSourceMap.Kind.TRANSFORMED)) {
                        var map = new SegmentSourceMap(request.snapshot(), List.of(new SegmentSourceMap.Segment(new DocumentSnapshot.Span(0, request.snapshot().length()), kind, anchor)));
                        var diagnostic = ProviderProtocol.mapDiagnostics(response, map).get(0).locations().get(0);
                        assertFalse(diagnostic.exact()); assertEquals(anchor, diagnostic.location());
                        assertThrows(IllegalArgumentException.class, () -> map.edit(response.diagnostics().get(0).locations().get(0).span()));
                    }
                } else {
                    var queries = new LanguageQueries(result.tree(), project, Map.of(javaLanguage, process));
                    var response = queries.query(host, project, 5 + base.cursor(), Operation.DEFINITION, Map.of());
                    assertEquals(LanguageRegions.State.COMPLETE, response.state()); assertEquals(1, response.items().size());
                    var definition = response.items().get(0).locations().get(0);
                    assertEquals("file:///Dep.java", definition.location().snapshot().uri());
                    assertEquals(new DocumentSnapshot.Span(0, 12), definition.location().span()); assertTrue(definition.exact());
                    var stale = new DocumentSnapshot(host.uri(), 8, host.text());
                    assertThrows(IllegalArgumentException.class, () -> queries.query(stale, project, 5 + base.cursor(), Operation.DEFINITION, Map.of()));
                }
            }
        }
        Files.copy(fixture.resolve("embedded_probe.rs"), directory.resolve("embedded_probe.rs"));
        run(List.of(rustc, "--edition=2021", "--extern", "unlaxer_runtime=" + runtime, directory.resolve("embedded_probe.rs").toString(), "-o", directory.resolve("embedded-probe").toString()));
        run(List.of(directory.resolve("embedded-probe").toString(), directory.toString(), java(), classpath()));
    }
    private Path prepareProcessorSentinel(Path directory) throws Exception {
        Path classes = Files.createDirectory(directory.resolve("processor"));
        Path marker = directory.resolve("processor-ran");
        Path source = directory.resolve("Sentinel.java");
        Files.writeString(source, """
            @javax.annotation.processing.SupportedAnnotationTypes("*")
            @javax.annotation.processing.SupportedSourceVersion(javax.lang.model.SourceVersion.RELEASE_21)
            public class Sentinel extends javax.annotation.processing.AbstractProcessor {
                public boolean process(java.util.Set<? extends javax.lang.model.element.TypeElement> a,
                                       javax.annotation.processing.RoundEnvironment r) {
                    try { java.nio.file.Files.writeString(java.nio.file.Path.of("%s"), "executed"); }
                    catch (java.io.IOException e) { throw new RuntimeException(e); }
                    return false;
                }
            }
            """.formatted(marker));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-proc:none", "-d", classes.toString(), source.toString()));
        Path services = Files.createDirectories(classes.resolve("META-INF/services"));
        Files.writeString(services.resolve("javax.annotation.processing.Processor"), "Sentinel\n");
        Path input = directory.resolve("Main.java"); Files.writeString(input, "class Main {}");
        assertEquals(0, compiler.run(null, null, null, "-proc:only", "-processor", "Sentinel", "-classpath", classes.toString(), input.toString()));
        assertTrue("sentinel must be executable when explicitly enabled", Files.exists(marker)); Files.delete(marker);
        Request base = ProviderProtocol.readRequest(Files.readString(directory.resolve("java-parse-only.wire")));
        var project = new LanguageQueries.Project("project", 12, Map.of(), Map.of("classpath", classes.toString()));
        Request request = new Request("java-processor-disabled", base.provider(), base.language(), "root", new DocumentSnapshot("file:///Main.java", 7, "class Main {}"), project, Operation.VALIDATE, 0, Map.of(), false);
        Files.writeString(directory.resolve(request.id() + ".wire"), ProviderProtocol.encode(request).text());
        Files.writeString(directory.resolve("expected.tsv"), request.id() + "\tOK\t-\t-\n", StandardOpenOption.APPEND);
        return marker;
    }
    private static String observation(String name, Response response) {
        var diagnostics = response.diagnostics().stream().flatMap(d -> d.locations().stream().map(l -> d.code() + "@" + l.snapshot().uri() + ":" + l.span().start() + ":" + l.span().end() + ":" + d.severity())).sorted().toList();
        var items = response.items().stream().map(item -> {
            String locations = item.locations().stream().map(l -> l.snapshot().uri() + ":" + l.span().start() + ":" + l.span().end()).collect(Collectors.joining(","));
            String edits = item.edits().stream().map(e -> e.location().snapshot().uri() + ":" + e.location().span().start() + ":" + e.location().span().end() + ":" + ProviderProtocol.hex(e.replacement())).collect(Collectors.joining(","));
            return ProviderProtocol.hex(item.label()) + ":" + ProviderProtocol.hex(item.detail()) + "[" + locations + "][" + edits + "]";
        }).toList();
        return name + "\t" + response.status() + "\t" + (diagnostics.isEmpty() ? "-" : String.join(",", diagnostics)) + "\t" + (items.isEmpty() ? "-" : String.join(",", items));
    }
    private String run(List<String> arguments) throws Exception {
        Path log = temporary.newFile().toPath();
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout: " + arguments); }
        String output = Files.readString(log);
        assertEquals(arguments + "\n" + output, 0, process.exitValue());
        return output;
    }
}
