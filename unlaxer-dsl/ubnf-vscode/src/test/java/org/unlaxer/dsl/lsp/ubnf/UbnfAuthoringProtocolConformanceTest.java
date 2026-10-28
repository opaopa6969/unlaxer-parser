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

    @Test public void bundledParserCandidatesMatchJavaClasspath() throws Exception {
        var actual = new java.util.TreeSet<String>();
        var classes = Path.of(org.unlaxer.parser.Parser.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var entries = new java.util.ArrayList<String>();
        if (Files.isDirectory(classes)) {
            try (var paths = Files.walk(classes.resolve("org/unlaxer/parser"))) {
                paths.filter(Files::isRegularFile).forEach(path -> entries.add(classes.relativize(path).toString().replace('\\', '/')));
            }
        } else {
            try (var jar = new java.util.jar.JarFile(classes.toFile())) {
                jar.stream().forEach(entry -> entries.add(entry.getName()));
            }
        }
        for (var entry : entries) {
            if (!entry.matches("org/unlaxer/parser/(elementary|posix|clang|combinator|ascii)/[^/$]+\\.class")) continue;
            var name = entry.replace('/', '.').replace(".class", "");
            Class.forName(name, false, getClass().getClassLoader());
            actual.add(name);
        }
        var expected = new java.util.TreeSet<String>();
        JsonParser.parseString(Files.readString(root().resolve("rust/unlaxer-generator/src/validation/java-classes.json")))
            .getAsJsonArray().forEach(name -> expected.add(name.getAsString()));
        assertEquals("native suggestions must follow the bundled reference classes", actual, expected);
    }

    @Test(timeout=240000) public void nativeAndJavaServersShareAuthoringFixtures() throws Exception {
        runProtocol("baseline", 127);
    }

    @Test(timeout=240000) public void nativeAndJavaServersShareNavigationFixtures() throws Exception {
        runProtocol("navigation", 39);
    }

    @Test(timeout=1080000) public void localImportsRejectUnsafeFilesWithoutBlockingServersOrClis() throws Exception {
        runProtocol("local-imports", 17);
    }

    @Test(timeout=300000) public void packageDocumentsRejectUnsafeFilesWithoutBlockingServersOrClis() throws Exception {
        runProtocol("package-documents", 27);
    }

    @Test(timeout=720000) public void packageArtifactsRejectUnsafeFilesWithoutBlockingServersOrClis() throws Exception {
        runProtocol("package-artifacts", 39);
    }

    @Test(timeout=300000) public void packageArtifactUtf8DiagnosticsAndHashOrderMatchAcrossHosts() throws Exception {
        runProtocol("package-artifact-utf8", 29);
    }

    @Test(timeout=300000) public void packageGrammarBasesMatchAcrossServers() throws Exception {
        runProtocol("package-grammar-paths", 14);
    }

    @Test(timeout=900000) public void packageJsonAcceptanceMatchesAcrossServers() throws Exception {
        runProtocol("package-json", 362);
    }

    private void runProtocol(String suite, int count) throws Exception {
        if (!Boolean.getBoolean("rustConformance")) System.out.println("[assumption] rustConformance=true is required for native authoring stdio");
        Assume.assumeTrue(Boolean.getBoolean("rustConformance"));
        var build = new ProcessBuilder("cargo", "build", "--locked", "-p", "unlaxer-ubnf-lsp", "-p", "unlaxer-generator").directory(root().resolve("rust").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/ubnf-authoring-build.log").toFile()).start();
        try { assertTrue("cargo build timeout", build.waitFor(120, TimeUnit.SECONDS)); assertEquals(0, build.exitValue()); }
        finally { build.descendants().forEach(child -> child.destroyForcibly()); build.destroyForcibly(); System.out.print(Files.readString(Path.of("target/ubnf-authoring-build.log"))); }
        var output = Path.of("target/ubnf-authoring-" + suite + "-protocol.json").toAbsolutePath();
        var process = new ProcessBuilder("python3", root().resolve("scripts/check-ubnf-authoring-lsp.py").toString(),
            "--java", Path.of(System.getProperty("java.home"), "bin/java").toString(),
            "--classpath", System.getProperty("java.class.path"),
            "--rust", root().resolve("rust/target/debug/ubnf-language-server").toString(), "--suite", suite, "--output", output.toString()).redirectErrorStream(true).redirectOutput(Path.of("target/ubnf-authoring-" + suite + "-wire.log").toFile()).start();
        try { assertTrue("protocol timeout", process.waitFor(suite.equals("local-imports") ? 900 : suite.equals("package-json") ? 800 : suite.equals("package-artifacts") ? 600 : (suite.equals("package-documents") || suite.equals("package-artifact-utf8")) ? 240 : 100, TimeUnit.SECONDS)); assertEquals(0, process.exitValue()); }
        finally { process.descendants().forEach(child -> child.destroyForcibly()); process.destroyForcibly(); System.out.print(Files.readString(Path.of("target/ubnf-authoring-" + suite + "-wire.log"))); }
        assertEquals("all authored cases and import/package scenarios executed", count, JsonParser.parseString(Files.readString(output)).getAsJsonArray().size());
    }
}
