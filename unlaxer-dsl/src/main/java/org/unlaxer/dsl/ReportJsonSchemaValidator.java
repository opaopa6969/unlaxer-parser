package org.unlaxer.dsl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

/**
 * Validates JSON report schema contracts by reportVersion.
 */
final class ReportJsonSchemaValidator {

    private ReportJsonSchemaValidator() {}

    static void validate(int reportVersion, String json) {
        switch (reportVersion) {
            case 1 -> validateV1(json);
            default -> fail(
                "E-REPORT-SCHEMA-UNSUPPORTED-VERSION",
                "Unsupported reportVersion for validation: " + reportVersion
            );
        }
    }

    private static void validateV1(String json) {
        Map<String, Object> obj = parseObject(json);
        String mode = requireString(obj, "mode");
        boolean ok = requireBoolean(obj, "ok");

        if ("validate".equals(mode) && ok) {
            requireTopLevelOrder(
                obj,
                List.of(
                    "reportVersion",
                    "schemaVersion",
                    "schemaUrl",
                    "toolVersion",
                    "argsHash",
                    "generatedAt",
                    "mode",
                    "ok",
                    "grammarCount",
                    "warningsCount",
                    "issues"
                )
            );
            requireConstInteger(obj, "reportVersion", 1);
            requireConstString(obj, "schemaVersion", "1.0");
            requireConstString(obj, "schemaUrl", "https://unlaxer.dev/schema/report-v1.json");
            requireString(obj, "toolVersion");
            requireString(obj, "argsHash");
            requireDateTimeString(obj, "generatedAt");
            requireConstString(obj, "mode", "validate");
            requireConstBoolean(obj, "ok", true);
            requireIntegerMin(obj, "grammarCount", 0);
            requireIntegerMin(obj, "warningsCount", 0);
            List<Object> issues = requireArray(obj, "issues");
            if (!issues.isEmpty()) {
                fail("E-REPORT-SCHEMA-CONSTRAINT", "validate-success issues must be empty");
            }
            return;
        }

        if ("validate".equals(mode) && !ok) {
            requireTopLevelOrder(
                obj,
                List.of(
                    "reportVersion",
                    "schemaVersion",
                    "schemaUrl",
                    "toolVersion",
                    "argsHash",
                    "generatedAt",
                    "mode",
                    "ok",
                    "failReasonCode",
                    "issueCount",
                    "warningsCount",
                    "severityCounts",
                    "categoryCounts",
                    "issues"
                )
            );
            requireConstInteger(obj, "reportVersion", 1);
            requireConstString(obj, "schemaVersion", "1.0");
            requireConstString(obj, "schemaUrl", "https://unlaxer.dev/schema/report-v1.json");
            requireString(obj, "toolVersion");
            requireString(obj, "argsHash");
            requireDateTimeString(obj, "generatedAt");
            requireConstString(obj, "mode", "validate");
            requireConstBoolean(obj, "ok", false);
            requireNullableEnum(
                obj,
                "failReasonCode",
                Set.of("FAIL_ON_WARNING", "FAIL_ON_WARNINGS_COUNT")
            );
            requireIntegerMin(obj, "issueCount", 1);
            requireIntegerMin(obj, "warningsCount", 0);
            requireCountMap(requireObject(obj, "severityCounts"), "severityCounts");
            requireCountMap(requireObject(obj, "categoryCounts"), "categoryCounts");
            List<Object> issues = requireArray(obj, "issues");
            if (issues.isEmpty()) {
                fail("E-REPORT-SCHEMA-CONSTRAINT", "validate-failure issues must contain at least one item");
            }
            for (Object issueObj : issues) {
                if (!(issueObj instanceof Map<?, ?>)) {
                    fail("E-REPORT-SCHEMA-TYPE", "Expected issue object");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> issue = (Map<String, Object>) issueObj;
                validateIssue(issue);
            }
            return;
        }

        if ("generate".equals(mode)) {
            requireTopLevelOrder(
                obj,
                List.of(
                    "reportVersion",
                    "schemaVersion",
                    "schemaUrl",
                    "toolVersion",
                    "argsHash",
                    "generatedAt",
                    "mode",
                    "ok",
                    "failReasonCode",
                    "grammarCount",
                    "generatedCount",
                    "warningsCount",
                    "writtenCount",
                    "skippedCount",
                    "conflictCount",
                    "dryRunCount",
                    "generatedFiles"
                )
            );
            requireConstInteger(obj, "reportVersion", 1);
            requireConstString(obj, "schemaVersion", "1.0");
            requireConstString(obj, "schemaUrl", "https://unlaxer.dev/schema/report-v1.json");
            requireString(obj, "toolVersion");
            requireString(obj, "argsHash");
            requireDateTimeString(obj, "generatedAt");
            requireConstString(obj, "mode", "generate");
            boolean generateOk = requireBoolean(obj, "ok");
            String failReason = requireNullableEnum(
                obj,
                "failReasonCode",
                Set.of("FAIL_ON_SKIPPED", "FAIL_ON_CONFLICT", "FAIL_ON_CLEANED")
            );
            requireOkFailReasonConsistency(generateOk, failReason, true, "generate");
            requireIntegerMin(obj, "grammarCount", 0);
            requireIntegerMin(obj, "generatedCount", 0);
            requireIntegerMin(obj, "warningsCount", 0);
            requireIntegerMin(obj, "writtenCount", 0);
            requireIntegerMin(obj, "skippedCount", 0);
            requireIntegerMin(obj, "conflictCount", 0);
            requireIntegerMin(obj, "dryRunCount", 0);
            List<Object> generatedFiles = requireArray(obj, "generatedFiles");
            for (Object file : generatedFiles) {
                if (!(file instanceof String s) || s.isBlank()) {
                    fail("E-REPORT-SCHEMA-TYPE", "generatedFiles items must be non-empty strings");
                }
            }
            return;
        }

        fail(
            "E-REPORT-SCHEMA-INVALID-SHAPE",
            "Unsupported report payload shape for mode='" + mode + "' and ok=" + ok
        );
    }

    private static void requireTopLevelOrder(Map<String, Object> obj, List<String> expectedOrder) {
        List<String> keys = new ArrayList<>(obj.keySet());
        if (!keys.equals(expectedOrder)) {
            fail(
                "E-REPORT-SCHEMA-KEY-ORDER",
                "Unexpected JSON keys/order. expected=" + expectedOrder + " actual=" + keys
            );
        }
    }

    private static String requireString(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof String s) || s.isBlank()) {
            fail("E-REPORT-SCHEMA-TYPE", "Expected string for key: " + key);
        }
        return (String) value;
    }

    private static boolean requireBoolean(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof Boolean b)) {
            fail("E-REPORT-SCHEMA-TYPE", "Expected boolean for key: " + key);
        }
        return (Boolean) value;
    }

    private static Number requireNumber(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof Number n)) {
            fail("E-REPORT-SCHEMA-TYPE", "Expected number for key: " + key);
        }
        return (Number) value;
    }

    private static String requireNullableString(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s) || s.isBlank()) {
            fail("E-REPORT-SCHEMA-TYPE", "Expected nullable string for key: " + key);
        }
        return (String) value;
    }

    private static long requireIntegerMin(Map<String, Object> obj, String key, long min) {
        Number n = requireNumber(obj, key);
        if (!(n instanceof Integer || n instanceof Long)) {
            fail("E-REPORT-SCHEMA-TYPE", "Expected integer for key: " + key);
        }
        long value = n.longValue();
        if (value < min) {
            fail("E-REPORT-SCHEMA-CONSTRAINT", "Expected " + key + " >= " + min);
        }
        return value;
    }

    private static void requireConstString(Map<String, Object> obj, String key, String expected) {
        String actual = requireString(obj, key);
        if (!expected.equals(actual)) {
            fail("E-REPORT-SCHEMA-CONSTRAINT", "Expected " + key + " == " + expected + " but was " + actual);
        }
    }

    private static void requireConstBoolean(Map<String, Object> obj, String key, boolean expected) {
        boolean actual = requireBoolean(obj, key);
        if (actual != expected) {
            fail("E-REPORT-SCHEMA-CONSTRAINT", "Expected " + key + " == " + expected + " but was " + actual);
        }
    }

    private static void requireConstInteger(Map<String, Object> obj, String key, long expected) {
        long actual = requireIntegerMin(obj, key, expected);
        if (actual != expected) {
            fail("E-REPORT-SCHEMA-CONSTRAINT", "Expected " + key + " == " + expected + " but was " + actual);
        }
    }

    private static String requireNullableEnum(Map<String, Object> obj, String key, Set<String> allowed) {
        String value = requireNullableString(obj, key);
        if (value == null) {
            return null;
        }
        if (!allowed.contains(value)) {
            fail("E-REPORT-SCHEMA-CONSTRAINT", "Unsupported value for " + key + ": " + value);
        }
        return value;
    }

    private static void requireOkFailReasonConsistency(
        boolean ok,
        String failReason,
        boolean requireFailReasonWhenNotOk,
        String mode
    ) {
        if (ok && failReason != null) {
            fail("E-REPORT-SCHEMA-CONSTRAINT", "failReasonCode must be null when ok=true");
        }
        if (!ok && requireFailReasonWhenNotOk && failReason == null) {
            fail("E-REPORT-SCHEMA-CONSTRAINT", "failReasonCode must be non-null when mode=" + mode + " and ok=false");
        }
    }

    private static void requireDateTimeString(Map<String, Object> obj, String key) {
        String value = requireString(obj, key);
        try {
            Instant.parse(value);
        } catch (RuntimeException e) {
            fail("E-REPORT-SCHEMA-CONSTRAINT", "Expected ISO-8601 instant for key: " + key);
        }
    }

    private static void requireCountMap(Map<String, Object> map, String key) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String k = entry.getKey();
            if (k == null || k.isBlank()) {
                fail("E-REPORT-SCHEMA-TYPE", key + " must not contain blank keys");
            }
            Object value = entry.getValue();
            if (!(value instanceof Number n) || !(n instanceof Integer || n instanceof Long) || n.longValue() < 1) {
                fail("E-REPORT-SCHEMA-CONSTRAINT", key + " values must be integers >= 1");
            }
        }
    }

    private static void validateIssue(Map<String, Object> issue) {
        Set<String> expected = Set.of("grammar", "rule", "code", "severity", "category", "message", "hint");
        if (!new HashSet<>(issue.keySet()).equals(expected)) {
            fail("E-REPORT-SCHEMA-KEY-ORDER", "Unexpected issue keys: " + issue.keySet());
        }
        requireString(issue, "grammar");
        Object rule = requireKey(issue, "rule");
        if (!(rule == null || (rule instanceof String s && !s.isBlank()))) {
            fail("E-REPORT-SCHEMA-TYPE", "Expected nullable non-empty string for key: rule");
        }
        requireString(issue, "code");
        requireString(issue, "severity");
        requireString(issue, "category");
        requireString(issue, "message");
        requireString(issue, "hint");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof Map<?, ?>)) {
            fail("E-REPORT-SCHEMA-TYPE", "Expected object for key: " + key);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireArray(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof List<?>)) {
            fail("E-REPORT-SCHEMA-TYPE", "Expected array for key: " + key);
        }
        return (List<Object>) value;
    }

    private static Object requireKey(Map<String, Object> obj, String key) {
        if (!obj.containsKey(key)) {
            fail("E-REPORT-SCHEMA-MISSING-KEY", "Missing key: " + key);
        }
        return obj.get(key);
    }

    private static Map<String, Object> parseObject(String json) {
        try {
            return JsonMiniParser.parseObject(json, "E-REPORT-SCHEMA");
        } catch (ReportSchemaValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            fail("E-REPORT-SCHEMA-PARSE", e.getMessage());
            return Map.of();
        }
    }

    private static void fail(String code, String message) {
        throw new ReportSchemaValidationException(code, message);
    }

}
