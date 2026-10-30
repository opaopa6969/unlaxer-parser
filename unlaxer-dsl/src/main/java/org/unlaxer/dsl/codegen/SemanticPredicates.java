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
    public static void requireValid(GrammarDecl grammar) {
        if (!enabled(grammar)) return;
        for (var rule : grammar.rules()) {
            var annotations = rule.annotations().stream().filter(PredicateAnnotation.class::isInstance).toList();
            if (annotations.size() > 1) throw invalid("DUPLICATE", rule.name());
            if (annotations.isEmpty()) continue;
            try { SemanticPredicateBindings.requireName(((PredicateAnnotation) annotations.get(0)).name()); }
            catch (IllegalArgumentException badName) { throw invalid("NAME", rule.name()); }
        }
        if (requirements(grammar).size() > 64) throw invalid("LIMIT", "at most 64 predicates");
    }
    private static IllegalArgumentException invalid(String code, String subject) {
        return new IllegalArgumentException("E-PREDICATE-" + code + ": " + subject);
    }
    static String wrapper(ParserGenerator.GenContext context, RuleDecl rule) {
        String child = NamePredicates.annotation(rule).isPresent() ? rule.name() + "NamePredicateParser"
            : context.recoveryRules.containsKey(rule.name()) ? rule.name() + "RecoveryParser"
            : rule.name() + "Parser";
        return """
                public static class %sPredicateParser extends org.unlaxer.parser.combinator.SemanticPredicateParser {
                    private static final long serialVersionUID = 1L;
                    public %sPredicateParser() {
                        super(Parser.get(%s.class), "%s");
                    }
                }

            """.formatted(rule.name(), rule.name(), child,
                ParserCodegenUtil.escapeString(annotation(rule).orElseThrow().name()));
    }
    static String javaRequirements(GrammarDecl grammar) {
        return "java.util.List.of(" + requirements(grammar).stream()
            .map(name -> "\"" + ParserCodegenUtil.escapeString(name) + "\"")
            .collect(java.util.stream.Collectors.joining(", ")) + ")";
    }
}
