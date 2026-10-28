package org.unlaxer.dsl.codegen;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import com.google.gson.JsonParser;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.unlaxer.dsl.bootstrap.*;
import org.unlaxer.dsl.PortabilityCheck;
import org.unlaxer.dsl.codegen.rust.PlaygroundGenerator;

/** Identical raw argv, independent package/source oracles and byte comparisons on real CLIs. */
public class PackageGrammarBaseConformanceTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @BeforeClass public static void buildNativeCli() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] grammar base conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        run(List.of("cargo", "build", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), Path.of("target/package-grammar-build.log"), 180);
    }

    @Test(timeout=300000) public void inspectUsesLexicalGrammarBase() throws Exception { check("inspect", 56); }
    @Test(timeout=300000) public void generationAndCheckUseLexicalGrammarBase() throws Exception { check("grammar", 140); }
    @Test(timeout=300000) public void packagedPlaygroundUsesLexicalManifestBase() throws Exception { check("package", 56); }

    @Test(timeout=300000) public void fileApisAndJavaFlagsUseLexicalGrammarBase() throws Exception {
        Path folder = temporary.newFolder().toPath(), work = folder.resolve("work");
        Files.createDirectories(work.resolve("a"));
        Files.createDirectories(folder.resolve("physical/deep"));
        Files.createSymbolicLink(work.resolve("a/link"), Path.of("../../physical/deep"));
        String source = "grammar Main { @import layout from 'pkg:std/layout' @ubnf: v2 @whitespace: layout.SPACES @root @mapping(Value) Start ::= 'a'; }\n";
        Files.writeString(work.resolve("g.ubnf"), source);
        Files.writeString(folder.resolve("g.ubnf"), "grammar Physical { token HOST = com.example.NonportableParser; @root Start ::= HOST; }");
        Files.writeString(work.resolve("ubnf.json"), "{\"schemaVersion\":1,\"dependencies\":{\"std/layout\":{\"version\":\"1.0.0\",\"source\":\"builtin:std/layout@1.0.0\"}}}");
        UBNFPackageResolver.resolve(work.resolve("ubnf.json"));
        Files.createDirectory(work.resolve("alias"));
        Files.createSymbolicLink(work.resolve("alias/g.ubnf"), Path.of("../g.ubnf"));
        Files.createSymbolicLink(work.resolve("alias/ubnf.json"), Path.of("../ubnf.json"));
        UBNFPackageResolver.resolve(work.resolve("alias/ubnf.json"));
        var fixtures = JsonParser.parseString(Files.readString(ROOT.resolve("spec-corpus/package-grammar-paths.json"))).getAsJsonArray();
        assertEquals(14, fixtures.size());
        int index = 0;
        for (var fixture : fixtures) {
            var item = fixture.getAsJsonObject();
            String id = item.get("id").getAsString();
            if (id.equals("symlink-file")) Files.delete(work.resolve("ubnf.lock.json"));
            Path path = work.resolve(item.get("grammar").getAsString().replace("${WORK}", work.toString()));
            assertEquals(id, "Main", UBNFModuleLoader.load(path).grammars().get(0).name());
            assertTrue(id, PortabilityCheck.checkFile(path).portable());
            assertEquals(id, source, PlaygroundGenerator.generate(path).get("public/grammar.ubnf"));
            Path output = folder.resolve("java-" + index++);
            run(List.of(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", classpath(),
                "org.unlaxer.dsl.CodegenMain", "--grammar", path.toString(), "--output", output.toString(),
                "--generators", "Parser"), folder.resolve("flags.log"), 30);
            try (var generated = Files.walk(output)) {
                assertTrue(id, generated.anyMatch(file -> file.getFileName().toString().equals("MainParsers.java")));
            }
        }
    }

    private static String classpath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(path -> Path.of(path).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
    }

    private static void check(String suite, int count) throws Exception {
        String classpath = classpath();
        Path output = Path.of("target/package-grammar-" + suite + ".json").toAbsolutePath();
        run(List.of("python3", ROOT.resolve("scripts/ubnf_package_grammar_paths.py").toString(),
            "--java", Path.of(System.getProperty("java.home"), "bin/java").toString(), "--classpath", classpath,
            "--rust", ROOT.resolve("rust/target/debug/unlaxer").toString(), "--suite", suite,
            "--output", output.toString()), Path.of("target/package-grammar-" + suite + ".log"), 240);
        assertEquals(count, JsonParser.parseString(Files.readString(output)).getAsJsonArray().size());
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
