package org.unlaxer.dsl.bootstrap;

import java.util.Map;
import org.unlaxer.dsl.runtime.LexicalExpression;

/** Built-in aliases are case-insensitive; declarative token names retain their spelling. */
public final class WhitespaceDefinitions {
    private WhitespaceDefinitions() {}
    public static String normalize(String style) {
        String value = style.trim();
        if (value.equalsIgnoreCase("javaStyle")) return "javaStyle";
        if (value.equalsIgnoreCase("none")) return "none";
        return value;
    }
    public static LexicalExpression resolve(String style, Map<String, LexicalExpression> tokens) {
        String name = normalize(style);
        if (name.equals("javaStyle") || name.equals("none")) return null;
        LexicalExpression expression = tokens.get(name);
        if (expression == null) throw new IllegalArgumentException("E-WHITESPACE: undefined declarative whitespace " + name);
        if (expression.nullable()) throw new IllegalArgumentException("E-WHITESPACE: nullable whitespace " + name);
        return expression;
    }
}
