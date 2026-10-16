package org.unlaxer.mapper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.unlaxer.Token;

/** Bounded, call-local validation of the mutable CST consumed by generated mappers. */
public final class MappingGuard {
    public static final int MAX_NODES = 1_048_576;
    public static final int MAX_EDGES = 8_388_608;
    public static final int MAX_DEPTH = 256;
    public static final int MAX_WORK = 8_388_608;
    public static final long MAX_TEXT_BYTES = 268_435_456L;

    private final IdentityHashMap<Token, State> nodes = new IdentityHashMap<>();
    private final ArrayList<Token> originalOrder = new ArrayList<>();
    private long work;
    private long textBytes;
    private long validationWork;
    private int edges;
    private boolean originalIsTree = true;
    private int originalClock;

    private static final class State {
        int originalHeight;
        Token originalParent;
        int originalEnter;
        int originalExit;
        int filteredHeight;
        long cost;
        long textBytes = -1;
    }
    private static final class Frame {
        final Token token;
        final List<Token> children;
        int next;
        int height = 1;
        IdentityHashMap<Token, Boolean> direct;
        Frame(Token token, boolean filtered) {
            this.token = token;
            children = filtered ? token.filteredChildren : token.getOriginalChildren();
        }
    }

    private MappingGuard() {}

    public static MappingGuard prepare(Token root) {
        MappingGuard guard = new MappingGuard();
        guard.validate(root, false);
        for (Token token : guard.originalOrder) {
            if (guard.nodes.get(token).filteredHeight == 0) guard.validate(token, true);
        }
        guard.originalOrder.clear();
        guard.originalOrder.trimToSize();
        return guard;
    }

    private void validate(Token root, boolean filtered) {
        if (root == null) throw invalid("null root");
        ArrayDeque<Frame> stack = new ArrayDeque<>();
        IdentityHashMap<Token, Boolean> active = new IdentityHashMap<>();
        enter(root, filtered, stack, active);
        while (!stack.isEmpty()) {
            Frame frame = stack.peek();
            if (frame.next == frame.children.size()) {
                State state = nodes.get(frame.token);
                if (filtered) state.filteredHeight = frame.height;
                else {
                    state.originalHeight = frame.height;
                    state.originalExit = ++originalClock;
                }
                active.remove(frame.token);
                stack.pop();
                if (!stack.isEmpty()) {
                    Frame parent = stack.peek();
                    parent.height = Math.max(parent.height, frame.height + 1);
                    if (parent.height > MAX_DEPTH) throw limit("projection depth");
                }
                continue;
            }
            Token child = frame.children.get(frame.next++);
            if (child == null) throw invalid("null child");
            if (filtered && !isOriginalDescendant(frame, child)) {
                throw invalid("filtered child is outside its original subtree");
            }
            if (active.containsKey(child)) throw invalid("cycle");
            State childState = nodes.get(child);
            if (!filtered && childState != null && childState.originalParent != frame.token) originalIsTree = false;
            int height = childState == null ? 0
                : filtered ? childState.filteredHeight : childState.originalHeight;
            if (height != 0) {
                frame.height = Math.max(frame.height, height + 1);
                if (frame.height > MAX_DEPTH) throw limit("projection depth");
            } else {
                enter(child, filtered, stack, active);
            }
        }
    }

    private void enter(Token token, boolean filtered, ArrayDeque<Frame> stack,
            IdentityHashMap<Token, Boolean> active) {
        if (stack.size() >= MAX_DEPTH) throw limit("projection depth");
        State state = nodes.get(token);
        if (state == null) {
            if (filtered) throw invalid("foreign filtered token");
            if (nodes.size() >= MAX_NODES) throw limit("nodes");
            if (token.parser == null || token.source == null || token.tokenKind == null) {
                throw invalid("missing token metadata");
            }
            int start = token.source.offsetFromRoot().value();
            int length = token.source.codePointLength().value();
            if (start < 0 || length < 0 || (long) start + length > Integer.MAX_VALUE) {
                throw invalid("invalid source span");
            }
            state = new State();
            state.originalParent = stack.isEmpty() ? null : stack.peek().token;
            state.originalEnter = ++originalClock;
            state.cost = 1L + token.getOriginalChildren().size() + token.filteredChildren.size();
            if (state.cost - 1 > MAX_EDGES - edges) throw limit("edges");
            edges += (int) state.cost - 1;
            nodes.put(token, state);
            originalOrder.add(token);
        }
        active.put(token, Boolean.TRUE);
        stack.push(new Frame(token, filtered));
    }

