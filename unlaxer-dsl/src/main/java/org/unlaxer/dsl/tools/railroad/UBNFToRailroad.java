package org.unlaxer.dsl.tools.railroad;

import java.util.ArrayList;
import java.util.List;

import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BoundedRepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ErrorElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Transforms UBNF AST nodes into {@link RailroadDiagram} model objects.
 *
 * Entry point: {@link #convertRule(RuleDecl, String)}.
 */
public class UBNFToRailroad {

    private static String nextId(Object node) {
        return String.valueOf(System.identityHashCode(node));
    }

    private UBNFToRailroad() {}

    public static RailroadDiagram convertRule(RuleDecl rule, String grammarName) {
        return convertRuleBody(rule.body(), grammarName);
    }

    public static RailroadDiagram convertRule(RuleDecl rule) {
        return convertRule(rule, "");
    }

    static RailroadDiagram convertRuleBody(RuleBody ruleBody, String grammarName) {
        if (ruleBody instanceof ChoiceBody choiceBody) {
            return convertChoiceBody(choiceBody, grammarName);
        }
        if (ruleBody instanceof SequenceBody sequenceBody) {
            return convertSequenceBody(sequenceBody, grammarName);
        }
        return new RailroadDiagram.Terminal(nextId(ruleBody), "?");
    }

    static RailroadDiagram convertChoiceBody(ChoiceBody choiceBody, String grammarName) {
        List<SequenceBody> alternatives = choiceBody.alternatives();

        if (alternatives.isEmpty()) {
            return new RailroadDiagram.Terminal(nextId(choiceBody), "ε");
        }
        if (alternatives.size() == 1) {
            return convertSequenceBody(alternatives.get(0), grammarName);
        }

        List<RailroadDiagram> railroadAlternatives = new ArrayList<>();
        for (SequenceBody alternative : alternatives) {
            railroadAlternatives.add(convertSequenceBody(alternative, grammarName));
        }
        return new RailroadDiagram.Choice(nextId(choiceBody), List.copyOf(railroadAlternatives));
    }

    static RailroadDiagram convertSequenceBody(SequenceBody sequenceBody, String grammarName) {
        List<AnnotatedElement> elements = sequenceBody.elements();

        if (elements.isEmpty()) {
            return new RailroadDiagram.Terminal(nextId(sequenceBody), "ε");
        }
        if (elements.size() == 1) {
            return convertAnnotatedElement(elements.get(0), grammarName);
        }

        List<RailroadDiagram> railroadElements = new ArrayList<>();
        for (AnnotatedElement annotatedElement : elements) {
            railroadElements.add(convertAnnotatedElement(annotatedElement, grammarName));
        }
        return new RailroadDiagram.Sequence(nextId(sequenceBody), List.copyOf(railroadElements));
    }

    static RailroadDiagram convertAnnotatedElement(AnnotatedElement annotatedElement, String grammarName) {
        return convertAtomicElement(annotatedElement.element(), grammarName);
    }

    static RailroadDiagram convertAtomicElement(AtomicElement atomicElement, String grammarName) {
        if (atomicElement instanceof TerminalElement terminalElement) {
            return new RailroadDiagram.Terminal(nextId(terminalElement), "'" + terminalElement.value() + "'");
        }
        if (atomicElement instanceof RuleRefElement ruleRefElement) {
            return new RailroadDiagram.NonTerminal(nextId(ruleRefElement), ruleRefElement.name(), grammarName);
        }
        if (atomicElement instanceof OptionalElement optionalElement) {
            RailroadDiagram inner = convertRuleBody(optionalElement.body(), grammarName);
            return new RailroadDiagram.Optional(nextId(optionalElement), inner);
        }
        if (atomicElement instanceof RepeatElement repeatElement) {
            RailroadDiagram inner = convertRuleBody(repeatElement.body(), grammarName);
            return new RailroadDiagram.Repeat(nextId(repeatElement), inner);
        }
        if (atomicElement instanceof OneOrMoreElement oneOrMoreElement) {
            RailroadDiagram inner = convertRuleBody(oneOrMoreElement.body(), grammarName);
            return new RailroadDiagram.Repeat(nextId(oneOrMoreElement), inner);
        }
        if (atomicElement instanceof BoundedRepeatElement boundedRepeatElement) {
            RailroadDiagram inner = convertRuleBody(boundedRepeatElement.body(), grammarName);
            return new RailroadDiagram.Repeat(nextId(boundedRepeatElement), inner);
        }
        if (atomicElement instanceof GroupElement groupElement) {
            RailroadDiagram inner = convertRuleBody(groupElement.body(), grammarName);
            return new RailroadDiagram.Group(nextId(groupElement), inner);
        }
        if (atomicElement instanceof SeparatedElement separatedElement) {
            RailroadDiagram elem = convertAtomicElement(separatedElement.element(), grammarName);
            RailroadDiagram sep  = convertAtomicElement(separatedElement.separator(), grammarName);
            // Render as: elem (sep elem)*
            RailroadDiagram sepElem = new RailroadDiagram.Sequence(nextId(separatedElement),
                List.of(sep, elem));
            return new RailroadDiagram.Sequence(nextId(separatedElement),
                List.of(elem, new RailroadDiagram.Repeat(nextId(separatedElement), sepElem)));
        }
        if (atomicElement instanceof ErrorElement errorElement) {
            return new RailroadDiagram.Terminal(nextId(errorElement), "ERROR('" + errorElement.message() + "')");
        }
        return new RailroadDiagram.Terminal(nextId(atomicElement), "?");
    }
    }
