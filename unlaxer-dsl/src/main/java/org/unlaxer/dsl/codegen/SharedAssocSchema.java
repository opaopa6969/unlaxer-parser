package org.unlaxer.dsl.codegen;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** One constructor contract for shared associative records and heterogeneous recursive operands. */
record SharedAssocSchema(String leftType, String rightType, boolean spine, boolean heterogeneous) {
    static Optional<SharedAssocSchema> resolve(GrammarDecl grammar, RuleDecl rule) {
        MappingAnnotation mapping = MapperElementUtil.getMappingAnnotation(rule).orElse(null);
        if (mapping == null) return Optional.empty();
        List<RuleDecl> family = grammar.rules().stream()
            .filter(candidate -> candidate.annotations().stream().noneMatch(a -> a instanceof SkipAnnotation))
            .filter(candidate -> MapperElementUtil.getMappingAnnotation(candidate)
                .map(other -> other.className().equals(mapping.className())).orElse(false)).toList();
        if (!isAssoc(rule, mapping)) {
            if (family.stream().anyMatch(candidate -> isAssoc(candidate,
                    MapperElementUtil.getMappingAnnotation(candidate).orElseThrow()))) {
                throw invalid(mapping.className(), "associative and non-associative rules cannot share one schema");
            }
            return Optional.empty();
        }
        Map<String, RuleDecl> rules = new LinkedHashMap<>();
        grammar.rules().forEach(candidate -> rules.put(candidate.name(), candidate));
        Map<String, TokenDecl> tokens = new LinkedHashMap<>();
        grammar.tokens().forEach(token -> tokens.put(token.name(), token));
        if (family.size() < 2 && !transparentRecursiveOperands(rule, mapping.className(), rules, tokens)) {
            return Optional.empty();
        }
        Set<String> mappedOperands = new LinkedHashSet<>();
        Set<String> leftTypes = new LinkedHashSet<>();
        Set<String> rightTypes = new LinkedHashSet<>();
        boolean allDirectMappedOperands = true;
        for (RuleDecl candidate : family) {
            MappingAnnotation other = MapperElementUtil.getMappingAnnotation(candidate).orElseThrow();
            if (!isAssoc(candidate, other) || !other.paramNames().equals(List.of("left", "op", "right"))) {
                throw invalid(mapping.className(), "all rules must be associative with params=[left, op, right]");
            }
            var shape = MapperElementUtil.findAssocShape(candidate, "left", "op", "right")
                .orElseThrow(() -> invalid(mapping.className(), "missing operator shape on " + candidate.name()));
            String left = MapperTypeResolver.inferType(grammar, candidate, "left");
            String right = MapperTypeResolver.unwrapListType(MapperTypeResolver.inferType(grammar, candidate, "right"))
                .orElseThrow(() -> invalid(mapping.className(), "right must be repeated on " + candidate.name()));
            if (!"List<String>".equals(MapperTypeResolver.inferType(grammar, candidate, "op"))
                    || container(left) || container(right)) {
                throw invalid(mapping.className(), "scalar operands and repeated text operators are required on " + candidate.name());
            }
            leftTypes.add(left);
            rightTypes.add(right);
            for (AtomicElement operand : List.of(shape.leftElement(), shape.rightElement())) {
                collectMappedOperands(operand, rules, tokens, mappedOperands);
                if (!(operand instanceof RuleRefElement reference)
                        || !rules.containsKey(reference.name())
                        || MapperElementUtil.getMappingAnnotation(rules.get(reference.name())).isEmpty()) {
                    allDirectMappedOperands = false;
                }
            }
        }
        String ast = grammar.name() + "AST";
        if (mappedOperands.contains(mapping.className())) {
            boolean heterogeneous = mappedOperands.size() > 1;
            String type = heterogeneous ? ast : ast + "." + mapping.className();
            return Optional.of(new SharedAssocSchema(type, type, true, heterogeneous));
        }
        if (!mappedOperands.isEmpty()
                && (!allDirectMappedOperands || leftTypes.size() != 1 || rightTypes.size() != 1)) {
            return Optional.of(new SharedAssocSchema(ast, ast, true, true));
        }
        if (leftTypes.size() != 1 || rightTypes.size() != 1) {
            throw invalid(mapping.className(), "incompatible scalar operand types: left=" + leftTypes + ", right=" + rightTypes);
        }
        return Optional.of(new SharedAssocSchema(leftTypes.iterator().next(), rightTypes.iterator().next(), false, false));
    }

    String fieldType(String name) {
        return switch (name) {
            case "left" -> leftType;
            case "op" -> "List<String>";
            case "right" -> "List<" + MapperTypeResolver.boxedType(rightType) + ">";
            default -> throw new IllegalArgumentException("Unknown associative field: " + name);
        };
    }

    private static boolean isAssoc(RuleDecl rule, MappingAnnotation mapping) {
        return MapperElementUtil.isLeftAssocRule(rule, mapping) || MapperElementUtil.isRightAssocRule(rule, mapping);
    }

    private static boolean container(String type) {
        return type.startsWith("List<") || type.startsWith("Optional<");
    }

    private static boolean transparentRecursiveOperands(RuleDecl rule, String className,
            Map<String, RuleDecl> rules, Map<String, TokenDecl> tokens) {
        var shape = MapperElementUtil.findAssocShape(rule, "left", "op", "right");
        if (shape.isEmpty()) return false;
        for (AtomicElement operand : List.of(shape.get().leftElement(), shape.get().rightElement())) {
            if (operand instanceof RuleRefElement reference) {
                RuleDecl target = rules.get(reference.name());
                if (target == null || MapperElementUtil.getMappingAnnotation(target).isPresent()) continue;
                Set<String> reachable = MapperElementUtil.reachableMappedClasses(operand, rules, tokens);
                if (reachable.contains(className) && reachable.size() > 1) return true;
            }
        }
        return false;
    }

    private static void collectMappedOperands(AtomicElement operand, Map<String, RuleDecl> rules,
            Map<String, TokenDecl> tokens, Set<String> result) {
        result.addAll(MapperElementUtil.reachableMappedClasses(operand, rules, tokens));
        if (operand instanceof GroupElement group) {
            for (RuleRefElement reference : MapperElementUtil.collectRuleRefs(group.body())) {
                result.addAll(MapperElementUtil.reachableMappedClasses(reference, rules, tokens));
            }
        }
    }

    private static IllegalArgumentException invalid(String name, String detail) {
        return new IllegalArgumentException("Incompatible shared associative mapping " + name + ": " + detail);
    }
}
