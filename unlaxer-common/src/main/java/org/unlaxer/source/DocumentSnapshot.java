package org.unlaxer.source;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable source identity and strict conversions at Unicode scalar boundaries. */
public record DocumentSnapshot(String uri, long version, String text) {
    public DocumentSnapshot {
        Objects.requireNonNull(uri);
        Objects.requireNonNull(text);
        if (uri.isEmpty() || version < 0) { throw new IllegalArgumentException("invalid snapshot"); }
        for (int index = 0; index < text.length(); index++) {
            char unit = text.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index == text.length() || false == Character.isLowSurrogate(text.charAt(index))) {
                    throw new IllegalArgumentException("unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(unit)) { throw new IllegalArgumentException("unpaired surrogate"); }
        }
    }
    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start) { throw new IllegalArgumentException("invalid span"); }
        }
        public int length() { return end - start; }
        public boolean contains(Span other) { return start <= other.start && other.end <= end; }
    }
    public record Position(int line, int character) {
        public Position {
            if (line < 0 || character < 0) { throw new IllegalArgumentException("invalid position"); }
        }
    }
    public int length() { return text.codePointCount(0, text.length()); }
    public void check(Span span) {
        if (span.end > length()) { throw new IllegalArgumentException("outside snapshot"); }
    }
    public int utf16(int point) {
        check(new Span(point, point));
        return text.offsetByCodePoints(0, point);
    }
    public int utf8(int point) { return text.substring(0, utf16(point)).getBytes(StandardCharsets.UTF_8).length; }
    public int fromUtf16(int offset) {
        if (offset < 0 || offset > text.length() || (offset > 0 && offset < text.length()
                && Character.isLowSurrogate(text.charAt(offset)))) {
            throw new IllegalArgumentException("not a UTF-16 boundary");
        }
        return text.codePointCount(0, offset);
    }
    public int fromUtf8(int offset) {
        // Visit each scalar once; re-encoding every prefix makes this quadratic.
        long bytes = 0;
        int point = 0;
        for (int index = 0; ; point++) {
            if (bytes == offset) { return point; }
            if (bytes > offset || index == text.length()) { break; }
            int scalar = text.codePointAt(index);
            bytes += scalar <= 0x7f ? 1 : scalar <= 0x7ff ? 2 : scalar <= 0xffff ? 3 : 4;
            index += Character.charCount(scalar);
        }
        throw new IllegalArgumentException("not a UTF-8 boundary");
    }
    public String slice(Span span) { check(span); return text.substring(utf16(span.start), utf16(span.end)); }
    public Position lsp(int point) {
        int end = utf16(point);
        int line = 0;
        int start = 0;
        for (int index = 0; index < end; index++) {
            char unit = text.charAt(index);
            if (unit == '\r') {
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    if (index + 1 == end) { throw new IllegalArgumentException("inside CRLF"); }
                    index++;
                }
                line++;
                start = index + 1;
            } else if (unit == '\n') { line++; start = index + 1; }
        }
        return new Position(line, end - start);
    }
    public int fromLsp(Position position) {
        if (position == null) { throw new IllegalArgumentException("not an LSP boundary"); }
        int line = 0;
        int column = 0;
        int point = 0;
        for (int index = 0; ; point++) {
            if (line == position.line && column == position.character) { return point; }
            if (index == text.length()) { break; }
            int scalar = text.codePointAt(index);
            index += Character.charCount(scalar);
            if (scalar == '\r' || scalar == '\n') {
                // CRLF is one line break and has no LSP position between its scalars.
                if (scalar == '\r' && index < text.length() && text.charAt(index) == '\n') {
                    index++;
                    point++;
                }
                line++;
                column = 0;
            } else { column += Character.charCount(scalar); }
        }
        throw new IllegalArgumentException("not an LSP boundary");
    }
}
