package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.bootstrap.UBNFAST.*;

import java.util.*;

/** Java's nullable synthetic literal leaves are distinct from Optional containers. */
public final class AssociativeProjection {
    private AssociativeProjection() {}

    public static Set<String> literalSpines(GrammarDecl grammar) {
        var rules = new LinkedHashMap<String, RuleDecl>();
        grammar.rules().forEach(r -> rules.put(r.name(), r));
        var result = new LinkedHashSet<String>();
        int[] work = {0};
        for (var rule : grammar.rules()) {
            var mapping = MapperElementUtil.getMappingAnnotation(rule).orElse(null);
            if (mapping == null || MapperElementUtil.isSkipped(rule)) continue;
            var shape = MapperElementUtil.findAssocShape(rule, "left", "op", "right");
            if (shape.isEmpty()
                    || !rule.annotations().stream()
                            .anyMatch(
                                    a ->
                                            a instanceof LeftAssocAnnotation
                                                    || a instanceof RightAssocAnnotation)) continue;
            String own = grammar.name() + "AST." + mapping.className();
            boolean spine =
                    SharedAssocSchema.resolve(grammar, rule)
                            .map(SharedAssocSchema::spine)
                            .orElseGet(
                                    () ->
                                            own.equals(
                                                            MapperTypeResolver.inferType(
                                                                    grammar, rule, "left"))
                                                    && ("List<" + own + ">")
                                                            .equals(
                                                                    MapperTypeResolver.inferType(
                                                                            grammar, rule, "right"))
                                                    && "List<String>"
                                                            .equals(
                                                                    MapperTypeResolver.inferType(
                                                                            grammar, rule, "op")));
            if (spine
                    && (raw(shape.get().leftElement(), rules, new HashSet<>(), 0, work)
                            || raw(shape.get().rightElement(), rules, new HashSet<>(), 0, work)))
                result.add(CommonFieldSchema.rustName(mapping.className()));
        }
        return Set.copyOf(result);
    }

    private static boolean raw(
            RuleBody body, Map<String, RuleDecl> rules, Set<String> seen, int depth, int[] work) {
        if (++work[0] > 1_000_000)
            throw new IllegalArgumentException("E-ASSOC: operand analysis work exceeds 1000000");
        if (depth > 256)
            throw new IllegalArgumentException("E-ASSOC: operand analysis exceeds 256");
        if (body instanceof ChoiceBody choice)
            return choice.alternatives().stream()
                    .anyMatch(a -> raw(a, rules, seen, depth + 1, work));
        return ((SequenceBody) body)
                .elements().stream().allMatch(a -> raw(a.element(), rules, seen, depth + 1, work));
    }

    private static boolean raw(
            AtomicElement atom,
            Map<String, RuleDecl> rules,
            Set<String> seen,
            int depth,
            int[] work) {
        if (++work[0] > 1_000_000)
            throw new IllegalArgumentException("E-ASSOC: operand analysis work exceeds 1000000");
        if (depth > 256)
            throw new IllegalArgumentException("E-ASSOC: operand analysis exceeds 256");
        if (atom instanceof RuleRefElement ref) {
            var rule = rules.get(ref.name());
            if (rule == null || MapperElementUtil.isSkipped(rule)) return true;
            if (MapperElementUtil.getMappingAnnotation(rule).isPresent()) return false;
            if (!seen.add(ref.name())) return false;
            boolean result = raw(rule.body(), rules, seen, depth + 1, work);
            seen.remove(ref.name());
            return result;
        }
        if (atom instanceof TransparentElement g) return raw(g.body(), rules, seen, depth + 1, work);
        if (atom instanceof OptionalElement || atom instanceof RepeatElement) return true;
        if (atom instanceof OneOrMoreElement r) return raw(r.body(), rules, seen, depth + 1, work);
        if (atom instanceof BoundedRepeatElement r)
            return r.min() == 0 || raw(r.body(), rules, seen, depth + 1, work);
        if (atom instanceof SeparatedElement r)
            return raw(r.element(), rules, seen, depth + 1, work);
        return true;
    }
}
