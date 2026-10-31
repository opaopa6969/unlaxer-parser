package org.unlaxer.dsl.codegen;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import org.unlaxer.context.SemanticPredicateBindings;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Named rule postconditions; generation never evaluates application code. */
public final class SemanticPredicates {
    private SemanticPredicates() {}
    public static Optional<PredicateAnnotation> annotation(RuleDecl rule) {
        return rule.annotations().stream().filter(PredicateAnnotation.class::isInstance)
            .map(PredicateAnnotation.class::cast).findFirst();
    }
    public static boolean enabled(GrammarDecl grammar) {
        return grammar.rules().stream().anyMatch(rule -> annotation(rule).isPresent());
    }
    public static List<String> requirements(GrammarDecl grammar) {
        var names = new LinkedHashSet<String>();
        for (var rule : grammar.rules()) annotation(rule).ifPresent(value -> names.add(value.name()));
        return List.copyOf(names);
    }
    /**
     * Version 1 accepts {@code @root}, {@code @mapping} and {@code @doc} beside {@code @predicate}.
     * Other rule annotations, name predicates, token-stream and embedding grammars are refused
     * with the same code by both frontends instead of being silently ignored. Callers check
     * lexical contexts and name predicates first, so their own codes win.
     */
    public static void requireValid(GrammarDecl grammar) {
        if (!enabled(grammar)) return;
        for (var rule : grammar.rules()) {
            var annotations = rule.annotations().stream().filter(PredicateAnnotation.class::isInstance).toList();
            if (annotations.size() > 1) throw invalid("DUPLICATE", rule.name());
            if (annotations.isEmpty()) continue;
            try { SemanticPredicateBindings.requireName(((PredicateAnnotation) annotations.get(0)).name()); }
            catch (IllegalArgumentException badName) { throw invalid("NAME", rule.name()); }
            for (var other : rule.annotations()) {
                if (!(other instanceof PredicateAnnotation || other instanceof RootAnnotation
                        || other instanceof MappingAnnotation || other instanceof DocAnnotation)) {
                    throw invalid("COMBINATION", rule.name() + " " + annotationName(other));
                }
            }
        }
        if (NamePredicates.enabled(grammar)) throw invalid("COMBINATION", "@namePredicate");
        if (TokenStreamGrammar.enabled(grammar) || LexicalContexts.enabled(grammar))
            throw invalid("COMBINATION", "token-stream grammar");
        // Embedded entries resolve rule classes without the registry; version 1 refuses them.
        if (EmbeddedGrammarEmitter.enabled(grammar)) throw invalid("COMBINATION", "embedding grammar");
        if (requirements(grammar).size() > 64) throw invalid("LIMIT", "at most 64 predicates");
    }
    private static String annotationName(Annotation annotation) {
        if (annotation instanceof SimpleAnnotation simple) return "@" + simple.name();
        String name = annotation.getClass().getSimpleName().replaceFirst("Annotation$", "");
        return "@" + Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
    private static IllegalArgumentException invalid(String code, String subject) {
        return new IllegalArgumentException("E-PREDICATE-" + code + ": " + subject);
    }
    /**
     * Generated wrapper class by predicate rule, computed from the grammar alone. The default is
     * {@code RulePredicateParser}; a name already taken by a rule class, a token class or another
     * rule wrapper gets the first free {@code _1}, {@code _2}... suffix, in rule order. Body helper
     * classes end with a digit or {@code Body} before {@code Parser}, so they never take one.
     */
    static java.util.Map<String, String> wrapperClassNames(GrammarDecl grammar) {
        var occupied = new java.util.HashSet<String>();
        for (var rule : grammar.rules()) {
            occupied.add(rule.name() + "Parser");
            if (ParserRuleEmitter.findRecoveryAnnotation(rule).isPresent()) occupied.add(rule.name() + "RecoveryParser");
            if (NamePredicates.annotation(rule).isPresent()) occupied.add(rule.name() + "NamePredicateParser");
        }
        for (var token : grammar.tokens()) occupied.add(ParserCodegenUtil.toParserClassName(token.name()));
        var names = new java.util.LinkedHashMap<String, String>();
        for (var rule : grammar.rules()) {
            if (annotation(rule).isEmpty()) continue;
            String name = rule.name() + "PredicateParser";
            for (int suffix = 1; occupied.contains(name); suffix++) name = rule.name() + "PredicateParser_" + suffix;
            occupied.add(name);
            names.put(rule.name(), name);
        }
        return java.util.Map.copyOf(names);
    }
    static String wrapper(ParserGenerator.GenContext context, RuleDecl rule) {
        String className = context.semanticPredicateWrappers.get(rule.name());
        return """
                public static class %s extends org.unlaxer.parser.combinator.SemanticPredicateParser {
                    private static final long serialVersionUID = 1L;
                    public %s() {
                        super(Parser.get(%sParser.class), "%s");
                    }
                }

            """.formatted(className, className, rule.name(),
                ParserCodegenUtil.escapeString(annotation(rule).orElseThrow().name()));
    }
    static String javaRequirements(GrammarDecl grammar) {
        return "java.util.List.of(" + requirements(grammar).stream()
            .map(name -> "\"" + ParserCodegenUtil.escapeString(name) + "\"")
            .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }
}
