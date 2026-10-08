package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFModuleLoader;
import org.unlaxer.dsl.bootstrap.UBNFPackageResolver;
import org.unlaxer.dsl.codegen.rust.RustBackend;

/** Shared rejection fixtures exercise real pinned artifacts through both complete module loaders. */
public class PackageResolverConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path repository = Path.of("..").toAbsolutePath().normalize();
    @Test public void packageResolutionFailuresMatchAcrossJavaAndNativeRust() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] package conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        assertEquals(0, run(List.of("cargo", "build", "--locked", "--manifest-path", repository.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path nativeGenerator = repository.resolve("rust/target/debug/unlaxer");
        JsonArray fixtures = JsonParser.parseString(Files.readString(repository.resolve("unlaxer-dsl/src/test/resources/packages/invalid.json"))).getAsJsonArray();
        List<String> report = new ArrayList<>(List.of("fixture\tphase\tjava_message\tnative_message"));
        for (JsonElement item : fixtures) {
            JsonObject fixture = item.getAsJsonObject();
            Path directory = temporary.newFolder().toPath();
            for (var file : fixture.getAsJsonObject("files").entrySet()) Files.writeString(directory.resolve(file.getKey()), file.getValue().getAsString());
            Path manifest = directory.resolve("ubnf.json");
            boolean resolve = fixture.get("operation").getAsString().equals("resolve");
            if (!resolve && (!fixture.has("mutation") || !fixture.get("mutation").getAsString().equals("missing-lock"))) {
                UBNFPackageResolver.resolve(manifest);
                mutate(directory, fixture.has("mutation") ? fixture.get("mutation").getAsString() : "none");
            }
            Exception failure = assertThrows(fixture.get("name").getAsString(), Exception.class, () -> {
                if (resolve) UBNFPackageResolver.resolve(manifest);
                else new RustBackend().generate(UBNFModuleLoader.load(directory.resolve("root.ubnf")).grammars().get(0));
            });
            Run nativeResult = run(resolve ? List.of(nativeGenerator.toString(), "deps", "resolve", "--manifest", manifest.toString())
                : List.of(nativeGenerator.toString(), "generate", "--grammar", directory.resolve("root.ubnf").toString(), "--output", directory.resolve("generated").toString()), true);
            assertNotEquals(fixture.get("name").getAsString() + ": " + nativeResult.output(), 0, nativeResult.code());
            String message = fixture.get("message").getAsString();
            assertTrue(fixture.get("name") + ": " + failure, failure.getMessage().contains(message));
            assertTrue(fixture.get("name") + ": " + nativeResult.output(), nativeResult.output().contains(message));
            assertFalse(Files.exists(directory.resolve("generated")));
            report.add(fixture.get("name").getAsString() + "\t" + fixture.get("operation").getAsString() + "\t" + failure.getMessage() + "\t" + nativeResult.output().strip());
        }
        Files.write(Path.of("target/rust-packages.tsv"), report, StandardCharsets.UTF_8);
    }
    @Test public void lockedGenerationNeverConnectsToArtifactOrigin() throws Exception {
        assumeTrue(Boolean.getBoolean("rustConformance"));
        assertEquals(0, run(List.of("cargo", "build", "--locked", "--manifest-path", repository.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator"), false).code());
        Path directory = temporary.newFolder().toPath();
        Path manifest = directory.resolve("ubnf.json");
        Files.writeString(manifest, "{\"schemaVersion\":1,\"dependencies\":{\"std/layout\":{\"version\":\"1.0.0\",\"source\":\"builtin:std/layout@1.0.0\"}}}");
        UBNFPackageResolver.resolve(manifest);
        Files.writeString(directory.resolve("root.ubnf"), "grammar G { @import layout from 'pkg:std/layout' @ubnf: v2 @whitespace: layout.SPACES_AND_COMMENTS @root @mapping(Root,params=[value]) Root ::= 'a' @value 'b'; }");
        try (java.net.ServerSocket observer = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            observer.setSoTimeout(250);
            JsonObject configuration = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
            configuration.getAsJsonObject("dependencies").getAsJsonObject("std/layout").addProperty("source", "https://localhost:" + observer.getLocalPort() + "/artifact.json");
            Files.writeString(manifest, configuration.toString());
            var grammar = UBNFModuleLoader.load(directory.resolve("root.ubnf")).grammars().get(0);
            Run nativeResult = run(List.of(repository.resolve("rust/target/debug/unlaxer").toString(), "generate", "--grammar", directory.resolve("root.ubnf").toString(), "--output", directory.resolve("generated").toString()), true);
            assertEquals(nativeResult.output(), 0, nativeResult.code());
            for (var file : new RustBackend().generate(grammar)) assertEquals(file.relativePath(), file.content(), Files.readString(directory.resolve("generated").resolve(file.relativePath())));
            assertThrows(java.net.SocketTimeoutException.class, observer::accept);
        }
    }

    private void mutate(Path directory, String mutation) throws Exception {
        if (mutation.equals("none")) return;
        JsonObject lock = JsonParser.parseString(Files.readString(directory.resolve("ubnf.lock.json"))).getAsJsonObject();
        JsonObject pinned = lock.getAsJsonObject("packages").getAsJsonObject("std/layout");
        Path cache = directory.resolve(".ubnf-cache/packages/" + pinned.get("sha256").getAsString() + ".json");
        switch (mutation) {
            case "missing-cache" -> Files.delete(cache);
            case "hash-mismatch" -> Files.writeString(cache, "corrupted bytes");
            case "stale-lock" -> {
                JsonObject manifest = JsonParser.parseString(Files.readString(directory.resolve("ubnf.json"))).getAsJsonObject();
                manifest.getAsJsonObject("dependencies").getAsJsonObject("std/layout").addProperty("version", "2.0.0");
                Files.writeString(directory.resolve("ubnf.json"), manifest.toString());
            }
            case "lock-cycle" -> {
                pinned.getAsJsonObject("dependencies").addProperty("std/layout", "1.0.0");
                Files.writeString(directory.resolve("ubnf.lock.json"), lock.toString());
            }
            case "invalid-lock-hash" -> {
                pinned.addProperty("sha256", "../invalid");
                Files.writeString(directory.resolve("ubnf.lock.json"), lock.toString());
            }
            default -> { }
        }
    }
    private record Run(int code, String output) {}
    private Run run(List<String> command, boolean offline) throws Exception {
        Path log = temporary.newFile().toPath();
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        if (offline) { builder.environment().put("PATH", ""); builder.environment().put("JAVA_HOME", "/nonexistent"); }
        Process process = builder.start();
        if (!process.waitFor(180, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("package process timed out"); }
        return new Run(process.exitValue(), Files.readString(log));
    }
}
