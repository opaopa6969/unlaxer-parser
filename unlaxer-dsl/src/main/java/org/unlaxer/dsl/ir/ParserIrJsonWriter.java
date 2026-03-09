package org.unlaxer.dsl.ir;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON serializer for parser IR payloads.
 */
public final class ParserIrJsonWriter {
    private ParserIrJsonWriter() {}

    public static String toJson(Map<String, Object> value) {
        StringBuilder sb = new StringBuilder();
        appendValue(sb, value);
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String s) {
            sb.append('"').append(escapeJson(s)).append('"');
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            appendObject(sb, map);
            return;
        }
        if (value instanceof List<?> list) {
            appendArray(sb, list);
            return;
        }
        throw new IllegalArgumentException("unsupported json value type: " + value.getClass().getName());
    }

    private static void appendObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escapeJson(String.valueOf(entry.getKey()))).append('"').append(':');
            appendValue(sb, entry.getValue());
        }
        sb.append('}');
    }

    private static void appendArray(StringBuilder sb, List<?> list) {
        sb.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendValue(sb, list.get(i));
        }
        sb.append(']');
    }

    private static String escapeJson(String s) {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
