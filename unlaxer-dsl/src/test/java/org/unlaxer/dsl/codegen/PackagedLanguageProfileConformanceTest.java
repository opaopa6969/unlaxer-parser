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
