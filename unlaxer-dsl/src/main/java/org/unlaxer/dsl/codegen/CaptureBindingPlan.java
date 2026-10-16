package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.TransparentElement;

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
    final boolean nested;
    private int nextId;

    CaptureBindingPlan(RuleDecl rule) {
        ruleName = rule.name();
        nested = NestedCaptures.enabled(rule);
        visit(rule.body());
    }

    CaptureBindingPlan(RuleDecl rule, SemanticCardinality semantics) {
        this(rule);
        if (!nested) markSemanticValues(rule.body(), semantics);
    }

    static final String NESTED_TEXT = "#nested:text";
    static final String NESTED_INTEGER = "#nested:integer";
    boolean outerCapture(Object element) {
        return bindings(element).stream().anyMatch(binding -> !binding.startsWith("#"));
    }
    void markNestedMixed(GrammarDecl grammar, RuleDecl rule) {
        if (nested) markNestedMixed(grammar, rule.body());
    }
    private void markNestedMixed(GrammarDecl grammar, RuleBody body) {
        if (body instanceof ChoiceBody choice) {
            boolean mixed;
            try { mixed=NestedCaptures.body(grammar,body,0).leaf()==org.unlaxer.dsl.codegen.rust.GrammarIR.Kind.VALUE; }
            catch (IllegalArgumentException ignored) { mixed=false; }
            for (var alternative : choice.alternatives()) {
                if (mixed) {
                    var shape=NestedCaptures.body(grammar,alternative,0);
                    if (shape.leaf()==org.unlaxer.dsl.codegen.rust.GrammarIR.Kind.TEXT) bindUnit(alternative,NESTED_TEXT);
                    else if (shape.leaf()==org.unlaxer.dsl.codegen.rust.GrammarIR.Kind.INTEGER) bindUnit(alternative,NESTED_INTEGER);
                }
                markNestedMixed(grammar,alternative);
            }
        } else for (var element:((SequenceBody)body).elements()) markNestedMixed(grammar,element.element());
    }
    private void markNestedMixed(GrammarDecl grammar, AtomicElement element) {
        if(element instanceof TransparentElement e) markNestedMixed(grammar,e.body());
        else if(element instanceof OptionalElement e) markNestedMixed(grammar,e.body());
        else if(element instanceof RepeatElement e) markNestedMixed(grammar,e.body());
        else if(element instanceof OneOrMoreElement e) markNestedMixed(grammar,e.body());
        else if(element instanceof BoundedRepeatElement e) markNestedMixed(grammar,e.body());
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
        if (element instanceof TransparentElement group) {
            markSemanticValues(group.body(), semantics);
        } else if (element instanceof OptionalElement optional) {
            markSemanticItem(optional.body(), semantics);
        } else if (element instanceof RepeatElement repeat) {
            markSemanticItem(repeat.body(), semantics);
        } else if (element instanceof OneOrMoreElement repeat) {
            markSemanticItem(repeat.body(), semantics);
        } else if (element instanceof BoundedRepeatElement repeat) {
            markSemanticItem(repeat.body(), semantics);
        } else if (element instanceof SeparatedElement separated) {
            markSemanticItem(separated.element(), semantics);
            markSemanticValues(separated.separator(), semantics);
        } else {

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

    Map<String, String> namesBySite() {
        Map<String, String> result = new LinkedHashMap<>();
        sites.forEach((name, occurrences) -> occurrences.forEach(site -> result.put(site.id(), name)));
        return result;
    }

    List<Site> allSites() { return sites.values().stream().flatMap(List::stream).toList(); }

    private void visit(RuleBody body) {
        if (body instanceof ChoiceBody choice) {
            choice.alternatives().forEach(this::visit);
        } else if (body instanceof SequenceBody sequence) {
            sequence.elements().forEach(annotated -> {
                annotated.captureName().ifPresent(name -> {
                    String id = ruleName + ":" + nextId++;
                    sites.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(new Site(id, annotated.element()));
                    if (nested) bind(annotated.element(), id); else bindValues(annotated.element(), id);
                });
                visit(annotated.element());
            });
        } else {
            throw new IllegalStateException("unhandled " + body);
        }
    }

    private void visit(AtomicElement element) {
        if (element instanceof TransparentElement group) {
            visit(group.body());
        } else if (element instanceof OptionalElement optional) {
            visit(optional.body());
        } else if (element instanceof RepeatElement repeat) {
            visit(repeat.body());
        } else if (element instanceof OneOrMoreElement repeat) {
            visit(repeat.body());
        } else if (element instanceof BoundedRepeatElement repeat) {
            visit(repeat.body());
        } else if (element instanceof SeparatedElement separated) {
            visit(separated.element()); visit(separated.separator()); 
        } else {

        }
    }

    // Bind each value occurrence, not its optional/list container or a separator.
    private void bindValues(AtomicElement element, String id) {
        if (element instanceof OptionalElement optional) {
            bindValues(optional.body(), id);
        } else if (element instanceof RepeatElement repeat) {
            bindValues(repeat.body(), id);
        } else if (element instanceof OneOrMoreElement repeat) {
            bindValues(repeat.body(), id);
        } else if (element instanceof BoundedRepeatElement repeat) {
            bindValues(repeat.body(), id);
        } else if (element instanceof SeparatedElement separated) {
            bindValues(separated.element(), id);
        } else {
            bind(element, id);
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
