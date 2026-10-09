package org.unlaxer.dsl.runtime;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable lexical program. Offsets used internally are UTF-16 boundaries; no context is mutated. */
public record LexicalExpression(Op op, String text, int min, int max,
        List<LexicalExpression> children) implements Serializable {
    public enum Op { LITERAL, ANY, XID_IDENTIFIER, EOF, BOF, BOL, EOL, RANGE, EXCEPT,
        SEQUENCE, CHOICE, REPEAT, LOOK, NOT, CAPTURE, BACKREF, REF, SCOPE }

    public LexicalExpression {
        java.util.Objects.requireNonNull(op);
        java.util.Objects.requireNonNull(text);
        children = List.copyOf(children);
        int arity = switch (op) {
            case CAPTURE, LOOK, NOT, SCOPE, REPEAT -> 1;
            case SEQUENCE, CHOICE -> children.isEmpty() ? -1 : children.size();
            default -> 0;
        };
        if (children.size() != arity) throw new IllegalArgumentException("invalid lexical arity");
        if (op == Op.REPEAT && (min < 0 || max < -1 || max >= 0 && min > max))
            throw new IllegalArgumentException("invalid lexical repeat bounds");
        if (op == Op.RANGE && (min < 0 || min > max || max > 0x10ffff
                || min >= 0xd800 && min <= 0xdfff || max >= 0xd800 && max <= 0xdfff))
            throw new IllegalArgumentException("invalid lexical scalar range");
    }
    public static LexicalExpression leaf(Op op, String text) {
        return new LexicalExpression(op, text, 0, 0, List.of());
    }
    public static LexicalExpression node(Op op, LexicalExpression... children) {
        return new LexicalExpression(op, "", 0, 0, List.of(children));
    }
    public static LexicalExpression repeat(LexicalExpression child, int min, int max) {
        return new LexicalExpression(Op.REPEAT, "", min, max, List.of(child));
    }

    /** Returns the end UTF-16 index, or -1. Capture storage is private to this invocation. */
    public int match(String source, int start) { return match(source, start, new HashMap<>()); }

    private int match(String source, int start, Map<String, String> bindings) {
        Map<String, String> before = bindings.isEmpty() ? Map.of() : new HashMap<>(bindings);
        int end = eval(source, start, bindings);
        if (end < 0) { bindings.clear(); bindings.putAll(before); }
        return end;
    }

    private int eval(String s, int p, Map<String, String> bindings) {
        return switch (op) {
            case LITERAL -> s.startsWith(text, p) ? p + text.length() : -1;
            case XID_IDENTIFIER -> UnicodeXid.identifierEnd(s, p);
            case ANY -> scalarAt(s, p) >= 0 ? p + Character.charCount(s.codePointAt(p)) : -1;
            case EOF -> p == s.length() ? p : -1;
            case BOF -> p == 0 ? p : -1;
            case BOL -> p == 0 || s.charAt(p - 1) == '\r' || s.charAt(p - 1) == '\n' ? p : -1;
            case EOL -> p == s.length() || s.charAt(p) == '\r' || s.charAt(p) == '\n' ? p : -1;
            case RANGE -> scalarAt(s, p) >= 0 && s.codePointAt(p) >= min && s.codePointAt(p) <= max
                ? p + Character.charCount(s.codePointAt(p)) : -1;
            case EXCEPT -> scalarAt(s, p) >= 0 && text.indexOf(s.codePointAt(p)) < 0
                ? p + Character.charCount(s.codePointAt(p)) : -1;
            case SEQUENCE -> {
                int end = p;
                for (var child : children) { end = child.match(s, end, bindings); if (end < 0) break; }
                yield end;
            }
            case CHOICE -> {
                int end = -1;
                for (var child : children) { end = child.match(s, p, bindings); if (end >= 0) break; }
                yield end;
            }
            case REPEAT -> {
                int end = p, count = 0;
                while (max < 0 || count < max) {
                    int next = children.get(0).match(s, end, bindings);
                    if (next < 0) break;
                    count++;
                    if (next == end && max < 0) throw new IllegalStateException("nullable lexical repeat");
                    end = next;
                }
                yield count >= min ? end : -1;
            }
            case LOOK, NOT -> {
                boolean matched = children.get(0).match(s, p, new HashMap<>(bindings)) >= 0;
                yield matched == (op == Op.LOOK) ? p : -1;
            }
            case CAPTURE -> {
                int end = children.get(0).match(s, p, bindings);
                if (end >= 0) bindings.put(text, s.substring(p, end));
                yield end;
            }
            case BACKREF -> {
                String value = bindings.get(text);
                yield value != null && s.startsWith(value, p) ? p + value.length() : -1;
            }
            case SCOPE -> children.get(0).match(s, p, new HashMap<>());
            case REF -> throw new IllegalStateException("unresolved lexical reference: " + text);
        };
    }

    private static int scalarAt(String source, int position) {
        if (position >= source.length()) return -1;
        int cp = source.codePointAt(position);
        return cp >= 0xd800 && cp <= 0xdfff ? -1 : cp;
    }

    /** Conservative nullability, after references have been expanded. */
    public boolean nullable() {
        return switch (op) {
            case LITERAL -> text.isEmpty();
            case ANY, XID_IDENTIFIER, RANGE, EXCEPT -> false;
            case EOF, BOF, BOL, EOL, LOOK, NOT, BACKREF, REF -> true;
            case SEQUENCE -> children.stream().allMatch(LexicalExpression::nullable);
            case CHOICE -> children.stream().anyMatch(LexicalExpression::nullable);
            case REPEAT -> min == 0 || children.get(0).nullable();
            case CAPTURE, SCOPE -> children.get(0).nullable();
        };
    }
}
