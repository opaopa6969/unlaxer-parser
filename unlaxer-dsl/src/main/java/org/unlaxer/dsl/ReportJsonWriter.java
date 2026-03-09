package org.unlaxer.dsl;

import java.util.List;

/**
 * Version-dispatch facade for JSON report writers.
 */
final class ReportJsonWriter {

    private ReportJsonWriter() {}
    static final String REPORT_SCHEMA_VERSION = "1.0";
    static final String REPORT_SCHEMA_URL = "https://unlaxer.dev/schema/report-v1.json";

    record ValidationIssueRow(
        String grammar,
        String rule,
        String code,
        String severity,
        String category,
        String message,
        String hint
    ) {}

    static String validationSuccess(
        int reportVersion,
        String toolVersion,
        String argsHash,
        String generatedAt,
        int grammarCount,
        int warningsCount
    ) {
        return switch (reportVersion) {
            case 1 -> ReportJsonWriterV1.validationSuccess(toolVersion, argsHash, generatedAt, grammarCount, warningsCount);
            default -> throw unsupportedVersion(reportVersion);
        };
    }

    static String validationFailure(
        int reportVersion,
        String toolVersion,
        String argsHash,
        String generatedAt,
        String failReasonCode,
        List<ValidationIssueRow> rows
    ) {
        return switch (reportVersion) {
            case 1 -> ReportJsonWriterV1.validationFailure(toolVersion, argsHash, generatedAt, failReasonCode, rows);
            default -> throw unsupportedVersion(reportVersion);
        };
    }

    static String generationResult(
        int reportVersion,
        String toolVersion,
        String argsHash,
        String generatedAt,
        boolean ok,
        String failReasonCode,
        int grammarCount,
        List<String> generatedFiles,
        int warningsCount,
        int writtenCount,
        int skippedCount,
        int conflictCount,
        int dryRunCount
    ) {
        return switch (reportVersion) {
            case 1 -> ReportJsonWriterV1.generationResult(
                toolVersion,
                argsHash,
                generatedAt,
                ok,
                failReasonCode,
                grammarCount,
                generatedFiles,
                warningsCount,
                writtenCount,
                skippedCount,
                conflictCount,
                dryRunCount
            );
            default -> throw unsupportedVersion(reportVersion);
        };
    }

    static String escapeJson(String s) {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static IllegalArgumentException unsupportedVersion(int reportVersion) {
        return new IllegalArgumentException("Unsupported reportVersion: " + reportVersion);
    }
}
