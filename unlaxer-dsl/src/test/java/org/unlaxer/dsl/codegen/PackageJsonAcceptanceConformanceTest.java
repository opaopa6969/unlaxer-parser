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
    @Test public void gsonStrictDocumentKindAndDepthAreTheReference() {
        var gson = new com.google.gson.GsonBuilder().setStrictness(com.google.gson.Strictness.STRICT).create();
        for (String bom : List.of("", "\ufeff")) {
            for (String text : List.of("", " \r\n\t"))
                assertNull(gson.fromJson(bom + text, com.google.gson.JsonObject.class));
            for (String text : List.of("null", "[]", "1", "\"x\"", "true", "false", "\u000b", "\f"))
                assertThrows(com.google.gson.JsonSyntaxException.class,
                    () -> gson.fromJson(bom + text, com.google.gson.JsonObject.class));
        }
        for (String opening : List.of("[", "{\"x\":")) {
            String closing = opening.equals("[") ? "]" : "}";
            for (int depth : new int[]{126, 127, 128, 253, 254, 255, 256, 10000}) {
                String text = "{\"meta\":" + opening.repeat(depth) + "0" + closing.repeat(depth) + "}";
                if (depth <= 254) assertNotNull(gson.fromJson(text, com.google.gson.JsonObject.class));
                else assertThrows(com.google.gson.JsonSyntaxException.class,
                    () -> gson.fromJson(text, com.google.gson.JsonObject.class));
            }
        }
        String largest = "{\"meta\":" + "[".repeat(4194299) + "0" + "]".repeat(4194299) + "}";
        assertEquals(8 * 1024 * 1024, largest.length());
        // A syntax exception, not StackOverflowError, is the reference at the byte limit.
        assertThrows(com.google.gson.JsonSyntaxException.class,
            () -> gson.fromJson(largest, com.google.gson.JsonObject.class));
    }

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

    @Test public void gsonStrictNumericTokensAreTheReference() {
        var gson = new com.google.gson.GsonBuilder().setStrictness(com.google.gson.Strictness.STRICT).create();
        for (String token : List.of("1", "1.0", "1e0", "1E+0", "-0", "1e999", "-1e999", "1e-999",
                "123456789012345678901234567890", "9".repeat(1023))) {
            var value = gson.fromJson("{\"schemaVersion\":" + token + "}", com.google.gson.JsonObject.class);
            assertTrue(value.get("schemaVersion").getAsJsonPrimitive().isNumber());
            assertEquals(token, value.get("schemaVersion").toString());
            // UBNFPackageResolver.versioned compares the JSON spelling, not numeric equality.
            assertEquals(token.equals("1"), value.get("schemaVersion").toString().equals("1"));
        }
        for (String token : List.of("9".repeat(1024), "1e" + "9".repeat(1022), "01", "1e+", "NaN", "Infinity"))
            assertThrows(com.google.gson.JsonSyntaxException.class,
                () -> gson.fromJson("{\"meta\":" + token + "}", com.google.gson.JsonObject.class));
        for (String member : List.of("\"1.5\"", "null", "[]")) {
            var value = gson.fromJson("{\"$serde_json::private::Number\":" + member + "}", com.google.gson.JsonObject.class);
            assertTrue(value.has("$serde_json::private::Number"));
            assertEquals(1, value.size());
        }
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
        assertEquals("all raw JSON cases executed", 481,
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
