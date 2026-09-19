package org.unlaxer.dsl.codegen;

import java.util.List;
import java.util.Optional;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** One constructor contract for non-associative rules sharing a concrete record. */
final class SharedPlainSchema {
    private SharedPlainSchema() {}

    static Optional<String> fieldType(GrammarDecl grammar, RuleDecl rule, String field) {
        MappingAnnotation mapping = MapperElementUtil.getMappingAnnotation(rule).orElse(null);
        if (mapping == null) return Optional.empty();
        List<RuleDecl> family = grammar.rules().stream()
            .filter(candidate -> candidate.annotations().stream().noneMatch(a -> a instanceof SkipAnnotation))
            .filter(candidate -> MapperElementUtil.getMappingAnnotation(candidate)
                .map(other -> other.className().equals(mapping.className())).orElse(false)).toList();
        if (family.size() < 2 || family.stream().anyMatch(candidate -> candidate.annotations().stream()
                .anyMatch(a -> a instanceof LeftAssocAnnotation || a instanceof RightAssocAnnotation))) {
            return Optional.empty();
        }
        String container = null;
        String element = null;
        for (RuleDecl candidate : family) {
            MappingAnnotation other = MapperElementUtil.getMappingAnnotation(candidate).orElseThrow();
            if (!other.paramNames().equals(mapping.paramNames())) {
                throw invalid(mapping.className(), "parameter names/order differ on " + candidate.name());
            }
            String type = MapperTypeResolver.inferType(grammar, candidate, field);
            Optional<String> list = MapperTypeResolver.unwrapListType(type);
            Optional<String> optional = MapperTypeResolver.unwrapOptionalType(type);
            String wrapper = list.isPresent() ? "List" : optional.isPresent() ? "Optional" : "";
            String inner = list.or(() -> optional).orElse(type);
            if (container == null) {
                container = wrapper;
                element = inner;
            } else {
                if (!container.equals(wrapper)) {
                    throw invalid(mapping.className(), "capture cardinality differs for " + field);
                }
                if (!element.equals(inner)) element = "Object";
            }
        }
        return Optional.of(container.isEmpty() ? element : container + "<" + MapperTypeResolver.boxedType(element) + ">");
    }

    private static IllegalArgumentException invalid(String name, String detail) {
        return new IllegalArgumentException("Incompatible shared mapping schema " + name + ": " + detail);
    }
}
