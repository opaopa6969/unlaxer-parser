package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.AutoCommit;
import org.unlaxer.dsl.bootstrap.UBNFAST.Annotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.SimpleAnnotation;

/**
 * Simple annotations ({@code '@' IDENTIFIER}, #715), shared in meaning with the native
 * {@code unlaxer_generator::simple_annotations}. A Simple annotation is retained metadata: every
 * generator treats a grammar with one as if it were absent, exactly as 3.3.0 did, and the parser
 * IR export keeps its name. Only {@code @nestedCaptures} and {@code @autoCommit} act as Simple
 * annotations. Every other one is reported as {@code W-ANNOTATION-IGNORED} at the annotation
 * span, so a typo or a misspelt form of a functional annotation is not lost silently; a strict /
 * fail-on-warning run turns it into a failure.
 */
public final class SimpleAnnotations {
    private SimpleAnnotations() {}

    public static final String IGNORED = "W-ANNOTATION-IGNORED";

    /** The Simple annotation names that have an effect. */
    public static final List<String> FUNCTIONAL = List.of("nestedCaptures", AutoCommit.ANNOTATION);

    /**
     * The dedicated annotation keywords. A Simple annotation with one of these names was written
     * with trivia after {@code @} ({@code @ leftAssoc}) or, for an argument keyword, without its
     * {@code (}.
     */
    public static final List<String> KEYWORDS = List.of(
        "root", "mapping", "eval", "whitespace", "interleave", "lexicalContext", "backref",
        "scopeTree", "declares", "catalog", "leftAssoc", "rightAssoc", "longestChoice",
        "uniqueLongestChoice", "namePredicate", "predictiveChoice", "precedence", "doc",
        "recovery", "skip", "commonField", "enum");

    /** The keywords that require arguments. */
    private static final Set<String> ARGUMENTS = Set.of(
        "mapping", "eval", "interleave", "lexicalContext", "backref", "scopeTree", "declares",
        "catalog", "namePredicate", "precedence", "doc", "recovery", "commonField");

    /**
     * One ignored Simple annotation; {@code node} is the annotation, whose source span runs from
     * {@code @} to the end of its name. {@code suggestion} is the annotation the author probably
     * meant, or null (see {@link #suggestion(String)}).
     */
    public record Warning(String rule, String subject, SimpleAnnotation node, String suggestion) {
        public String code() { return IGNORED; }

        public String message() {
            String text;
            if (KEYWORDS.contains(subject)) {
                text = "@" + subject + " is a plain annotation here and has no effect; write @" + subject
                    + (ARGUMENTS.contains(subject) ? "(...)" : "") + " with no space after '@'";
            } else if (suggestion != null) {
                text = "unknown annotation @" + subject + " has no effect; did you mean @" + suggestion + "?";
            } else {
                text = "unknown annotation @" + subject + " has no effect";
            }
            return IGNORED + ": " + rule + ": " + text;
        }
    }

    /**
     * One warning per Simple annotation without an effect, in rule order and then source order.
     * A tree that still has {@code @autoCommit} may be passed: that name is never reported.
     */
    public static List<Warning> warnings(GrammarDecl grammar) {
        var out = new ArrayList<Warning>();
        for (RuleDecl rule : grammar.rules()) {
            for (Annotation annotation : rule.annotations()) {
                if (!(annotation instanceof SimpleAnnotation simple) || FUNCTIONAL.contains(simple.name())) continue;
                out.add(new Warning(rule.name(), simple.name(), simple, suggestion(simple.name())));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The known annotation name nearest to {@code name}: an exact keyword is itself; otherwise
     * the smallest optimal-string-alignment distance after ASCII lowercasing, at most
     * {@code max(1, code points / 3)}, with ties going to the earlier name of {@link #KEYWORDS}
     * then {@link #FUNCTIONAL}. The native {@code simple_annotations::suggestion} is the same
     * function. Null when nothing is near.
     */
    public static String suggestion(String name) {
        if (KEYWORDS.contains(name)) return name;
        if (FUNCTIONAL.contains(name)) return null;
        int[] typed = lower(name);
        int limit = Math.max(1, typed.length / 3);
        String best = null;
        int least = Integer.MAX_VALUE;
        var candidates = new ArrayList<>(KEYWORDS);
        candidates.addAll(FUNCTIONAL);
        for (String known : candidates) {
            int d = distance(typed, lower(known));
            if (d <= limit && d < least) {
                best = known;
                least = d;
            }
        }
        return best;
    }

    private static int[] lower(String value) {
        return value.codePoints().map(c -> c >= 'A' && c <= 'Z' ? c + ('a' - 'A') : c).toArray();
    }

    /**
     * Optimal string alignment distance: insertions, deletions, substitutions and transpositions
     * of adjacent code points, each substring edited at most once.
     */
    private static int distance(int[] a, int[] b) {
        int[][] rows = new int[a.length + 1][b.length + 1];
        for (int i = 0; i <= a.length; i++) rows[i][0] = i;
        for (int j = 0; j <= b.length; j++) rows[0][j] = j;
        for (int i = 1; i <= a.length; i++) {
            for (int j = 1; j <= b.length; j++) {
                int cost = a[i - 1] == b[j - 1] ? 0 : 1;
                int value = Math.min(Math.min(rows[i - 1][j] + 1, rows[i][j - 1] + 1), rows[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                    value = Math.min(value, rows[i - 2][j - 2] + 1);
                rows[i][j] = value;
            }
        }
        return rows[a.length][b.length];
    }
}
