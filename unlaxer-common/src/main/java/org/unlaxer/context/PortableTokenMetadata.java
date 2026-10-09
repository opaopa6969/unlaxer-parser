package org.unlaxer.context;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;

/**
 * Opt-in transactional string metadata and related token IDs. Token's existing
 * arbitrary Object / related Token maps are independent and remain unchanged.
 * Use one owner per ParseContext; direct Token map mutation is not transactional.
 */
public final class PortableTokenMetadata implements MutationAwareTransactionalState {
    public static final class NodeId {
        private final Object owner;
        private final long generation;
        private final int index;
        private NodeId(Object owner, long generation, int index) {
            this.owner = owner; this.generation = generation; this.index = index;
        }
        public int index() { return index; }
    }

    public record Info(TokenKind kind, String source, int offset, int end, int sourceOffset, boolean detached,
                       Map<String, String> extra, Map<String, NodeId> related) {
        public Info {
            extra = Map.copyOf(extra);
            related = Map.copyOf(related);
        }
    }

    public static final class Snapshot {
        private final Map<NodeId, Info> values;
        private Snapshot(Map<NodeId, Info> values) { this.values = Map.copyOf(values); }
        public Optional<Info> info(NodeId id) { return Optional.ofNullable(values.get(id)); }
    }

    private static final class Entry {
        final Token token;
        Integer anchor;
        final Map<String, String> extra = new HashMap<>();
        final Map<String, NodeId> related = new HashMap<>();
        Entry(Token token) { this.token = token; }
        Entry(Entry other) { token = other.token; anchor = other.anchor; extra.putAll(other.extra); related.putAll(other.related); }
        Info info() {
            int sourceOffset = token.source.offsetFromRoot().value();
            int start = anchor == null ? sourceOffset : anchor;
            int end = anchor == null ? start + token.source.codePointLength().value() : start;
            return new Info(token.tokenKind, token.source.toString(), start, end, sourceOffset,
                token.source.sourceKind().isDetached(), extra, related);
        }
    }

    private final ParseContext context;
    private final Object owner = new Object();
    private long generation;
    private Map<NodeId, Entry> values = new HashMap<>();
    private IdentityHashMap<Token, NodeId> ids = new IdentityHashMap<>();

    public PortableTokenMetadata(ParseContext context) {
        this.context = java.util.Objects.requireNonNull(context);
        context.registerTransactionalState(this);
    }

    private void beforeMutation() {
        context.beforeTransactionalStateMutation(this);
        context.markMemoizationStateChanged();
    }

    public NodeId register(Token token) {
        java.util.Objects.requireNonNull(token);
        NodeId known = ids.get(token);
        if (known != null) return known;
        beforeMutation();
        NodeId id = new NodeId(owner, ++generation, values.size());
        values.put(id, new Entry(token));
        ids.put(token, id);
        return id;
    }

    /** Retains a detached virtual source with a separate, zero-width input anchor. */
    public NodeId registerGenerated(Token token, int anchor) {
        java.util.Objects.requireNonNull(token);
        if (!token.tokenKind.isVirtual() || !token.source.sourceKind().isDetached()
                || anchor < 0 || anchor > context.getSource().codePointLength().value()) {
            throw new IllegalArgumentException("generated token must be virtual, detached, and anchored within input");
        }
        NodeId id = register(token);
        beforeMutation();
        values.get(id).anchor = anchor;
        return id;
    }

    public Optional<Info> info(NodeId id) {
        Entry entry = values.get(id);
        return entry == null ? Optional.empty() : Optional.of(entry.info());
    }

    public boolean putExtra(NodeId id, String name, String value) {
        Entry entry = values.get(id);
        if (entry == null || name == null || value == null) return false;
        beforeMutation();
        entry.extra.put(name, value);
        return true;
    }

    public Optional<String> removeExtra(NodeId id, String name) {
        Entry entry = values.get(id);
        if (entry == null || name == null) return Optional.empty();
        beforeMutation();
        return Optional.ofNullable(entry.extra.remove(name));
    }

    public boolean putRelated(NodeId id, String name, NodeId related) {
        Entry entry = values.get(id);
        if (entry == null || name == null || !values.containsKey(related)) return false;
        beforeMutation();
        entry.related.put(name, related);
        return true;
    }

    public Optional<NodeId> removeRelated(NodeId id, String name) {
        Entry entry = values.get(id);
        if (entry == null || name == null) return Optional.empty();
        beforeMutation();
        return Optional.ofNullable(entry.related.remove(name));
    }

    public Snapshot snapshot() {
        Map<NodeId, Info> snapshot = new HashMap<>();
        values.forEach((id, entry) -> snapshot.put(id, entry.info()));
        return new Snapshot(snapshot);
    }

    @Override public Runnable checkpoint() {
        Map<NodeId, Entry> saved = new HashMap<>();
        values.forEach((id, entry) -> saved.put(id, new Entry(entry)));
        IdentityHashMap<Token, NodeId> savedIds = new IdentityHashMap<>(ids);
        return () -> {
            values = new HashMap<>();
            saved.forEach((id, entry) -> values.put(id, new Entry(entry)));
            ids = new IdentityHashMap<>(savedIds);
        };
    }
}
