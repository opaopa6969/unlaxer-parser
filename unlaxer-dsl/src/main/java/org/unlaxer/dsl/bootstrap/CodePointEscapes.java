package org.unlaxer.dsl.bootstrap;

/** UBNF v2 scalar escapes, deliberately restricted to character-class arguments. */
public final class CodePointEscapes {
    private CodePointEscapes() {}

    public static String decode(String quoted) {
        String value = quoted.substring(1, quoted.length() - 1);
        StringBuilder decoded = new StringBuilder();
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '\\' && index < value.length()) {
                int next = value.codePointAt(index);
                index += Character.charCount(next);
                if (next == 'u') {
                    int start;
                    int end;
                    if (index < value.length() && value.charAt(index) == '{') {
                        start = ++index;
                        end = value.indexOf('}', start);
                        if (end < 0 || value.codePointCount(start, end) < 1 || value.codePointCount(start, end) > 6) {
                            throw invalid("E-TOKEN-ESCAPE-LENGTH", "braced escape requires 1 to 6 hex digits");
                        }
                        index = end + 1;
                    } else {
                        start = index;
                        if (value.codePointCount(index, value.length()) < 4) {
                            throw invalid("E-TOKEN-ESCAPE-LENGTH", "escape requires exactly 4 hex digits");
                        }
                        end = value.offsetByCodePoints(index, 4);
                        index = end;
                    }
                    codePoint = 0;
                    for (int digitIndex = start; digitIndex < end; digitIndex++) {
                        char digit = value.charAt(digitIndex);
                        int hex = digit >= '0' && digit <= '9' ? digit - '0'
                            : digit >= 'a' && digit <= 'f' ? digit - 'a' + 10
                            : digit >= 'A' && digit <= 'F' ? digit - 'A' + 10 : -1;
                        if (hex < 0) { throw invalid("E-TOKEN-ESCAPE-HEX", "escape requires ASCII hex digits"); }
                        codePoint = (codePoint << 4) | hex;
                    }
                } else {
                    codePoint = switch (next) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '\\' -> '\\';
                        case '\'' -> '\'';
                        default -> { decoded.append('\\'); yield next; }
                    };
                }
            }
            if (codePoint > 0x10ffff) { throw invalid("E-TOKEN-ESCAPE-RANGE", "code point exceeds U+10FFFF"); }
            if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
                throw invalid("E-TOKEN-ESCAPE-SURROGATE", "surrogate is not a Unicode scalar");
            }
            decoded.appendCodePoint(codePoint);
        }
        return decoded.toString();
    }

    public static int boundary(String value) {
        if (value.codePointCount(0, value.length()) != 1) {
            throw invalid("E-TOKEN-RANGE-BOUNDARY", "range boundary requires exactly one scalar");
        }
        return value.codePointAt(0);
    }

    public static void validateRange(int min, int max) {
        if (min > max) { throw invalid("E-TOKEN-RANGE-ORDER", "minimum exceeds maximum"); }
        if (min <= 0xdfff && max >= 0xd800) {
            throw invalid("E-TOKEN-RANGE-SURROGATE", "range must not contain surrogates");
        }
    }

    private static IllegalArgumentException invalid(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }
}
