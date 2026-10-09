package org.unlaxer.context;

import java.util.*;
import org.unlaxer.Name;

/** Parse-local semantic failures survive discarded alternatives within one explicit entry scope. */
public final class NameResolution {
    private NameResolution() {}
    private static final Name KEY = Name.of(NameResolution.class, "entryState");
    public record Failure(String kind, int start, int end, String expected) {}
    public static final class Frame {
        private Failure failure;
        public Optional<Failure> failure() { return Optional.ofNullable(failure); }
    }
    private static final class State {
        final Deque<Frame> frames = new ArrayDeque<>();
        final Map<String, NameSnapshot> snapshots = new HashMap<>();
        Failure last;
    }
    private static State state(ParseContext context) {
        return (State) context.getGlobalScopeTreeMap().computeIfAbsent(KEY, ignored -> new State());
    }
    public static Frame begin(ParseContext context, List<NameSnapshot.Requirement> requirements) {
        context.disableSpeculativeOptimizations();
        State state = state(context);
        if (state.frames.isEmpty()) { state.last = null; state.snapshots.clear(); }
        Frame frame = new Frame();
        state.frames.push(frame);
        int start = context.getConsumedPosition().value();
        if (requirements.size() > 64) {
            reject(context, "name_snapshot_limit", start, start, "at most 64 name snapshots");
            return frame;
        }
        for (var requirement : requirements) {
            try {
                NameSnapshot snapshot = state.snapshots.get(requirement.id());
                if (snapshot == null) {
                    var candidate = NameSnapshot.fromContext(context, requirement.id());
                    if (candidate.isEmpty()) {
                        reject(context, "name_snapshot_missing", start, start, "name snapshot " + requirement.id() + "@" + requirement.version());
                        continue;
                    }
                    snapshot = candidate.get();
                    if (state.snapshots.size() >= 64 || state.snapshots.values().stream().mapToInt(value -> value.names().size()).sum()
                            + snapshot.names().size() > 4096) {
                        reject(context, "name_snapshot_limit", start, start, "at most 64 snapshots and 4096 names");
                        continue;
                    }
                    state.snapshots.put(snapshot.id(), snapshot);
                }
                if (!snapshot.version().equals(requirement.version()))
                    reject(context, "name_snapshot_version", start, start, "name snapshot " + requirement.id() + "@" + requirement.version());
            } catch (IllegalArgumentException failure) {
                reject(context, "name_snapshot_invalid", start, start, "valid immutable name snapshot " + requirement.id());
            }
        }
        return frame;
    }
    public static void end(ParseContext context, Frame frame) {
        State state = state(context);
        if (state.frames.peek() != frame) throw new IllegalStateException("unbalanced name resolution scope");
        state.frames.pop();
        if (state.frames.isEmpty()) state.last = frame.failure;
    }
    public static boolean matches(ParseContext context, String id, String version, String kind,
            String name, int start, int end) {
        State state = state(context);
        if (state.frames.isEmpty()) {
            reject(context, "name_scope_missing", start, end, "explicit name resolution entry scope");
            return false;
        }
        NameSnapshot snapshot = state.snapshots.get(id);
        if (snapshot == null || !snapshot.version().equals(version)) {
            reject(context, "name_snapshot_missing", start, end, "name snapshot " + id + "@" + version);
            return false;
        }
        var resolved = snapshot.lookup(name);
        if (resolved.isEmpty()) {
            reject(context, "unresolved_name", start, end, "resolved name in snapshot " + id + "@" + version);
            return false;
        }
        return kind.equals("resolved") || kind.equals(resolved.get().name().toLowerCase(Locale.ROOT));
    }
    public static void reject(ParseContext context, String kind, int start, int end, String expected) {
        State state = state(context);
        Failure failure = new Failure(kind, start, end, expected);
        for (Frame frame : state.frames) if (frame.failure == null) frame.failure = failure;
        if (state.frames.isEmpty()) state.last = failure;
    }
    public static Optional<Failure> failure(ParseContext context) {
        State state = state(context);
        return Optional.ofNullable(state.frames.isEmpty() ? state.last : state.frames.peek().failure);
    }
}
