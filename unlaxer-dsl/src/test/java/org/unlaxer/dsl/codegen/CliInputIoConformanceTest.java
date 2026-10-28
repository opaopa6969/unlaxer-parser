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

/** Handwritten filesystem/error oracles, exercised by both real CLI processes. */
public class CliInputIoConformanceTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path EVIDENCE = Path.of(System.getProperty("cliInputEvidence", "target"));

    @BeforeClass public static void buildNativeCli() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] CLI input conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Files.createDirectories(EVIDENCE);
        run(List.of("cargo", "build", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), EVIDENCE.resolve("cli-input-build.log"), 180);
    }

    @Test(timeout=300000) public void entryIoMatchesJavaWithoutOutput() throws Exception { check("entry", 144); }
    @Test(timeout=300000) public void importedIoRetainsItsExitCategoryAndCheckJson() throws Exception { check("import", 208); }
    @Test(timeout=300000) public void readableInputsKeepOutputAndGeneratedBytes() throws Exception { check("success", 32); }

    private static void check(String suite, int observations) throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(path -> Path.of(path).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        Path output = EVIDENCE.resolve("cli-input-" + suite + ".json").toAbsolutePath();
        run(List.of("python3", ROOT.resolve("scripts/cli_input_errors.py").toString(),
            "--java", Path.of(System.getProperty("java.home"), "bin/java").toString(), "--classpath", classpath,
            "--rust", ROOT.resolve("rust/target/debug/unlaxer").toString(), "--suite", suite,
            "--output", output.toString()), EVIDENCE.resolve("cli-input-" + suite + ".log"), 240);
        var evidence = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        var excluded = evidence.getAsJsonArray("excluded");
        assertTrue("only chmod(000) may be inapplicable", excluded.size() <= (suite.equals("success") ? 0 : 1));
        for (var entry : excluded) System.out.println("[fixture exclusion] " + entry);
        assertEquals(observations - excluded.size() * 16, evidence.getAsJsonArray("observations").size());
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
