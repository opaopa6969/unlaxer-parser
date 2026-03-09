package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.Test;

public class CliErrorCodeContractTest {

    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("^E-[A-Z0-9-]+$");

    @Test
    public void testUsageErrorCodeMatchesPatternInNdjson() {
        RunResult result = runCodegen("--validate-only", "--report-format", "ndjson");
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertCliErrorCodePattern(result.out());
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testUnsupportedReportVersionErrorCodeMatchesPatternInNdjson() throws Exception {
        Path grammarFile = Files.createTempFile("cli-error-contract-report-version", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson",
            "--report-version", "2"
        );
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertCliErrorCodePattern(result.out());
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testUnknownGeneratorErrorCodeMatchesPatternInNdjson() throws Exception {
        Path grammarFile = Files.createTempFile("cli-error-contract-unknown-generator", ".ubnf");
        Path outputDir = Files.createTempDirectory("cli-error-contract-unknown-generator-out");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "Nope",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertCliErrorCodePattern(result.out());
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testRuntimeErrorCodeMatchesPatternInNdjson() throws Exception {
        Path grammarFile = Files.createTempFile("cli-error-contract-runtime", ".ubnf");
        Files.writeString(grammarFile, "grammar Broken {");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertCliErrorCodePattern(result.out());
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testIoErrorCodeMatchesPatternInNdjson() throws Exception {
        Path grammarFile = Files.createTempFile("cli-error-contract-io", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);
        Path blocker = Files.createTempFile("cli-error-contract-io-blocker", ".tmp");
        Path reportFile = blocker.resolve("report.json");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--report-format", "ndjson",
            "--report-file", reportFile.toString()
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        assertCliErrorCodePattern(result.out());
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testUnsafeCleanOutputErrorCodeMatchesPatternInNdjson() throws Exception {
        Path grammarFile = Files.createTempFile("cli-error-contract-unsafe-clean", ".ubnf");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", "/",
            "--generators", "AST",
            "--clean-output",
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_CLI_ERROR, result.exitCode());
        assertCliErrorCodePattern(result.out());
        assertTrue(result.err().isBlank());
    }

    @Test
    public void testParserIrExportErrorCodeMatchesPatternInNdjson() throws Exception {
        String source = "grammar Broken {";
        Path grammarFile = Files.createTempFile("cli-error-contract-parser-ir-export", ".ubnf");
        Path exportFile = Files.createTempFile("cli-error-contract-parser-ir-export-out", ".json");
        Files.writeString(grammarFile, source);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--export-parser-ir", exportFile.toString(),
            "--report-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_VALIDATION_ERROR, result.exitCode());
        assertCliErrorCodePattern(result.out());
        assertTrue(result.err().isBlank());
    }

    private static void assertCliErrorCodePattern(String stdout) {
        Map<String, Object> event = JsonTestUtil.parseObject(lastJsonLine(stdout));
        assertEquals("cli-error", JsonTestUtil.getString(event, "event"));
        String code = JsonTestUtil.getString(event, "code");
        assertTrue("code should match E-[A-Z0-9-]+ but was: " + code, ERROR_CODE_PATTERN.matcher(code).matches());
    }

    private static String lastJsonLine(String text) {
        String[] lines = text.trim().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        throw new IllegalStateException("JSON line not found");
    }

    private static RunResult runCodegen(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.run(args, new PrintStream(out), new PrintStream(err));
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    private record RunResult(int exitCode, String out, String err) {}
}
