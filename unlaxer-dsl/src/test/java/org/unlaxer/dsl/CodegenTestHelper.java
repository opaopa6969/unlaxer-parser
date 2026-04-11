package org.unlaxer.dsl;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helper methods and types for CodegenMain split-test classes.
 */
final class CodegenTestHelper {

    private CodegenTestHelper() {}

    static RunResult runCodegen(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.run(args, new PrintStream(out), new PrintStream(err));
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    static RunResult runCodegenWithClock(Clock clock, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = CodegenMain.runWithClock(args, new PrintStream(out), new PrintStream(err), clock);
        return new RunResult(exitCode, out.toString(), err.toString());
    }

    static void assertHasNonEmptyJsonField(String json, String fieldName) {
        String value = extractJsonStringField(json, fieldName);
        assertTrue(fieldName + " should be non-empty", value != null && !value.isBlank());
    }

    static void assertGeneratedAtIsIsoInstant(String json) {
        String value = extractJsonStringField(json, "generatedAt");
        assertTrue("generatedAt should exist", value != null);
        Instant.parse(value);
    }

    static String extractJsonStringField(String json, String fieldName) {
        Pattern pattern = Pattern.compile("\"" + Pattern.quote(fieldName) + "\":\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    static String lastJsonLine(String text) {
        String[] lines = text.trim().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.startsWith("{") && line.endsWith("}")) {
                return line;
            }
        }
        throw new IllegalStateException("JSON line not found");
    }

    record RunResult(int exitCode, String out, String err) {}
}
