package org.unlaxer.capture;

import java.util.ArrayList;
import java.util.List;
import org.unlaxer.Token;
import org.unlaxer.parser.combinator.CaptureBoundaryParser;
import org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind;

/** Immutable, context-independent view of explicit capture boundaries; never reparses source. */
public final class CaptureSnapshot {
    private CaptureSnapshot() {}
    public record Element(Kind kind, org.unlaxer.TokenKind tokenKind, boolean generated, int startCP, int endCP, String text, List<Element> children) {
        public Element {
            java.util.Objects.requireNonNull(kind);
            java.util.Objects.requireNonNull(tokenKind);
            java.util.Objects.requireNonNull(text);
            children = List.copyOf(children);
            if (startCP < 0 || endCP < startCP || kind == Kind.VALUE && !children.isEmpty()
                    || kind == Kind.OPTIONAL && children.size() > 1) throw new IllegalArgumentException("invalid capture element");
        }
    }
    public enum Error { NOT_BOUNDARY, INVALID_TREE, LIMIT }
    public static final class SnapshotException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final Error error;
        private SnapshotException(Error error) { super(error.name()); this.error = error; }
        public Error error() { return error; }
    }
    private static final int MAX_DEPTH = 256, MAX_NODES = 65_536, MAX_BYTES = 2 * 1024 * 1024;
    private static final class Budget { int nodes, bytes; }
    public static Element from(Token root) { return build(root, 0, new Budget()); }
    private static void visit(int depth, Budget budget) {
        if (depth >= MAX_DEPTH || ++budget.nodes > MAX_NODES) throw new SnapshotException(Error.LIMIT);
    }
    private static Element build(Token token, int depth, Budget budget) {
        visit(depth, budget);
        if (token == null) throw new SnapshotException(Error.INVALID_TREE);
        if (!(token.parser instanceof CaptureBoundaryParser boundary)) throw new SnapshotException(Error.NOT_BOUNDARY);
        if (token.source == null) throw new SnapshotException(Error.INVALID_TREE);
        int sourceLength = token.source.codePointLength().value();
        if (sourceLength < 0) throw new SnapshotException(Error.INVALID_TREE);
        if (sourceLength > MAX_BYTES - budget.bytes) throw new SnapshotException(Error.LIMIT);
        // StringSource reads its existing CP slice without materializing a String first.
        for (int offset = 0; offset < sourceLength; offset++) {
            int cp = token.source.codePointValueAt(offset);
            if (cp < 0 || cp > 0x10ffff || cp >= 0xd800 && cp <= 0xdfff) throw new SnapshotException(Error.INVALID_TREE);
            budget.bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (budget.bytes > MAX_BYTES) throw new SnapshotException(Error.LIMIT);
        }
        String text = token.source.sourceAsString();
        List<Element> children = new ArrayList<>();
        if (boundary.boundaryKind() != Kind.VALUE) {
            for (Token child : token.getOriginalChildren()) collect(child, depth + 1, budget, children);
            if (boundary.boundaryKind() == Kind.OPTIONAL && children.size() > 1) throw new SnapshotException(Error.INVALID_TREE);
        }
        int start = token.source.offsetFromRoot().value();
        int length = token.source.codePointLength().value();
        if (start < 0 || length > Integer.MAX_VALUE - start) throw new SnapshotException(Error.INVALID_TREE);
        var origin = CaptureBoundaryParser.originOf(token).orElse(new CaptureBoundaryParser.Origin(
                token.source.sourceKind().isDetached() && length > 0,
                start,token.source.sourceKind().isDetached() ? start : start + length));
        return new Element(boundary.boundaryKind(), token.tokenKind, origin.generated(), origin.start(), origin.end(), text, children);
    }
    private static void collect(Token token, int depth, Budget budget, List<Element> output) {
        if (token == null) throw new SnapshotException(Error.INVALID_TREE);
        if (token.parser instanceof CaptureBoundaryParser) output.add(build(token, depth, budget));
        else {
            visit(depth, budget);
            for (Token child : token.getOriginalChildren()) collect(child, depth + 1, budget, output);
        }
    }
}
