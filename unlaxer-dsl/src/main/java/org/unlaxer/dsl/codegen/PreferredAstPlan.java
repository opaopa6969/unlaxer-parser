package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.*;

import java.util.*;

/** Generated Java token depths of rule call sites, including helper and capture wrappers. */
public final class PreferredAstPlan {
    private PreferredAstPlan() {}

    public record Call(int rule, int depth) {}

    public static List<List<Call>> calls(GrammarDecl grammar) {
        var semantics = new SemanticCardinality(grammar);
        boolean semantic = semantics.enabled();
        var rules = grammar.rules().stream().map(RuleDecl::name).toList();
        var result = new ArrayList<List<Call>>();
        for (var rule : grammar.rules()) {
            var plan =
                    semantic
                            ? new CaptureBindingPlan(rule, semantics)
                            : new CaptureBindingPlan(rule);
            var calls = new ArrayList<Call>();
            var right = ParserRuleEmitter.getRightAssocShape(rule);
            if (right == null) body(rule.body(), 0, plan, rules, calls);
            else {
                atom(right.base(),2,plan,rules,calls);
                atom(right.op(),2,plan,rules,calls);
                atom(right.right(),2,plan,rules,calls);
                atom(right.base(),1,plan,rules,calls);
            }
            result.add(List.copyOf(calls));
        }
        return List.copyOf(result);
    }

    private static void body(
            RuleBody body,
            int depth,
            CaptureBindingPlan plan,
            List<String> rules,
            List<Call> calls) {
        if (body instanceof ChoiceBody choice) {
            for (var sequence : choice.alternatives()) {
                int extra =
                        choice.alternatives().size() > 1 && sequence.elements().size() > 1
                                ? 1 + (plan.bindings(sequence).isEmpty() ? 0 : 1)
                                : 0;
                sequence(sequence, depth + extra, plan, rules, calls);
            }
        } else sequence((SequenceBody) body, depth, plan, rules, calls);
    }

    private static void sequence(
            SequenceBody sequence,
            int depth,
            CaptureBindingPlan plan,
            List<String> rules,
            List<Call> calls) {
        for (var element : sequence.elements())
            atom(element.element(), depth + 1, plan, rules, calls);
    }

    private static AtomicElement single(RuleBody body) {
        if (body instanceof ChoiceBody choice)
            return choice.alternatives().size() == 1 ? single(choice.alternatives().get(0)) : null;
        var elements = ((SequenceBody) body).elements();
        return elements.size() == 1 ? elements.get(0).element() : null;
    }

    private static void atom(
            AtomicElement atom,
            int depth,
            CaptureBindingPlan plan,
            List<String> rules,
            List<Call> calls) {
        if (!plan.bindings(atom).isEmpty()) depth++;
        if (atom instanceof RuleRefElement ref) {
            int id = rules.indexOf(ref.name());
            if (id >= 0) calls.add(new Call(id, depth));
        } else if (atom instanceof TransparentElement group) {
            var child=single(group.body());
            if (!(atom instanceof GroupElement) && child instanceof RuleRefElement)
                atom(child,depth,plan,rules,calls);
            else body(group.body(), depth, plan, rules, calls);
        }
        else if (atom instanceof OptionalElement optional) {
            var child = single(optional.body());
            if (child instanceof RuleRefElement || child instanceof TerminalElement)
                atom(child, depth + 1, plan, rules, calls);
            else body(optional.body(), depth + 1, plan, rules, calls);
        } else if (atom instanceof RepeatElement repeat) {
            var child = single(repeat.body());
            if (child instanceof RuleRefElement) atom(child, depth + 1, plan, rules, calls);
            else body(repeat.body(), depth + 1, plan, rules, calls);
        } else if (atom instanceof OneOrMoreElement repeat)
            repeated(repeat.body(), depth, plan, rules, calls);
        else if (atom instanceof BoundedRepeatElement repeat)
            repeated(repeat.body(), depth, plan, rules, calls);
        else if (atom instanceof SeparatedElement separated) {
            atom(separated.element(), depth + 1, plan, rules, calls);
            atom(separated.separator(), depth + 3, plan, rules, calls);
        }
    }

    private static void repeated(
            AtomicElement child,
            int depth,
            CaptureBindingPlan plan,
            List<String> rules,
            List<Call> calls) {
        atom(child, depth + (child instanceof RuleRefElement ? 1 : 2), plan, rules, calls);
    }
}
