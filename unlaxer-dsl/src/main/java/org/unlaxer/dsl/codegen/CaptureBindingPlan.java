package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Stable grammar-site identities, never mutations of shared Parser.get instances. */
final class CaptureBindingPlan {
    record Site(String id, AtomicElement element) {}

    private final Map<Object, List<String>> bindings = new IdentityHashMap<>();
    private final Map<String, List<Site>> sites = new LinkedHashMap<>();
    private final String ruleName;
    private int nextId;

    CaptureBindingPlan(RuleDecl rule) {
        ruleName = rule.name();
        visit(rule.body());
    }

    CaptureBindingPlan(RuleDecl rule, SemanticCardinality semantics) {
        this(rule);
        markSemanticValues(rule.body(), semantics);
    }

    private void markSemanticValues(RuleBody body, SemanticCardinality semantics) {
        if (body instanceof ChoiceBody choice) {
            boolean mixed = semantics.shape(body).kind() == SemanticCardinality.Kind.VALUE;
            for (SequenceBody alternative : choice.alternatives()) {
                if (mixed && semantics.shape(alternative).kind() == SemanticCardinality.Kind.TEXT) {
                    bindUnit(alternative, SemanticCardinality.TEXT_BINDING);
                }
                markSemanticValues(alternative, semantics);
            }
        } else {
            ((SequenceBody) body).elements().forEach(item -> markSemanticValues(item.element(), semantics));
        }
    }

    private void markSemanticValues(AtomicElement element, SemanticCardinality semantics) {
        switch (element) {
            case GroupElement group -> markSemanticValues(group.body(), semantics);
            case OptionalElement optional -> markSemanticItem(optional.body(), semantics);
            case RepeatElement repeat -> markSemanticItem(repeat.body(), semantics);
            case OneOrMoreElement repeat -> markSemanticItem(repeat.body(), semantics);
            case BoundedRepeatElement repeat -> markSemanticItem(repeat.body(), semantics);
            case SeparatedElement separated -> {
                markSemanticItem(separated.element(), semantics);
                markSemanticValues(separated.separator(), semantics);
            }
            default -> { }
        }
    }

    private void markSemanticItem(RuleBody body, SemanticCardinality semantics) {
        var shape = semantics.shape(body);
        if (shape.kind() == SemanticCardinality.Kind.VALUE && shape.count() != SemanticCardinality.Count.MANY) {
            bindUnit(body, SemanticCardinality.BOUNDARY_BINDING);
        }
        markSemanticValues(body, semantics);
    }

    private void markSemanticItem(AtomicElement element, SemanticCardinality semantics) {
        var shape = semantics.shape(element);
        if (shape.kind() == SemanticCardinality.Kind.VALUE && shape.count() != SemanticCardinality.Count.MANY) {
            bind(element, SemanticCardinality.BOUNDARY_BINDING);
        }
        markSemanticValues(element, semantics);
    }

    private void bindUnit(RuleBody body, String binding) {
        if (body instanceof ChoiceBody choice && choice.alternatives().size() == 1
                && choice.alternatives().get(0).elements().size() == 1) {
            bind(choice.alternatives().get(0).elements().get(0).element(), binding);
        } else if (body instanceof SequenceBody sequence && sequence.elements().size() == 1) {
            bind(sequence.elements().get(0).element(), binding);
        } else bind(body, binding);
    }

    List<String> bindings(Object element) {
        return bindings.getOrDefault(element, List.of());
    }

    List<Site> sites(String capture) {
        return sites.getOrDefault(capture, List.of());
    }

    List<Site> allSites() { return sites.values().stream().flatMap(List::stream).toList(); }

    private void visit(RuleBody body) {
        switch (body) {
            case ChoiceBody choice -> choice.alternatives().forEach(this::visit);
            case SequenceBody sequence -> sequence.elements().forEach(annotated -> {
                annotated.captureName().ifPresent(name -> {
                    String id = ruleName + ":" + nextId++;
                    sites.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(new Site(id, annotated.element()));
                    bindValues(annotated.element(), id);
                });
                visit(annotated.element());
            });
        }
    }

    private void visit(AtomicElement element) {
        switch (element) {
            case GroupElement group -> visit(group.body());
            case OptionalElement optional -> visit(optional.body());
            case RepeatElement repeat -> visit(repeat.body());
            case OneOrMoreElement repeat -> visit(repeat.body());
            case BoundedRepeatElement repeat -> visit(repeat.body());
            case SeparatedElement separated -> { visit(separated.element()); visit(separated.separator()); }
            default -> { }
        }
    }

    // Bind each value occurrence, not its optional/list container or a separator.
    private void bindValues(AtomicElement element, String id) {
        switch (element) {
            case OptionalElement optional -> bindValues(optional.body(), id);
            case RepeatElement repeat -> bindValues(repeat.body(), id);
            case OneOrMoreElement repeat -> bindValues(repeat.body(), id);
            case BoundedRepeatElement repeat -> bindValues(repeat.body(), id);
            case SeparatedElement separated -> bindValues(separated.element(), id);
            default -> bind(element, id);
        }
    }

    private void bindValues(RuleBody body, String id) {
        if (body instanceof ChoiceBody choice && choice.alternatives().size() == 1
                && choice.alternatives().get(0).elements().size() == 1) {
            bindValues(choice.alternatives().get(0).elements().get(0).element(), id);
        } else if (body instanceof SequenceBody sequence && sequence.elements().size() == 1) {
            bindValues(sequence.elements().get(0).element(), id);
        } else {
            bind(body, id);
        }
    }

    private void bind(Object element, String id) {
        bindings.computeIfAbsent(element, ignored -> new ArrayList<>()).add(id);
    }
}
