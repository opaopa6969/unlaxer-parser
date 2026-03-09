package org.unlaxer.dsl;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.regex.Pattern;

/**
 * Utility entry point to refresh CLI report/manifest golden fixtures.
 */
public final class CliFixtureWriter {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final Pattern TOOL_VERSION_PATTERN = Pattern.compile("\"toolVersion\":\"[^\"]+\"");
    private static final Pattern ARGS_HASH_PATTERN = Pattern.compile("\"argsHash\":\"[^\"]+\"");

    private CliFixtureWriter() {}

    public static void main(String[] args) throws Exception {
        Path outputRoot = resolveOutputDir(args);
        Path outputDir = outputRoot.resolve("cli");
        Files.createDirectories(outputDir);

        writeValidateFailWarning(outputDir.resolve("validate_fail_warning.json"));
        writeValidateFailWarningsCount(outputDir.resolve("validate_fail_warnings_count.json"));
        writeGenerateFailConflict(outputDir.resolve("generate_fail_conflict.json"));
        writeGenerateFailSkipped(outputDir.resolve("generate_fail_skipped.json"));
        writeGenerateFailCleaned(outputDir.resolve("generate_fail_cleaned.json"));
        writeGenerateFailConflictManifestNdjson(outputDir.resolve("generate_fail_conflict.manifest.ndjson"));
    }

    private static void writeValidateFailWarning(Path outFile) throws Exception {
        Path grammar = Files.createTempFile("fixture-validate-warning", ".ubnf");
        Files.writeString(grammar, CliFixtureData.WARN_ONLY_GRAMMAR);

        RunResult result = runWithClock(
            "--grammar", grammar.toString(),
            "--validate-only",
            "--fail-on", "warning",
            "--report-format", "json"
        );
        String payload = lastJsonLine(result.err());
        Files.writeString(outFile, normalizePayload(payload, null));
    }

    private static void writeValidateFailWarningsCount(Path outFile) throws Exception {
        Path grammar = Files.createTempFile("fixture-validate-warning-threshold", ".ubnf");
        Files.writeString(grammar, CliFixtureData.WARN_ONLY_GRAMMAR);

        RunResult result = runWithClock(
            "--grammar", grammar.toString(),
            "--validate-only",
            "--fail-on", "warnings-count>=1",
            "--report-format", "json"
        );
        String payload = lastJsonLine(result.err());
        Files.writeString(outFile, normalizePayload(payload, null));
    }

    private static void writeGenerateFailConflict(Path outFile) throws Exception {
        Path grammar = Files.createTempFile("fixture-generate-conflict", ".ubnf");
        Path outDir = Files.createTempDirectory("fixture-generate-conflict-out");
        Files.writeString(grammar, CliFixtureData.VALID_GRAMMAR);

        Path ast = outDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// existing");

        RunResult result = runWithClock(
            "--grammar", grammar.toString(),
            "--output", outDir.toString(),
            "--generators", "AST",
            "--overwrite", "never",
            "--fail-on", "conflict",
            "--report-format", "json"
        );
        String payload = lastJsonLine(result.err());
        Files.writeString(outFile, normalizePayload(payload, outDir));
    }

    private static void writeGenerateFailSkipped(Path outFile) throws Exception {
        Path grammar = Files.createTempFile("fixture-generate-skipped", ".ubnf");
        Path outDir = Files.createTempDirectory("fixture-generate-skipped-out");
        Files.writeString(grammar, CliFixtureData.VALID_GRAMMAR);

        RunResult first = runWithClock(
            "--grammar", grammar.toString(),
            "--output", outDir.toString(),
            "--generators", "AST"
        );
        if (first.exitCode() != CodegenMain.EXIT_OK) {
            throw new IllegalStateException("fixture pre-run failed");
        }

        RunResult second = runWithClock(
            "--grammar", grammar.toString(),
            "--output", outDir.toString(),
            "--generators", "AST",
            "--overwrite", "if-different",
            "--fail-on", "skipped",
            "--report-format", "json"
        );
        String payload = lastJsonLine(second.err());
        Files.writeString(outFile, normalizePayload(payload, outDir));
    }

    private static void writeGenerateFailCleaned(Path outFile) throws Exception {
        Path grammar = Files.createTempFile("fixture-generate-cleaned", ".ubnf");
        Path outDir = Files.createTempDirectory("fixture-generate-cleaned-out");
        Files.writeString(grammar, CliFixtureData.VALID_GRAMMAR);
        Path ast = outDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// stale");

        RunResult result = runWithClock(
            "--grammar", grammar.toString(),
            "--output", outDir.toString(),
            "--generators", "AST",
            "--clean-output",
            "--fail-on", "cleaned",
            "--report-format", "json"
        );
        String payload = lastJsonLine(result.err());
        Files.writeString(outFile, normalizePayload(payload, outDir));
    }

    private static void writeGenerateFailConflictManifestNdjson(Path outFile) throws Exception {
        Path grammar = Files.createTempFile("fixture-manifest-conflict", ".ubnf");
        Path outDir = Files.createTempDirectory("fixture-manifest-conflict-out");
        Path manifest = Files.createTempFile("fixture-manifest-conflict", ".ndjson");
        Files.writeString(grammar, CliFixtureData.VALID_GRAMMAR);
        Path ast = outDir.resolve("org/example/valid/ValidAST.java");
        Files.createDirectories(ast.getParent());
        Files.writeString(ast, "// existing");

        RunResult result = runWithClock(
            "--grammar", grammar.toString(),
            "--output", outDir.toString(),
            "--generators", "AST",
            "--overwrite", "never",
            "--fail-on", "conflict",
            "--report-format", "json",
            "--output-manifest", manifest.toString(),
            "--manifest-format", "ndjson"
        );
        if (result.exitCode() != CodegenMain.EXIT_GENERATION_ERROR) {
            throw new IllegalStateException("expected generation error for conflict manifest fixture");
        }

        String raw = Files.readString(manifest);
        String normalized = normalizePayload(raw, outDir).replace(manifest.toString(), "/path/to/manifest.ndjson");
        Files.writeString(outFile, normalized);
    }

    private static RunResult runWithClock(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.runWithClock(args, new PrintStream(out), new PrintStream(err), FIXED_CLOCK);
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    private static String lastJsonLine(String text) {
        String[] lines = text.trim().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        throw new IllegalStateException("JSON payload not found in output");
    }

    private static String normalizePayload(String payload, Path outDir) {
        String out = payload.replace("\r\n", "\n");
        if (outDir != null) {
            out = out.replace(outDir.toString(), "/path/to/out");
        }
        out = TOOL_VERSION_PATTERN.matcher(out).replaceAll("\"toolVersion\":\"<toolVersion>\"");
        out = ARGS_HASH_PATTERN.matcher(out).replaceAll("\"argsHash\":\"<argsHash>\"");
        return out;
    }

    private static Path resolveOutputDir(String[] args) {
        if (args == null || args.length == 0) {
            return Path.of("src/test/resources/golden");
        }
        if (args.length == 2 && "--output-dir".equals(args[0])) {
            return Path.of(args[1]);
        }
        throw new IllegalArgumentException("Usage: CliFixtureWriter [--output-dir <path>]");
    }

    private record RunResult(int exitCode, String out, String err) {}
}
