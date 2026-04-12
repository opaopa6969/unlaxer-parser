package org.unlaxer.dsl.init;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class InitRunnerTest {

    private Path tmp;

    @Before
    public void setUp() throws IOException {
        tmp = Files.createTempDirectory("init-runner-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (tmp != null && Files.exists(tmp)) {
            try (var s = Files.walk(tmp)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                });
            }
        }
    }

    private InitCliParser.InitOptions opts(String name, boolean withDap, String outputDir) {
        return new InitCliParser.InitOptions(
            name, null, null, null, outputDir, null, withDap, false, false);
    }

    @Test
    public void testInitWithBuiltInSampleAndDap() {
        Path out = tmp.resolve("mylang");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        int rc = InitRunner.run(opts("myLang", true, out.toString()),
            new PrintStream(stdout), new PrintStream(stderr));
        assertEquals("stderr=" + stderr, 0, rc);

        // top-level files
        assertTrue(Files.exists(out.resolve("pom.xml")));
        assertTrue(Files.exists(out.resolve("Makefile")));
        assertTrue(Files.exists(out.resolve(".gitignore")));
        assertTrue(Files.exists(out.resolve("README.md")));
        assertTrue(Files.exists(out.resolve("README.ja.md")));
        assertTrue(Files.exists(out.resolve("IMPLEMENTATION.md")));
        assertTrue(Files.exists(out.resolve("IMPLEMENTATION.ja.md")));

        // grammar — uses lowercase name
        assertTrue(Files.exists(out.resolve("grammar/mylang.ubnf")));

        // vscode-extension
        assertTrue(Files.exists(out.resolve("vscode-extension/package.json")));
        assertTrue(Files.exists(out.resolve("vscode-extension/tsconfig.json")));
        assertTrue(Files.exists(out.resolve("vscode-extension/language-configuration.json")));
        assertTrue(Files.exists(out.resolve("vscode-extension/src/extension.ts")));
        assertTrue(Files.exists(out.resolve("vscode-extension/syntaxes/mylang.tmLanguage.json")));
        assertTrue(Files.exists(out.resolve("vscode-extension/server-dist/.gitkeep")));
    }

    @Test
    public void testPackageJsonReflectsName() throws IOException {
        Path out = tmp.resolve("foo");
        InitRunner.run(opts("foo", true, out.toString()),
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(new ByteArrayOutputStream()));
        String pkg = Files.readString(out.resolve("vscode-extension/package.json"));
        assertTrue(pkg.contains("\"name\": \"foo-lsp\""));
        assertTrue(pkg.contains("\"id\": \"foo\""));
        assertTrue(pkg.contains("\".foo\""));
        assertTrue("DAP-enabled scaffolds include debuggers section",
            pkg.contains("\"debuggers\""));
    }

    @Test
    public void testNoDapExcludesDapSections() throws IOException {
        Path out = tmp.resolve("nodap");
        InitRunner.run(opts("nodap", false, out.toString()),
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(new ByteArrayOutputStream()));
        String pkg = Files.readString(out.resolve("vscode-extension/package.json"));
        assertFalse(pkg.contains("\"debuggers\""));

        String pom = Files.readString(out.resolve("pom.xml"));
        assertFalse("DAP dependency should be excluded", pom.contains("lsp4j.debug"));
        assertTrue("generators argument should be Parser,LSP,Launcher",
            pom.contains("Parser,LSP,Launcher"));
        assertFalse("generators should not include DAP", pom.contains("DAPLauncher"));

        String ext = Files.readString(out.resolve("vscode-extension/src/extension.ts"));
        assertFalse(ext.contains("DapLauncher"));
    }

    @Test
    public void testPomXmlReferencesCorrectClasses() throws IOException {
        Path out = tmp.resolve("calc");
        InitRunner.run(opts("calc", true, out.toString()),
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(new ByteArrayOutputStream()));
        String pom = Files.readString(out.resolve("pom.xml"));
        assertTrue(pom.contains("<groupId>org.example</groupId>"));
        assertTrue(pom.contains("<artifactId>calc-vscode</artifactId>"));
        assertTrue(pom.contains("org.example.calc.CalcLspLauncher"));
        assertTrue(pom.contains("calc-lsp-server.jar"));
        // Maven placeholders should still be present (not eaten by template renderer)
        assertTrue(pom.contains("${project.basedir}"));
        assertTrue(pom.contains("${project.build.directory}"));
    }

    @Test
    public void testSyntaxOnlyMode() throws IOException {
        // First do a full init.
        Path out = tmp.resolve("syn");
        InitRunner.run(opts("syn", true, out.toString()),
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(new ByteArrayOutputStream()));

        // Now invoke syntax-only against the existing grammar.
        var synOpts = new InitCliParser.InitOptions(
            "syn", null, null, null, out.toString(),
            out.resolve("grammar/syn.ubnf").toString(), true, true, false);
        int rc = InitRunner.run(synOpts,
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, rc);
        assertTrue(Files.exists(out.resolve("vscode-extension/syntaxes/syn.tmLanguage.json")));
    }

    @Test
    public void testRefusesNonEmptyDirWithoutForce() throws IOException {
        Path out = tmp.resolve("dirty");
        Files.createDirectories(out);
        Files.writeString(out.resolve("preexisting.txt"), "hi");

        var stderr = new ByteArrayOutputStream();
        int rc = InitRunner.run(opts("dirty", true, out.toString()),
            new PrintStream(new ByteArrayOutputStream()),
            new PrintStream(stderr));
        assertFalse(rc == 0);
        assertTrue(stderr.toString().contains("not empty"));
    }
}
