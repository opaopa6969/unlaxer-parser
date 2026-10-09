package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.*;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.source.*;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.*;

/** Sustained document/registry/query history, independent from fresh mutation rows. */
public class LanguageProfileEditReplayConformanceTest {
    @Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final List<String> LANGUAGES = List.of("java", "typescript", "rust");
    private static final LanguageQueries.Provider DISABLED = new LanguageQueries.Provider() {
        public Set<Operation> capabilities() { return Set.of(); }
        public LanguageQueries.Response query(LanguageQueries.Request request) { throw new AssertionError("unsupported provider invoked"); }
    };
    private record Retained(DocumentSnapshot host, EmbeddedLanguages.Result parsed, LanguageQueries.Project project,
                            LanguageQueries queries) {}
    private static Retained bind(DocumentSnapshot host, Language language, Map<Language, EmbeddedLanguages.Grammar> registry) {
        var parsed = EmbeddedLanguages.parse(host, language, registry, 4, 64);
        var project = new LanguageQueries.Project("edit-replay", host.version(), Map.of(host.uri(), host), Map.of());
        var queries = new LanguageQueries(parsed.tree(), project, Map.of(language, DISABLED));
        return new Retained(host, parsed, project, queries);
    }
    @Test public void persistentThreeProfileSessionsRejectAllHistoricalBindings() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] profile edit replay requires -DrustConformance=true and Rust1.85");
        Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        Path directory = temporary.newFolder().toPath();
        List<JavaFileObject> units = new ArrayList<>();
        for (String language : LANGUAGES) {
            Path profilePath = ROOT.resolve("language-profiles/" + language + "/profile.tsv");
            var profile = LanguageProfile.parse(Files.readString(profilePath));
            var grammar = UBNFMapper.parse(Files.readString(profilePath.getParent().resolve(profile.grammarFile()))).grammars().get(0);
            for (var generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
                var generated = generator.generate(grammar);
                units.add(new SimpleJavaFileObject(URI.create("string:///" + generated.packageName().replace('.', '/') + "/" + generated.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    @Override public CharSequence getCharContent(boolean ignored) { return generated.source(); }
                });
            }
            Path module = Files.createDirectory(directory.resolve(language));
            var generated = new RustBackend().generate(grammar);
            for (var file : generated) Files.writeString(module.resolve(file.relativePath()), file.content());
            Path nativeModule = directory.resolve("native-" + language);
            run(List.of("cargo", "run", "--quiet", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--bin", "unlaxer", "--", "generate", "--grammar", profilePath.getParent().resolve(profile.grammarFile()).toString(), "--output", nativeModule.toString()));
            for (var file : generated) assertEquals(language + ":" + file.relativePath(), file.content(), Files.readString(nativeModule.resolve(file.relativePath())));
        }
        Path classes = Files.createDirectory(directory.resolve("classes"));
        var errors = new StringWriter(); var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(errors.toString(), compiler.getTask(new PrintWriter(errors), manager, null,
                List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()), null, units).call());
        }
        Path fixture = ROOT.resolve("docs/fixtures/language-profile-edit-replay/edits.tsv");
        List<String> observations = new ArrayList<>();
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            for (String language : LANGUAGES) {
                var profile = LanguageProfile.parse(Files.readString(ROOT.resolve("language-profiles/" + language + "/profile.tsv")));
                String entry = switch (language) { case "java" -> "CompilationUnit"; case "typescript" -> "SourceFile"; default -> "Crate"; };
                Language identity = profile.identity(entry);
                assertEquals(LanguageProfile.Support.UNSUPPORTED, profile.capabilities().get("CODE_ACTION"));
                assertEquals(LanguageProfile.Support.EXTERNAL, profile.capabilities().get("VALIDATE"));
                String prefix = "org.unlaxer.languages." + language + "." + identity.grammar();
                var grammar = (EmbeddedLanguages.Grammar) loader.loadClass(prefix + "Parsers").getMethod("embeddedGrammar").invoke(null);
                var mapper = loader.loadClass(prefix + "Mapper");
                // One stable registry and document session for all 48 changes. Historical
                // bindings remain alive, rather than restarting a process per fixture row.
                Map<Language, EmbeddedLanguages.Grammar> registry = Map.of(identity, grammar);
                Retained current = bind(new DocumentSnapshot("file:///edit-replay." + language, 0, ""), identity, registry);
                List<Retained> history = new ArrayList<>();
                int changes = 0;
                for (String row : Files.readAllLines(fixture)) {
                    if (!row.startsWith(language + "\t")) continue;
                    String[] fields = row.split("\t");
                    int step = Integer.parseInt(fields[1]); assertEquals(++changes, step);
                    assertEquals(entry, fields[2]);
                    history.add(current);
                    var edits = List.of(new Edit(new Span(Integer.parseInt(fields[3]), Integer.parseInt(fields[4])), decode(fields[5])));
                    DocumentSnapshot next = current.parsed().tree().apply(current.host(), step, edits);
                    assertEquals(current.host().uri(), next.uri()); assertEquals(step, next.version());
                    assertEquals(decode(fields[6]), next.text());
                    assertEquals(Integer.parseInt(fields[8]), next.length());
                    assertEquals(Integer.parseInt(fields[9]), next.text().length());
                    assertEquals(new DocumentSnapshot.Position(Integer.parseInt(fields[10]), Integer.parseInt(fields[11])), next.lsp(next.length()));
                    current = bind(next, identity, registry);
                    var region = current.parsed().regions().get(0);
                    boolean accepted = Boolean.parseBoolean(fields[7]);
                    assertEquals(accepted ? State.COMPLETE : State.FAILED, region.parseState());
                    assertEquals(new Span(0, next.length()), region.full());
                    assertEquals(next, region.sourceMap().output());
                    assertEquals(1, current.parsed().regions().size());
                    var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, next.text());
                    assertEquals(accepted, diagnostic.isEmpty());
                    if (accepted) {
                        Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, next.text());
                        Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                        var span = (Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, ast);
                        assertArrayEquals(new int[]{0, next.length()}, (int[]) span.orElseThrow());
                    } else {
                        assertEquals(next.length(), diagnostic.orElseThrow().getClass().getMethod("farthestOffset").invoke(diagnostic.orElseThrow()));
                    }
                    var unsupported = current.queries().query(next, current.project(), 0, Operation.CODE_ACTION, Map.of());
                    assertEquals(State.valueOf(fields[13]), unsupported.state()); assertTrue(unsupported.items().isEmpty());
                    // Every past query/edit/source identity is checked against the current
                    // state, including older versions whose text happens to recur.
                    final Retained latest = current;
                    for (Retained old : history) {
                        assertThrows(IllegalArgumentException.class, () -> old.queries().query(latest.host(), latest.project(), 0, Operation.CODE_ACTION, Map.of()));
                        assertThrows(IllegalArgumentException.class, () -> latest.queries().query(old.host(), old.project(), 0, Operation.CODE_ACTION, Map.of()));
                        assertThrows(IllegalArgumentException.class, () -> old.parsed().tree().apply(latest.host(), step + 1, List.of(new Edit(new Span(0, 0), "bad"))));
                        assertEquals(old.host(), old.parsed().snapshot());
                        assertEquals(old.host(), old.queries().host());
                    }
                    assertEquals(Integer.parseInt(fields[12]), history.size());
                    assertThrows(IllegalArgumentException.class, () -> latest.parsed().tree().apply(latest.host(), step, edits));
                    assertThrows(IllegalArgumentException.class, () -> latest.queries().query(new DocumentSnapshot(next.uri(), next.version(), next.text() + "x"), latest.project(), 0, Operation.CODE_ACTION, Map.of()));
                    assertEquals(next, current.host()); assertEquals(next, current.queries().host());
                    observations.add(String.join("\t", language, Integer.toString(step), entry, Boolean.toString(accepted), fields[8], fields[9], fields[10], fields[11], Integer.toString(history.size()), unsupported.state().name()));
                }
                assertEquals(48, changes);
            }
        }
        String rustc = run(List.of("rustup", "which", "rustc", "--toolchain", "1.85.0")).trim();
        Path runtime = directory.resolve("libunlaxer_runtime.rlib");
        run(List.of(rustc, "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime", ROOT.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        Path probe = directory.resolve("main.rs"); Files.copy(ROOT.resolve("docs/fixtures/language-profile-edit-replay/probe.rs"), probe);
        Path executable = directory.resolve("probe");
        run(List.of(rustc, "--edition=2021", "-Awarnings", "--extern", "unlaxer_runtime=" + runtime, probe.toString(), "-o", executable.toString()));
        List<String> nativeRows = run(List.of(executable.toString(), ROOT.resolve("language-profiles").toString(), fixture.toString())).lines().toList();
        assertEquals(observations, nativeRows);
        assertEquals(144, observations.size());
        List<String> evidence = new ArrayList<>(); evidence.add("backend\tlanguage\tversion\tentry\taccepted\tcp_length\tutf16_length\teof_line\teof_character\trejected_history\tquery_state");
        for (String row : observations) evidence.add("java\t" + row);
        for (String row : nativeRows) evidence.add("rust\t" + row);
        Files.write(Path.of("target/language-profile-edit-replay.tsv"), evidence);
    }
    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        var builder = new ProcessBuilder(command).directory(ROOT.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("CARGO_INCREMENTAL", "0"); builder.environment().put("CARGO_TARGET_DIR", ROOT.resolve("rust/target").toString());
        var process = builder.start();
        try {
            assertTrue("timeout: " + command, process.waitFor(120, TimeUnit.SECONDS));
            assertEquals(command + "\n" + Files.readString(log), 0, process.exitValue());
            return Files.readString(log);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
    private static String decode(String value) { return value.equals("-") ? "" : new String(HexFormat.of().parseHex(value), StandardCharsets.UTF_8); }
}
