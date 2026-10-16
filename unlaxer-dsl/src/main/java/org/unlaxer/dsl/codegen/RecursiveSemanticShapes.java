package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.TransparentElement;

import java.util.LinkedHashMap;
import java.util.Map;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;

/** Finite fixed point for transparent rule values; no recursive expansion of rule references. */
public final class RecursiveSemanticShapes {
    public enum Count { ONE, OPTIONAL, MANY }
    /** kind is #text, #node, #join, or the declaring enum rule name. */
    public record Shape(String kind, Count count) {}
    private static final Shape EMPTY = new Shape("#empty", Count.OPTIONAL);
    private static final Shape TEXT = new Shape("#text", Count.ONE);
    private final Map<String, RuleDecl> rules;
    private final boolean enumNodes;
    private Map<String, Shape> values;
    private int work;

    public RecursiveSemanticShapes(GrammarDecl grammar) {
        this(grammar, false);
    }
    public RecursiveSemanticShapes(GrammarDecl grammar, boolean enumNodes) {
        this.rules = grammar.rules().stream().collect(java.util.stream.Collectors.toMap(
            RuleDecl::name, r -> r, (a, b) -> a, LinkedHashMap::new));
        this.enumNodes = enumNodes;
    }
    RecursiveSemanticShapes(Map<String, RuleDecl> rules) { this.rules = rules; this.enumNodes = false; }

    public Shape rule(String name) {
        if (values == null) solve();
        return values.getOrDefault(name, TEXT);
    }

    private void solve() {
        Map<String, Shape> current = new LinkedHashMap<>();
        for (var rule : rules.values()) {
            Shape terminal = terminal(rule);
            if (terminal != null) current.put(rule.name(), terminal);
        }
        // Kind can gain enum/node/text alternatives and count can widen twice.
        // Bound even malformed raw ASTs, without using the VM call stack for cycles.
        for (int iteration = 0; iteration < 4 * rules.size() + 4; iteration++) {
            Map<String, Shape> next = new LinkedHashMap<>(current);
            for (var rule : rules.values()) {
                if (terminal(rule) != null) continue;
                Shape shape = body(rule.body(), current, 0);
                if (shape != null) next.put(rule.name(), shape);
            }
            if (next.equals(current)) {
                rules.keySet().forEach(name -> { if (!next.containsKey(name) || next.get(name).equals(EMPTY)) next.put(name, TEXT); });
                values = next;
                return;
            }
            current = next;
        }
        throw new IllegalArgumentException("E-RECURSIVE-SHAPE: transparent shape analysis did not converge");
    }

    private Shape terminal(RuleDecl rule) {
        if (MapperElementUtil.isSkipped(rule)) return TEXT;
        if (EnumSchema.isEnum(rule)) return new Shape(enumNodes ? "#node" : rule.name(), Count.ONE);
        if (MapperElementUtil.getMappingAnnotation(rule).isPresent()) return new Shape("#node", Count.ONE);
        return null;
    }

    private Shape body(RuleBody body, Map<String, Shape> state, int depth) {
        checkDepth(depth);
        if (body instanceof ChoiceBody choice) {
            Shape result = null;
            for (var alternative : choice.alternatives()) {
                Shape candidate = body(alternative, state, depth + 1);
                if (candidate != null) result = result == null ? candidate : join(result, candidate, false);
            }
            return result;
        }
        Shape result = null;
        boolean text = false;
        for (var item : ((SequenceBody) body).elements()) {
            Shape child = element(item.element(), state, depth + 1);
            if (child == null) return null; // branch has no known finite derivation yet
            if (child.equals(EMPTY)) continue;
            if (child.kind().equals("#text")) text = true;
            else result = result == null ? child : join(result, child, true);
        }
        return result == null ? (text ? TEXT : EMPTY) : result;
    }

    private Shape element(AtomicElement element, Map<String, Shape> state, int depth) {
        checkDepth(depth);
        if (element instanceof RuleRefElement ref) return rules.containsKey(ref.name()) ? state.get(ref.name()) : TEXT;
        if (element instanceof TransparentElement group) return body(group.body(), state, depth + 1);
        if (element instanceof OptionalElement optional) return wrapNullable(body(optional.body(), state, depth + 1), Count.OPTIONAL);
        if (element instanceof RepeatElement repeat) return wrapNullable(body(repeat.body(), state, depth + 1), Count.MANY);
        if (element instanceof OneOrMoreElement repeat) return wrap(element(repeat.body(), state, depth + 1), Count.MANY);
        if (element instanceof BoundedRepeatElement repeat) {
            Shape child = element(repeat.body(), state, depth + 1);
            return repeat.min() == 0 ? wrapNullable(child, Count.MANY) : wrap(child, Count.MANY);
        }
        if (element instanceof SeparatedElement separated) {
            Shape separator = element(separated.separator(), state, depth + 1);
            if (separator != null && !separator.equals(EMPTY) && !separator.kind().equals("#text"))
                throw new IllegalArgumentException("Mapped semantic separator is unsupported");
            return wrap(element(separated.element(), state, depth + 1), Count.MANY);
        }
        return TEXT;
    }

    private static Shape wrapNullable(Shape shape, Count count) { return shape == null ? EMPTY : wrap(shape, count); }
    private static Shape wrap(Shape shape, Count count) {
        if (shape == null || shape.equals(EMPTY) || shape.kind().equals("#text")) return shape;
        return new Shape(shape.kind(), wider(shape.count(), count));
    }
    private static Shape join(Shape a, Shape b, boolean sequence) {
        if (a.equals(EMPTY)) return sequence ? b : wrap(b, Count.OPTIONAL);
        if (b.equals(EMPTY)) return sequence ? a : wrap(a, Count.OPTIONAL);
        return new Shape(a.kind().equals(b.kind()) ? a.kind() : "#join",
            sequence ? Count.MANY : wider(a.count(), b.count()));
    }
    private static Count wider(Count a, Count b) { return a.ordinal() >= b.ordinal() ? a : b; }
    private void checkDepth(int depth) {
        if (++work > 1_000_000) throw new IllegalArgumentException("E-RECURSIVE-SHAPE: analysis work exceeds 1000000");
        if (depth > 256) throw new IllegalArgumentException("E-RECURSIVE-SHAPE: expression depth exceeds 256");
    }
}
