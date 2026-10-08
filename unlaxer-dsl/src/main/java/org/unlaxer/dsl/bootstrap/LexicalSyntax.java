package org.unlaxer.dsl.bootstrap;

import java.util.ArrayList;
import java.util.List;
import org.unlaxer.dsl.runtime.LexicalExpression;
import org.unlaxer.dsl.runtime.LexicalExpression.Op;

/** Parser for the token-expression sublanguage; the outer frontend owns declaration spans. */
public final class LexicalSyntax {
    /** Canonical, re-readable form; never substitutes a host class name for recognition. */
    public static String render(LexicalExpression e) {
        return switch (e.op()) {
            case LITERAL -> quote(e.text());
            case REF -> e.text();
            case ANY, EOF, BOF, BOL, EOL -> e.op().name();
            case RANGE -> "CHAR_RANGE(" + quote(new String(Character.toChars(e.min()))) + ", "
                + quote(new String(Character.toChars(e.max()))) + ")";
            case EXCEPT -> "NEGATION(" + quote(e.text()) + ")";
            case SEQUENCE, CHOICE -> "(" + e.children().stream().map(LexicalSyntax::render)
                .collect(java.util.stream.Collectors.joining(e.op() == Op.CHOICE ? " | " : " ")) + ")";
            case REPEAT -> "(" + render(e.children().get(0)) + "){" + e.min()
                + (e.min() == e.max() ? "" : "," + (e.max() < 0 ? "" : e.max())) + "}";
            case LOOK, NOT -> (e.op() == Op.LOOK ? "LOOKAHEAD(" : "NEGATIVE_LOOKAHEAD(")
                + render(e.children().get(0)) + ")";
            case CAPTURE -> "CAPTURE(" + e.text() + ", " + render(e.children().get(0)) + ")";
            case BACKREF -> "SAME_AS(" + e.text() + ")";
            case SCOPE -> throw new IllegalArgumentException("render declarations before scope expansion");
        };
    }
    private static String quote(String text) {
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'";
    }
    public record Result(LexicalExpression expression, int end) {}
    private final String source;
    private int position, depth;
    private LexicalSyntax(String source) { this.source = source; }
    public static Result parse(String source) {
        var parser = new LexicalSyntax(source);
        var expression = parser.expression();
        parser.require(';');
        return new Result(expression, parser.position);
    }
    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at lexical offset " + position);
    }
    private void space() {
        while (position < source.length()) {
            char c = source.charAt(position);
            if (" \t\r\n\u000b\f".indexOf(c) >= 0) { position++; continue; }
            if (source.startsWith("//", position)) {
                while (position < source.length() && source.charAt(position) != '\n' && source.charAt(position) != '\r') position++;
                continue;
            }
            break;
        }
    }
    private char peek() { space(); return position == source.length() ? '\0' : source.charAt(position); }
    private boolean eat(char c) { if (peek() != c) return false; position++; return true; }
    private void require(char c) { if (!eat(c)) throw error("expected " + c); }
    private String identifier() {
        space(); int start = position;
        if (!head(peek())) throw error("expected identifier");
        position++;
        while (position < source.length() && (head(source.charAt(position)) || digit(source.charAt(position)))) position++;
        return source.substring(start, position);
    }
    private static boolean head(char c) { return c == '_' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z'; }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private int integer() {
        space(); int start = position;
        while (position < source.length() && digit(source.charAt(position))) position++;
        if (start == position) throw error("expected repeat bound");
        try { return Integer.parseInt(source.substring(start, position)); }
        catch (NumberFormatException e) { throw error("repeat bound exceeds 2147483647"); }
    }
    private String quoted() {
        require('\''); int start = position - 1;
        while (position < source.length()) {
            char c = source.charAt(position++);
            if (c == '\'') return UBNFMapper.stripQuotes(source.substring(start, position));
            if (c == '\\' && position < source.length()) position++;
        }
        throw error("unclosed literal");
    }
    private LexicalExpression expression() {
        if (++depth > 128) throw error("lexical nesting exceeds 128");
        var alternatives = new ArrayList<LexicalExpression>();
        do { alternatives.add(sequence()); } while (eat('|'));
        depth--;
        return new LexicalExpression(Op.CHOICE, "", 0, 0, alternatives);
    }
    private LexicalExpression sequence() {
        var elements = new ArrayList<LexicalExpression>();
        while (peek() == '\'' || peek() == '(' || peek() == '[' || peek() == '{' || head(peek())) elements.add(element());
        if (elements.isEmpty()) throw error("empty lexical sequence; use an empty literal");
        return new LexicalExpression(Op.SEQUENCE, "", 0, 0, elements);
    }
    private LexicalExpression element() {
        var result = atom();
        if (eat('?')) return LexicalExpression.repeat(result, 0, 1);
        if (eat('*')) return LexicalExpression.repeat(result, 0, -1);
        if (eat('+')) return LexicalExpression.repeat(result, 1, -1);
        int saved = position;
        if (eat('{')) {
            if (!digit(peek())) { position = saved; return result; }
            int min = integer(), max = min;
            if (eat(',')) max = digit(peek()) ? integer() : -1;
            require('}');
            if (max >= 0 && min > max) throw error("inverted repeat bounds");
            result = LexicalExpression.repeat(result, min, max);
        }
        return result;
    }
    private LexicalExpression atom() {
        if (peek() == '\'') return LexicalExpression.leaf(Op.LITERAL, quoted());
        if (eat('(')) { var result = expression(); require(')'); return result; }
        if (eat('[')) { var result = expression(); require(']'); return LexicalExpression.repeat(result, 0, 1); }
        if (eat('{')) { var result = expression(); require('}'); return LexicalExpression.repeat(result, 0, -1); }
        String name = identifier();
        if (List.of("LOOKAHEAD", "NEGATIVE_LOOKAHEAD", "CAPTURE", "SAME_AS", "NEGATION", "CHAR_RANGE").contains(name)
                && eat('(')) {
            var result = switch (name) {
                case "LOOKAHEAD", "NEGATIVE_LOOKAHEAD" -> LexicalExpression.node(name.equals("LOOKAHEAD") ? Op.LOOK : Op.NOT, expression());
                case "CAPTURE" -> {
                    String binding = identifier(); require(',');
                    yield new LexicalExpression(Op.CAPTURE, binding, 0, 0, List.of(expression()));
                }
                case "SAME_AS" -> LexicalExpression.leaf(Op.BACKREF, identifier());
                case "NEGATION" -> LexicalExpression.leaf(Op.EXCEPT, quoted());
                case "CHAR_RANGE" -> {
                    String from = quoted(); require(','); String to = quoted();
                    if (from.codePointCount(0, from.length()) != 1 || to.codePointCount(0, to.length()) != 1)
                        throw error("range requires scalar boundaries");
                    int min = from.codePointAt(0), max = to.codePointAt(0);
                    if (min > max || min >= 0xd800 && min <= 0xdfff || max >= 0xd800 && max <= 0xdfff)
                        throw error("invalid scalar range");
                    yield new LexicalExpression(Op.RANGE, "", min, max, List.of());
                }
                default -> throw error("unknown lexical constructor " + name);
            };
            require(')'); return result;
        }
        if (List.of("ANY", "EOF", "BOF", "BOL", "EOL").contains(name)) return LexicalExpression.leaf(Op.valueOf(name), "");
        while (eat('.')) name += "." + identifier();
        return LexicalExpression.leaf(Op.REF, name);
    }
}
