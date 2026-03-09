package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.Test;

public class FailReasonContractTest {

    @Test
    public void testFailOnWarningReasonInJsonReportAndManifestJson() throws Exception {
        Path grammarFile = Files.createTempFile("fail-reason-warning", ".ubnf");
        Path manifest = Files.createTempFile("fail-reason-warning-manifest", ".json");
        Files.writeString(grammarFile, CliFixtureData.WARN_ONLY_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--fail-on", "warning",
            "--report-format", "json",
            "--output-manifest", manifest.toString(),
            "--manifest-format", "json"
        );
        assertEquals(CodegenMain.EXIT_STRICT_VALIDATION_ERROR, result.exitCode());

        Map<String, Object> report = JsonTestUtil.parseObject(lastJsonLine(result.err()));
        assertEquals("FAIL_ON_WARNING", JsonTestUtil.getString(report, "failReasonCode"));

        Map<String, Object> manifestObj = JsonTestUtil.parseObject(Files.readString(manifest));
        assertEquals("FAIL_ON_WARNING", JsonTestUtil.getString(manifestObj, "failReasonCode"));
    }

    @Test
    public void testFailOnWarningsCountReasonInNdjsonReportAndManifestNdjson() throws Exception {
        Path grammarFile = Files.createTempFile("fail-reason-warning-count", ".ubnf");
        Path manifest = Files.createTempFile("fail-reason-warning-count-manifest", ".ndjson");
        Files.writeString(grammarFile, CliFixtureData.WARN_ONLY_GRAMMAR);

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--validate-only",
            "--fail-on", "warnings-count>=1",
            "--report-format", "ndjson",
            "--output-manifest", manifest.toString(),
            "--manifest-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_STRICT_VALIDATION_ERROR, result.exitCode());
        Map<String, Object> reportEvent = JsonTestUtil.parseObject(lastJsonLine(result.err()));
        assertEquals("strict-failure", JsonTestUtil.getString(reportEvent, "event"));
        Map<String, Object> payload = JsonTestUtil.getObject(reportEvent, "payload");
        assertEquals("FAIL_ON_WARNINGS_COUNT", JsonTestUtil.getString(payload, "failReasonCode"));

        Map<String, Object> summary = findManifestSummary(Files.readString(manifest));
        assertEquals("FAIL_ON_WARNINGS_COUNT", JsonTestUtil.getString(summary, "failReasonCode"));
    }

    @Test
    public void testFailOnSkippedReasonInJsonReportAndManifestJson() throws Exception {
        Path grammarFile = Files.createTempFile("fail-reason-skipped", ".ubnf");
        Path outputDir = Files.createTempDirectory("fail-reason-skipped-out");
        Path manifest = Files.createTempFile("fail-reason-skipped-manifest", ".json");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);

        RunResult first = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST"
        );
        assertEquals(CodegenMain.EXIT_OK, first.exitCode());

        RunResult second = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--overwrite", "if-different",
            "--fail-on", "skipped",
            "--report-format", "json",
            "--output-manifest", manifest.toString(),
            "--manifest-format", "json"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, second.exitCode());
        Map<String, Object> report = JsonTestUtil.parseObject(lastJsonLine(second.err()));
        assertEquals("FAIL_ON_SKIPPED", JsonTestUtil.getString(report, "failReasonCode"));

        Map<String, Object> manifestObj = JsonTestUtil.parseObject(Files.readString(manifest));
        assertEquals("FAIL_ON_SKIPPED", JsonTestUtil.getString(manifestObj, "failReasonCode"));
    }

    @Test
    public void testFailOnConflictReasonInNdjsonReportAndManifestNdjson() throws Exception {
        Path grammarFile = Files.createTempFile("fail-reason-conflict", ".ubnf");
        Path outputDir = Files.createTempDirectory("fail-reason-conflict-out");
        Path manifest = Files.createTempFile("fail-reason-conflict-manifest", ".ndjson");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// existing");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--overwrite", "never",
            "--fail-on", "conflict",
            "--report-format", "ndjson",
            "--output-manifest", manifest.toString(),
            "--manifest-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        Map<String, Object> reportEvent = JsonTestUtil.parseObject(lastJsonLine(result.out()));
        assertEquals("generate-summary", JsonTestUtil.getString(reportEvent, "event"));
        Map<String, Object> payload = JsonTestUtil.getObject(reportEvent, "payload");
        assertEquals("FAIL_ON_CONFLICT", JsonTestUtil.getString(payload, "failReasonCode"));

        Map<String, Object> summary = findManifestSummary(Files.readString(manifest));
        assertEquals("FAIL_ON_CONFLICT", JsonTestUtil.getString(summary, "failReasonCode"));
    }

    @Test
    public void testFailOnCleanedReasonInJsonReportAndManifestNdjson() throws Exception {
        Path grammarFile = Files.createTempFile("fail-reason-cleaned", ".ubnf");
        Path outputDir = Files.createTempDirectory("fail-reason-cleaned-out");
        Path manifest = Files.createTempFile("fail-reason-cleaned-manifest", ".ndjson");
        Files.writeString(grammarFile, CliFixtureData.VALID_GRAMMAR);
        Path ast = outputDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// stale");

        RunResult result = runCodegen(
            "--grammar", grammarFile.toString(),
            "--output", outputDir.toString(),
            "--generators", "AST",
            "--clean-output",
            "--fail-on", "cleaned",
            "--report-format", "json",
            "--output-manifest", manifest.toString(),
            "--manifest-format", "ndjson"
        );
        assertEquals(CodegenMain.EXIT_GENERATION_ERROR, result.exitCode());
        Map<String, Object> report = JsonTestUtil.parseObject(lastJsonLine(result.err()));
        assertEquals("FAIL_ON_CLEANED", JsonTestUtil.getString(report, "failReasonCode"));

        Map<String, Object> summary = findManifestSummary(Files.readString(manifest));
        assertEquals("FAIL_ON_CLEANED", JsonTestUtil.getString(summary, "failReasonCode"));
    }

    private static Map<String, Object> findManifestSummary(String ndjson) {
        String[] lines = ndjson.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Map<String, Object> obj = JsonTestUtil.parseObject(trimmed);
            if ("manifest-summary".equals(obj.get("event"))) {
                return obj;
            }
        }
        throw new IllegalStateException("manifest-summary not found");
    }

    private static String lastJsonLine(String text) {
        String[] lines = text.trim().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        throw new IllegalStateException("JSON payload not found");
    }

    private static RunResult runCodegen(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.run(args, new PrintStream(out), new PrintStream(err));
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    private record RunResult(int exitCode, String out, String err) {}
}
