package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.unlaxer.dsl.bootstrap.LexicalCompiler;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.runtime.LexicalExpression;
import org.unlaxer.dsl.runtime.Lexing.Terminal;

/** Explicit lexical goals, resolved after lexical module imports have been flattened. */
public final class LexicalContexts {
    private LexicalContexts() {}
    public static boolean enabled(GrammarDecl grammar) {
        return grammar.rules().stream().flatMap(rule -> rule.annotations().stream()).anyMatch(LexicalContextAnnotation.class::isInstance);
    }
    public record Problem(String code, String subject, Object node) {}
    /** Fail-first structural diagnostic with the owning annotation's original source span. */
    public static List<Problem> problems(GrammarDecl grammar) {
        try { requireValid(grammar); return List.of(); }
        catch (IllegalArgumentException error) {
            String message=error.getMessage();
            if (message == null || !message.startsWith("E-LEXICAL-CONTEXT-")) throw error;
            int separator=message.indexOf(": ");
            String code=message.substring(0,separator),subject=message.substring(separator+2);
            Object node=grammar;
            if (!code.equals("E-LEXICAL-CONTEXT-VERSION")) {
                for(RuleDecl rule:grammar.rules()) {
                    List<LexicalContextAnnotation> contexts=rule.annotations().stream().filter(LexicalContextAnnotation.class::isInstance).map(LexicalContextAnnotation.class::cast).toList();
                    if(contexts.isEmpty()) continue;
                    if(code.equals("E-LEXICAL-CONTEXT-DUPLICATE") && contexts.size()>1) {node=contexts.get(1);break;}
                    if(!code.equals("E-LEXICAL-CONTEXT-DUPLICATE")) {
                        try {terminals(grammar,contexts.get(0));}
                        catch(IllegalArgumentException invalid) {node=contexts.get(0);break;}
                    }
                }
                if(node==grammar) for(TokenDecl token:grammar.tokens()) if(!(token instanceof TokenDecl.Declarative) && !(token instanceof TokenDecl.Eof) && !(token instanceof TokenDecl.Empty)) {node=token;break;}
            }
            return List.of(new Problem(code,subject,node));
        }
    }
    public static void requireValid(GrammarDecl grammar) {
        if (!enabled(grammar)) return;
        if (grammar.settings().stream().noneMatch(setting -> setting.key().equals("ubnf") && setting.value() instanceof StringSettingValue value && value.value().equals("v2")))
            throw new IllegalArgumentException("E-LEXICAL-CONTEXT-VERSION: requires v2");
        for (RuleDecl rule : grammar.rules()) {
            List<LexicalContextAnnotation> annotations = rule.annotations().stream().filter(LexicalContextAnnotation.class::isInstance).map(LexicalContextAnnotation.class::cast).toList();
            if (annotations.size() > 1) throw new IllegalArgumentException("E-LEXICAL-CONTEXT-DUPLICATE: " + rule.name());
            if (!annotations.isEmpty()) terminals(grammar, annotations.get(0));
        }
        for (TokenDecl token : grammar.tokens()) {
            if (!(token instanceof TokenDecl.Declarative) && !(token instanceof TokenDecl.Eof) && !(token instanceof TokenDecl.Empty))
                throw new IllegalArgumentException("E-LEXICAL-CONTEXT-TOKEN: " + token.name());
        }
    }
    public static List<Terminal> terminals(GrammarDecl grammar, LexicalContextAnnotation context) {
        Map<String,LexicalExpression> programs = LexicalCompiler.compile(grammar);
        if (context.tokens().size() + context.literals().size() > 256) throw new IllegalArgumentException("E-LEXICAL-CONTEXT-LIMIT: 256 terminals");
        List<Terminal> result = new ArrayList<>();
        HashSet<String> literals = new HashSet<>();
        for (String literal : context.literals()) {
            if (literal.isEmpty() || !literals.add(literal)) throw new IllegalArgumentException("E-LEXICAL-CONTEXT-LITERAL: empty or duplicate literal");
            result.add(new Terminal(literal, true, LexicalExpression.leaf(LexicalExpression.Op.LITERAL, literal)));
        }
        HashSet<String> names = new HashSet<>();
        for (String name : context.tokens()) {
            LexicalExpression expression = programs.get(name);
            if (!names.add(name) || expression == null || expression.nullable()) throw new IllegalArgumentException("E-LEXICAL-CONTEXT-TOKEN: " + name);
        }
        // Grammar declaration order is stable across selector order and both generators.
        for (TokenDecl token : grammar.tokens()) if (names.contains(token.name())) result.add(new Terminal(token.name(), false, programs.get(token.name())));
        return List.copyOf(result);
    }
    public static String javaTerminals(GrammarDecl grammar, LexicalContextAnnotation context) {
        return "java.util.List.of(" + terminals(grammar, context).stream().map(terminal ->
            "new org.unlaxer.dsl.runtime.Lexing.Terminal(\"" + ParserCodegenUtil.escapeString(terminal.name()) + "\", " + terminal.literal() + ", " + LexicalCompiler.javaExpression(terminal.expression()) + ")").reduce((left,right) -> left + ", " + right).orElse("") + ")";
    }
}
