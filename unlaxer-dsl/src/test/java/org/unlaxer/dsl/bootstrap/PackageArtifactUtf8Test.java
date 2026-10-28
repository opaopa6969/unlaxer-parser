package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.HexFormat;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** The reference implementation consumes the same raw bytes as the stdio/CLI suite. */
public class PackageArtifactUtf8Test {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cachedArtifactsCheckRawHashBeforeUtf8AndJson() throws Exception {
        check("cache", 15);
    }

    @Test public void explicitLocalArtifactsRejectInvalidUtf8WithoutPublishing() throws Exception {
        check("local", 6);
    }

    private void check(String mode, int expectedCount) throws Exception {
        Path repository = Path.of("..");
        var cases = JsonParser.parseString(Files.readString(repository.resolve(
            "spec-corpus/ubnf-authoring-lsp/package-artifact-utf8.json"))).getAsJsonArray();
        assertEquals(21, cases.size());
        byte[] standard = Files.readAllBytes(repository.resolve(
            "unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json"));
        int count = 0;
        for (var item : cases) {
            var fixture = item.getAsJsonObject();
            if (!fixture.get("mode").getAsString().equals(mode)) continue;
            count++;
            String name = fixture.get("id").getAsString();
            Path folder = temporary.newFolder(name).toPath(), manifest = folder.resolve("ubnf.json");
            Files.writeString(manifest, "{\"schemaVersion\":1,\"dependencies\":{\"std/layout\":{\"version\":\"1.0.0\",\"source\":\"local:artifact.json\"}}}");
            Files.write(folder.resolve("artifact.json"), standard);
            JsonObject lock = UBNFPackageResolver.resolve(manifest);
            byte[] bytes = HexFormat.of().parseHex(fixture.get("bytesHex").getAsString());
            Path cache = folder.resolve(".ubnf-cache/packages");
            boolean cached = mode.equals("cache");
            if (cached) {
                String hash = UBNFPackageResolver.sha256(fixture.has("hashMismatch")
                    && fixture.get("hashMismatch").getAsBoolean() ? standard : bytes);
                lock.getAsJsonObject("packages").getAsJsonObject("std/layout").addProperty("sha256", hash);
                Files.writeString(folder.resolve("ubnf.lock.json"), lock.toString());
                Files.write(cache.resolve(hash + ".json"), bytes);
            } else Files.write(folder.resolve("artifact.json"), bytes);
            byte[] beforeLock = Files.readAllBytes(folder.resolve("ubnf.lock.json"));
            var beforeCache = snapshot(cache);
            Path root = folder.resolve("root.ubnf");
            var resolver = new UBNFPackageResolver(root, UBNFPackageResolver.diskReader());
            String actual = assertThrows(name, IllegalArgumentException.class, () -> {
                if (cached) resolver.importPath(root, "pkg:std/layout");
                else UBNFPackageResolver.resolve(manifest);
            }).getMessage();
            assertEquals(name, "E-PACKAGE: " + fixture.get(cached ? "loadError" : "resolveError").getAsString(), actual);
            assertArrayEquals(name, beforeLock, Files.readAllBytes(folder.resolve("ubnf.lock.json")));
            assertEquals(name, beforeCache, snapshot(cache));
            System.out.println(name + ": " + actual);
        }
        assertEquals(expectedCount, count);
    }

    private static java.util.Map<String, String> snapshot(Path cache) throws Exception {
        var result = new java.util.TreeMap<String, String>();
        try (var files = Files.list(cache)) {
            for (Path file : files.toList()) result.put(file.getFileName().toString(),
                HexFormat.of().formatHex(Files.readAllBytes(file)));
        }
        return result;
    }
}
