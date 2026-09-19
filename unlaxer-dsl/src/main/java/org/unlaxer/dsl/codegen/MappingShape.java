package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.EnumAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SkipAnnotation;

/** Shared AST/mapper distinction between concrete zero-field nodes and mapped sums. */
final class MappingShape {
    private MappingShape() {}

    /** Mapped types emitted by ASTGenerator; enum rules are separate non-AST values. */
    static Map<String, RuleDecl> astMappings(GrammarDecl grammar) {
        Map<String, RuleDecl> mappings = new LinkedHashMap<>();
        for (RuleDecl rule : grammar.rules()) {
            if (rule.annotations().stream().anyMatch(a -> a instanceof SkipAnnotation || a instanceof EnumAnnotation)) continue;
            MapperElementUtil.getMappingAnnotation(rule).ifPresent(m -> mappings.putIfAbsent(m.className(), rule));
        }
        return mappings;
    }

    /** Explicit mapped aliases/sums plus the implicit owners of dotted records. */
    static Set<String> sumTypeNames(GrammarDecl grammar) {
        Set<String> names = new LinkedHashSet<>();
        for (var entry : astMappings(grammar).entrySet()) {
            String name = entry.getKey();
            if (name.contains(".")) names.add(name.substring(0, name.indexOf('.')));
            else if (!sumVariants(grammar, entry.getValue(),
                    MapperElementUtil.getMappingAnnotation(entry.getValue()).orElseThrow()).isEmpty()) names.add(name);
        }
        return names;
    }

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
