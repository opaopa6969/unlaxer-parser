package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.UBNFPackageResolver;

/** Shared filesystem fixtures go through both real deps resolve CLIs, including relative argv. */
public class PackageLocalPathConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path REPOSITORY = Path.of("..").toAbsolutePath().normalize();
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();

    @BeforeClass public static void buildNativeCli() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] local path conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path log = Path.of("target/package-local-path-build.log");
        Process process = new ProcessBuilder("cargo", "build", "--locked", "--manifest-path",
            REPOSITORY.resolve("rust/Cargo.toml").toString(), "-p", "unlaxer-generator")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("cargo build timeout", process.waitFor(180, TimeUnit.SECONDS));
            assertEquals(Files.readString(log), 0, process.exitValue());
        } finally { stop(process); }
    }

    @Test(timeout=300000) public void lexicalPathsPublishIdenticalLockAndCacheBytes() throws Exception {
        check(false, 19);
    }

    @Test(timeout=300000) public void invalidPathsKeepSnapshotsAndDiagnosticsIdentical() throws Exception {
        check(true, 11);
    }

    private void check(boolean rejected, int count) throws Exception {
        var fixtures = JsonParser.parseString(Files.readString(REPOSITORY.resolve(
            "spec-corpus/package-local-paths.json"))).getAsJsonArray();
        assertEquals("fixtures must not silently disappear", 30, fixtures.size());
        byte[] standard = Files.readAllBytes(REPOSITORY.resolve(
            "unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json"));
        var evidence = new JsonArray();
        int executed = 0;
        for (var item : fixtures) {
            JsonObject fixture = item.getAsJsonObject();
            if (fixture.has("error") != rejected) continue;
            executed++;
            String name = fixture.get("id").getAsString();
            Path folder = temporary.newFolder(name).toPath().toAbsolutePath();
            setup(folder, fixture, standard);
            // Both an existing snapshot and a first resolution must be transactional.
            for (boolean existing : new boolean[] {false, true}) {
                Map<String, String> javaResult = null;
                for (boolean nativeRust : new boolean[] {false, true}) {
                    resetSnapshot(folder, existing, standard);
                    var before = snapshot(folder);
                    Run result = run(folder, fixture, nativeRust);
                    var after = snapshot(folder);
                    String context = name + "/" + (existing ? "existing" : "absent") + "/" + (nativeRust ? "rust" : "java");
                    if (rejected) {
                        assertNotEquals(context, 0, result.code());
                        assertEquals(context, "E-PACKAGE: " + fixture.get("error").getAsString(), result.error().strip().lines().reduce((first, last) -> last).orElse(""));
                        assertEquals(context + ": failed resolve published files", before, after);
                    } else {
                        assertEquals(context + ": " + result.error(), 0, result.code());
                        byte[] child = Files.readAllBytes(folder.resolve(value(fixture, "expectedArtifact", "artifact.json")));
                        Map<String, byte[]> artifacts = new TreeMap<>();
                        artifacts.put("std/layout", child);
                        if (fixture.has("childSource")) artifacts.put("team/parent", Files.readAllBytes(
                            folder.resolve(value(fixture, "expectedParent", "nested/parent.json"))));
                        var expected = new TreeMap<>(before);
                        expected.put("ubnf.lock.json", hex(lock(artifacts).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        expected.put(".ubnf-cache/", "directory");
                        expected.put(".ubnf-cache/packages/", "directory");
                        artifacts.values().forEach(bytes -> expected.put(".ubnf-cache/packages/"
                            + UBNFPackageResolver.sha256(bytes) + ".json", hex(bytes)));
                        assertEquals(context + ": fixed byte oracle", expected, after);
                    }
                    if (nativeRust) assertEquals(context + ": Java/Rust bytes", javaResult, after);
                    else javaResult = after;
                    var record = new JsonObject();
                    record.addProperty("case", name);
                    record.addProperty("host", nativeRust ? "rust" : "java");
                    record.addProperty("existingSnapshot", existing);
                    record.addProperty("exit", result.code());
                    record.addProperty("stderr", result.error());
                    record.add("snapshot", JSON.toJsonTree(after));
                    evidence.add(record);
                    System.out.println(context + ": OK");
                }
            }
        }
        assertEquals(count, executed);
        Files.writeString(Path.of("target/package-local-path-" + (rejected ? "rejected" : "accepted") + ".json"),
            JSON.toJson(evidence) + "\n");
    }

    private static String value(JsonObject fixture, String key, String fallback) {
        return fixture.has(key) ? fixture.get(key).getAsString() : fallback;
    }

    private static void setup(Path folder, JsonObject fixture, byte[] standard) throws Exception {
        for (String directory : List.of("nested", "a", "physical/deep", "space \uD83D\uDE00")) Files.createDirectories(folder.resolve(directory));
        for (String path : List.of("artifact.json", "a/x.json", "nested/child.json", "space \uD83D\uDE00/artifact.json")) Files.write(folder.resolve(path), standard);
        byte[] different = Arrays.copyOf(standard, standard.length + 1);
        different[different.length - 1] = ' ';
        for (String path : List.of("physical/x.json", "physical/child.json", "physical/standard.json"))
            Files.write(folder.resolve(path), different);
        Files.createSymbolicLink(folder.resolve("a/link"), Path.of("../physical/deep"));
        Files.createSymbolicLink(folder.resolve("file-link.json"), Path.of("physical/standard.json"));
        Files.createSymbolicLink(folder.resolve("nested/alias.json"), Path.of("../physical/parent.json"));
        boolean transitive = fixture.has("childSource");
        if (transitive) {
            String parent = JSON.toJson(Map.of("schemaVersion", 1, "id", "team/parent", "version", "1.0.0",
                "entry", "parent.ubnf", "files", Map.of("parent.ubnf", "grammar Parent { @ubnf: v2 token P ::= 'p'; }"),
                "dependencies", Map.of("std/layout", Map.of("version", "1.0.0", "source", fixture.get("childSource").getAsString()))));
            Files.writeString(folder.resolve("nested/parent.json"), parent);
            Files.writeString(folder.resolve("physical/parent.json"), parent);
        }
        String source = fixture.get("source").getAsString().replace("${ROOT}", folder.toString());
        Files.writeString(folder.resolve("ubnf.json"), JSON.toJson(Map.of("schemaVersion", 1,
            "dependencies", Map.of(transitive ? "team/parent" : "std/layout", Map.of("version", "1.0.0", "source", source)))));
    }

    private static String lock(Map<String, byte[]> artifacts) {
        var packages = new TreeMap<String, Object>();
        artifacts.forEach((id, bytes) -> packages.put(id, new TreeMap<>(Map.of("id", id, "version", "1.0.0",
            "sha256", UBNFPackageResolver.sha256(bytes), "dependencies",
            id.equals("team/parent") ? Map.of("std/layout", "1.0.0") : Map.of()))));
        return JSON.toJson(new TreeMap<>(Map.of("schemaVersion", 1, "packages", packages,
            "roots", Map.of(artifacts.containsKey("team/parent") ? "team/parent" : "std/layout", "1.0.0")))) + "\n";
    }

    private static void resetSnapshot(Path folder, boolean existing, byte[] standard) throws Exception {
        Path cache = folder.resolve(".ubnf-cache");
        if (Files.exists(cache)) {
            try (var paths = Files.walk(cache)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Files.deleteIfExists(folder.resolve("ubnf.lock.json"));
        if (existing) {
            Files.createDirectories(cache.resolve("packages"));
            Files.write(cache.resolve("packages/" + UBNFPackageResolver.sha256(standard) + ".json"), standard);
            Files.writeString(cache.resolve("keep.txt"), "unrelated cached data\n");
            Files.writeString(folder.resolve("ubnf.lock.json"), lock(Map.of("std/layout", standard)));
        }
    }

    private static Map<String, String> snapshot(Path folder) throws Exception {
        var result = new TreeMap<String, String>();
        Path lock = folder.resolve("ubnf.lock.json"), cache = folder.resolve(".ubnf-cache");
        if (Files.exists(lock)) result.put("ubnf.lock.json", hex(Files.readAllBytes(lock)));
        if (Files.exists(cache)) {
            try (var paths = Files.walk(cache)) {
                for (Path path : paths.toList()) {
                    String name = folder.relativize(path).toString().replace(File.separatorChar, '/');
                    result.put(name + (Files.isDirectory(path) ? "/" : ""),
                        Files.isDirectory(path) ? "directory" : hex(Files.readAllBytes(path)));
                }
            }
        }
        return result;
    }

    private static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    private record Run(int code, String error) {}

    private static Run run(Path folder, JsonObject fixture, boolean nativeRust) throws Exception {
        // The working directory changes; every Java classpath entry must remain absolute.
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(path -> Path.of(path).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        var command = nativeRust ? new ArrayList<>(List.of(REPOSITORY.resolve("rust/target/debug/unlaxer").toString()))
            : new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp", classpath, "org.unlaxer.dsl.CodegenMain"));
        command.addAll(List.of("deps", "resolve", "--manifest",
            value(fixture, "manifest", "ubnf.json").replace("${ROOT}", folder.toString())));
        Path stdout = folder.resolve("stdout.log"), stderr = folder.resolve("stderr.log");
        var builder = new ProcessBuilder(command).directory(folder.resolve(value(fixture, "workingDirectory", ".")).toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
        if (nativeRust) { builder.environment().put("PATH", ""); builder.environment().put("JAVA_HOME", "/nonexistent-java"); }
        Process process = builder.start();
        try {
            assertTrue("deps resolve timeout: " + fixture.get("id"), process.waitFor(20, TimeUnit.SECONDS));
            return new Run(process.exitValue(), Files.readString(stderr));
        } finally { stop(process); }
    }

    private static void stop(Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        process.destroyForcibly();
    }
}
