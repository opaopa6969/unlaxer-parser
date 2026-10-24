package org.unlaxer.dsl.codegen;

import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Rust integer refinement, including the effective schema of shared mappings. */
public final class NumericFields {
    private NumericFields() {}

    public static boolean isInteger(GrammarDecl grammar, RuleDecl rule, String name) {
        // Lowering has already computed kind/cardinality. Integer refinement only needs the
        // captures, exactly as numeric_fields.rs does. Java AST inference would also solve
        // unrelated recursive shapes for every field and can exhaust its work limit (#758).
        return numericCaptures(grammar, rule, name);
    }

    /** Port of numeric_fields.rs refine: every contributor of the mapping captures only numbers. */
    static boolean numericCaptures(GrammarDecl grammar, RuleDecl rule, String name) {
        String mapping = mapping(rule);
        if (mapping == null) return false;
        for (RuleDecl contributor : grammar.rules()) {
            if (!mapping.equals(mapping(contributor))
                    || contributor.annotations().stream().anyMatch(SkipAnnotation.class::isInstance)) continue;
            var captures = new java.util.ArrayList<Boolean>();
            collect(grammar, contributor.body(), name, captures);
            if (captures.isEmpty() || captures.contains(false)) return false;
        }
        return true;
    }

    private static String mapping(RuleDecl rule) {
        return rule.annotations().stream().filter(MappingAnnotation.class::isInstance)
                .map(annotation -> ((MappingAnnotation) annotation).className()).findFirst().orElse(null);
    }

    private static List<SequenceBody> alternatives(RuleBody body) {
        return body instanceof ChoiceBody choice ? choice.alternatives() : List.of((SequenceBody) body);
    }

    private static boolean numericBody(GrammarDecl grammar, RuleBody body) {
        var alternatives = alternatives(body);
        return alternatives.size() == 1 && alternatives.get(0).elements().size() == 1
                && scalar(grammar, alternatives.get(0).elements().get(0).element());
    }

    private static boolean scalar(GrammarDecl grammar, AtomicElement element) {
        if (!(element instanceof RuleRefElement ref)
                || grammar.rules().stream().anyMatch(rule -> rule.name().equals(ref.name()))) return false;
        return grammar.tokens().stream().anyMatch(token -> token.name().equals(ref.name())
                && token instanceof TokenDecl.Simple simple
                && List.of("NumberParser", "DigitParser").contains(
                        simple.parserClass().substring(simple.parserClass().lastIndexOf('.') + 1)));
    }

    private static boolean numeric(GrammarDecl grammar, AtomicElement element) {
        if (element instanceof RuleRefElement) return scalar(grammar, element);
        if (element instanceof OptionalElement optional) return numericBody(grammar, optional.body());
        if (element instanceof RepeatElement repeat) return numericBody(grammar, repeat.body());
        if (element instanceof OneOrMoreElement repeat) return scalar(grammar, repeat.body());
        if (element instanceof BoundedRepeatElement repeat) return scalar(grammar, repeat.body());
        if (element instanceof SeparatedElement separated) return scalar(grammar, separated.element());
        return false;
    }

    private static void collect(GrammarDecl grammar, RuleBody body, String name, List<Boolean> values) {
        for (var sequence : alternatives(body)) {
            for (var child : sequence.elements()) {
                if (child.captureName().filter(name::equals).isPresent()) values.add(numeric(grammar, child.element()));
                nested(grammar, child.element(), name, values);
            }
        }
    }

    private static void nested(GrammarDecl grammar, AtomicElement element, String name, List<Boolean> values) {
        if (element instanceof TransparentElement transparent) collect(grammar, transparent.body(), name, values);
        else if (element instanceof OptionalElement optional) collect(grammar, optional.body(), name, values);
        else if (element instanceof RepeatElement repeat) collect(grammar, repeat.body(), name, values);
        else if (element instanceof OneOrMoreElement repeat) nested(grammar, repeat.body(), name, values);
        else if (element instanceof BoundedRepeatElement repeat) nested(grammar, repeat.body(), name, values);
        else if (element instanceof SeparatedElement separated) nested(grammar, separated.element(), name, values);
    }
}
