package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SkipAnnotation;

/** Shared AST/mapper distinction between concrete zero-field nodes and mapped sums. */
final class MappingShape {
    private MappingShape() {}

    static List<RuleDecl> sumVariants(GrammarDecl grammar, RuleDecl rule, MappingAnnotation mapping) {
        if (!mapping.paramNames().isEmpty() || mapping.className().contains(".")
                || !(rule.body() instanceof ChoiceBody choice) || choice.alternatives().isEmpty()) {
            return List.of();
        }
        var variants = new ArrayList<RuleDecl>();
        for (var alternative : choice.alternatives()) {
            if (alternative.elements().size() != 1
                    || !(alternative.elements().get(0).element() instanceof RuleRefElement ref)) {
                return List.of();
            }
            RuleDecl target = grammar.rules().stream().filter(candidate -> candidate.name().equals(ref.name()))
                .findFirst().orElse(null);
            if (target == null || target.annotations().stream().anyMatch(SkipAnnotation.class::isInstance)) {
                return List.of(); // Token declarations and unmapped syntax are not AST variants.
            }
            var targetMapping = MapperElementUtil.getMappingAnnotation(target).orElse(null);
            if (targetMapping == null || targetMapping.className().equals(mapping.className())) {
                return List.of();
            }
            String name = targetMapping.className();
            if (name.contains(".") && !name.startsWith(mapping.className() + ".")) {
                return List.of(); // A record owned by another nested sum cannot be its direct subtype.
            }
            if (!variants.contains(target)) variants.add(target);
        }
        return List.copyOf(variants);
    }
}
