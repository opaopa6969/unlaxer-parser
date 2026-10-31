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
     * Other rule annotations, name predicates and token-stream grammars are refused with the
     * same code by both frontends instead of being silently ignored.
     */
    public static void requireValid(GrammarDecl grammar) {
        if (!enabled(grammar)) return;
        for (var rule : grammar.rules()) {
            var annotations = rule.annotations().stream().filter(PredicateAnnotation.class::isInstance).toList();
            if (annotations.size() > 1) throw invalid("DUPLICATE", rule.name());
            if (annotations.isEmpty()) continue;
            try { SemanticPredicateBindings.requireName(((PredicateAnnotation) annotations.get(0)).name()); }
            catch (IllegalArgumentException badName) { throw invalid("NAME", rule.name()); }
            String wrapper = rule.name() + "Predicate";
            if (grammar.rules().stream().anyMatch(candidate -> candidate.name().equals(wrapper))) {
                throw invalid("COMBINATION", rule.name() + " generated class " + wrapper + "Parser");
            }
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
    static String wrapper(ParserGenerator.GenContext context, RuleDecl rule) {
        return """
                public static class %sPredicateParser extends org.unlaxer.parser.combinator.SemanticPredicateParser {
                    private static final long serialVersionUID = 1L;
                    public %sPredicateParser() {
                        super(Parser.get(%sParser.class), "%s");
                    }
                }

            """.formatted(rule.name(), rule.name(), rule.name(),
                ParserCodegenUtil.escapeString(annotation(rule).orElseThrow().name()));
    }
    static String javaRequirements(GrammarDecl grammar) {
        return "java.util.List.of(" + requirements(grammar).stream()
            .map(name -> "\"" + ParserCodegenUtil.escapeString(name) + "\"")
            .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }
}
