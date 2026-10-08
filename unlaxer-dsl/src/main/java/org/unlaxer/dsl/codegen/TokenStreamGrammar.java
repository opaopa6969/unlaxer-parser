package org.unlaxer.dsl.codegen;

import java.util.*;
import org.unlaxer.dsl.bootstrap.LexicalCompiler;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.runtime.LexicalExpression;
import org.unlaxer.dsl.runtime.Lexing.Terminal;

/** Shared opt-in profile; unsupported input never silently changes lexing mode. */
public final class TokenStreamGrammar {
    private TokenStreamGrammar() {}
    public record Problem(String code, String subject, Object node) {}
    public static boolean enabled(GrammarDecl grammar) {
        return grammar.settings().stream().anyMatch(s -> s.key().equals("tokenStream"));
    }
    public static boolean whitespace(GrammarDecl grammar) {
        return grammar.settings().stream().anyMatch(s -> s.key().equals("whitespace")
            && s.value() instanceof StringSettingValue v && v.value().trim().equalsIgnoreCase("javaStyle"));
    }
    public static List<Problem> problems(GrammarDecl grammar) {
        if (!enabled(grammar)) return List.of();
        var issues = new ArrayList<Problem>();
        var settings = grammar.settings().stream().filter(s -> s.key().equals("tokenStream")).toList();
        for (int i = 0; i < settings.size(); i++) {
            var s = settings.get(i);
            if (i > 0 || !(s.value() instanceof StringSettingValue v) || !v.value().equals("enabled"))
                issues.add(new Problem("E-TOKEN-STREAM-SETTING", "tokenStream", s));
        }
        if (grammar.settings().stream().noneMatch(s -> s.key().equals("ubnf")
                && s.value() instanceof StringSettingValue v && v.value().equals("v2")))
            issues.add(new Problem("E-TOKEN-STREAM-VERSION", "tokenStream", settings.get(0)));
        for (var s : grammar.settings()) if (s.key().equals("comment") || s.key().equals("whitespace")
            && s.value() instanceof StringSettingValue value && !value.value().equalsIgnoreCase("javaStyle") && !value.value().equalsIgnoreCase("none"))
            issues.add(new Problem("E-TOKEN-STREAM-TRIVIA", s.key(), s));
        Map<String, LexicalExpression> programs;
        try { programs = LexicalCompiler.compile(grammar); }
        catch (IllegalArgumentException error) { return issues; } // the lexical validator reports the underlying error
        var references = new HashSet<String>();
        for (var rule : grammar.rules()) visit(rule.body(), new LinkedHashSet<>(), references);
        for (var token : grammar.tokens()) {
            if (token instanceof TokenDecl.Eof || token instanceof TokenDecl.Empty) continue;
            if (!(token instanceof TokenDecl.Declarative) || references.contains(token.name()) && programs.get(token.name()).nullable())
                issues.add(new Problem("E-TOKEN-STREAM-TOKEN", token.name(), token));
        }
        for (var rule : grammar.rules()) for (var annotation : rule.annotations()) {
            String name = annotation instanceof WhitespaceAnnotation ? "whitespace"
                : annotation instanceof InterleaveAnnotation ? "interleave"
                : annotation instanceof BackrefAnnotation ? "backref"
                : annotation instanceof RecoveryAnnotation ? "recovery" : null;
            if (name != null) issues.add(new Problem("E-TOKEN-STREAM-ANNOTATION", name, annotation));
        }
        return List.copyOf(issues);
    }
    public static void requireValid(GrammarDecl grammar) {
        var problems = problems(grammar);
        if (!problems.isEmpty()) throw new IllegalArgumentException(problems.get(0).code() + ": " + problems.get(0).subject());
    }
    public static List<Terminal> terminals(GrammarDecl grammar) {
        requireValid(grammar);
        var literals = new LinkedHashSet<String>(); var references = new HashSet<String>();
        for (var rule : grammar.rules()) visit(rule.body(), literals, references);
        var terminals = new ArrayList<Terminal>();
        for (String literal : literals) if (!literal.isEmpty())
            terminals.add(new Terminal(literal, true, LexicalExpression.leaf(LexicalExpression.Op.LITERAL, literal)));
        var programs = LexicalCompiler.compile(grammar);
        for (var token : grammar.tokens()) if (references.contains(token.name()) && token instanceof TokenDecl.Declarative)
            terminals.add(new Terminal(token.name(), false, programs.get(token.name())));
        return List.copyOf(terminals);
    }
    private static void visit(RuleBody body, Set<String> literals, Set<String> references) {
        if (body instanceof ChoiceBody c) c.alternatives().forEach(s -> visit(s, literals, references));
        else if (body instanceof SequenceBody s) s.elements().forEach(e -> visit(e.element(), literals, references));
    }
    private static void visit(AtomicElement e, Set<String> literals, Set<String> references) {
        if (e instanceof TerminalElement t) literals.add(t.value());
        else if (e instanceof RuleRefElement r) references.add(r.name());
        else if (e instanceof GroupElement g) visit(g.body(), literals, references);
        else if (e instanceof OptionalElement o) visit(o.body(), literals, references);
        else if (e instanceof RepeatElement r) visit(r.body(), literals, references);
        else if (e instanceof OneOrMoreElement o) visit(o.body(), literals, references);
        else if (e instanceof BoundedRepeatElement b) visit(b.body(), literals, references);
        else if (e instanceof SeparatedElement s) { visit(s.element(), literals, references); visit(s.separator(), literals, references); }
    }
    static String javaApi(GrammarDecl grammar) {
        if (!enabled(grammar)) return "";
        var out = new StringBuilder("    private static final java.util.List<org.unlaxer.dsl.runtime.Lexing.Terminal> __LEXICAL_TERMINALS = java.util.List.of(\n");
        var rows = new ArrayList<String>();
        for (var t : terminals(grammar)) rows.add("        new org.unlaxer.dsl.runtime.Lexing.Terminal(\""
            + ParserCodegenUtil.escapeString(t.name()) + "\", " + t.literal() + ", " + LexicalCompiler.javaExpression(t.expression()) + ")");
        out.append(String.join(",\n", rows)).append("\n    );\n\n")
            .append("    public static org.unlaxer.dsl.runtime.Lexing.Outcome parseWithLexing(String source, org.unlaxer.dsl.runtime.Lexing.Options options) {\n")
            .append("        return org.unlaxer.dsl.runtime.Lexing.parse(getRootParser(), source, options, __LEXICAL_TERMINALS, ")
            .append(whitespace(grammar)).append(");\n    }\n\n");
        return out.toString();
    }
}
