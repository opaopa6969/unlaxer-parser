package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.*;
import org.unlaxer.dsl.codegen.rust.PlaygroundGenerator;

/** Both hosts resolve the same bytes; independent expected identities and explicit failures. */
public class PackagedLanguageProfileConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repository = Path.of("..").toAbsolutePath().normalize();
    private Path nativeGenerator;
    @Before public void nativeHost() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] packaged profile conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        assertEquals(0, run(List.of("cargo", "build", "--locked", "--manifest-path", repository.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        nativeGenerator = repository.resolve("rust/target/debug/unlaxer");
    }
    @Test public void bundledPackagesGenerateIdenticallyWithoutRuntimeResolution() throws Exception {
        for (String language : List.of("java", "typescript", "rust")) {
            Path directory = temporary.newFolder().toPath();
            Path manifest = manifest(directory, "lang/" + language, "builtin:lang/" + language + "@0.1.0");
            String lock = UBNFPackageResolver.resolve(manifest).toString();
            assertEquals(0, run(List.of(nativeGenerator.toString(), "deps", "resolve", "--manifest", manifest.toString()), true).code());
            assertEquals(JsonParser.parseString(lock), JsonParser.parseString(Files.readString(directory.resolve("ubnf.lock.json"))));
            var selected = PackagedLanguageProfile.load(manifest, "lang/" + language);
            assertEquals("lang/" + language, selected.identity().get("id").getAsString());
            assertEquals("0.1.0", selected.identity().get("version").getAsString());
            assertEquals(Files.readString(repository.resolve("language-profiles/" + language + "/" + selected.profile().grammarFile())), selected.source());
            assertEquals(5, selected.profile().entries().size());
            assertEquals(org.unlaxer.source.LanguageProfile.Support.UNSUPPORTED, selected.profile().capabilities().get("EXECUTE"));
            compare(manifest, "lang/" + language);
            Path root = directory.resolve("root.ubnf");
            Files.writeString(root, "grammar G { @import foreign from 'pkg:lang/" + language + "' @ubnf: v2 @root Root ::= 'a'; }");
            assertTrue(assertThrows(IllegalArgumentException.class, () -> UBNFModuleLoader.load(root)).getMessage().contains("only declarative token modules"));
            var rejection = run(List.of(nativeGenerator.toString(), "generate", "--grammar", root.toString(), "--output", directory.resolve("forbidden").toString()), true);
            assertNotEquals(0, rejection.code()); assertTrue(rejection.output(), rejection.output().contains("only declarative token modules"));
            assertFalse(Files.exists(directory.resolve("forbidden")));
        }
    }
    @Test public void packageRootCanUsePinnedTokenDependencies() throws Exception {
        Path directory = temporary.newFolder().toPath();
        JsonObject artifact = javaArtifact();
        var dependency = new JsonObject(); dependency.addProperty("version", "1.0.0"); dependency.addProperty("source", "builtin:std/layout@1.0.0");
        artifact.getAsJsonObject("dependencies").add("std/layout", dependency);
        String source = artifact.getAsJsonObject("files").get("Java21.ubnf").getAsString().replace("grammar Java21 {", "grammar Java21 {\n @import layout from 'pkg:std/layout'").replace("@whitespace: javaStyle", "@whitespace: layout.SPACES_AND_COMMENTS");
        artifact.getAsJsonObject("files").addProperty("Java21.ubnf", source);
        Files.writeString(directory.resolve("artifact.json"), artifact.toString());
        Path manifest = manifest(directory, "lang/java", "local:artifact.json"); UBNFPackageResolver.resolve(manifest);
        var selected = PackagedLanguageProfile.load(manifest, "lang/java");
        assertEquals("std/layout", selected.vocabulary().getAsJsonArray("modules").get(0).getAsJsonObject().getAsJsonObject("identity").get("id").getAsString());
        compare(manifest, "lang/java");
    }
    @Test public void profilePackageAndCacheFailuresRejectBeforeOutput() throws Exception {
        for (String mutation : List.of("missing-lock", "hash", "unknown-root", "profile-id", "profile-version", "entry-file", "missing-profile", "missing-fixture", "entry-rule", "host-binding")) {
            Path directory = temporary.newFolder().toPath(); JsonObject artifact = javaArtifact();
            JsonObject files = artifact.getAsJsonObject("files"); String profile = files.get("profile.tsv").getAsString();
            switch (mutation) {
                case "profile-id" -> files.addProperty("profile.tsv", profile.replace("lang/java", "lang/wrong"));
                case "profile-version" -> files.addProperty("profile.tsv", profile.replace("0.1.0", "0.2.0"));
                case "entry-file" -> files.addProperty("profile.tsv", profile.replace("Java21.ubnf", "Other.ubnf"));
                case "missing-profile" -> files.remove("profile.tsv");
                case "missing-fixture" -> files.remove("corpus.tsv");
                case "entry-rule" -> files.addProperty("profile.tsv", profile.replace("entry\tType\t", "entry\tMissing\t"));
                case "host-binding" -> files.addProperty("Java21.ubnf", files.get("Java21.ubnf").getAsString().replace("token DIGIT ::= CHAR_RANGE('0', '9');", "token DIGIT = org.unlaxer.parser.elementary.WordParser"));
                default -> { }
            }
            Files.writeString(directory.resolve("artifact.json"), artifact.toString());
            Path manifest = manifest(directory, "lang/java", "local:artifact.json");
            if (!mutation.equals("missing-lock")) UBNFPackageResolver.resolve(manifest);
            if (mutation.equals("hash")) {
                Path cache; try (var paths = Files.list(directory.resolve(".ubnf-cache/packages"))) { cache = paths.findFirst().orElseThrow(); }
                Files.writeString(cache, "corrupted bytes");
            }
            String id = mutation.equals("unknown-root") ? "lang/missing" : "lang/java";
            Exception failure = assertThrows(mutation, Exception.class, () -> PlaygroundGenerator.generatePackage(manifest, id));
            Path output = directory.resolve("rejected");
            var result = run(List.of(nativeGenerator.toString(), "playground", "--manifest", manifest.toString(), "--package", id, "--output", output.toString()), true);
            assertNotEquals(mutation + ": " + result.output(), 0, result.code()); assertFalse(Files.exists(output));
            String expected = switch (mutation) {
                case "profile-id", "profile-version" -> "profile package identity mismatch";
                case "entry-file", "entry-rule" -> "profile grammar/entry mismatch";
                case "host-binding" -> "Rust-portable grammar";
                default -> "E-PACKAGE:";
            };
            assertTrue(mutation + ": " + failure, failure.getMessage().contains(expected));
            assertTrue(mutation + ": " + result.output(), result.output().contains(expected));
        }
    }
    @Test public void lockedPackagesExposeEntriesToParentRegistries() throws Exception {
        Path directory = temporary.newFolder().toPath();
        Path fixture = repository.resolve("docs/fixtures/language-profiles/package-entries.tsv");
        List<String[]> rows = Files.readAllLines(fixture).stream().map(line -> line.split("\t", -1)).toList();
        var profiles = new HashMap<String, org.unlaxer.source.LanguageProfile>();
        var units = new ArrayList<javax.tools.JavaFileObject>();
        for (String language : List.of("java", "typescript", "rust")) {
            Path project = Files.createDirectory(directory.resolve(language));
            Path manifest = manifest(project, "lang/" + language, "builtin:lang/" + language + "@0.1.0");
            UBNFPackageResolver.resolve(manifest);
            var selected = PackagedLanguageProfile.load(manifest, "lang/" + language);
            profiles.put(language, selected.profile());
            assertThrows(IllegalArgumentException.class, () -> selected.profile().identity("Missing"));
            var grammar = selected.ast().grammars().get(0);
            packageEntryUnits(grammar, units);
            Path nativeOutput = project.resolve("native");
            assertEquals(0, run(List.of(nativeGenerator.toString(), "playground", "--manifest", manifest.toString(), "--package", "lang/" + language, "--output", nativeOutput.toString()), true).code());
            // The native production path loads the same lock, obtains load.ast and generates it.
            assertEquals(selected.profile().canonicalTsv(), Files.readString(nativeOutput.resolve("public/profile.tsv")));
            for (var generated : new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar)) {
                assertEquals(language + " " + generated.relativePath(), generated.content(), Files.readString(nativeOutput.resolve("src/generated").resolve(generated.relativePath())));
            }
        }
        for (int index = 0; index < rows.size(); index++) {
            String[] row = rows.get(index);
            String source = "grammar Parent" + index + " {\n @package: example.packaged\n @embedded: { rule: 'Root' body: 'body' language: '" + row[1] + "' package: 'lang/" + row[1] + "' version: '" + row[5] + "' grammar: '" + row[2] + "' entry: '" + row[4] + "' }\n token BODY = UNTIL(']F')\n @root @mapping(Host, params=[body]) Root ::= '😀F[' BODY @body ']F';\n}\n";
            var grammar = UBNFMapper.parse(source).grammars().get(0);
            packageEntryUnits(grammar, units);
            Path input = directory.resolve("parent" + index + ".ubnf"); Files.writeString(input, source);
            Path output = directory.resolve("parent" + index);
            assertEquals(0, run(List.of(nativeGenerator.toString(), "generate", "--grammar", input.toString(), "--output", output.toString()), true).code());
            for (var generated : new org.unlaxer.dsl.codegen.rust.RustBackend().generate(grammar)) assertEquals(generated.content(), Files.readString(output.resolve(generated.relativePath())));
        }
        Path classes = Files.createDirectory(directory.resolve("classes"));
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        var diagnostics = new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            assertTrue(diagnostics.getDiagnostics().toString(), compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString()), null, units).call());
        }
        var expected = new ArrayList<String>();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            for (int index = 0; index < rows.size(); index++) {
                String[] row = rows.get(index);
                String body = new String(HexFormat.of().parseHex(row[6]), java.nio.charset.StandardCharsets.UTF_8);
                var host = new org.unlaxer.source.DocumentSnapshot("file:///packaged.formula", 7, "😀F[" + body + "]F");
                var parent = (org.unlaxer.source.EmbeddedLanguages.Grammar) loader.loadClass("example.packaged.Parent" + index + "Parsers").getMethod("embeddedGrammar").invoke(null);
                var child = (org.unlaxer.source.EmbeddedLanguages.Grammar) loader.loadClass("org.unlaxer.languages." + row[1] + "." + row[2] + "Parsers").getMethod("embeddedGrammar").invoke(null);
                var root = new org.unlaxer.source.LanguageRegions.Language("host", "example", "1", "Parent" + index, "Root");
                var registered = profiles.get(row[1]).identity(row[4].equals("Missing") ? row[3] : row[4]);
                var result = org.unlaxer.source.EmbeddedLanguages.parse(host, root, Map.of(root, parent, registered, child), 8, 32);
                assertEquals(row[0], 2, result.regions().size());
                assertEquals(org.unlaxer.source.LanguageRegions.State.COMPLETE, result.regions().get(0).parseState());
                var region = result.regions().get(1);
                String actual = String.join("\t", row[0], region.parseState().name(), region.language().packageId(), region.language().version(), region.language().grammar(), region.language().entry(), Integer.toString(region.full().start()), Integer.toString(region.full().end()), Integer.toString(region.body().start()), Integer.toString(region.body().end()));
                String oracle = String.join("\t", row[0], row[7], "lang/" + row[1], row[5], row[2], row[4], row[8], row[9], row[10], row[11]);
                assertEquals(row[0], oracle, actual); expected.add(oracle);
                assertEquals(body, region.sourceMap().output().text());
                var mapped = region.sourceMap().edit(new org.unlaxer.source.DocumentSnapshot.Span(0, body.codePointCount(0, body.length())));
                assertEquals(host, mapped.snapshot()); assertEquals(region.body(), mapped.span());
                var tree = result.tree();
                assertEquals(root, tree.at(0).language());
                assertEquals(root, tree.at(Integer.parseInt(row[11])).language());
                assertEquals(region.language(), tree.at(Integer.parseInt(row[10])).language());
                assertEquals(org.unlaxer.source.LanguageRegions.State.UNSUPPORTED, child.parse("Missing", new org.unlaxer.source.DocumentSnapshot("child", 7, body)).state());
            }
        }
        StringBuilder nativeSource = new StringBuilder("#![allow(dead_code)]\n");
        for (String language : List.of("java", "typescript", "rust")) nativeSource.append("#[path=\"").append(language).append("/native/src/generated/mod.rs\"] mod ").append(language).append(";\n");
        for (int index = 0; index < rows.size(); index++) nativeSource.append("mod parent").append(index).append(";\n");
        nativeSource.append("fn parents() -> Vec<Box<dyn unlaxer_runtime::embedded::Grammar>> { vec![");
        for (int index = 0; index < rows.size(); index++) nativeSource.append("Box::new(parent").append(index).append("::parser::embedded_grammar()),");
        nativeSource.append("] }\n").append(Files.readString(repository.resolve("docs/fixtures/language-profiles/package_entries_probe.rs")));
        Path probe = directory.resolve("probe.rs"); Files.writeString(probe, nativeSource);
        String rustc = run(List.of("rustup", "which", "rustc", "--toolchain", "1.85.0"), false).output().trim();
        Path runtime = directory.resolve("libunlaxer_runtime.rlib");
        assertEquals(0, run(List.of(rustc, "--edition=2021", "--crate-type=rlib", "--crate-name=unlaxer_runtime", repository.resolve("rust/unlaxer-runtime/src/lib.rs").toString(), "-o", runtime.toString()), false).code());
        var compile = run(List.of(rustc, "--edition=2021", "--extern", "unlaxer_runtime=" + runtime, probe.toString(), "-o", directory.resolve("probe").toString()), false);
        assertEquals(compile.output(), 0, compile.code());
        var observed = run(List.of(directory.resolve("probe").toString(), fixture.toString(), directory.toString()), true);
        assertEquals(observed.output(), 0, observed.code()); assertEquals(expected, observed.output().lines().toList());
        Files.write(Path.of("target/language-profile-package-entries.tsv"), expected);
    }
    private void packageEntryUnits(UBNFAST.GrammarDecl grammar, List<javax.tools.JavaFileObject> units) {
        for (CodeGenerator generator : List.of(new ParserGenerator(), new ASTGenerator(), new MapperGenerator())) {
            var generated = generator.generate(grammar);
            units.add(new javax.tools.SimpleJavaFileObject(java.net.URI.create("string:///" + generated.packageName().replace('.', '/') + "/" + generated.className() + ".java"), javax.tools.JavaFileObject.Kind.SOURCE) {
                @Override public CharSequence getCharContent(boolean ignored) { return generated.source(); }
            });
        }
    }
    private JsonObject javaArtifact() throws Exception { return JsonParser.parseString(Files.readString(repository.resolve("unlaxer-dsl/src/main/resources/ubnf-packages/lang-java-0.1.0.json"))).getAsJsonObject(); }
    private Path manifest(Path directory, String id, String source) throws Exception {
        var dependency = new JsonObject(); dependency.addProperty("version", "0.1.0"); dependency.addProperty("source", source);
        var dependencies = new JsonObject(); dependencies.add(id, dependency);
        var configuration = new JsonObject(); configuration.addProperty("schemaVersion", 1); configuration.add("dependencies", dependencies);
        Path result = directory.resolve("ubnf.json"); Files.writeString(result, configuration.toString()); return result;
    }
    private void compare(Path manifest, String id) throws Exception {
        var expected = PlaygroundGenerator.generatePackage(manifest, id); Path output = manifest.getParent().resolve("playground");
        var result = run(List.of(nativeGenerator.toString(), "playground", "--manifest", manifest.toString(), "--package", id, "--output", output.toString()), true);
        assertEquals(result.output(), 0, result.code());
        for (var file : expected.entrySet()) assertEquals(file.getKey(), file.getValue(), Files.readString(output.resolve(file.getKey())));
    }
    private record Run(int code, String output) {}
    private Run run(List<String> command, boolean offline) throws Exception {
        Path log = temporary.newFile().toPath(); var builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (offline) { builder.environment().put("PATH", ""); builder.environment().put("JAVA_HOME", "/nonexistent"); }
        var process = builder.start(); if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("process timeout"); }
        return new Run(process.exitValue(), Files.readString(log));
    }
}
