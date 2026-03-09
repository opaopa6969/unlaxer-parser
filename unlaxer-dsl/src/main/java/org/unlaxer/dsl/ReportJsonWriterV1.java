package org.unlaxer.dsl;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Report JSON writer implementation for reportVersion=1.
 */
final class ReportJsonWriterV1 {

    private ReportJsonWriterV1() {}

    static String validationSuccess(
        String toolVersion,
        String argsHash,
        String generatedAt,
        int grammarCount,
        int warningsCount
    ) {
        return "{\"reportVersion\":1"
            + ",\"schemaVersion\":\"" + ReportJsonWriter.escapeJson(ReportJsonWriter.REPORT_SCHEMA_VERSION) + "\""
            + ",\"schemaUrl\":\"" + ReportJsonWriter.escapeJson(ReportJsonWriter.REPORT_SCHEMA_URL) + "\""
            + ",\"toolVersion\":\"" + ReportJsonWriter.escapeJson(toolVersion) + "\""
            + ",\"argsHash\":\"" + ReportJsonWriter.escapeJson(argsHash) + "\""
            + ",\"generatedAt\":\"" + ReportJsonWriter.escapeJson(generatedAt) + "\""
            + ",\"mode\":\"validate\""
            + ",\"ok\":true,\"grammarCount\":" + grammarCount + ",\"warningsCount\":" + warningsCount + ",\"issues\":[]}";
    }

    static String validationFailure(
        String toolVersion,
        String argsHash,
        String generatedAt,
        String failReasonCode,
        List<ReportJsonWriter.ValidationIssueRow> rows
    ) {
        Map<String, Integer> severityCounts = new TreeMap<>();
        Map<String, Integer> categoryCounts = new TreeMap<>();
        for (ReportJsonWriter.ValidationIssueRow row : rows) {
            severityCounts.merge(row.severity(), 1, Integer::sum);
            categoryCounts.merge(row.category(), 1, Integer::sum);
        }
        int warningsCount = severityCounts.getOrDefault("WARNING", 0);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"reportVersion\":1")
            .append(",\"schemaVersion\":\"").append(ReportJsonWriter.escapeJson(ReportJsonWriter.REPORT_SCHEMA_VERSION)).append("\"")
            .append(",\"schemaUrl\":\"").append(ReportJsonWriter.escapeJson(ReportJsonWriter.REPORT_SCHEMA_URL)).append("\"")
            .append(",\"toolVersion\":\"").append(ReportJsonWriter.escapeJson(toolVersion)).append("\"")
            .append(",\"argsHash\":\"").append(ReportJsonWriter.escapeJson(argsHash)).append("\"")
            .append(",\"generatedAt\":\"").append(ReportJsonWriter.escapeJson(generatedAt)).append("\"")
            .append(",\"mode\":\"validate\",\"ok\":false,\"failReasonCode\":");
        if (failReasonCode == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(ReportJsonWriter.escapeJson(failReasonCode)).append("\"");
        }
        sb.append(",\"issueCount\":")
            .append(rows.size())
            .append(",\"warningsCount\":").append(warningsCount)
            .append(",\"severityCounts\":").append(toCountsJson(severityCounts))
            .append(",\"categoryCounts\":").append(toCountsJson(categoryCounts))
            .append(",\"issues\":[");

        for (int i = 0; i < rows.size(); i++) {
            ReportJsonWriter.ValidationIssueRow row = rows.get(i);
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{")
                .append("\"grammar\":\"").append(ReportJsonWriter.escapeJson(row.grammar())).append("\",")
                .append("\"rule\":");
            if (row.rule() == null) {
                sb.append("null,");
            } else {
                sb.append("\"").append(ReportJsonWriter.escapeJson(row.rule())).append("\",");
            }
            sb.append("\"code\":\"").append(ReportJsonWriter.escapeJson(row.code())).append("\",")
                .append("\"severity\":\"").append(ReportJsonWriter.escapeJson(row.severity())).append("\",")
                .append("\"category\":\"").append(ReportJsonWriter.escapeJson(row.category())).append("\",")
                .append("\"message\":\"").append(ReportJsonWriter.escapeJson(row.message())).append("\",")
                .append("\"hint\":\"").append(ReportJsonWriter.escapeJson(row.hint())).append("\"")
                .append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    static String generationResult(
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
        StringBuilder sb = new StringBuilder();
        sb.append("{\"reportVersion\":1")
            .append(",\"schemaVersion\":\"").append(ReportJsonWriter.escapeJson(ReportJsonWriter.REPORT_SCHEMA_VERSION)).append("\"")
            .append(",\"schemaUrl\":\"").append(ReportJsonWriter.escapeJson(ReportJsonWriter.REPORT_SCHEMA_URL)).append("\"")
            .append(",\"toolVersion\":\"").append(ReportJsonWriter.escapeJson(toolVersion)).append("\"")
            .append(",\"argsHash\":\"").append(ReportJsonWriter.escapeJson(argsHash)).append("\"")
            .append(",\"generatedAt\":\"").append(ReportJsonWriter.escapeJson(generatedAt)).append("\"")
            .append(",\"mode\":\"generate\",\"ok\":").append(ok)
            .append(",\"failReasonCode\":");
        if (failReasonCode == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(ReportJsonWriter.escapeJson(failReasonCode)).append("\"");
        }
        sb.append(",\"grammarCount\":")
            .append(grammarCount)
            .append(",\"generatedCount\":").append(generatedFiles.size())
            .append(",\"warningsCount\":").append(warningsCount)
            .append(",\"writtenCount\":").append(writtenCount)
            .append(",\"skippedCount\":").append(skippedCount)
            .append(",\"conflictCount\":").append(conflictCount)
            .append(",\"dryRunCount\":").append(dryRunCount)
            .append(",\"generatedFiles\":[");

        for (int i = 0; i < generatedFiles.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(ReportJsonWriter.escapeJson(generatedFiles.get(i))).append("\"");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String toCountsJson(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("\"").append(ReportJsonWriter.escapeJson(entry.getKey())).append("\":")
                .append(entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }
}
