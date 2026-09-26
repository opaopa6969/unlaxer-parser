package org.unlaxer.dsl.codegen;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Semantic values inside transparent helpers, independent of their parser/token wrappers. */
final class SemanticCardinality {
    enum Kind { TEXT, NODE, VALUE }
    enum Count { ONE, OPTIONAL, MANY }
    record Shape(Kind kind, Count count) {}
    static final String TEXT_BINDING = "#semantic:text";
    static final String BOUNDARY_BINDING = "#semantic:boundary";
    private final GrammarDecl grammar;
    private final Map<String, RuleDecl> rules = new LinkedHashMap<>();

    SemanticCardinality(GrammarDecl grammar) {
        this.grammar = grammar;
        grammar.rules().forEach(rule -> rules.put(rule.name(), rule));
    }

    boolean enabled() {
        return rules.values().stream().filter(rule -> !associative(rule))
            .anyMatch(rule -> new CaptureBindingPlan(rule).allSites().stream().anyMatch(site -> needsCollection(site.element())));
    }

    static boolean associative(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a -> a instanceof LeftAssocAnnotation || a instanceof RightAssocAnnotation);
    }

    boolean needsCollection(AtomicElement element) {
        Shape shape = siteShape(element);
        return shape.kind() != Kind.TEXT && shape.count() != Count.ONE;
    }

    Shape siteShape(AtomicElement element) {
        if (element instanceof OptionalElement optional) {
            return siteShape(optional.body());
        }
        if (element instanceof RepeatElement repeat) {
            return siteShape(repeat.body());
        }
        if (element instanceof OneOrMoreElement repeat) {
            return siteShape(repeat.body());
        }
        if (element instanceof BoundedRepeatElement repeat) {
            return siteShape(repeat.body());
        }
        if (element instanceof SeparatedElement separated) {
            return siteShape(separated.element());
        }
        return shape(element);
    }

    private Shape siteShape(RuleBody body) {
        if (body instanceof ChoiceBody choice && choice.alternatives().size() == 1) return siteShape(choice.alternatives().get(0));
        if (body instanceof SequenceBody sequence && sequence.elements().size() == 1) return siteShape(sequence.elements().get(0).element());
        return shape(body);
    }

    String fieldType(RuleDecl rule, String field, String legacyType) {
        if (associative(rule)) return legacyType;
        Shape shape = captures(rule.body()).get(field);
        if (shape == null) return legacyType;
        String inner = legacyType;
        while (MapperTypeResolver.unwrapListType(inner).isPresent() || MapperTypeResolver.unwrapOptionalType(inner).isPresent()) {
            inner = inner.substring(inner.indexOf('<') + 1, inner.length() - 1);
        }
        var sites = new CaptureBindingPlan(rule).sites(field);
        if (shape.kind() == Kind.TEXT && sites.size() > 1) {
            // A group and its child can produce the same text type even when the old
            // container-level inference merged Object/List<String>/String to Object.
            Map<String, TokenDecl> tokens = new LinkedHashMap<>();
            grammar.tokens().forEach(token -> tokens.put(token.name(), token));
            Set<String> types = new java.util.LinkedHashSet<>();
            for (var site : sites) {
                String type = MapperElementUtil.usesBoundTextCapture(site.element(), rules, tokens) ? "String"
                    : MapperTypeResolver.inferTypeFromElement(grammar,
                        MapperElementUtil.normalizeCapturedElement(site.element()).orElse(site.element()));
                types.add(type);
            }
            inner = types.size() == 1 ? types.iterator().next() : "Object";
        }
        if (shape.kind() != Kind.TEXT && inner.equals("String")) inner = "Object";
        return switch (shape.count()) {
            case ONE -> inner;
            case OPTIONAL -> "Optional<" + MapperTypeResolver.boxedType(inner) + ">";
            case MANY -> "List<" + MapperTypeResolver.boxedType(inner) + ">";
        };
    }

    Shape shape(AtomicElement element) { return shape(element, new HashSet<>()); }
    Shape shape(RuleBody body) { return shape(body, new HashSet<>()); }

    private Shape shape(AtomicElement element, Set<String> visiting) {
        if (element instanceof RuleRefElement ref) {
            RuleDecl rule = rules.get(ref.name());
            if (rule == null || !MapperElementUtil.containsMappedValue(ref, rules)) return text();
            if (MapperElementUtil.getMappingAnnotation(rule).isPresent()) return new Shape(Kind.NODE, Count.ONE);
            if (!visiting.add(ref.name())) throw new IllegalArgumentException("Recursive unmapped semantic helper: " + ref.name());
            Shape result = shape(rule.body(), visiting);
            visiting.remove(ref.name());
            return result;
        }
        if (element instanceof GroupElement group) {
            return shape(group.body(), visiting);
        }
        if (element instanceof OptionalElement optional) {
            return semanticWrap(shape(optional.body(), visiting), Count.OPTIONAL);
        }
        if (element instanceof RepeatElement repeat) {
            return semanticWrap(shape(repeat.body(), visiting), Count.MANY);
        }
        if (element instanceof OneOrMoreElement repeat) {
            return semanticWrap(shape(repeat.body(), visiting), Count.MANY);
        }
        if (element instanceof BoundedRepeatElement repeat) {
            return semanticWrap(shape(repeat.body(), visiting), Count.MANY);
        }
        if (element instanceof SeparatedElement separated) {
            if (shape(separated.separator(), visiting).kind() != Kind.TEXT) {
                throw new IllegalArgumentException("Mapped semantic separator is unsupported");
            }
            return semanticWrap(shape(separated.element(), visiting), Count.MANY);
        }
        return text();
    }

    private Shape shape(RuleBody body, Set<String> visiting) {
        if (body instanceof ChoiceBody choice) {
            List<Shape> alternatives = choice.alternatives().stream().map(alt -> shape(alt, visiting)).toList();
            return alternatives.stream().reduce((a, b) -> merge(a, b, false)).orElse(text());
        }
        SequenceBody sequence = (SequenceBody) body;
        return sequence.elements().stream().map(element -> shape(element.element(), visiting))
            .filter(shape -> shape.kind() != Kind.TEXT).reduce((a, b) -> merge(a, b, true)).orElse(text());
    }

    private Shape capturedShape(AtomicElement element) {
        if (element instanceof OptionalElement optional) {
            return wrap(siteShape(optional.body()), Count.OPTIONAL);
        }
        if (element instanceof RepeatElement repeat) {
            return wrap(siteShape(repeat.body()), Count.MANY);
        }
        if (element instanceof OneOrMoreElement repeat) {
            return wrap(siteShape(repeat.body()), Count.MANY);
        }
        if (element instanceof BoundedRepeatElement repeat) {
            return wrap(siteShape(repeat.body()), Count.MANY);
        }
        if (element instanceof SeparatedElement separated) {
            return wrap(siteShape(separated.element()), Count.MANY);
        }
        return shape(element);
    }

    private Map<String, Shape> captures(RuleBody body) {
        if (body instanceof ChoiceBody choice) {
            List<Map<String, Shape>> alternatives = choice.alternatives().stream().map(this::captures).toList();
            Map<String, Shape> result = new LinkedHashMap<>();
            alternatives.forEach(fields -> fields.forEach((name, value) -> result.merge(name, value, (a, b) -> merge(a, b, false))));
            result.replaceAll((name, value) -> alternatives.stream().anyMatch(fields -> !fields.containsKey(name))
                ? wrap(value, Count.OPTIONAL) : value);
            return result;
        }
        Map<String, Shape> result = new LinkedHashMap<>();
        for (AnnotatedElement item : ((SequenceBody) body).elements()) {
            item.captureName().ifPresent(name -> result.merge(name, capturedShape(item.element()), (a, b) -> merge(a, b, true)));
            captures(item.element()).forEach((name, value) -> result.merge(name, value, (a, b) -> merge(a, b, true)));
        }
        return result;
    }

    private Map<String, Shape> captures(AtomicElement element) {
        if (element instanceof GroupElement group) {
            return captures(group.body());
        }
        if (element instanceof OptionalElement optional) {
            return wrapCaptures(captures(optional.body()), Count.OPTIONAL);
        }
        if (element instanceof RepeatElement repeat) {
            return wrapCaptures(captures(repeat.body()), Count.MANY);
        }
        if (element instanceof OneOrMoreElement repeat) {
            return wrapCaptures(captures(repeat.body()), Count.MANY);
        }
        if (element instanceof BoundedRepeatElement repeat) {
            return wrapCaptures(captures(repeat.body()), Count.MANY);
        }
        if (element instanceof SeparatedElement separated) {
            return wrapCaptures(captures(separated.element()), Count.MANY);
        }
        return Map.of();
    }

    private static Map<String, Shape> wrapCaptures(Map<String, Shape> fields, Count count) {
        Map<String, Shape> result = new LinkedHashMap<>();
        fields.forEach((name, shape) -> result.put(name, wrap(shape, count)));
        return result;
    }

    private static Shape text() { return new Shape(Kind.TEXT, Count.ONE); }
    private static Shape semanticWrap(Shape shape, Count count) { return shape.kind() == Kind.TEXT ? shape : wrap(shape, count); }
    private static Shape wrap(Shape shape, Count count) {
        return new Shape(shape.kind(), shape.count() == Count.MANY || count == Count.MANY ? Count.MANY
            : shape.count() == Count.OPTIONAL || count == Count.OPTIONAL ? Count.OPTIONAL : Count.ONE);
    }
    private static Shape merge(Shape left, Shape right, boolean sequence) {
        return new Shape(left.kind() == right.kind() ? left.kind() : Kind.VALUE,
            sequence || left.count() == Count.MANY || right.count() == Count.MANY ? Count.MANY
                : left.count() == Count.OPTIONAL || right.count() == Count.OPTIONAL ? Count.OPTIONAL : Count.ONE);
    }
}
