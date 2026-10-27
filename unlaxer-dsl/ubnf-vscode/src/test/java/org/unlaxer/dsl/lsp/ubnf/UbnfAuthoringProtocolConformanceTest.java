package org.unlaxer.dsl.lsp.ubnf;

import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import com.google.gson.*;
import org.junit.Test;
import org.junit.Assume;

/** The reference authoring server and the native server consume the same wire fixtures. */
public class UbnfAuthoringProtocolConformanceTest {
    private static Path root() { return Path.of("../..").toAbsolutePath().normalize(); }

    @Test public void authoringDataMatchesTheJavaReference() throws Exception {
        var data = JsonParser.parseString(Files.readString(root().resolve("rust/unlaxer-ubnf-lsp/src/authoring-data.json"))).getAsJsonObject();
        var gson = new Gson();
        assertEquals(gson.toJsonTree(UBNFLanguageServerExt.ANNOTATIONS), data.get("annotations"));
        assertEquals(gson.toJsonTree(UBNFLanguageServerExt.BLOCK_SNIPPETS), data.get("snippets"));
        assertEquals(gson.toJsonTree(UBNFLanguageServerExt.KNOWN_PARSER_CLASSES), data.get("parsers"));
    }

    @Test(timeout=240000) public void nativeAndJavaServersShareAuthoringFixtures() throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] rustConformance=true is required for native authoring stdio");
        Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        var build = new ProcessBuilder("cargo", "build", "--locked", "-p", "unlaxer-ubnf-lsp").directory(root().resolve("rust").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/ubnf-authoring-build.log").toFile()).start();
        try { assertTrue("cargo build timeout", build.waitFor(120, TimeUnit.SECONDS)); assertEquals(0, build.exitValue()); }
        finally { build.descendants().forEach(child -> child.destroyForcibly()); build.destroyForcibly(); System.out.print(Files.readString(Path.of("target/ubnf-authoring-build.log"))); }
        var output = Path.of("target/ubnf-authoring-protocol.json").toAbsolutePath();
        var process = new ProcessBuilder("python3", root().resolve("scripts/check-ubnf-authoring-lsp.py").toString(),
            "--java", Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--classpath", System.getProperty("java.class.path"),
            "--rust", root().resolve("rust/target/debug/ubnf-language-server").toString(), "--output", output.toString()).redirectErrorStream(true).redirectOutput(Path.of("target/ubnf-authoring-wire.log").toFile()).start();
        try { assertTrue("protocol timeout", process.waitFor(100, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
        finally { process.descendants().forEach(child -> child.destroyForcibly()); process.destroyForcibly(); System.out.print(Files.readString(Path.of("target/ubnf-authoring-wire.log"))); }
        assertEquals("all authored cases and import/package scenarios executed", 22, JsonParser.parseString(Files.readString(output)).getAsJsonArray().size());
    }
}
