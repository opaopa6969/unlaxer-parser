package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.TransparentElement;

import org.unlaxer.dsl.bootstrap.UBNFAST.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Rule-local mapping constraints; forward references are resolved after all values are bound. */
public final class TypeofConstraints {
    private TypeofConstraints() {}

    public record Constraint(String capture, String reference) {}

    public record Problem(String code, String message, String rule) {}

    public static List<Constraint> collect(RuleDecl rule) {
        var constraints = new ArrayList<Constraint>();
        var pending = new java.util.ArrayDeque<Object>();
        pending.push(rule.body());
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            if (value instanceof ChoiceBody body) {
                for (int i = body.alternatives().size() - 1; i >= 0; i--)
                    pending.push(body.alternatives().get(i));
            } else if (value instanceof SequenceBody body) {
                for (int i = body.elements().size() - 1; i >= 0; i--)
                    pending.push(body.elements().get(i));
            } else if (value instanceof AnnotatedElement element) {
                element.typeofConstraint()
                        .ifPresent(
                                type ->
                                        constraints.add(
                                                new Constraint(
                                                        element.captureName().orElse(null),
                                                        type.captureName())));
                pending.push(element.element());
            } else if (value instanceof TransparentElement element) pending.push(element.body());
            else if (value instanceof OptionalElement element) pending.push(element.body());
            else if (value instanceof RepeatElement element) pending.push(element.body());
            else if (value instanceof OneOrMoreElement element) pending.push(element.body());
            else if (value instanceof BoundedRepeatElement element) pending.push(element.body());
            else if (value instanceof SeparatedElement element) {
                pending.push(element.separator());
                pending.push(element.element());
            }
        }
        return List.copyOf(constraints);
    }

    /** Public fields use the joined constructor schema; private locals stay rule-local. */
    public static String valueType(GrammarDecl grammar, RuleDecl rule, String capture) {
        boolean published =
                MapperElementUtil.getMappingAnnotation(rule)
                        .map(mapping -> mapping.paramNames().contains(capture))
                        .orElse(false);
        return published
                ? new ASTGenerator().inferType(grammar, rule, capture)
                : MapperTypeResolver.inferType(grammar, rule, capture);
    }

    public static Set<String> captures(RuleDecl rule) {
        var names = new LinkedHashSet<String>();
        for (var constraint : collect(rule)) {
            if (constraint.capture() != null) names.add(constraint.capture());
            names.add(constraint.reference());
        }
        return names;
    }

    public static List<Problem> problems(RuleDecl rule) {
        var constraints = collect(rule);
        if (constraints.isEmpty()) return List.of();
        var problems = new ArrayList<Problem>();
        Set<String> names =
                new LinkedHashSet<>(new CaptureBindingPlan(rule).namesBySite().values());
        if (MapperElementUtil.getMappingAnnotation(rule).isEmpty() || EnumSchema.isEnum(rule)) {
            problems.add(
                    new Problem(
                            "E-TYPEOF-MAPPING",
                            "@typeof requires a non-skipped @mapping rule",
                            rule.name()));
        }
        for (var constraint : constraints) {
            if (constraint.capture() == null || constraint.capture().isEmpty()) {
                problems.add(
                        new Problem(
                                "E-TYPEOF-MISSING-CAPTURE",
                                "@typeof must be paired with its own capture",
                                rule.name()));
            }
            if (!names.contains(constraint.reference())) {
                problems.add(
                        new Problem(
                                "E-TYPEOF-UNKNOWN-CAPTURE",
                                "@typeof refers to unknown capture '"
                                        + constraint.reference()
                                        + "'",
                                rule.name()));
            }
        }
        return List.copyOf(problems);
    }

    public static void validate(GrammarDecl grammar) {
        for (var rule : grammar.rules())
            for (var problem : problems(rule)) {
                throw new IllegalArgumentException(
                        problem.code() + ": rule " + problem.rule() + ": " + problem.message());
            }
    }
}
