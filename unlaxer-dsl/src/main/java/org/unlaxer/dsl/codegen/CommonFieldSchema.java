package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.*;

import java.util.*;

/** Common family membership and exact field contracts, shared by validation and emitters. */
public final class CommonFieldSchema {
    private CommonFieldSchema() {}

    public record Field(String name, String javaType) {}

    public record Family(String name, String rule, List<String> variants, List<Field> fields) {
        public Family {
            variants = List.copyOf(variants);
            fields = List.copyOf(fields);
        }
    }

    public static List<Family> build(GrammarDecl grammar) {
        var result = new ArrayList<Family>();
        var normalized = new HashMap<String, String>();
        for (var candidate : grammar.rules()) {
            var m = MapperElementUtil.getMappingAnnotation(candidate).orElse(null);
            if (m != null) {
                if (m.className().split("\\.", -1).length > 2)
                    throw invalid(candidate, "nested dotted mapping requires #547");
                String previous = normalized.putIfAbsent(rustName(m.className()), m.className());
                if (previous != null && !previous.equals(m.className()))
                    throw invalid(candidate, "normalized mapping name collision");
            }
        }
        // Validate transparent families even when no common accessor is requested.
        for (var rule : grammar.rules())
            if (isSum(grammar, rule)) collect(grammar, rule, new HashSet<>(), new LinkedHashMap<>());
        var names = new HashSet<String>();
        for (var rule : grammar.rules()) {
            var requested =
                    rule.annotations().stream()
                            .filter(CommonFieldAnnotation.class::isInstance)
                            .map(CommonFieldAnnotation.class::cast)
                            .flatMap(a -> a.fieldNames().stream())
                            .toList();
            if (requested.isEmpty()) continue;
            var mapping = MapperElementUtil.getMappingAnnotation(rule).orElse(null);
            if (rule.annotations().stream().anyMatch(SkipAnnotation.class::isInstance)
                    || mapping == null
                    || !mapping.paramNames().isEmpty()
                    || MappingShape.sumVariants(grammar, rule, mapping).isEmpty())
                throw invalid(rule, "requires a mapped sum family");
            if (!names.add(mapping.className()))
                throw invalid(rule, "duplicate family declaration");
            String view = rustName(mapping.className()) + "Fields";
            if (normalized.containsKey(view)
                    || grammar.rules().stream()
                            .anyMatch(r -> EnumSchema.isEnum(r) && r.name().equals(view)))
                throw invalid(rule, "view name collision");
            var leaves = new LinkedHashMap<String, RuleDecl>();
            collect(grammar, rule, new HashSet<>(), leaves);
            // Dotted siblings implement their implicit owner even when omitted from the choice.
            for (var sibling : grammar.rules()) {
                if (sibling.annotations().stream()
                        .anyMatch(a -> a instanceof SkipAnnotation || a instanceof EnumAnnotation))
                    continue;
                var sm = MapperElementUtil.getMappingAnnotation(sibling).orElse(null);
                if (sm != null && sm.className().startsWith(mapping.className() + "."))
                    leaves.put(sm.className(), sibling);
            }
            var fields = new ArrayList<Field>();
            var seen = new HashSet<String>();
            for (String name : requested) {
                if (!seen.add(name)
                        || Set.of(
                                        "span",
                                        "semantics",
                                        "getClass",
                                        "hashCode",
                                        "toString",
                                        "clone",
                                        "finalize",
                                        "notify",
                                        "notifyAll",
                                        "wait")
                                .contains(name))
                    throw invalid(rule, "duplicate or reserved field " + name);
                String type = null;
                for (var leaf : leaves.values()) {
                    var lm = MapperElementUtil.getMappingAnnotation(leaf).orElseThrow();
                    if (!lm.paramNames().contains(name))
                        throw invalid(rule, "missing field " + name + " on " + lm.className());
                    // Object is the established mixed-value contract, not evidence of a missing
                    // capture.
                    for (var contributor : grammar.rules()) {
                        var cm = MapperElementUtil.getMappingAnnotation(contributor).orElse(null);
                        if (cm != null
                                && cm.className().equals(lm.className())
                                && contributor.annotations().stream()
                                        .noneMatch(SkipAnnotation.class::isInstance)
                                && (!cm.paramNames().contains(name)
                                        || MapperElementUtil.findCapturedElement(
                                                        contributor.body(), name)
                                                .isEmpty()))
                            throw invalid(
                                    rule, "missing capture " + name + " on " + contributor.name());
                    }
                    String actual = new ASTGenerator().inferType(grammar, leaf, name);
                    if (type != null && !type.equals(actual))
                        throw invalid(rule, "incompatible field " + name + " on " + lm.className());
                    type = actual;
                }
                fields.add(new Field(name, type));
            }
            result.add(
                    new Family(
                            mapping.className(),
                            rule.name(),
                            new ArrayList<>(leaves.keySet()),
                            fields));
        }
        return List.copyOf(result);
    }

    private static void collect(
            GrammarDecl grammar,
            RuleDecl rule,
            Set<String> visiting,
            Map<String, RuleDecl> leaves) {
        if (visiting.size() >= 256 || !visiting.add(rule.name()))
            throw invalid(rule, "cyclic or over-deep sum family");
        var mapping = MapperElementUtil.getMappingAnnotation(rule).orElseThrow();
        var variants = MappingShape.sumVariants(grammar, rule, mapping);
        if (variants.isEmpty()) leaves.put(mapping.className(), rule);
        else for (var variant : variants) collect(grammar, variant, visiting, leaves);
        visiting.remove(rule.name());
    }

    public static boolean enabled(GrammarDecl grammar) {
        return grammar.rules().stream()
                .anyMatch(
                        r ->
                                r.annotations().stream()
                                        .anyMatch(CommonFieldAnnotation.class::isInstance));
    }

    public static boolean isSum(GrammarDecl grammar, RuleDecl rule) {
        var mapping = MapperElementUtil.getMappingAnnotation(rule).orElse(null);
        return mapping != null && !MappingShape.sumVariants(grammar, rule, mapping).isEmpty();
    }

    public static String rustName(String name) {
        return name.replace(".", "__");
    }

    /**
     * Rule origin for the structured portability diagnostic; other failures keep their own code.
     */
    public static Optional<String> errorRule(Throwable error) {
        return error instanceof SchemaException schema
                ? Optional.of(schema.rule)
                : Optional.empty();
    }

    static final class SchemaException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        final String rule;

        SchemaException(String rule, String detail) {
            super("E-COMMONFIELD-MISMATCH: " + rule + ": " + detail);
            this.rule = rule;
        }
    }

    private static SchemaException invalid(RuleDecl rule, String detail) {
        return new SchemaException(rule.name(), detail);
    }
}
