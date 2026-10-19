package org.unlaxer.dsl.codegen.propertybased;

import org.unlaxer.dsl.codegen.propertybased.GrammarModel.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Seeded, deterministic generator of valid {@link GrammarModel}s and of inputs for them (#705).
 *
 * <p>{@link java.util.Random} is used because its sequence is specified by the JDK, so a seed
 * reproduces the same grammar and inputs on every JVM.
 */
public final class GrammarGenerator {
    /** Upper bounds of one generated grammar. */
    public record Limits(int maxRules, int maxDepth, int maxItems, int maxAlternatives) {
        public static final Limits CI = new Limits(4, 3, 3, 3);
    }

    /** Includes a supplementary character, precomposed and combining (e + U+0301) accents. */
    static final String[] LITERALS = {"a", "b", "ab", "x", "y", ",", "😀", "é", "e\u0301", "界"};
    static final String[] MUTATION_TEXT = {
        "a", "b", "x", "y", ",", "1", "😀", "́", "é", "\r\n", " ", "\t", "🧩"
    };

    static final int MAX_DERIVED_CHARS = 32;

    private final Random random;
    private final Limits limits;
    private int rules;
    private int owned;

    public GrammarGenerator(long seed, Limits limits) {
        this.random = new Random(seed);
        this.limits = limits;
    }

    // ------------------------------------------------------------------ grammars

    public GrammarModel grammar() {
        for (int attempt = 0; ; attempt++) {
            GrammarModel model = attempt();
            for (int round = 0; round < 6 && !model.valid(); round++) model = repair(model);
            if (model.valid()) return model;
            if (attempt > 50) throw new IllegalStateException("generator could not produce a valid grammar");
        }
    }

    private GrammarModel attempt() {
        rules = 1 + random.nextInt(limits.maxRules());
        owned = 0;
        var list = new ArrayList<Rule>();
        boolean whitespaceNone = random.nextInt(4) != 0;
        boolean nullableLoops = random.nextInt(3) == 0;
        for (int i = 0; i < rules; i++) {
            boolean mapped = i == 0 || random.nextInt(3) != 0;
            list.add(new Rule(ruleBody(i, mapped), mapped));
        }
        // Every rule must be reachable: attach unreferenced rules as an extra choice of an
        // earlier rule (a forward reference, so it cannot introduce left recursion).
        for (int j = 1; j < rules; j++) {
            final int target = j;
            boolean[] referenced = {false};
            for (Rule r : list) GrammarModel.walk(r.body(), e -> {
                if (e instanceof Ref ref && ref.rule() == target) referenced[0] = true;
            });
            if (referenced[0]) continue;
            int i = random.nextInt(j);
            Rule r = list.get(i);
            var alternatives = new ArrayList<Ex>();
            if (r.body() instanceof Alt alt) alternatives.addAll(alt.alternatives());
            else alternatives.add(r.body());
            alternatives.add(new Seq(List.of(new Item(new Ref(j), null))));
            list.set(i, new Rule(new Alt(alternatives), r.mapped()));
        }
        return new GrammarModel(list, whitespaceNone, nullableLoops);
    }

    /**
     * A left-recursive variant: the root's first alternative starts with a reference back to the
     * root, possibly through the other rules' leftmost positions. Both frontends must reject it
     * with E-LEFT-RECURSION (docs/left-recursion.md); it is never executed.
     */
    public static GrammarModel leftRecursive(GrammarModel model) {
        // Field captures are removed so that no other frontend error (#583 subset) comes first.
        var list = new ArrayList<Rule>();
        for (Rule r : model.rules) list.add(new Rule(stripFields(r.body()), r.mapped()));
        int last = list.size() - 1;
        Rule r = list.get(last);
        Ex back = new Ref(0);
        Ex body = r.body() instanceof Alt alt
                ? new Alt(prepend(alt.alternatives(), back))
                : new Seq(prependItem(r.body(), back));
        list.set(last, new Rule(body, r.mapped()));
        // Make the path from the root to the last rule leftmost and nullable-free.
        for (int i = last - 1; i >= 0; i--) {
            Rule ri = list.get(i);
            Ex lead = new Ref(i + 1);
            list.set(i, new Rule(ri.body() instanceof Alt alt ? new Alt(prepend(alt.alternatives(), lead))
                    : new Seq(prependItem(ri.body(), lead)), ri.mapped()));
        }
        return model.withRules(list);
    }

    private static List<Ex> prepend(List<Ex> alternatives, Ex lead) {
        var out = new ArrayList<Ex>();
        out.add(new Seq(prependItem(alternatives.get(0), lead)));
        out.addAll(alternatives.subList(1, alternatives.size()));
        return out;
    }

    private static List<Item> prependItem(Ex body, Ex lead) {
        var items = new ArrayList<Item>();
        items.add(new Item(lead, null));
        if (body instanceof Seq seq) items.addAll(seq.items());
        else items.add(new Item(body, null));
        return items;
    }

    private Ex ruleBody(int owner, boolean mapped) {
        if (random.nextInt(3) == 0) {
            int n = 2 + random.nextInt(limits.maxAlternatives() - 1);
            var alternatives = new ArrayList<Ex>();
            for (int k = 0; k < n; k++) alternatives.add(sequence(owner, 1, mapped));
            return new Alt(alternatives);
        }
        return sequence(owner, 1, mapped);
    }

    private Seq sequence(int owner, int depth, boolean fields) {
        int n = 1 + random.nextInt(limits.maxItems());
        var items = new ArrayList<Item>();
        boolean guarded = false;
        String pendingReplay = null;
        for (int k = 0; k < n; k++) {
            Ex ex;
            if (pendingReplay != null && random.nextBoolean()) {
                ex = new Same(pendingReplay);
                pendingReplay = null;
            } else if (depth < limits.maxDepth() && random.nextInt(8) == 0) {
                String name = "w" + owned++;
                ex = new Cap(name, element(owner, depth + 1, guarded));
                pendingReplay = name;
            } else ex = element(owner, depth, guarded);
            // Field captures over containers are mostly rejected by both frontends (#583 subset),
            // so they are generated rarely; scalar and rule-reference fields are the common case.
            boolean scalar = ex instanceof Lit || ex instanceof Tok || ex instanceof Ref || ex instanceof Cap || ex instanceof Same;
            String field = fields && random.nextInt(5) < 2 && (scalar || random.nextInt(8) == 0) ? "f" + random.nextInt(3) : null;
            items.add(new Item(ex, field));
            guarded = guarded || ex instanceof Lit || (ex instanceof Tok t && !t.token().nullable);
        }
        if (pendingReplay != null && random.nextBoolean())
            items.add(new Item(new Same(pendingReplay), null));
        return new Seq(items);
    }

    private Ex element(int owner, int depth, boolean guarded) {
        boolean deeper = depth < limits.maxDepth();
        int choice = random.nextInt(deeper ? 15 : 8);
        return switch (choice) {
            case 0, 1, 2 -> literal();
            case 3, 4 -> token();
            case 5, 6 -> {
                if (owner + 1 < rules) yield new Ref(owner + 1 + random.nextInt(rules - owner - 1));
                yield guarded ? new Ref(random.nextInt(owner + 1)) : literal();
            }
            case 7 -> guarded ? new Ref(random.nextInt(rules)) : token();
            case 8 -> new Opt(nested(owner, depth, guarded));
            case 9 -> {
                int form = random.nextInt(3);
                Ex body = nested(owner, depth, guarded);
                yield switch (form) {
                    case 0 -> new Rep(body, 0, -1);
                    case 1 -> new Rep(body, 1, -1);
                    default -> {
                        int min = random.nextInt(2);
                        yield new Rep(body, min, min + 1 + random.nextInt(2));
                    }
                };
            }
            case 10 -> new Sep(element(owner, depth + 1, guarded), random.nextBoolean() ? new Lit(",") : literal());
            case 11, 12 -> {
                int n = 2 + random.nextInt(limits.maxAlternatives() - 1);
                var alternatives = new ArrayList<Ex>();
                for (int k = 0; k < n; k++) alternatives.add(sequence(owner, depth + 1, false));
                yield new Alt(alternatives);
            }
            default -> sequence(owner, depth + 1, false);
        };
    }

    private Ex nested(int owner, int depth, boolean guarded) {
        return random.nextBoolean() ? element(owner, depth + 1, guarded) : sequence(owner, depth + 1, false);
    }

    private Lit literal() {
        return new Lit(LITERALS[random.nextInt(LITERALS.length)]);
    }

    private Tok token() {
        var tokens = GrammarModel.Token.values();
        return new Tok(tokens[random.nextInt(tokens.length)]);
    }

    /** Makes loop bodies non-nullable and turns unguarded back references into literals. */
    static GrammarModel repair(GrammarModel model) {
        boolean[] nullable = model.nullableRules();
        var list = new ArrayList<Rule>();
        for (int i = 0; i < model.rules.size(); i++) {
            Rule r = model.rules.get(i);
            list.add(new Rule(repair(r.body(), i, false, nullable, model.rules.size(), new LinkedHashSet<>(), model.nullableLoops), r.mapped()));
        }
        return model.withRules(list);
    }

    private static Ex repair(Ex ex, int owner, boolean guarded, boolean[] nullable, int count, LinkedHashSet<String> bound, boolean loops) {
        if (ex instanceof Ref r) {
            return r.rule() > owner && r.rule() < count || guarded && r.rule() < count ? r : new Lit("a");
        } else if (ex instanceof Seq s) {
            var items = new ArrayList<Item>();
            boolean g = guarded;
            var local = new LinkedHashSet<>(bound);
            for (Item item : s.items()) {
                Ex fixed = repair(item.ex(), owner, g, nullable, count, local, loops);
                items.add(new Item(fixed, item.field()));
                if (fixed instanceof Cap c) local.add(c.name());
                g = g || !GrammarModel.nullable(fixed, nullable);
            }
            return new Seq(items);
        } else if (ex instanceof Alt a) {
            return new Alt(a.alternatives().stream().map(x -> repair(x, owner, guarded, nullable, count, bound, loops)).toList());
        } else if (ex instanceof Opt o) {
            return new Opt(repair(o.body(), owner, guarded, nullable, count, bound, loops));
        } else if (ex instanceof Rep r) {
            return new Rep(consuming(loops, repair(r.body(), owner, guarded, nullable, count, bound, loops), nullable), r.min(), r.max());
        } else if (ex instanceof Sep s) {
            return new Sep(
                    consuming(loops, repair(s.element(), owner, guarded, nullable, count, bound, loops), nullable),
                    consuming(loops, repair(s.separator(), owner, guarded, nullable, count, bound, loops), nullable));
        } else if (ex instanceof Cap c) {
            Ex body = c.body() instanceof Cap inner ? inner.body() : c.body();
            return new Cap(c.name(), repair(body, owner, guarded, nullable, count, bound, loops));
        } else if (ex instanceof Same s) {
            return bound.contains(s.name()) ? s : new Lit("b");
        }
        return ex;
    }

    private static Ex consuming(boolean loops, Ex ex, boolean[] nullable) {
        if (loops || !GrammarModel.nullable(ex, nullable)) return ex;
        return new Seq(List.of(new Item(ex, null), new Item(new Lit("a"), null)));
    }

    // ------------------------------------------------------------------ inputs

    /**
     * Inputs for one grammar: derivations (usually accepted) plus broken variants with
     * insertion, deletion, replacement, supplementary characters, combining marks and CRLF.
     */
    public List<String> inputs(GrammarModel model, int derivations, int mutations) {
        var result = new LinkedHashSet<String>();
        result.add("");
        int[] minimum = minimumLengths(model);
        var bases = new ArrayList<String>();
        for (int i = 0; i < derivations; i++) {
            var out = new StringBuilder();
            derive(model, model.rules.get(0).body(), out, new HashMap<>(), 0, minimum);
            bases.add(out.toString());
            result.add(out.toString());
        }
        for (int i = 0; i < mutations; i++) {
            String base = bases.get(random.nextInt(bases.size()));
            result.add(mutate(base));
        }
        return List.copyOf(result);
    }

    /**
     * Extra derivation groups {derivation, mutations...}. Derivations ignore lookahead and EOF
     * constraints, so the harness keeps only groups whose derivation the Java parser accepts.
     */
    public List<List<String>> reserve(GrammarModel model, int groups, int mutations) {
        int[] minimum = minimumLengths(model);
        var result = new ArrayList<List<String>>();
        for (int i = 0; i < groups; i++) {
            var out = new StringBuilder();
            derive(model, model.rules.get(0).body(), out, new HashMap<>(), 0, minimum);
            var group = new ArrayList<String>();
            group.add(out.toString());
            for (int m = 0; m < mutations; m++) group.add(mutate(out.toString()));
            result.add(List.copyOf(group));
        }
        return List.copyOf(result);
    }

    private String mutate(String base) {
        var cps = new ArrayList<String>();
        base.codePoints().forEach(cp -> cps.add(new String(Character.toChars(cp))));
        int at = random.nextInt(cps.size() + 1);
        int last = Math.min(at, cps.size() - 1);
        switch (random.nextInt(7)) {
            case 0 -> cps.add(at, MUTATION_TEXT[random.nextInt(MUTATION_TEXT.length)]);
            case 1 -> { if (!cps.isEmpty()) cps.remove(last); }
            case 2 -> {
                if (!cps.isEmpty()) cps.set(last, MUTATION_TEXT[random.nextInt(MUTATION_TEXT.length)]);
                else cps.add("a");
            }
            case 3 -> cps.add(at, "😀");
            case 4 -> cps.add(at, "\u0301");
            case 5 -> cps.add(at, "\r\n");
            default -> cps.subList(at, cps.size()).clear();
        }
        return String.join("", cps);
    }

    private static int[] minimumLengths(GrammarModel model) {
        int n = model.rules.size();
        int[] min = new int[n];
        java.util.Arrays.fill(min, 1_000);
        for (int round = 0; round < n + 2; round++)
            for (int i = 0; i < n; i++) min[i] = Math.min(min[i], minimum(model.rules.get(i).body(), min));
        return min;
    }

    private static int minimum(Ex ex, int[] rules) {
        if (ex instanceof Lit l) {
            return l.text().codePointCount(0, l.text().length());
        } else if (ex instanceof Tok t) {
            return t.token().nullable ? 0 : 1;
        } else if (ex instanceof Ref r) {
            return rules[r.rule()];
        } else if (ex instanceof Seq s) {
            return Math.min(1_000, s.items().stream().mapToInt(i -> minimum(i.ex(), rules)).sum());
        } else if (ex instanceof Alt a) {
            return a.alternatives().stream().mapToInt(x -> minimum(x, rules)).min().orElse(0);
        } else if (ex instanceof Opt o) {
            return 0;
        } else if (ex instanceof Rep r) {
            return Math.min(1_000, r.min() * minimum(r.body(), rules));
        } else if (ex instanceof Sep s) {
            return minimum(s.element(), rules);
        } else if (ex instanceof Cap c) {
            return minimum(c.body(), rules);
        } else if (ex instanceof Same s) {
            return 0;
        }
        throw new AssertionError(ex);
    }

    private String tokenText(GrammarModel.Token token) {
        return switch (token) {
            case ID -> new String[] {"x", "ab", "q1", "_z"}[random.nextInt(4)];
            case NUM -> new String[] {"7", "42"}[random.nextInt(2)];
            case RANGE -> new String[] {"a", "b", "c"}[random.nextInt(3)];
            case ANYC -> new String[] {"a", "😀", "é", ","}[random.nextInt(4)];
            case NEG -> new String[] {"x", "😀", ","}[random.nextInt(3)];
            case KW -> new String[] {"ab", "AB", "aB"}[random.nextInt(3)];
            case AHEAD, NOTAHEAD, END -> "";
        };
    }

    private void derive(GrammarModel model, Ex ex, StringBuilder out, Map<String, String> owned, int depth, int[] min) {
        boolean shallow = depth > 6;
        // Grammars may have no finite derivation; long inputs only make backtracking slower.
        if (depth > 16 || out.length() > MAX_DERIVED_CHARS) return;
        if (ex instanceof Lit l) {
            out.append(l.text());
        } else if (ex instanceof Tok t) {
            out.append(tokenText(t.token()));
        } else if (ex instanceof Ref r) {
            derive(model, model.rules.get(r.rule()).body(), out, owned, depth + 1, min);
        } else if (ex instanceof Seq s) {
            var local = new HashMap<>(owned);
            for (Item item : s.items()) derive(model, item.ex(), out, local, depth, min);
        } else if (ex instanceof Alt a) {
            Ex chosen = a.alternatives().get(random.nextInt(a.alternatives().size()));
            if (shallow)
                for (Ex x : a.alternatives()) if (minimum(x, min) < minimum(chosen, min)) chosen = x;
            derive(model, chosen, out, owned, depth + 1, min);
        } else if (ex instanceof Opt o) {
            if (!shallow && random.nextBoolean()) derive(model, o.body(), out, owned, depth + 1, min);
        } else if (ex instanceof Rep r) {
            int count = r.min() + (shallow ? 0 : random.nextInt(3));
            if (r.max() >= 0) count = Math.min(count, r.max());
            for (int i = 0; i < count; i++) derive(model, r.body(), out, owned, depth + 1, min);
        } else if (ex instanceof Sep s) {
            int count = 1 + (shallow ? 0 : random.nextInt(3));
            for (int i = 0; i < count; i++) {
                if (i > 0) derive(model, s.separator(), out, owned, depth + 1, min);
                derive(model, s.element(), out, owned, depth + 1, min);
            }
        } else if (ex instanceof Cap c) {
            int start = out.length();
            derive(model, c.body(), out, owned, depth + 1, min);
            owned.put(c.name(), out.substring(start));
        } else if (ex instanceof Same s) {
            out.append(owned.getOrDefault(s.name(), ""));
        }
    }

    // ------------------------------------------------------------------ shrinking

    /** One-step simplifications of a grammar, smallest first; every candidate is valid. */
    public static List<GrammarModel> shrinkGrammar(GrammarModel model) {
        var candidates = new ArrayList<GrammarModel>();
        for (int i = 1; i < model.rules.size(); i++) {
            candidates.add(removeRule(model, i));
            candidates.add(inlineRule(model, i));
        }
        for (int i = 0; i < model.rules.size(); i++) {
            Rule r = model.rules.get(i);
            for (Ex variant : variants(r.body())) {
                var list = new ArrayList<>(model.rules);
                list.set(i, new Rule(variant, r.mapped()));
                candidates.add(model.withRules(list));
            }
            if (i > 0 && r.mapped()) {
                var list = new ArrayList<>(model.rules);
                list.set(i, new Rule(stripFields(r.body()), false));
                candidates.add(model.withRules(list));
            }
        }
        if (!model.whitespaceNone) candidates.add(new GrammarModel(model.rules, true, model.nullableLoops));
        if (model.nullableLoops) candidates.add(new GrammarModel(model.rules, model.whitespaceNone, false));
        var unique = new LinkedHashSet<String>();
        var result = new ArrayList<GrammarModel>();
        for (GrammarModel candidate : candidates) {
            GrammarModel pruned = pruneUnreachable(candidate);
            if (pruned.valid() && pruned.size() <= model.size() && unique.add(pruned.render("G", "p"))
                    && !pruned.render("G", "p").equals(model.render("G", "p")))
                result.add(pruned);
        }
        result.sort(java.util.Comparator.comparingInt(GrammarModel::size));
        return result;
    }

    private static GrammarModel removeRule(GrammarModel model, int index) {
        var list = new ArrayList<Rule>();
        for (int i = 0; i < model.rules.size(); i++)
            if (i != index) list.add(new Rule(renumber(model.rules.get(i).body(), index), model.rules.get(i).mapped()));
        return model.withRules(list);
    }

    /** Replaces every reference to rule {@code index} by its body (without fields), then drops it. */
    private static GrammarModel inlineRule(GrammarModel model, int index) {
        Ex body = stripFields(model.rules.get(index).body());
        var list = new ArrayList<Rule>();
        for (Rule r : model.rules)
            list.add(new Rule(map(r.body(), e -> e instanceof Ref ref && ref.rule() == index ? body : e), r.mapped()));
        list.set(index, model.rules.get(index));
        return removeRule(model.withRules(list), index);
    }

    private static Ex renumber(Ex ex, int removed) {
        return map(ex, e -> e instanceof Ref r
                ? r.rule() == removed ? new Lit("a") : r.rule() > removed ? new Ref(r.rule() - 1) : r
                : e);
    }

    private static GrammarModel pruneUnreachable(GrammarModel model) {
        for (int i = model.rules.size() - 1; i > 0; i--) {
            final int target = i;
            boolean[] used = {false};
            for (int j = 0; j < model.rules.size(); j++)
                if (j != i) GrammarModel.walk(model.rules.get(j).body(), e -> {
                    if (e instanceof Ref r && r.rule() == target) used[0] = true;
                });
            if (!used[0]) return pruneUnreachable(removeRule(model, i));
        }
        return model;
    }

    private static Ex stripFields(Ex ex) {
        return map(ex, e -> e instanceof Seq s
                ? new Seq(s.items().stream().map(i -> new Item(i.ex(), null)).toList())
                : e);
    }

    /** Bottom-up structural map. */
    private static Ex map(Ex ex, java.util.function.UnaryOperator<Ex> f) {
        Ex rebuilt = ex;
        if (ex instanceof Seq s) rebuilt = new Seq(s.items().stream().map(i -> new Item(map(i.ex(), f), i.field())).toList());
        else if (ex instanceof Alt a) rebuilt = new Alt(a.alternatives().stream().map(x -> map(x, f)).toList());
        else if (ex instanceof Opt o) rebuilt = new Opt(map(o.body(), f));
        else if (ex instanceof Rep r) rebuilt = new Rep(map(r.body(), f), r.min(), r.max());
        else if (ex instanceof Sep s) rebuilt = new Sep(map(s.element(), f), map(s.separator(), f));
        else if (ex instanceof Cap c) rebuilt = new Cap(c.name(), map(c.body(), f));
        return f.apply(rebuilt);
    }

    /** All one-step variants of an expression, including variants of its descendants. */
    static List<Ex> variants(Ex ex) {
        var out = new ArrayList<Ex>();
        // Local replacements.
        if (ex instanceof Lit l) {
            if (l.text().codePointCount(0, l.text().length()) > 1) out.add(new Lit(l.text().substring(0, l.text().offsetByCodePoints(0, 1))));
            if (!l.text().equals("a")) out.add(new Lit("a"));
        } else if (ex instanceof Tok t) {
            out.add(new Lit("a"));
        } else if (ex instanceof Ref r) {
            out.add(new Lit("a"));
        } else if (ex instanceof Seq s) {
            for (int i = 0; i < s.items().size(); i++) {
                if (s.items().size() > 1) {
                    var items = new ArrayList<>(s.items());
                    items.remove(i);
                    out.add(new Seq(items));
                }
                if (s.items().get(i).field() != null) {
                    var items = new ArrayList<>(s.items());
                    items.set(i, new Item(s.items().get(i).ex(), null));
                    out.add(new Seq(items));
                }
            }
            if (s.items().size() == 1 && s.items().get(0).field() == null) out.add(s.items().get(0).ex());
        } else if (ex instanceof Alt a) {
            for (int i = 0; i < a.alternatives().size(); i++) {
                out.add(a.alternatives().get(i));
                if (a.alternatives().size() > 2) {
                    var alternatives = new ArrayList<>(a.alternatives());
                    alternatives.remove(i);
                    out.add(new Alt(alternatives));
                }
            }
        } else if (ex instanceof Opt o) {
            out.add(o.body());
        } else if (ex instanceof Rep r) {
            out.add(r.body());
            if (r.min() > 0 || r.max() >= 0) out.add(new Rep(r.body(), 0, -1));
        } else if (ex instanceof Sep s) {
            out.add(s.element());
            out.add(new Rep(s.element(), 1, -1));
        } else if (ex instanceof Cap c) {
            out.add(c.body());
        } else if (ex instanceof Same s) {
            out.add(new Lit("a"));
        }
        // Descendant replacements.
        if (ex instanceof Seq s) {
            for (int i = 0; i < s.items().size(); i++)
                for (Ex v : variants(s.items().get(i).ex())) {
                    var items = new ArrayList<>(s.items());
                    items.set(i, new Item(v, s.items().get(i).field()));
                    out.add(new Seq(items));
                }
        } else if (ex instanceof Alt a) {
            for (int i = 0; i < a.alternatives().size(); i++)
                for (Ex v : variants(a.alternatives().get(i))) {
                    var alternatives = new ArrayList<>(a.alternatives());
                    alternatives.set(i, v);
                    out.add(new Alt(alternatives));
                }
        } else if (ex instanceof Opt o) {
            variants(o.body()).forEach(v -> out.add(new Opt(v)));
        } else if (ex instanceof Rep r) {
            variants(r.body()).forEach(v -> out.add(new Rep(v, r.min(), r.max())));
        } else if (ex instanceof Sep s) {
            variants(s.element()).forEach(v -> out.add(new Sep(v, s.separator())));
            variants(s.separator()).forEach(v -> out.add(new Sep(s.element(), v)));
        } else if (ex instanceof Cap c) {
            variants(c.body()).forEach(v -> out.add(new Cap(c.name(), v)));
        }
        return out;
    }

    /** One-step simplifications of an input, shortest first. */
    public static List<String> shrinkInput(String input) {
        int[] cps = input.codePoints().toArray();
        var out = new LinkedHashSet<String>();
        if (cps.length > 1) {
            out.add(new String(cps, 0, cps.length / 2));
            out.add(new String(cps, cps.length / 2, cps.length - cps.length / 2));
        }
        for (int i = 0; i < cps.length; i++) {
            var b = new StringBuilder();
            for (int j = 0; j < cps.length; j++) if (j != i) b.appendCodePoint(cps[j]);
            out.add(b.toString());
        }
        for (int i = 0; i < cps.length; i++) {
            if (cps[i] == 'a') continue;
            var b = new StringBuilder();
            for (int j = 0; j < cps.length; j++) b.appendCodePoint(j == i ? 'a' : cps[j]);
            out.add(b.toString());
        }
        out.remove(input);
        var list = new ArrayList<>(out);
        list.sort(java.util.Comparator.comparingInt(String::length));
        return list;
    }
}
