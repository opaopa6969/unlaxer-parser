package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.rust.RustBackend;
import org.unlaxer.source.*;
import org.unlaxer.source.LanguageRegions.*;
import org.unlaxer.source.ProviderProtocol.*;

/** Independent coverage corpus, real generated parsers and fixed official compiler adapters. */
public class LanguageProfileConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repo = Path.of("..").toAbsolutePath().normalize();
    private final Path profiles = repo.resolve("language-profiles");
    private static final List<String> LANGUAGES = List.of("java", "typescript", "rust");
    @Test public void profilesRequirePinnedIdentityAndExplicitCapabilities() throws Exception {
        for (String language : LANGUAGES) {
            String text = Files.readString(profiles.resolve(language).resolve("profile.tsv"));
            LanguageProfile profile = LanguageProfile.parse(text);
            assertEquals(language, profile.language());
            assertEquals("lang/" + language, profile.identity(profile.entries().keySet().iterator().next()).packageId());
            assertEquals(LanguageProfile.Support.UNSUPPORTED, profile.capabilities().get("EXECUTE"));
            assertEquals(profile.canonicalTsv(), LanguageProfile.parse(profile.canonicalTsv()).canonicalTsv());
            for (String malformed : List.of(text + "\n", text + "language\tjava\n", text.replace("0.1.0", "latest"), text.replace("EXECUTE\tUNSUPPORTED", "EXECUTE\tSUPPORTED"), text.replace("corpus.tsv", "../corpus.tsv"), text.replace("profile\t1", "profile\t2"), text.replace("capability\tFORMAT\tUNSUPPORTED\n", ""))) {
                assertThrows(IllegalArgumentException.class, () -> LanguageProfile.parse(malformed));
            }
            assertThrows(IllegalArgumentException.class, () -> profile.identity("Unknown"));
        }
    }
    @Test public void generatedBothHostsMatchSourceProfilesAndOfficialParsers() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] LanguageProfileConformanceTest requires -DrustConformance=true and fixed compiler tools");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        assertTrue(Runtime.version().toString().startsWith("21.0.9"));
        String rustc = run(List.of("rustup", "which", "rustc", "--toolchain", "1.85.0")).trim();
        Path directory = temporary.newFolder().toPath();
        List<JavaFileObject> units = new ArrayList<>();
        for (String language : LANGUAGES) {
            Path profileDir = profiles.resolve(language);
            LanguageProfile profile = LanguageProfile.parse(Files.readString(profileDir.resolve("profile.tsv")));
            var grammar = UBNFMapper.parse(Files.readString(profileDir.resolve(profile.grammarFile()))).grammars().get(0);
            for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
                var generated = generator.generate(grammar);
                units.add(new SimpleJavaFileObject(URI.create("string:///" + generated.packageName().replace('.', '/') + "/" + generated.className() + ".java"), JavaFileObject.Kind.SOURCE) {
                    public CharSequence getCharContent(boolean ignored) { return generated.source(); }
                });
            }
            Path module = Files.createDirectory(directory.resolve(language));
            for (var generated : new RustBackend().generate(grammar)) Files.writeString(module.resolve(generated.relativePath()), generated.content());
            Path nativeModule = directory.resolve("native-" + language);
            run(List.of("cargo", "run", "--quiet", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--", "generate", "--grammar", profileDir.resolve(profile.grammarFile()).toString(), "--output", nativeModule.toString()));
            for (var generated : new RustBackend().generate(grammar)) assertEquals(language + " " + generated.relativePath(), generated.content(), Files.readString(nativeModule.resolve(generated.relativePath())));
            Path playground = directory.resolve("playground-" + language);
            run(List.of("cargo", "run", "--quiet", "--manifest-path", repo.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator", "--", "playground", "--profile", profileDir.resolve("profile.tsv").toString(), "--output", playground.toString()));
            for (var file : org.unlaxer.dsl.codegen.rust.PlaygroundGenerator.generateProfile(profileDir.resolve("profile.tsv")).entrySet()) {
                assertEquals(language + " " + file.getKey(), file.getValue(), Files.readString(playground.resolve(file.getKey())));
            }
            run(List.of("node", playground.resolve("build.mjs").toString()));
            run(List.of("node", repo.resolve("unlaxer-dsl/ubnf-vscode/scripts/language-profile-browser.mjs").toString(), playground.resolve("public").toString(), language));

        }
        Path classes = Files.createDirectory(directory.resolve("classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()), null, units).call());
        }
        List<String> observations = new ArrayList<>();
        List<String> officialObservations = new ArrayList<>();
        Path officialDirectory = Files.createDirectory(directory.resolve("official"));
        List<String> nativeOfficial = new ArrayList<>();
        try (var loader = new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            for (String language : LANGUAGES) {
                Path profileDir = profiles.resolve(language);
                LanguageProfile profile = LanguageProfile.parse(Files.readString(profileDir.resolve("profile.tsv")));
                String name = profile.identity(profile.entries().keySet().iterator().next()).grammar();
                String prefix = "org.unlaxer.languages." + language + "." + name;
                var grammar = (EmbeddedLanguages.Grammar) loader.loadClass(prefix + "Parsers").getMethod("embeddedGrammar").invoke(null);
                var mapper = loader.loadClass(prefix + "Mapper");
                List<String> corpus = new ArrayList<>(Files.readAllLines(profileDir.resolve(profile.fixtureFile())));
                corpus.addAll(Files.readAllLines(profileDir.resolve("mutations.tsv")));
                for (String row : corpus) {
                    String[] fields = row.split("\t");
                    String source = new String(HexFormat.of().parseHex(fields[2]), StandardCharsets.UTF_8);
                    DocumentSnapshot snapshot = new DocumentSnapshot("file:///profile", 7, source);
                    assertEquals(Integer.parseInt(fields[4]), snapshot.length());
                    Language identity = profile.identity(fields[1]);
                    var result = grammar.parse(identity.entry(), snapshot);
                    boolean accepted = result.state() == State.COMPLETE;
                    assertEquals(language + " " + fields[0], Boolean.parseBoolean(fields[3]), accepted);
                    observations.add(String.join("\t", language, fields[0], identity.entry(), Boolean.toString(accepted), fields[4]));
                    if (!fields[5].equals("SKIP")) {
                        var diagnostic = (Optional<?>) mapper.getMethod("diagnose", String.class).invoke(null, source);
                        assertEquals(accepted, diagnostic.isEmpty());
                        if (!accepted && (fields[0].equals("unclosed") || fields[0].endsWith("remove-close"))) {
                            assertEquals(snapshot.length(), diagnostic.get().getClass().getMethod("farthestOffset").invoke(diagnostic.get()));
                        }
                        if (accepted) {
                            Object mapped = mapper.getMethod("parseWithSourceMap", String.class).invoke(null, source);
                            Object ast = mapped.getClass().getMethod("ast").invoke(mapped);
                            var span = (Optional<?>) mapped.getClass().getMethod("sourceSpanOf", Object.class).invoke(mapped, ast);
                            assertArrayEquals(fields[0], new int[]{0, snapshot.length()}, (int[]) span.orElseThrow());
                        }
                        if (!fields[5].equals("GENERATED")) official(profile, fields, source, rustc, officialObservations, officialDirectory, nativeOfficial);
                    }
                }
            }
        }
        Path runtime = directory.resolve("libunlaxer_runtime.rlib");
        run(List.of(rustc, "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime", repo.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()));
        Path probe = directory.resolve("main.rs");
        Files.copy(repo.resolve("docs/fixtures/language-profiles/probe.rs"), probe);
        run(List.of(rustc, "--edition=2021", "-Awarnings", "--extern", "unlaxer_runtime=" + runtime, probe.toString(), "-o", directory.resolve("probe").toString()));
        assertEquals(observations, run(List.of(directory.resolve("probe").toString(), profiles.toString())).lines().toList());
        Files.write(officialDirectory.resolve("expected.tsv"), nativeOfficial);
        Path officialProbe = directory.resolve("official-probe");
        run(List.of(rustc, "--edition=2021", "--extern", "unlaxer_runtime=" + runtime, repo.resolve("docs/fixtures/language-profiles/official_probe.rs").toString(), "-o", officialProbe.toString()));
        assertEquals(nativeOfficial, run(List.of(officialProbe.toString(), officialDirectory.toString(), repo.toString(), Path.of(System.getProperty("java.home"), "bin", "java").toString(), System.getProperty("java.class.path"), repo.resolve("unlaxer-dsl/ubnf-vscode/node_modules/typescript/lib/typescript.js").toString(), rustc)).lines().toList());
        Files.write(Path.of("target/language-profile-official-positions.tsv"), nativeOfficial);
        Files.write(Path.of("target/language-profile-conformance.tsv"), observations);
        Files.write(Path.of("target/language-profile-official.tsv"), officialObservations);
    }
    private void official(LanguageProfile profile, String[] fields, String source, String rustc, List<String> observations, Path directory, List<String> nativeOfficial) throws Exception {
        String language = profile.language();
        String provider = language.equals("java") ? "javac" : language.equals("rust") ? "rustc" : "typescript";
        String version = language.equals("java") ? "21.0.9" : language.equals("rust") ? "1.85.0" : "5.9.3";
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = language.equals("java") ? List.of(java, "-cp", System.getProperty("java.class.path"), "org.unlaxer.dsl.provider.JavacProvider")
            : List.of("python3", repo.resolve("scripts/language-providers/provider.py").toString(), language, language.equals("rust") ? rustc : repo.resolve("unlaxer-dsl/ubnf-vscode/node_modules/typescript/lib/typescript.js").toString());
        var snapshot = new DocumentSnapshot("file:///Main." + (language.equals("java") ? "java" : language.equals("rust") ? "rs" : "ts"), 7, source);
        var project = new LanguageQueries.Project("profile-corpus", 1, Map.of(), Map.of());
        var identity = new Identity(provider, version);
        var process = new ProviderProcess(command, identity, Set.of(Operation.PARSE, Operation.VALIDATE), Duration.ofSeconds(30));
        Operation operation = language.equals("rust") ? Operation.VALIDATE : Operation.PARSE;
        var request = new Request(fields[0], identity, profile.identity(fields[1]), "root", snapshot, project, operation, 0, Map.of(), false);
        ProviderProtocol.Response response = process.invoke(request);
        assertTrue(language + " " + fields[0] + " " + response.status(), response.status() == Status.OK || response.status() == Status.DIAGNOSTICS);
        recordOfficial(language + "-" + fields[0], request, response, directory, nativeOfficial);
        boolean errors = response.diagnostics().stream().anyMatch(d -> d.severity().equals("ERROR"));
        boolean expected = fields[5].equals("SYNTAX") || language.equals("rust") && fields[5].equals("SEMANTIC");
        assertEquals(language + " " + fields[0], expected, errors);
        if (fields[5].equals("SEMANTIC") && !language.equals("rust")) {
            var typedRequest = new Request(fields[0] + "-typed", identity, profile.identity(fields[1]), "root", snapshot, project, Operation.VALIDATE, 0, Map.of(), false);
            ProviderProtocol.Response typed = process.invoke(typedRequest);
            recordOfficial(language + "-" + fields[0] + "-typed", typedRequest, typed, directory, nativeOfficial);
            assertEquals(Status.DIAGNOSTICS, typed.status());
            assertTrue(typed.diagnostics().stream().anyMatch(d -> d.severity().equals("ERROR")));
        }
        observations.add(String.join("\t", language, fields[0], fields[5], Boolean.toString(errors)));
    }
    private static void recordOfficial(String name, Request request, ProviderProtocol.Response response, Path directory, List<String> observations) throws Exception {
        Files.writeString(directory.resolve(name + ".wire"), ProviderProtocol.encode(request).text());
        String diagnostics = response.diagnostics().stream().flatMap(d -> d.locations().stream().map(location -> d.code() + "@" + location.snapshot().uri() + ":" + location.span().start() + ":" + location.span().end() + ":" + d.severity())).sorted().collect(java.util.stream.Collectors.joining(","));
        observations.add(String.join("\t", name, response.status().name(), diagnostics.isEmpty() ? "-" : diagnostics, "-"));
    }
    private String run(List<String> command) throws Exception {
        Path log = temporary.newFile().toPath();
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).redirectOutput(log.toFile()).start();
        assertTrue("timeout " + command, process.waitFor(120, TimeUnit.SECONDS));
        String output = Files.readString(log);
        assertEquals(command + "\n" + output, 0, process.exitValue()); return output;
    }
}
