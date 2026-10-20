package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.unlaxer.dsl.bootstrap.UBNFAST;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;

/**
 * Validation of COMMIT / LABEL / CATCH (#700). A problem names the offending element, so a
 * caller holding the source snapshot can report its exact code-point span.
 */
public final class RaisingOperators {
    private RaisingOperators() {}

    /** One rejected operator. {@code element} is the AST node whose span locates it. */
    public record Problem(String code, String rule, AtomicElement element, String subject) {
        public String message() {
            return code + ": " + rule + ": " + subject;
        }
    }

    public static List<Problem> problems(GrammarDecl grammar) {
        Set<String> declared = new HashSet<>();
        List<Site> sites = new ArrayList<>();
        for (RuleDecl rule : grammar.rules()) body(rule.name(), rule.body(), declared, sites, recovers(rule));
        List<Problem> problems = new ArrayList<>();
        for (Site site : sites) {
            if (raisingRepetition(site.element())) {
                problems.add(new Problem("E-RAISING-REPETITION", site.rule(), site.element(),
                    "unbounded repetition of COMMIT or LABEL always ends with that failure"));
            } else if (site.element() instanceof UBNFAST.CommitElement commit) {
                if (commit.message().isPresent() && commit.message().get().isEmpty()) {
                    problems.add(new Problem("E-COMMIT-EMPTY-MESSAGE", site.rule(), commit, "COMMIT message must not be empty"));
                }
            } else if (site.element() instanceof UBNFAST.CatchElement caught) {
                if (caught.labels().isEmpty()) {
                    problems.add(new Problem("E-CATCH-EMPTY", site.rule(), caught, "CATCH requires at least one label"));
                    continue;
                }
                Set<String> seen = new LinkedHashSet<>();
                for (String label : caught.labels()) {
                    if (!seen.add(label)) {
                        problems.add(new Problem("E-CATCH-DUPLICATE-LABEL", site.rule(), caught, "duplicate CATCH label " + label));
                    } else if (!declared.contains(label)) {
                        problems.add(new Problem("E-CATCH-UNKNOWN-LABEL", site.rule(), caught,
                            "CATCH label " + label + " is not raised by any LABEL"));
                    }
                }
            }
        }
        return problems;
    }

    /** Generators reject an invalid grammar before emitting anything. */
    public static void requireValid(GrammarDecl grammar) {
        var problems = problems(grammar);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(problems.stream().map(Problem::message)
                .collect(java.util.stream.Collectors.joining("; ")));
        }
    }

    /** Whether a grammar uses any of the three operators. */
    public static boolean used(GrammarDecl grammar) {
        List<Site> sites = new ArrayList<>();
        for (RuleDecl rule : grammar.rules()) body(rule.name(), rule.body(), new HashSet<>(), sites, false);
        return !sites.isEmpty();
    }

    private record Site(String rule, AtomicElement element) {}

    private static void body(String rule, RuleBody body, Set<String> declared, List<Site> sites, boolean exempt) {
        if (body instanceof ChoiceBody choice) {
            for (SequenceBody sequence : choice.alternatives()) body(rule, sequence, declared, sites, exempt);
        } else if (body instanceof SequenceBody sequence) {
            for (var annotated : sequence.elements()) element(rule, annotated.element(), declared, sites, exempt);
        }
    }

    /**
     * The repetition check is skipped where an enclosing construct can catch the raised failure:
     * inside a CATCH operand (a label) and in a {@code @recovery} rule (a committed error).
     * The check stays syntactic and identical on both hosts; it does not follow rule references.
     */
    private static boolean recovers(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(UBNFAST.RecoveryAnnotation.class::isInstance);
    }

    /**
     * An unbounded repetition whose whole body is one COMMIT or LABEL: the attempt that finds no
     * further item raises instead of ending the repetition, so it can never succeed.
     */
    static boolean raisingRepetition(AtomicElement element) {
        AtomicElement item = null;
        if (element instanceof UBNFAST.RepeatElement repeat) item = single(repeat.body());
        else if (element instanceof UBNFAST.OneOrMoreElement repeat) item = repeat.body();
        else if (element instanceof UBNFAST.BoundedRepeatElement repeat
            && repeat.max() == UBNFAST.BoundedRepeatElement.UNBOUNDED) item = repeat.body();
        return item instanceof UBNFAST.CommitElement || item instanceof UBNFAST.LabelElement;
    }

    private static AtomicElement single(RuleBody body) {
        if (body instanceof ChoiceBody choice) {
            return choice.alternatives().size() == 1 ? single(choice.alternatives().get(0)) : null;
        }
        var elements = ((SequenceBody) body).elements();
        return elements.size() == 1 ? elements.get(0).element() : null;
    }

    private static void element(String rule, AtomicElement element, Set<String> declared, List<Site> sites,
            boolean exempt) {
        if (!exempt && raisingRepetition(element)) sites.add(new Site(rule, element));
        if (element instanceof UBNFAST.CatchElement caught) {
            sites.add(new Site(rule, caught));
            body(rule, caught.child().body(), declared, sites, true);
            body(rule, caught.alternative().body(), declared, sites, true);
            return;
        }
        if (element instanceof UBNFAST.LabelElement label) {
            declared.add(label.label());
            sites.add(new Site(rule, label));
        } else if (element instanceof UBNFAST.CommitElement commit) {
            sites.add(new Site(rule, commit));
        }
        if (element instanceof UBNFAST.TransparentElement transparent) body(rule, transparent.body(), declared, sites, exempt);
        else if (element instanceof UBNFAST.OptionalElement optional) body(rule, optional.body(), declared, sites, exempt);
        else if (element instanceof UBNFAST.RepeatElement repeat) body(rule, repeat.body(), declared, sites, exempt);
        else if (element instanceof UBNFAST.OneOrMoreElement repeat) element(rule, repeat.body(), declared, sites, exempt);
        else if (element instanceof UBNFAST.BoundedRepeatElement repeat) element(rule, repeat.body(), declared, sites, exempt);
        else if (element instanceof UBNFAST.SeparatedElement separated) {
            element(rule, separated.element(), declared, sites, exempt);
            element(rule, separated.separator(), declared, sites, exempt);
        }
    }
}
