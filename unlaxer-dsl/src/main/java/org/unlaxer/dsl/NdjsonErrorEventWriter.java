package org.unlaxer.dsl;

import java.util.List;

/**
 * Writes NDJSON error events for machine-readable CLI integrations.
 */
final class NdjsonErrorEventWriter {

    private NdjsonErrorEventWriter() {}

    static String cliErrorEvent(
        String code,
        String message,
        String detail,
        List<String> availableGenerators
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"event\":\"cli-error\"")
            .append(",\"code\":\"").append(ReportJsonWriter.escapeJson(code)).append("\"")
            .append(",\"message\":\"").append(ReportJsonWriter.escapeJson(message)).append("\"")
            .append(",\"detail\":");
        if (detail == null) {
            sb.append("null");
        } else {
            sb.append("\"").append(ReportJsonWriter.escapeJson(detail)).append("\"");
        }
        sb.append(",\"availableGenerators\":");
        if (availableGenerators == null || availableGenerators.isEmpty()) {
            sb.append("[]");
        } else {
            sb.append("[");
            for (int i = 0; i < availableGenerators.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append("\"").append(ReportJsonWriter.escapeJson(availableGenerators.get(i))).append("\"");
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }
}
