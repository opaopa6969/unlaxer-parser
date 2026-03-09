package org.unlaxer.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Instant;

/**
 * Validates manifest JSON/NDJSON schema contracts.
 */
final class ManifestSchemaValidator {

    private ManifestSchemaValidator() {}

    static void validate(String manifestFormat, String payload) {
        if ("json".equals(manifestFormat)) {
            validateJson(payload);
            return;
        }
        if ("ndjson".equals(manifestFormat)) {
            validateNdjson(payload);
            return;
        }
        fail("E-MANIFEST-SCHEMA-UNSUPPORTED-FORMAT", "Unsupported manifest format: " + manifestFormat);
    }

    private static void validateJson(String payload) {
        Map<String, Object> obj = parseObject(payload);
        requireTopLevelOrder(
            obj,
            List.of(
                "mode",
                "generatedAt",
                "toolVersion",
                "argsHash",
                "ok",
                "failReasonCode",
                "exitCode",
                "warningsCount",
                "writtenCount",
                "skippedCount",
                "conflictCount",
                "dryRunCount",
                "files"
            )
        );
        String mode = requireEnum(obj, "mode", Set.of("validate", "generate"));
        requireDateTimeString(obj, "generatedAt");
        requireString(obj, "toolVersion");
        requireString(obj, "argsHash");
        boolean ok = requireBoolean(obj, "ok");
        String failReason = requireNullableEnum(
            obj,
            "failReasonCode",
            Set.of("FAIL_ON_WARNING", "FAIL_ON_WARNINGS_COUNT", "FAIL_ON_SKIPPED", "FAIL_ON_CONFLICT", "FAIL_ON_CLEANED")
        );
        requireOkFailReasonConsistency(mode, ok, failReason);
        requireIntegerMin(obj, "exitCode", 0);
        requireIntegerMin(obj, "warningsCount", 0);
        requireIntegerMin(obj, "writtenCount", 0);
        requireIntegerMin(obj, "skippedCount", 0);
        requireIntegerMin(obj, "conflictCount", 0);
        requireIntegerMin(obj, "dryRunCount", 0);
        List<Object> files = requireArray(obj, "files");
        for (Object item : files) {
            if (!(item instanceof Map<?, ?>)) {
                fail("E-MANIFEST-SCHEMA-TYPE", "Expected object in files array");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> fileObj = (Map<String, Object>) item;
            requireTopLevelOrder(fileObj, List.of("action", "path"));
            requireEnum(fileObj, "action", Set.of("written", "skipped", "dry-run", "conflict", "cleaned"));
            requireString(fileObj, "path");
        }
    }

    private static void validateNdjson(String payload) {
        String[] lines = payload.split("\\R");
        if (lines.length == 0) {
            fail("E-MANIFEST-SCHEMA-EMPTY", "NDJSON manifest payload is empty");
        }
        int summaryCount = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Map<String, Object> obj = parseObject(line);
            String event = requireString(obj, "event");
            if ("file".equals(event)) {
                requireTopLevelOrder(obj, List.of("event", "action", "path"));
                requireEnum(obj, "action", Set.of("written", "skipped", "dry-run", "conflict", "cleaned"));
                requireString(obj, "path");
                continue;
            }
            if ("manifest-summary".equals(event)) {
                summaryCount++;
                requireTopLevelOrder(
                    obj,
                    List.of(
                        "event",
                        "mode",
                        "generatedAt",
                        "toolVersion",
                        "argsHash",
                        "ok",
                        "failReasonCode",
                        "exitCode",
                        "warningsCount",
                        "writtenCount",
                        "skippedCount",
                        "conflictCount",
                        "dryRunCount"
                    )
                );
                String mode = requireEnum(obj, "mode", Set.of("validate", "generate"));
                requireDateTimeString(obj, "generatedAt");
                requireString(obj, "toolVersion");
                requireString(obj, "argsHash");
                boolean ok = requireBoolean(obj, "ok");
                String failReason = requireNullableEnum(
                    obj,
                    "failReasonCode",
                    Set.of(
                        "FAIL_ON_WARNING",
                        "FAIL_ON_WARNINGS_COUNT",
                        "FAIL_ON_SKIPPED",
                        "FAIL_ON_CONFLICT",
                        "FAIL_ON_CLEANED"
                    )
                );
                requireOkFailReasonConsistency(mode, ok, failReason);
                requireIntegerMin(obj, "exitCode", 0);
                requireIntegerMin(obj, "warningsCount", 0);
                requireIntegerMin(obj, "writtenCount", 0);
                requireIntegerMin(obj, "skippedCount", 0);
                requireIntegerMin(obj, "conflictCount", 0);
                requireIntegerMin(obj, "dryRunCount", 0);
                continue;
            }
            fail("E-MANIFEST-SCHEMA-INVALID-EVENT", "Unsupported manifest event: " + event);
        }
        if (summaryCount != 1) {
            fail("E-MANIFEST-SCHEMA-SUMMARY", "NDJSON manifest must include exactly one manifest-summary event");
        }
    }

