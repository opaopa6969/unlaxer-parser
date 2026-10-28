package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Raw JSON, semantic diagnostics and transactional snapshots through both real CLIs. */
public class PackageJsonAcceptanceConformanceTest {
    @Test public void gsonStrictKeepsFirstPositionAndLastDuplicateValue() {
        var gson = new com.google.gson.GsonBuilder().setStrictness(com.google.gson.Strictness.STRICT).create();
        var value = gson.fromJson("{\"b\":1,\"a\":2,\"\\u0062\":3}", com.google.gson.JsonObject.class);
        assertEquals(List.of("b", "a"), List.copyOf(value.keySet()));
        assertEquals(3, value.get("b").getAsInt());
        var reordered = gson.fromJson("{\"a\":2,\"b\":3}", com.google.gson.JsonObject.class);
        assertEquals(reordered, value);
        value = gson.fromJson("{\"b\\ud800\":1,\"a\":2,\"b\\uD800\":3}", com.google.gson.JsonObject.class);
        assertEquals(List.of("b\ud800", "a"), List.copyOf(value.keySet()));
        assertEquals(3, value.get("b\ud800").getAsInt());
    }

    @Test(timeout=1200000) public void packageJsonAcceptanceMatchesAcrossClis() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] package JSON conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Path root = Path.of("..").toAbsolutePath().normalize();
        run(List.of("cargo", "build", "--locked", "--manifest-path", root.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), Path.of("target/package-json-build.log"), 180);
        Path output = Path.of("target/package-json-cli.json").toAbsolutePath();
        run(List.of("python3", root.resolve("scripts/ubnf_package_json.py").toString(),
            "--java", Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--classpath", System.getProperty("java.class.path"),
            "--rust", root.resolve("rust/target/debug/unlaxer").toString(),
            "--output", output.toString()), Path.of("target/package-json-cli.log"), 1000);
        assertEquals("all raw JSON cases executed", 196,
            JsonParser.parseString(Files.readString(output)).getAsJsonArray().size());
    }

    private static void run(List<String> command, Path log, int seconds) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue("process timeout: " + command, process.waitFor(seconds, TimeUnit.SECONDS));
            assertEquals(Files.readString(log), 0, process.exitValue());
        } finally {
            process.descendants().forEach(child -> child.destroyForcibly());
            process.destroyForcibly();
        }
    }
}
