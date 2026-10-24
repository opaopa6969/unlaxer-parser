package org.unlaxer.dsl.codegen;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** The kind of a committed error among the raised kinds; labels are identifiers, so it never collides. */
    private static final String COMMIT = "#commit";

    /** One rejected operator. {@code element} is the AST node whose span locates it. */
    public record Problem(String code, String rule, AtomicElement element, String subject) {
        public String message() {
            return code + ": " + rule + ": " + subject;
        }
    }

    public static List<Problem> problems(GrammarDecl grammar) {
        Walk walk = new Walk();
        for (RuleDecl rule : grammar.rules()) walk.body(rule.name(), rule.body(), Set.of());
        // Without COMMIT or LABEL nothing raises, so no repetition is analysed (or rejected).
        Raising raising = walk.sites.stream().anyMatch(site -> site.element() instanceof UBNFAST.CommitElement
            || site.element() instanceof UBNFAST.LabelElement) ? new Raising(grammar, walk) : null;
        List<Problem> problems = new ArrayList<>();
        for (Site site : walk.sites) {
            if (site.catching() != null) {
                if (raising != null && raising.uncaught(site)) {
                    problems.add(new Problem("E-RAISING-REPETITION", site.rule(), site.element(),
                        "unbounded repetition of COMMIT or LABEL always ends with that failure"));
                }
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
                    } else if (!walk.declared.contains(label)) {
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
        Walk walk = new Walk();
        for (RuleDecl rule : grammar.rules()) walk.body(rule.name(), rule.body(), Set.of());
        return walk.sites.stream().anyMatch(site -> site.catching() == null);
    }

    /**
     * An operator site. {@code catching} is null except for an unbounded repetition of a COMMIT,
     * LABEL or local rule reference, where it holds the labels of the CATCH bodies enclosing it
     * within its rule.
     */
    private record Site(String rule, AtomicElement element, Set<String> catching) {}

    /** A local rule reference in {@code from}, inside CATCH bodies of {@code from} catching {@code catching}. */
    private record Reference(String from, String to, Set<String> catching) {}

    /** Collects operator sites, rule references and the kinds each rule raises syntactically. */
    private static final class Walk {
        final Set<String> declared = new HashSet<>();
        final List<Site> sites = new ArrayList<>();
        final List<Reference> references = new ArrayList<>();

        void body(String rule, RuleBody body, Set<String> catching) {
            if (body instanceof ChoiceBody choice) {
                for (SequenceBody sequence : choice.alternatives()) body(rule, sequence, catching);
            } else if (body instanceof SequenceBody sequence) {
                for (var annotated : sequence.elements()) element(rule, annotated.element(), catching);
            }
        }

        void element(String rule, AtomicElement element, Set<String> catching) {
            AtomicElement item = repeated(element);
            if (item instanceof UBNFAST.CommitElement || item instanceof UBNFAST.LabelElement
                || item instanceof UBNFAST.RuleRefElement ref && ref.namespace().isEmpty()) {
                sites.add(new Site(rule, element, catching));
            }
            if (element instanceof UBNFAST.CatchElement caught) {
                sites.add(new Site(rule, caught, null));
                // Only the body operand is caught; the alternative runs after the catch.
                Set<String> inner = new HashSet<>(catching);
                inner.addAll(caught.labels());
                body(rule, caught.child().body(), Set.copyOf(inner));
                body(rule, caught.alternative().body(), catching);
                return;
            }
            if (element instanceof UBNFAST.LabelElement label) {
                declared.add(label.label());
                sites.add(new Site(rule, label, null));
            } else if (element instanceof UBNFAST.CommitElement commit) {
                sites.add(new Site(rule, commit, null));
            } else if (element instanceof UBNFAST.RuleRefElement ref && ref.namespace().isEmpty()) {
                references.add(new Reference(rule, ref.name(), catching));
            }
            if (element instanceof UBNFAST.TransparentElement transparent) body(rule, transparent.body(), catching);
            else if (element instanceof UBNFAST.OptionalElement optional) body(rule, optional.body(), catching);
            else if (element instanceof UBNFAST.RepeatElement repeat) body(rule, repeat.body(), catching);
            else if (element instanceof UBNFAST.OneOrMoreElement repeat) element(rule, repeat.body(), catching);
            else if (element instanceof UBNFAST.BoundedRepeatElement repeat) element(rule, repeat.body(), catching);
            else if (element instanceof UBNFAST.SeparatedElement separated) {
                element(rule, separated.element(), catching);
                element(rule, separated.separator(), catching);
            }
        }
    }

    /**
     * Where a raised failure can be caught (#722). A labeled failure {@code L} is caught only by a
     * CATCH listing {@code L} whose body operand encloses the raise, in the same rule or around a
     * reference on some path to the rule; a committed error only by a {@code @recovery} rule, the
     * raising rule itself or one on some path to it. The analysis is conservative: a repetition
     * is rejected only when no reference context can catch any kind it may raise, and only when
     * every successful item consumes input. Each fixed point is a worklist over the reference
     * graph, so the work is linear in the references times the number of kinds.
     */
    private static final class Raising {
        final Map<String, RuleDecl> rules = new LinkedHashMap<>();
        /** References into each rule, and out of each rule. */
        final Map<String, List<Reference>> into = new HashMap<>();
        final Map<String, List<Reference>> out = new HashMap<>();
        /** Rules that may match the empty string; a token or an unknown reference counts as nullable. */
        final Set<String> nullable = new HashSet<>();
        /**
         * Whether {@code ''} succeeds with width 0 (#749): {@code Lexing.LiteralParser("")} under
         * {@code @tokenStream} or {@code @lexicalContext}. Otherwise it is {@code WordParser("")},
         * which always fails (#713), so it is not nullable. Same condition as the generators.
         */
        final boolean emptyLiteralSucceeds;
        /** Kinds a rule may raise: every COMMIT and LABEL in it and in the rules it references. */
        final Map<String, Set<String>> raisable = new HashMap<>();
        /** Labels caught around some reference path to a rule. */
        final Map<String, Set<String>> catchable = new HashMap<>();
        /** Rules on some reference path below a {@code @recovery} rule, recovery rules included. */
        final Set<String> recovered = new HashSet<>();
        /** Rules whose every outcome is a raised failure. */
        final Set<String> always = new HashSet<>();
        /** Rules that never fail ordinarily and consume input whenever they succeed. */
        final Set<String> only = new HashSet<>();

        Raising(GrammarDecl grammar, Walk walk) {
            emptyLiteralSucceeds = TokenStreamGrammar.enabled(grammar) || LexicalContexts.enabled(grammar);
            for (RuleDecl rule : grammar.rules()) rules.putIfAbsent(rule.name(), rule);
            for (RuleDecl rule : rules.values()) {
                into.put(rule.name(), new ArrayList<>());
                out.put(rule.name(), new ArrayList<>());
                raisable.put(rule.name(), new HashSet<>());
                catchable.put(rule.name(), new HashSet<>());
            }
            for (Reference reference : walk.references) {
                if (!rules.containsKey(reference.to())) continue;
                into.get(reference.to()).add(reference);
                out.get(reference.from()).add(reference);
            }
            // Nullable rules: re-examine the referrers of each rule that becomes nullable.
            Deque<String> work = new ArrayDeque<>(rules.keySet());
            while (!work.isEmpty()) {
                String name = work.poll();
                if (nullable.contains(name) || !nullable(rules.get(name).body())) continue;
                nullable.add(name);
                for (Reference reference : into.get(name)) work.add(reference.from());
            }
            // Raisable kinds flow backwards along references.
            for (Site site : walk.sites) {
                if (site.element() instanceof UBNFAST.CommitElement) raisable.get(site.rule()).add(COMMIT);
                else if (site.element() instanceof UBNFAST.LabelElement label) raisable.get(site.rule()).add(label.label());
            }
            work.addAll(rules.keySet());
            while (!work.isEmpty()) {
                String name = work.poll();
                for (Reference reference : into.get(name)) {
                    if (raisable.get(reference.from()).addAll(raisable.get(name))) work.add(reference.from());
                }
            }
            // Catchable labels and recovery flow forwards along references.
            for (RuleDecl rule : rules.values()) if (recovery(rule) != null) recovered.add(rule.name());
            work.addAll(rules.keySet());
            while (!work.isEmpty()) {
                String name = work.poll();
                for (Reference reference : out.get(name)) {
                    Set<String> target = catchable.get(reference.to());
                    boolean changed = target.addAll(reference.catching());
                    changed |= target.addAll(catchable.get(name));
                    if (recovered.contains(name)) changed |= recovered.add(reference.to());
                    if (changed) work.add(reference.to());
                }
            }
            // Always / only: re-examine the referrers of each rule whose status changes.
            work.addAll(rules.keySet());
            while (!work.isEmpty()) {
                String name = work.poll();
                RuleDecl rule = rules.get(name);
                var recovery = recovery(rule);
                boolean changed = false;
                if (!always.contains(name) && alwaysBody(rule.body())
                    && (recovery == null || !raisable.get(name).contains(COMMIT))) {
                    changed |= always.add(name);
                }
                if (!only.contains(name) && onlyBody(rule.body())
                    && (recovery == null || recovery.mode() == UBNFAST.RecoveryMode.SYNC)) {
                    changed |= only.add(name);
                }
                if (changed) for (Reference reference : into.get(name)) work.add(reference.from());
            }
        }

        boolean uncaught(Site site) {
            AtomicElement item = repeated(site.element());
            if (item == null || !onlyRaises(item)) return false;
            Set<String> kinds = new HashSet<>();
            if (item instanceof UBNFAST.CommitElement commit) {
                kinds.add(COMMIT);
                kinds.addAll(raisedIn(commit.body()));
            } else if (item instanceof UBNFAST.LabelElement label) {
                kinds.add(label.label());
                kinds.addAll(raisedIn(label.body()));
            } else if (item instanceof UBNFAST.RuleRefElement ref && only.contains(ref.name()) && !always.contains(ref.name())) {
                // A rule that always raises is reported inside it; here the repetition makes it certain.
                kinds.addAll(raisable.get(ref.name()));
            } else {
                return false;
            }
            for (String kind : kinds) {
                if (kind.equals(COMMIT) ? recovered.contains(site.rule())
                    : site.catching().contains(kind) || catchable.get(site.rule()).contains(kind)) {
                    return false;
                }
            }
            return true;
        }

        /** The kinds raised in a body: its COMMITs and LABELs and those of the rules it references. */
        Set<String> raisedIn(RuleBody body) {
            Set<String> kinds = new HashSet<>();
            Walk walk = new Walk();
            walk.body("", body, Set.of());
            for (Site site : walk.sites) {
                if (site.element() instanceof UBNFAST.CommitElement) kinds.add(COMMIT);
                else if (site.element() instanceof UBNFAST.LabelElement label) kinds.add(label.label());
            }
            for (Reference reference : walk.references) {
                Set<String> referenced = raisable.get(reference.to());
                if (referenced != null) kinds.addAll(referenced);
            }
            return kinds;
        }

        /** Whether a body may match the empty string (an over-approximation). */
        boolean nullable(RuleBody body) {
            if (body instanceof ChoiceBody choice) {
                for (SequenceBody sequence : choice.alternatives()) if (nullable(sequence)) return true;
                return false;
            }
            for (var annotated : ((SequenceBody) body).elements()) if (!nullable(annotated.element())) return false;
            return true;
        }

        boolean nullable(AtomicElement element) {
            if (element instanceof UBNFAST.TerminalElement terminal) return terminal.value().isEmpty() && emptyLiteralSucceeds;
            if (element instanceof UBNFAST.RuleRefElement ref) {
                return ref.namespace().isPresent() || !rules.containsKey(ref.name()) || nullable.contains(ref.name());
            }
            if (element instanceof UBNFAST.ErrorElement) return false;
            if (element instanceof UBNFAST.TransparentElement transparent) return nullable(transparent.body());
            if (element instanceof UBNFAST.OneOrMoreElement repeat) return nullable(repeat.body());
            if (element instanceof UBNFAST.BoundedRepeatElement repeat) return repeat.min() == 0 || nullable(repeat.body());
            if (element instanceof UBNFAST.SeparatedElement separated) return nullable(separated.element());
            return true; // optional, repetition, replay
        }

        /** Never fails ordinarily, and consumes input whenever it succeeds. */
        boolean onlyRaises(AtomicElement element) {
            if (element instanceof UBNFAST.CommitElement commit) return !nullable(commit.body());
            if (element instanceof UBNFAST.LabelElement label) return !nullable(label.body());
            return element instanceof UBNFAST.RuleRefElement ref && ref.namespace().isEmpty() && only.contains(ref.name())
                || alwaysRaises(element);
        }

        boolean alwaysRaises(AtomicElement element) {
            AtomicElement item = repeated(element);
            if (item != null) return onlyRaises(item);
            return element instanceof UBNFAST.RuleRefElement ref && ref.namespace().isEmpty() && always.contains(ref.name());
        }

        boolean onlyBody(RuleBody body) {
            var elements = sole(body);
            if (elements == null) return false;
            return elements.size() == 1 ? onlyRaises(elements.get(0)) : alwaysRaises(elements.get(0));
        }

        boolean alwaysBody(RuleBody body) {
            var elements = sole(body);
            return elements != null && alwaysRaises(elements.get(0));
        }
    }

    private static UBNFAST.RecoveryAnnotation recovery(RuleDecl rule) {
        for (var annotation : rule.annotations()) {
            if (annotation instanceof UBNFAST.RecoveryAnnotation recovery) return recovery;
        }
        return null;
    }

    /** The elements of a body with exactly one nonempty alternative, otherwise null. */
    private static List<AtomicElement> sole(RuleBody body) {
        SequenceBody sequence;
        if (body instanceof ChoiceBody choice) {
            if (choice.alternatives().size() != 1) return null;
            sequence = choice.alternatives().get(0);
        } else {
            sequence = (SequenceBody) body;
        }
        if (sequence.elements().isEmpty()) return null;
        return sequence.elements().stream().map(UBNFAST.AnnotatedElement::element).toList();
    }

    /** The item of an unbounded repetition ({@code { X }}, {@code X*}, {@code X+}, {@code X{n,}}), otherwise null. */
    private static AtomicElement repeated(AtomicElement element) {
        if (element instanceof UBNFAST.RepeatElement repeat) {
            var elements = sole(repeat.body());
            return elements != null && elements.size() == 1 ? elements.get(0) : null;
        }
        if (element instanceof UBNFAST.OneOrMoreElement repeat) return repeat.body();
        if (element instanceof UBNFAST.BoundedRepeatElement repeat
            && repeat.max() == UBNFAST.BoundedRepeatElement.UNBOUNDED) return repeat.body();
        return null;
    }
}