    private boolean isOriginalDescendant(Frame frame, Token target) {
        Token owner = frame.token;
        if (originalIsTree) {
            // The original DFS has already validated every node and assigned nested intervals.
            // Use identity-derived ancestry, never source-span containment: lookahead/generated
            // nodes may legitimately share or exceed their owner's consumed source range.
            chargeValidation();
            State parent = nodes.get(owner), child = nodes.get(target);
            return child != null && parent.originalEnter < child.originalEnter
                && child.originalExit < parent.originalExit;
        }
        // Shared original DAGs have no single DFS interval; preserve their bounded exact search.
        if (owner.getOriginalChildren().size() > 4) {
            if (frame.direct == null) {
                frame.direct = new IdentityHashMap<>();
                for (Token child : owner.getOriginalChildren()) {
                    chargeValidation();
                    frame.direct.put(child, Boolean.TRUE);
                }
            }
            if (frame.direct.containsKey(target)) return true;
        } else {
            // Original and filtered lists may differ in order; flattened descendants are legal.
            for (Token child : owner.getOriginalChildren()) {
                chargeValidation();
                if (child == target) return true;
            }
        }
        ArrayDeque<Token> pending = new ArrayDeque<>();
        IdentityHashMap<Token, Boolean> seen = new IdentityHashMap<>();
        for (Token child : owner.getOriginalChildren()) {
            if (seen.put(child, Boolean.TRUE) == null) pending.add(child);
        }
        while (!pending.isEmpty()) {
            Token child = pending.removeLast();
            chargeValidation();
            if (child == target) return true;
            for (Token next : child.getOriginalChildren()) {
                chargeValidation();
                if (seen.put(next, Boolean.TRUE) == null) pending.add(next);
            }
        }
        return false;
    }

    private void chargeValidation() {
        if (++validationWork > MAX_WORK) throw limit("filtered reachability");
    }

    /** Charge actual visits, including repeated expansion of a shared DAG. */
    public void visit(Token token) {
        if (token == null) return;
        State state = nodes.get(token);
        if (state == null) throw invalid("foreign mapping token");
        chargeWork(state.cost);
    }

    public void chargeWork(long amount) {
        if (amount < 0 || amount > MAX_WORK - work) throw limit("projection work");
        work += amount;
    }

    /** Count UTF-8 without materializing the Source; each token is scanned at most once. */
    public void text(Token token) {
        if (token == null) return;
        State state = nodes.get(token);
        if (state == null) throw invalid("foreign text token");
        if (state.textBytes < 0) {
            int length = token.source.codePointLength().value();
            if (length > MAX_TEXT_BYTES - textBytes) throw limit("requested text");
            long bytes = 0;
            for (int i = 0; i < length; i++) {
                int cp = token.source.codePointValueAt(i);
                if (!Character.isValidCodePoint(cp) || cp >= 0xd800 && cp <= 0xdfff) throw invalid("invalid source scalar");
                bytes += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
                if (bytes > MAX_TEXT_BYTES - textBytes) throw limit("requested text");
            }
            state.textBytes = bytes;
        }
        chargeTextBytes(state.textBytes);
    }

    public void chargeText(String text) {
        long bytes = 0;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            bytes += cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
            if (bytes > MAX_TEXT_BYTES - textBytes) throw limit("requested text");
            i += Character.charCount(cp);
        }
        chargeTextBytes(bytes);
    }

    public void chargeTextBytes(long amount) {
        if (amount < 0 || amount > MAX_TEXT_BYTES - textBytes) throw limit("requested text");
        textBytes += amount;
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("E-MAPPING-CST: " + detail);
    }
    private static IllegalArgumentException limit(String detail) {
        return new IllegalArgumentException("E-MAPPING-LIMIT: " + detail);
    }
}
