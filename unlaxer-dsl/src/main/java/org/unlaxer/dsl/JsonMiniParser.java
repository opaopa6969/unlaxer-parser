package org.unlaxer.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small JSON parser for internal schema checks.
 */
final class JsonMiniParser {

    private JsonMiniParser() {}

    static Map<String, Object> parseObject(String json, String codePrefix) {
        Parser p = new Parser(json, codePrefix);
        Object v = p.parseValue();
        p.skipWhitespace();
        if (!p.isEnd()) {
            fail(codePrefix, "PARSE", "unexpected trailing characters");
        }
        if (!(v instanceof Map<?, ?>)) {
            fail(codePrefix, "PARSE", "expected JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) v;
        return out;
    }

    private static void fail(String codePrefix, String suffix, String message) {
        throw new ReportSchemaValidationException(codePrefix + "-" + suffix, message);
    }

    private static final class Parser {
        private final String s;
        private final String codePrefix;
        private int i;

        private Parser(String s, String codePrefix) {
            this.s = s;
            this.codePrefix = codePrefix;
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
                fail(codePrefix, "PARSE", "unexpected end");
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
                    fail(codePrefix, "PARSE", "unexpected char: " + c);
                    yield null;
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
                        fail(codePrefix, "PARSE", "invalid escape");
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
                                fail(codePrefix, "PARSE", "invalid unicode escape");
                            }
                            String hex = s.substring(i, i + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                fail(codePrefix, "PARSE", "invalid unicode escape: " + hex);
                            }
                            i += 4;
                        }
                        default -> fail(codePrefix, "PARSE", "invalid escape: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            fail(codePrefix, "PARSE", "unterminated string");
            return null;
        }

        private Object parseLiteral(String text, Object value) {
            if (s.startsWith(text, i)) {
                i += text.length();
                return value;
            }
            fail(codePrefix, "PARSE", "invalid literal");
            return null;
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
            try {
                if (isDecimal) {
                    return Double.parseDouble(token);
                }
                return Long.parseLong(token);
            } catch (NumberFormatException e) {
                fail(codePrefix, "PARSE", "invalid number: " + token);
                return null;
            }
        }

        private boolean peek(char c) {
            skipWhitespace();
            return !isEnd() && s.charAt(i) == c;
        }

        private void expect(char c) {
            skipWhitespace();
            if (isEnd() || s.charAt(i) != c) {
                fail(codePrefix, "PARSE", "expected '" + c + "'");
            }
            i++;
        }
    }
}
