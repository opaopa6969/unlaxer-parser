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

/** Fixed independent filesystem/error oracles, exercised by both real CLI processes. */
public class CliStderrConformanceTest {
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path EVIDENCE = Path.of(System.getProperty("cliStderrEvidence", "target"));

    @BeforeClass public static void buildNativeCli() throws Exception {
        if (!Boolean.getBoolean("rustConformance"))
            System.out.println("[assumption] CLI stderr conformance requires -DrustConformance=true");
        assumeTrue(Boolean.getBoolean("rustConformance"));
        Files.createDirectories(EVIDENCE);
        run(List.of("cargo", "build", "--locked", "--manifest-path", ROOT.resolve("rust/Cargo.toml").toString(),
            "-p", "unlaxer-generator"), EVIDENCE.resolve("cli-stderr-build.log"), 180);
    }

    @Test(timeout=300000) public void outputFailuresNameTheFailedPath() throws Exception { check("output"); }
    @Test(timeout=300000) public void outputPathsMatchJavaAndRetainNativeLinkPolicy() throws Exception { check("paths"); }
    @Test(timeout=120000) public void existingOutputMetadataMatchesJava() throws Exception { check("replacement"); }
    @Test(timeout=900000) public void malformedUtf8MatchesEachJavaReader() throws Exception { check("utf8"); }
    @Test(timeout=600000) public void readinessRendersEveryDiagnosticAsJavaRecords() throws Exception { check("readiness"); }
    @Test(timeout=300000) public void profileInputIoAndReadableOutputsMatchJava() throws Exception { check("profile"); }
    @Test(timeout=120000) public void syntaxKeepsDocumentedNativeLocations() throws Exception { check("syntax"); }

    @Test(timeout=120000) public void playgroundBlankOptionsMatchJavaWhitespace() throws Exception { check("blank"); }
    @Test(timeout=120000) public void regexSyntaxPriorityMatchesJavaBeforeOutput() throws Exception { check("regex"); }

    @Test(timeout=120000) public void qualifiedReferenceSyntaxMatchesJavaBeforeOutput() throws Exception { check("qualified"); }

    @Test(timeout=240000) public void localePathsRetainHostLimitsAndUtf8FileBytes() throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(path -> Path.of(path).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        Path output = EVIDENCE.resolve("cli-stderr-locale.json").toAbsolutePath();
        run(List.of("python3", ROOT.resolve("scripts/cli_locale_paths.py").toString(),
            "--java", Path.of(System.getProperty("java.home"), "bin/java").toString(), "--classpath", classpath,
            "--rust", ROOT.resolve("rust/target/debug/unlaxer").toString(),
            "--output", output.toString()), EVIDENCE.resolve("cli-stderr-locale.log"), 210);
        var evidence = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertEquals("all LC_ALL/LANG combinations", 9, evidence.getAsJsonArray("environments").size());
        assertEquals("all paths/controls, both hosts and snapshots", 972,
            evidence.getAsJsonArray("observations").size());
        assertEquals("locale fixtures must never be excluded", 0, evidence.getAsJsonArray("excluded").size());
    }

    private static void check(String suite) throws Exception {
        String classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .map(path -> Path.of(path).toAbsolutePath().toString()).collect(Collectors.joining(File.pathSeparator));
        Path output = EVIDENCE.resolve("cli-stderr-" + suite + ".json").toAbsolutePath();
        run(List.of("python3", ROOT.resolve("scripts/cli_stderr.py").toString(),
            "--java", Path.of(System.getProperty("java.home"), "bin/java").toString(), "--classpath", classpath,
            "--rust", ROOT.resolve("rust/target/debug/unlaxer").toString(), "--suite", suite,
            "--output", output.toString()), EVIDENCE.resolve("cli-stderr-" + suite + ".log"), 840);
        var evidence = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        var excluded = evidence.getAsJsonArray("excluded");
        int permissionCases = suite.equals("replacement") ? 4 : suite.equals("output") ? 4 : suite.equals("paths") || suite.equals("profile") ? 3 : 0;
        assertTrue("only chmod(000/555/444) may be inapplicable", excluded.size() <= permissionCases);
        for (var entry : excluded) System.out.println("[fixture exclusion] " + entry);
        if (System.getenv("GITHUB_ACTIONS") != null)
            assertEquals("CI must exercise chmod(000/555/444): " + excluded, 0, excluded.size());
        int observations = switch (suite) {
            case "replacement" -> 60;
            case "regex" -> 640;
            case "qualified" -> 608;
            case "blank" -> 350;
            case "output" -> 112;
            case "paths" -> 244;
            case "profile" -> 296;
            case "utf8" -> 768;
            case "readiness" -> 384;
            default -> 16;
        };
        int skipped = 0;
        for (var entry : excluded) skipped += entry.getAsJsonObject().get("observations").getAsInt();
        assertEquals(observations - skipped, evidence.getAsJsonArray("observations").size());
        if (suite.equals("qualified")) assertEquals(16, evidence.getAsJsonArray("checks").size());
        if (suite.equals("paths")) assertEquals(114, evidence.getAsJsonArray("checks").size());
        if (suite.equals("profile")) assertEquals(4, evidence.getAsJsonArray("checks").size());
        if (suite.equals("replacement") && excluded.size() == 0) assertEquals(44, evidence.getAsJsonArray("checks").size());
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
