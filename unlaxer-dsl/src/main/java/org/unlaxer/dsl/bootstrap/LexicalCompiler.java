package org.unlaxer.dsl.bootstrap;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.runtime.LexicalExpression;
import org.unlaxer.dsl.runtime.LexicalExpression.Op;

/** Resolves lexical references and proves local-binding / progress invariants before generation. */
public final class LexicalCompiler {
    private LexicalCompiler() {}
    public static Map<String, LexicalExpression> compile(GrammarDecl grammar) {
        Map<String, LexicalExpression> definitions = new LinkedHashMap<>(), compiled = new LinkedHashMap<>();
        for (var token : grammar.tokens()) if (token instanceof TokenDecl.Declarative value) {
            if (definitions.putIfAbsent(value.name(), value.expression()) != null) fail("duplicate token " + value.name());
        }
        if (definitions.isEmpty()) return compiled;
        if (grammar.settings().stream().noneMatch(s -> s.key().equals("ubnf")
                && s.value() instanceof StringSettingValue v && v.value().equals("v2"))) fail("declarative tokens require @ubnf: v2");
        for (String name : definitions.keySet()) {
            var expression = expand(LexicalExpression.leaf(Op.REF, name), definitions, new HashSet<>(), 0, new int[]{0});
            validate(expression, new HashSet<>());
            compiled.put(name, expression);
        }
        return compiled;
    }
    private static void fail(String message) { throw new IllegalArgumentException("E-LEXICAL: " + message); }
    private static LexicalExpression expand(LexicalExpression expression, Map<String, LexicalExpression> definitions,
            Set<String> visiting, int depth, int[] nodes) {
        if (depth > 128) fail("lexical expansion exceeds 128");
        if (++nodes[0] > 4096) fail("lexical expansion exceeds 4096 nodes");
        if (expression.op() == Op.REF) {
            var target = definitions.get(expression.text());
            if (target == null) fail("undefined declarative token " + expression.text());
            if (!visiting.add(expression.text())) fail("cyclic lexical reference " + expression.text());
            var result = LexicalExpression.node(Op.SCOPE, expand(target, definitions, visiting, depth + 1, nodes));
            visiting.remove(expression.text());
            return result;
        }
        return new LexicalExpression(expression.op(), expression.text(), expression.min(), expression.max(),
            expression.children().stream().map(child -> expand(child, definitions, visiting, depth + 1, nodes)).toList());
    }
    private static void validate(LexicalExpression expression, Set<String> bound) {
        switch (expression.op()) {
            case BACKREF -> { if (!bound.contains(expression.text())) fail("unbound SAME_AS(" + expression.text() + ")"); }
            case SCOPE -> validate(expression.children().get(0), new HashSet<>());
            case LOOK, NOT -> validate(expression.children().get(0), new HashSet<>(bound));
            case CAPTURE -> { validate(expression.children().get(0), bound); bound.add(expression.text()); }
            case CHOICE -> {
                Set<String> definite = null;
                for (var child : expression.children()) {
                    Set<String> branch = new HashSet<>(bound); validate(child, branch);
                    if (definite == null) definite = branch; else definite.retainAll(branch);
                }
                if (definite != null) bound.addAll(definite);
            }
            case REPEAT -> {
                var child = expression.children().get(0);
                if (expression.max() < 0 && child.nullable()) fail("nullable unbounded lexical repeat");
                Set<String> iteration = new HashSet<>(bound); validate(child, iteration);
                if (expression.min() > 0) bound.addAll(iteration);
            }
            default -> { for (var child : expression.children()) validate(child, bound); }
        }
    }
    public static LexicalExpression prefix(LexicalExpression expression, String alias) {
        return new LexicalExpression(expression.op(), expression.op() == Op.REF ? alias + "." + expression.text() : expression.text(),
            expression.min(), expression.max(), expression.children().stream().map(c -> prefix(c, alias)).toList());
    }
    public static String javaExpression(LexicalExpression expression) {
        return "new org.unlaxer.dsl.runtime.LexicalExpression(org.unlaxer.dsl.runtime.LexicalExpression.Op."
            + expression.op() + ", " + javaString(expression.text()) + ", " + expression.min() + ", " + expression.max()
            + ", java.util.List.of(" + expression.children().stream().map(LexicalCompiler::javaExpression)
                .collect(Collectors.joining(", ")) + "))";
    }
    private static String javaString(String value) {
        StringBuilder result = new StringBuilder("\"");
        value.chars().forEach(c -> {
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\r' -> result.append("\\r");
                case '\n' -> result.append("\\n");
                case '\t' -> result.append("\\t");
                default -> { if (c < 32) result.append(String.format("\\%03o", c)); else result.append((char)c); }
            }
        });
        return result.append('"').toString();
    }
}