    private static void requireTopLevelOrder(Map<String, Object> obj, List<String> expectedOrder) {
        List<String> keys = new ArrayList<>(obj.keySet());
        if (!keys.equals(expectedOrder)) {
            fail(
                "E-MANIFEST-SCHEMA-KEY-ORDER",
                "Unexpected JSON keys/order. expected=" + expectedOrder + " actual=" + keys
            );
        }
    }

    private static String requireString(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof String s) || s.isBlank()) {
            fail("E-MANIFEST-SCHEMA-TYPE", "Expected string for key: " + key);
        }
        return (String) value;
    }

    private static String requireNullableString(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s) || s.isBlank()) {
            fail("E-MANIFEST-SCHEMA-TYPE", "Expected nullable string for key: " + key);
        }
        return (String) value;
    }

    private static String requireEnum(Map<String, Object> obj, String key, Set<String> allowed) {
        String value = requireString(obj, key);
        if (!allowed.contains(value)) {
            fail("E-MANIFEST-SCHEMA-CONSTRAINT", "Unsupported value for " + key + ": " + value);
        }
        return value;
    }

    private static String requireNullableEnum(Map<String, Object> obj, String key, Set<String> allowed) {
        String value = requireNullableString(obj, key);
        if (value == null) {
            return null;
        }
        if (!allowed.contains(value)) {
            fail("E-MANIFEST-SCHEMA-CONSTRAINT", "Unsupported value for " + key + ": " + value);
        }
        return value;
    }

    private static void requireOkFailReasonConsistency(String mode, boolean ok, String failReason) {
        if (ok && failReason != null) {
            fail("E-MANIFEST-SCHEMA-CONSTRAINT", "failReasonCode must be null when ok=true");
        }
        if ("generate".equals(mode) && !ok && failReason == null) {
            fail("E-MANIFEST-SCHEMA-CONSTRAINT", "failReasonCode must be non-null when mode=generate and ok=false");
        }
    }

    private static boolean requireBoolean(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof Boolean b)) {
            fail("E-MANIFEST-SCHEMA-TYPE", "Expected boolean for key: " + key);
        }
        return (Boolean) value;
    }

    private static Number requireNumber(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof Number n)) {
            fail("E-MANIFEST-SCHEMA-TYPE", "Expected number for key: " + key);
        }
        return (Number) value;
    }

    private static long requireIntegerMin(Map<String, Object> obj, String key, long min) {
        Number n = requireNumber(obj, key);
        if (!(n instanceof Integer || n instanceof Long)) {
            fail("E-MANIFEST-SCHEMA-TYPE", "Expected integer for key: " + key);
        }
        long value = n.longValue();
        if (value < min) {
            fail("E-MANIFEST-SCHEMA-CONSTRAINT", "Expected " + key + " >= " + min);
        }
        return value;
    }

    private static void requireDateTimeString(Map<String, Object> obj, String key) {
        String value = requireString(obj, key);
        try {
            Instant.parse(value);
        } catch (RuntimeException e) {
            fail("E-MANIFEST-SCHEMA-CONSTRAINT", "Expected ISO-8601 instant for key: " + key);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requireArray(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof List<?>)) {
            fail("E-MANIFEST-SCHEMA-TYPE", "Expected array for key: " + key);
        }
        return (List<Object>) value;
    }

    private static Object requireKey(Map<String, Object> obj, String key) {
        if (!obj.containsKey(key)) {
            fail("E-MANIFEST-SCHEMA-MISSING-KEY", "Missing key: " + key);
        }
        return obj.get(key);
    }

    private static Map<String, Object> parseObject(String json) {
        try {
            return JsonMiniParser.parseObject(json, "E-MANIFEST-SCHEMA");
        } catch (ReportSchemaValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            fail("E-MANIFEST-SCHEMA-PARSE", e.getMessage());
            return Map.of();
        }
    }

    private static void fail(String code, String message) {
        throw new ReportSchemaValidationException(code, message);
    }

}
