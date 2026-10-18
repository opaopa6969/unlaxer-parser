package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.TransparentElement;

import java.util.*;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Bounded source analysis for recursion reachable without consuming input. */
final class LeftRecursionAnalysis {
    private static final int DEPTH = 256;
    private final Map<String, RuleDecl> rules = new LinkedHashMap<>();
    private final Map<String, TokenDecl> tokens = new HashMap<>();
    private final Set<String> nullable = new HashSet<>();
    private int work;
    private final List<String> cycles = new ArrayList<>();

    private LeftRecursionAnalysis(GrammarDecl grammar) {
        grammar.rules().forEach(rule -> rules.put(rule.name(), rule));
        grammar.tokens().forEach(token -> tokens.put(token.name(), token));
    }

    static void requireValid(GrammarDecl grammar) {
        var cycles = cycles(grammar);
        if (!cycles.isEmpty()) throw new IllegalArgumentException("E-LEFT-RECURSION: left recursion at "
            + cycles.get(0) + "; rewrite using repetition or input-consuming right recursion");
    }

    static List<String> cycles(GrammarDecl grammar) {
        var analysis = new LeftRecursionAnalysis(grammar);
        analysis.check();
        return List.copyOf(analysis.cycles);
    }

    private void tick(int depth) {
        if (++work > 1_000_000 || depth > DEPTH)
            throw new IllegalArgumentException("E-LEFT-RECURSION-LIMIT: rule analysis resource limit");
    }

    private void check() {
        boolean changed;
        do {
            changed = false;
            for (var rule : rules.values())
                if (!nullable.contains(rule.name()) && body(rule.body(), null, 0))
                    changed |= nullable.add(rule.name());
        } while (changed);
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (var rule : rules.values()) {
            Set<String> leading = new LinkedHashSet<>();
            body(rule.body(), leading, 0);
            edges.put(rule.name(), leading);
        }
        Set<String> done = new HashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String rule : rules.keySet()) visit(rule, edges, done, visiting);
    }

    private void visit(String rule, Map<String, Set<String>> edges,
            Set<String> done, Set<String> visiting) {
        tick(visiting.size() + 1);
        if (done.contains(rule)) return;
        if (!visiting.add(rule)) {
            var path = new ArrayList<>(visiting);
            var cycle = new ArrayList<>(path.subList(path.indexOf(rule), path.size()));
            cycle.add(rule);
            cycles.add(String.join(" -> ", cycle));
            return;
        }
        for (String next : edges.get(rule)) visit(next, edges, done, visiting);
        visiting.remove(rule);
        done.add(rule);
    }

    /** Return nullability while collecting only references before consumption. */
    private boolean body(RuleBody body, Set<String> leading, int depth) {
        tick(depth);
        if (body instanceof ChoiceBody choice) {
            boolean empty = false;
            for (var alternative : choice.alternatives())
                empty |= body(alternative, leading, depth + 1);
            return empty;
        }
        // A sequence owns the annotated atoms; it is not another nested grammar element.
        // Counting this representation wrapper rejected the established 96-group contract.
        for (var element : ((SequenceBody) body).elements())
            if (!atom(element.element(), leading, depth)) return false;
        return true;
    }

    private boolean atom(AtomicElement element, Set<String> leading, int depth) {
        tick(depth);
        if (element instanceof RuleRefElement ref) {
            if (ref.namespace().isPresent()) return true; // external contract may be nullable
            // Token aliases take priority over local rule names in generated parsers.
            var token = tokens.get(ref.name());
            if (token != null) return tokenNullable(token);
            if (!rules.containsKey(ref.name())) return true; // diagnosed by reference validation
            if (leading != null) leading.add(ref.name());
            return nullable.contains(ref.name());
        }
        if (element instanceof ReplayElement) return true; // binding may be empty
        if (element instanceof TerminalElement terminal) return terminal.value().isEmpty();
        if (element instanceof ErrorElement) return false;
        if (element instanceof TransparentElement group) return body(group.body(), leading, depth + 1);
        if (element instanceof OptionalElement optional) {
            body(optional.body(), leading, depth + 1);
            return true;
        }
        if (element instanceof RepeatElement repeat) {
            body(repeat.body(), leading, depth + 1);
            return true;
        }
        if (element instanceof OneOrMoreElement repeat) return atom(repeat.body(), leading, depth + 1);
        if (element instanceof BoundedRepeatElement repeat) {
            if (repeat.max() == 0) return true; // the body is never evaluated
            boolean empty = atom(repeat.body(), leading, depth + 1);
            return repeat.min() == 0 || empty;
        }
        if (element instanceof SeparatedElement separated) {
            boolean empty = atom(separated.element(), leading, depth + 1);
            if (empty) atom(separated.separator(), leading, depth + 1);
            return empty;
        }
        throw new IllegalStateException("unhandled " + element);
    }

    private boolean tokenNullable(TokenDecl token) {
        if (token instanceof TokenDecl.Declarative declarative) return declarative.expression().nullable();
        if (token instanceof TokenDecl.Empty || token instanceof TokenDecl.Eof
                || token instanceof TokenDecl.Lookahead || token instanceof TokenDecl.NegativeLookahead
                || token instanceof TokenDecl.Until) return true;
        if (token instanceof TokenDecl.Simple simple) {
            // Known builtins consume a nonempty token; custom classes may succeed at zero width.
            return simple.parserClass().endsWith("EndOfSourceParser")
                || !org.unlaxer.dsl.codegen.rust.RustGrammarLowering.supportsSimpleToken(simple.parserClass());
        }
        if (token instanceof TokenDecl.Adapter adapter)
            return !Set.of("tinyexpression.string", "tinyexpression.code-start", "tinyexpression.code-end",
                "tinyexpression.long-code-block").contains(adapter.id());
        return false; // ANY, range, negation, CI and REGEX have a nonempty-token contract.
    }
}
