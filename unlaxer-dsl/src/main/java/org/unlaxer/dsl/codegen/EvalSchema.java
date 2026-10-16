package org.unlaxer.dsl.codegen;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Evaluation belongs to the emitted mapping type, including all rules that share it. */
public final class EvalSchema {
    private EvalSchema() {}
    public static boolean automatic(EvalAnnotation value) {
        return value != null && !value.strategy().equals("manual")
            && List.of("literal", "passthrough", "conditional", "binary_arithmetic", "variable_ref").contains(value.kind());
    }
    public static Map<String, EvalAnnotation> specifications(GrammarDecl grammar) {
        var result = new LinkedHashMap<String, EvalAnnotation>();
        for (var rule : grammar.rules()) {
            var annotations = rule.annotations().stream().filter(EvalAnnotation.class::isInstance)
                .map(EvalAnnotation.class::cast).toList();
            if (annotations.isEmpty()) continue;
            if (annotations.size() != 1) throw invalid(rule.name(), "duplicate annotation");
            var annotation = annotations.get(0);
            var mapping = rule.annotations().stream().filter(MappingAnnotation.class::isInstance)
                .map(MappingAnnotation.class::cast).findFirst().orElseThrow(() -> invalid(rule.name(), "requires @mapping"));
            if (rule.annotations().stream().anyMatch(a -> a instanceof SkipAnnotation || a instanceof EnumAnnotation))
                throw invalid(rule.name(), "requires an emitted concrete mapping");
            var previous = result.putIfAbsent(mapping.className(), annotation);
            if (previous != null && !previous.equals(annotation))
                throw invalid(rule.name(), "conflicting annotations on shared mapping " + mapping.className());
            if (!automatic(annotation)) continue;
            List<String> required = switch (annotation.kind()) {
                case "literal", "passthrough" -> List.of("value");
                case "conditional" -> List.of("condition", "thenExpr", "elseExpr");
                case "binary_arithmetic" -> List.of("left", "op", "right");
                case "variable_ref" -> List.of("name");
                default -> throw new AssertionError();
            };
            if (!mapping.paramNames().containsAll(required))
                throw invalid(rule.name(), "missing fields " + required);
            var types = new ASTGenerator();
            for (String name : required) {
                String type = types.inferType(grammar, rule, name);
                boolean valid = switch (annotation.kind()) {
                    case "variable_ref" -> type.equals("String");
                    case "conditional" -> scalarAstField(grammar, mapping.className(), name);
                    case "binary_arithmetic" -> name.equals("op") ? type.equals("List<String>")
                        : name.equals("right") ? type.startsWith("List<")
                        : !type.startsWith("List<") && !type.startsWith("Optional<");
                    default -> true;
                };
                if (!valid) throw invalid(rule.name(), "invalid field " + name);
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    private static boolean scalarAstField(GrammarDecl grammar, String mappingName, String field) {
        var semantics = new SemanticCardinality(grammar);
        for (var contributor : grammar.rules()) {
            if (MapperElementUtil.isSkipped(contributor)
                    || MapperElementUtil.getMappingAnnotation(contributor)
                        .filter(mapping -> mapping.className().equals(mappingName)).isEmpty()) continue;
            var shape = semantics.captureShape(contributor, field);
            if (shape == null || shape.count() != SemanticCardinality.Count.ONE) return false;
            var sites = new CaptureBindingPlan(contributor).sites(field);
            if (sites.isEmpty() || sites.stream().anyMatch(site -> !EnumSchema.onlyAstValues(grammar, site.element())))
                return false;
        }
        return true;
    }
    private static IllegalArgumentException invalid(String rule, String message) {
        return new IllegalArgumentException("E-EVAL: " + message + " on " + rule);
    }
}
