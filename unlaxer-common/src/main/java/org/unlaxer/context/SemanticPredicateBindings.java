package org.unlaxer.context;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.unlaxer.CapturedValue;
import org.unlaxer.Name;
import org.unlaxer.Source;

/** Parse-local named, pure conditions. Callbacks receive values, never a mutable parse context. */
public final class SemanticPredicateBindings {
    private SemanticPredicateBindings() {}
    private static final Name KEY = Name.of(SemanticPredicateBindings.class, "bindings");

    /** Original matched text and its code point range, end exclusive; not a CST object (#851). */
    public record Candidate(String text, int start, int end) {}
    public record Failure(String kind, int start, int end, String expected) {}
    public static final class Frame {
        private Failure failure;
        public boolean failed() { return failure != null; }
    }
    private static final class State {
        Map<String, Predicate<Candidate>> predicates = Map.of();
        final Deque<Frame> frames = new ArrayDeque<>();
        Failure last;
    }
    private static State state(ParseContext context) {
        return (State) context.getGlobalScopeTreeMap().computeIfAbsent(KEY, ignored -> new State());
    }

    /** Copy the registry before parsing. Captured application values must also remain immutable. */
    public static void install(ParseContext context, Map<String, Predicate<Candidate>> predicates) {
        State state = state(context);
        if (!state.frames.isEmpty()) throw new IllegalStateException("cannot replace active semantic predicates");
        if (predicates.size() > 64) throw new IllegalArgumentException("at most 64 semantic predicates");
        for (String name : predicates.keySet()) requireName(name);
        state.predicates = Map.copyOf(predicates);
    }

    public static void requireName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128
                || !asciiInitial(name.charAt(0))) throw new IllegalArgumentException("invalid semantic predicate name");
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!asciiInitial(c) && !(c >= '0' && c <= '9') && c != '.' && c != '-')
                throw new IllegalArgumentException("invalid semantic predicate name");
        }
    }
    private static boolean asciiInitial(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c == '_';
    }

    public static Frame begin(ParseContext context, List<String> requirements) {
        context.disableSpeculativeOptimizations();
        State state = state(context);
        if (state.frames.isEmpty()) state.last = null;
        Frame frame = new Frame();
        state.frames.push(frame);
        int at = context.getConsumedPosition().value();
        for (String name : requirements) {
            if (!state.predicates.containsKey(name)) {
                reject(context, "predicate_missing", at, at, "registered predicate " + name);
                break;
            }
        }
        return frame;
    }

    /**
     * Evaluates {@code name} over a successful child's matched source. Only input-backed text has an
     * original span: projected or generated text is the sticky {@code predicate_source} failure.
     */
    public static boolean test(ParseContext context, String name, Source matched, int emptyAt) {
        State state = state(context);
        int at = matched.codePointLength().value() == 0 ? emptyAt : matched.offsetFromRoot().value();
        if (state.frames.isEmpty()) {
            reject(context, "predicate_scope_missing", at, at, "semantic predicate entry scope");
            return false;
        }
        if (matched.codePointLength().value() == 0) return test(state, context, name, new Candidate("", at, at));
        if (CapturedValue.provenanceOf(matched, context.source) != CapturedValue.Provenance.INPUT) {
            reject(context, "predicate_source", at, at, "input-backed candidate for predicate " + name);
            return false;
        }
        String text = matched.sourceAsString();
        return test(state, context, name, new Candidate(text, at, at + text.codePointCount(0, text.length())));
    }

    private static boolean test(State state, ParseContext context, String name, Candidate candidate) {
        Predicate<Candidate> predicate = state.predicates.get(name);
        if (predicate == null) {
            reject(context, "predicate_missing", candidate.start(), candidate.end(), "registered predicate " + name);
            return false;
        }
        try {
            return predicate.test(candidate);
        } catch (ParseLimitException resource) {
            throw resource;
        } catch (RuntimeException failure) {
            // Application exception text is deliberately not exposed as a portable diagnostic.
            reject(context, "predicate_error", candidate.start(), candidate.end(), "predicate " + name + " completed normally");
            return false;
        }
    }

    private static void reject(ParseContext context, String kind, int start, int end, String expected) {
        State state = state(context);
        Failure failure = new Failure(kind, start, end, expected);
        for (Frame frame : state.frames) if (frame.failure == null) frame.failure = failure;
        if (state.frames.isEmpty()) state.last = failure;
    }
    public static void end(ParseContext context, Frame frame) {
        State state = state(context);
        if (state.frames.peek() != frame) throw new IllegalStateException("unbalanced semantic predicate scope");
        state.frames.pop();
        if (state.frames.isEmpty()) state.last = frame.failure;
    }
    public static Optional<Failure> failure(ParseContext context) {
        State state = state(context);
        return Optional.ofNullable(state.frames.isEmpty() ? state.last : state.frames.peek().failure);
    }
}
