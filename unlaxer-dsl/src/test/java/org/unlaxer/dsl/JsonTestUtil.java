package org.unlaxer.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonTestUtil {

    private JsonTestUtil() {}

    static Map<String, Object> parseObject(String json) {
        Parser p = new Parser(json);
        Object v = p.parseValue();
        p.skipWhitespace();
        if (!p.isEnd()) {
            throw new IllegalArgumentException("unexpected trailing characters");
        }
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("expected JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m;
        return out;
    }

    static String getString(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("expected string for key: " + key);
        }
        return s;
    }

    static long getLong(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("expected number for key: " + key);
        }
        return n.longValue();
    }

    static boolean getBoolean(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof Boolean b)) {
            throw new IllegalArgumentException("expected boolean for key: " + key);
        }
        return b;
    }

    static Map<String, Object> getObject(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("expected object for key: " + key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m;
        return out;
    }

    static List<Object> getArray(Map<String, Object> obj, String key) {
        Object value = requireKey(obj, key);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("expected array for key: " + key);
        }
        @SuppressWarnings("unchecked")
        List<Object> out = (List<Object>) list;
        return out;
    }

    static String toJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escapeJson(s) + "\"";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append("\"").append(escapeJson(String.valueOf(entry.getKey()))).append("\":");
                sb.append(toJson(entry.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(toJson(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass());
    }

    private static String escapeJson(String s) {
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static Object requireKey(Map<String, Object> obj, String key) {
        if (!obj.containsKey(key)) {
            throw new IllegalArgumentException("missing key: " + key);
        }
        return obj.get(key);
    }

    private static final class Parser {
        private final String s;
        private int i;

        private Parser(String s) {
            this.s = s;
            this.i = 0;
        }

        private boolean isEnd() {
            return i >= s.length();
        }

        private void skipWhitespace() {
            while (!isEnd()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    i++;
                } else {
                    break;
                }
            }
        }

        private Object parseValue() {
            skipWhitespace();
            if (isEnd()) {
                throw new IllegalArgumentException("unexpected end");
            }
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> {
                    if (c == '-' || Character.isDigit(c)) {
                        yield parseNumber();
                    }
                    throw new IllegalArgumentException("unexpected char: " + c);
                }
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            skipWhitespace();
            Map<String, Object> obj = new LinkedHashMap<>();
            if (peek('}')) {
                expect('}');
                return obj;
            }
            while (true) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                obj.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    break;
                }
                expect(',');
            }
            return obj;
        }

        private List<Object> parseArray() {
            expect('[');
            skipWhitespace();
            List<Object> arr = new ArrayList<>();
            if (peek(']')) {
                expect(']');
                return arr;
            }
            while (true) {
                arr.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    break;
                }
                expect(',');
            }
            return arr;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!isEnd()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (isEnd()) {
                        throw new IllegalArgumentException("invalid escape");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                throw new IllegalArgumentException("invalid unicode escape");
                            }
                            String hex = s.substring(i, i + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                        default -> throw new IllegalArgumentException("invalid escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private Object parseLiteral(String text, Object value) {
            if (s.startsWith(text, i)) {
                i += text.length();
                return value;
            }
            throw new IllegalArgumentException("invalid literal");
        }

        private Number parseNumber() {
            int start = i;
            if (peek('-')) {
                i++;
            }
            while (!isEnd() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            boolean isDecimal = false;
            if (!isEnd() && s.charAt(i) == '.') {
                isDecimal = true;
                i++;
                while (!isEnd() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            if (!isEnd() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                isDecimal = true;
                i++;
                if (!isEnd() && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
                    i++;
                }
                while (!isEnd() && Character.isDigit(s.charAt(i))) {
                    i++;
                }
            }
            String token = s.substring(start, i);
            if (isDecimal) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }

        private boolean peek(char c) {
            skipWhitespace();
            return !isEnd() && s.charAt(i) == c;
        }

        private void expect(char c) {
            skipWhitespace();
            if (isEnd() || s.charAt(i) != c) {
                throw new IllegalArgumentException("expected '" + c + "'");
            }
            i++;
        }
    }
}
