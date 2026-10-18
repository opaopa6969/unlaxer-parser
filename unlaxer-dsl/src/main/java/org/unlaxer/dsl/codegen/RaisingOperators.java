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
        for (RuleDecl rule : grammar.rules()) body(rule.name(), rule.body(), declared, sites);
        List<Problem> problems = new ArrayList<>();
        for (Site site : sites) {
            if (site.element() instanceof UBNFAST.CommitElement commit) {
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
        for (RuleDecl rule : grammar.rules()) body(rule.name(), rule.body(), new HashSet<>(), sites);
        return !sites.isEmpty();
    }

    private record Site(String rule, AtomicElement element) {}

    private static void body(String rule, RuleBody body, Set<String> declared, List<Site> sites) {
        if (body instanceof ChoiceBody choice) {
            for (SequenceBody sequence : choice.alternatives()) body(rule, sequence, declared, sites);
        } else if (body instanceof SequenceBody sequence) {
            for (var annotated : sequence.elements()) element(rule, annotated.element(), declared, sites);
        }
    }

    private static void element(String rule, AtomicElement element, Set<String> declared, List<Site> sites) {
        if (element instanceof UBNFAST.CatchElement caught) {
            sites.add(new Site(rule, caught));
            body(rule, caught.child().body(), declared, sites);
            body(rule, caught.alternative().body(), declared, sites);
            return;
        }
        if (element instanceof UBNFAST.LabelElement label) {
            declared.add(label.label());
            sites.add(new Site(rule, label));
        } else if (element instanceof UBNFAST.CommitElement commit) {
            sites.add(new Site(rule, commit));
        }
        if (element instanceof UBNFAST.TransparentElement transparent) body(rule, transparent.body(), declared, sites);
        else if (element instanceof UBNFAST.OptionalElement optional) body(rule, optional.body(), declared, sites);
        else if (element instanceof UBNFAST.RepeatElement repeat) body(rule, repeat.body(), declared, sites);
        else if (element instanceof UBNFAST.OneOrMoreElement repeat) element(rule, repeat.body(), declared, sites);
        else if (element instanceof UBNFAST.BoundedRepeatElement repeat) element(rule, repeat.body(), declared, sites);
        else if (element instanceof UBNFAST.SeparatedElement separated) {
            element(rule, separated.element(), declared, sites);
            element(rule, separated.separator(), declared, sites);
        }
    }
}
