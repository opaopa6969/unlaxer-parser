package org.unlaxer.dsl.codegen.propertybased;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small, renderable model of a generated UBNF grammar (#705).
 *
 * <p>The model only contains the constructs the property-based parity test targets. It is
 * intentionally separate from {@code UBNFAST}: the generator and shrinker need structural
 * edits, and the rendered text is what both frontends (Java and native Rust) receive.
 */
public final class GrammarModel {

    /** Token declarations the generator can reference. Names never start with {@code R}. */
    public enum Token {
        ID("org.unlaxer.parser.clang.IdentifierParser", false),
        NUM("org.unlaxer.parser.elementary.NumberParser", false),
        AHEAD("LOOKAHEAD('x')", true),
        NOTAHEAD("NEGATIVE_LOOKAHEAD('y')", true),
        RANGE("CHAR_RANGE('a','c')", false),
        ANYC("ANY", false),
        NEG("NEGATION('ab')", false),
        KW("CI('ab')", false),
        END("EOF", true);

        public final String declaration;
        public final boolean nullable;

        Token(String declaration, boolean nullable) {
            this.declaration = declaration;
            this.nullable = nullable;
        }
    }

    /** Expression tree. */
    public sealed interface Ex
            permits Lit, Tok, Ref, Seq, Alt, Opt, Rep, Sep, Cap, Same {}

    public record Lit(String text) implements Ex {}

    public record Tok(Token token) implements Ex {}

    public record Ref(int rule) implements Ex {}

    /** A sequence item, optionally carrying an AST field capture {@code @field}. */
    public record Item(Ex ex, String field) {}

    public record Seq(List<Item> items) implements Ex {
        public Seq {
            items = List.copyOf(items);
        }
    }

    public record Alt(List<Ex> alternatives) implements Ex {
        public Alt {
            alternatives = List.copyOf(alternatives);
        }
    }

    public record Opt(Ex body) implements Ex {}

    /** {@code max < 0} means unbounded. */
    public record Rep(Ex body, int min, int max) implements Ex {}

    public record Sep(Ex element, Ex separator) implements Ex {}

    /** General owned binding {@code CAPTURE(name, body)}. */
    public record Cap(String name, Ex body) implements Ex {}

    /** Replay of an owned binding {@code SAME_AS(name)}. */
    public record Same(String name) implements Ex {}

    /** One rule; rule {@code i} is rendered as {@code R<i>}, its AST type as {@code N<i>}. */
    public record Rule(Ex body, boolean mapped) {}

    public final List<Rule> rules;
    public final boolean whitespaceNone;
    /**
     * Allow repetition / separated bodies that can succeed without consuming input. Both hosts
     * support them with the Java Occurs termination rule (docs/nullable-repetition.md), so they
     * are generated on purpose for a part of the seeds.
     */
    public final boolean nullableLoops;

    public GrammarModel(List<Rule> rules, boolean whitespaceNone) {
        this(rules, whitespaceNone, false);
    }

    public GrammarModel(List<Rule> rules, boolean whitespaceNone, boolean nullableLoops) {
        this.rules = List.copyOf(rules);
        this.whitespaceNone = whitespaceNone;
        this.nullableLoops = nullableLoops;
    }

    public GrammarModel withRules(List<Rule> replacement) {
        return new GrammarModel(replacement, whitespaceNone, nullableLoops);
    }

    // ------------------------------------------------------------------ rendering

    public String render(String grammarName, String packageName) {
        var out = new StringBuilder();
        out.append("grammar ").append(grammarName).append(" {\n");
        out.append("  @package: ").append(packageName).append('\n');
        if (whitespaceNone) out.append("  @whitespace: none\n");
        for (Token token : usedTokens())
            out.append("  token ").append(token.name()).append(" = ").append(token.declaration).append('\n');
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            out.append("  ");
            if (i == 0) out.append("@root ");
            if (rule.mapped()) {
                out.append("@mapping(N").append(i);
                Set<String> fields = fields(rule.body());
                if (!fields.isEmpty()) out.append(", params=[").append(String.join(",", fields)).append(']');
                out.append(") ");
            }
            out.append('R').append(i).append(" ::= ").append(body(rule.body())).append(" ;\n");
        }
        return out.append("}\n").toString();
    }

    /** Compact one-line form for corpus files and failure messages. */
    public String renderOneLine(String grammarName, String packageName) {
        return render(grammarName, packageName).replaceAll("\n\\s*", " ").trim();
    }

    private String body(Ex ex) {
        if (ex instanceof Alt alt) {
            var parts = new ArrayList<String>();
            for (Ex a : alt.alternatives()) parts.add(sequence(a));
            return String.join(" | ", parts);
        }
        return sequence(ex);
    }

    private String sequence(Ex ex) {
        if (ex instanceof Seq seq) {
            var parts = new ArrayList<String>();
            for (Item item : seq.items())
                parts.add(atom(item.ex()) + (item.field() == null ? "" : " @" + item.field()));
            return String.join(" ", parts);
        }
        return atom(ex);
    }

    private String atom(Ex ex) {
        if (ex instanceof Lit l) {
            return "'" + l.text() + "'";
        } else if (ex instanceof Tok t) {
            return t.token().name();
        } else if (ex instanceof Ref r) {
            return "R" + r.rule();
        } else if (ex instanceof Seq s) {
            return s.items().size() == 1 && s.items().get(0).field() == null ? atom(s.items().get(0).ex()) : "(" + sequence(s) + ")";
        } else if (ex instanceof Alt a) {
            return "(" + body(a) + ")";
        } else if (ex instanceof Opt o) {
            return "[" + body(o.body()) + "]";
        } else if (ex instanceof Rep r) {
            return repeat(r);
        } else if (ex instanceof Sep s) {
            return "(" + operand(s.element()) + " % " + operand(s.separator()) + ")";
        } else if (ex instanceof Cap c) {
            return "CAPTURE(" + c.name() + ", " + body(c.body()) + ")";
        } else if (ex instanceof Same s) {
            return "SAME_AS(" + s.name() + ")";
        }
        throw new AssertionError(ex);
    }

    /** Postfix and {@code %} operands must not themselves be postfix forms ({@code X+ % ','} is a syntax error). */
    private String operand(Ex ex) {
        while (ex instanceof Seq s && s.items().size() == 1 && s.items().get(0).field() == null) ex = s.items().get(0).ex();
        String text = atom(ex);
        return ex instanceof Rep r && !(r.min() == 0 && r.max() < 0) ? "(" + text + ")" : text;
    }

    private String repeat(Rep r) {
        if (r.min() == 0 && r.max() < 0) return "{" + body(r.body()) + "}";
        String inner = operand(r.body());
        if (r.min() == 1 && r.max() < 0) return inner + "+";
        return inner + "{" + r.min() + "," + (r.max() < 0 ? "" : String.valueOf(r.max())) + "}";
    }

    // ------------------------------------------------------------------ analysis

    public Set<Token> usedTokens() {
        var used = new java.util.TreeSet<Token>();
        for (Rule r : rules) walk(r.body(), e -> {
            if (e instanceof Tok t) used.add(t.token());
        });
        return used;
    }

    /** AST field names of a rule, in first-occurrence order. */
    public static Set<String> fields(Ex body) {
        var fields = new LinkedHashSet<String>();
        walk(body, e -> {
            if (e instanceof Seq s) for (Item i : s.items()) if (i.field() != null) fields.add(i.field());
        });
        return fields;
    }

    /** Owned CAPTURE names used anywhere in the grammar. */
    public Set<String> ownedNames() {
        var names = new java.util.TreeSet<String>();
        for (Rule r : rules) walk(r.body(), e -> {
            if (e instanceof Cap c) names.add(c.name());
        });
        return names;
    }

    public Set<String> allFields() {
        var names = new java.util.TreeSet<String>();
        for (Rule r : rules) names.addAll(fields(r.body()));
        return names;
    }

    public static void walk(Ex ex, java.util.function.Consumer<Ex> visitor) {
        visitor.accept(ex);
        if (ex instanceof Seq s) {
            s.items().forEach(i -> walk(i.ex(), visitor));
        } else if (ex instanceof Alt a) {
            a.alternatives().forEach(x -> walk(x, visitor));
        } else if (ex instanceof Opt o) {
            walk(o.body(), visitor);
        } else if (ex instanceof Rep r) {
            walk(r.body(), visitor);
        } else if (ex instanceof Sep s) {
            walk(s.element(), visitor);
            walk(s.separator(), visitor);
        } else if (ex instanceof Cap c) {
            walk(c.body(), visitor);
        }
    }

    /** Fixpoint nullability of every rule. */
    public boolean[] nullableRules() {
        boolean[] nullable = new boolean[rules.size()];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < rules.size(); i++)
                if (!nullable[i] && nullable(rules.get(i).body(), nullable)) {
                    nullable[i] = true;
                    changed = true;
                }
        }
        return nullable;
    }

    public static boolean nullable(Ex ex, boolean[] rules) {
        if (ex instanceof Lit l) {
            return l.text().isEmpty();
        } else if (ex instanceof Tok t) {
            return t.token().nullable;
        } else if (ex instanceof Ref r) {
            return rules[r.rule()];
        } else if (ex instanceof Seq s) {
            return s.items().stream().allMatch(i -> nullable(i.ex(), rules));
        } else if (ex instanceof Alt a) {
            return a.alternatives().stream().anyMatch(x -> nullable(x, rules));
        } else if (ex instanceof Opt o) {
            return true;
        } else if (ex instanceof Rep r) {
            return r.min() == 0 || nullable(r.body(), rules);
        } else if (ex instanceof Sep s) {
            return nullable(s.element(), rules);
        } else if (ex instanceof Cap c) {
            return nullable(c.body(), rules);
        } else if (ex instanceof Same s) {
            return true; // conservative: the replayed text may be empty
        }
        throw new AssertionError(ex);
    }

    /**
     * Structural validity enforced by construction and re-checked after every shrink step:
     * references are in range, no left recursion (a reference to the same or an earlier rule must
     * follow a non-nullable item of the same sequence), repetition and separated bodies are not
     * nullable unless {@link #nullableLoops}, and each SAME_AS follows its CAPTURE in the same
     * sequence.
     */
    public boolean valid() {
        if (rules.isEmpty()) return false;
        boolean[] nullable = nullableRules();
        for (int i = 0; i < rules.size(); i++)
            if (!valid(rules.get(i).body(), i, false, nullable, new LinkedHashSet<>())) return false;
        if (!reachableAll()) return false;
        for (int i = 0; i < rules.size(); i++)
            if (!rules.get(i).mapped() && !fields(rules.get(i).body()).isEmpty()) return false;
        return rules.get(0).mapped();
    }

    private boolean valid(Ex ex, int owner, boolean guarded, boolean[] nullable, Set<String> bound) {
        if (ex instanceof Lit l) {
            return !l.text().isEmpty();
        } else if (ex instanceof Tok t) {
            return true;
        } else if (ex instanceof Ref r) {
            return r.rule() >= 0 && r.rule() < rules.size() && (r.rule() > owner || guarded);
        } else if (ex instanceof Seq s) {
            if (s.items().isEmpty()) return false;
            boolean g = guarded;
            var local = new LinkedHashSet<>(bound);
            for (Item item : s.items()) {
                if (!valid(item.ex(), owner, g, nullable, local)) return false;
                if (item.ex() instanceof Cap c) local.add(c.name());
                g = g || !nullable(item.ex(), nullable);
            }
            return true;
        } else if (ex instanceof Alt a) {
            return a.alternatives().size() >= 2
                    && a.alternatives().stream().allMatch(x -> valid(x, owner, guarded, nullable, bound));
        } else if (ex instanceof Opt o) {
            return valid(o.body(), owner, guarded, nullable, bound);
        } else if (ex instanceof Rep r) {
            return (nullableLoops || !nullable(r.body(), nullable))
                    && r.min() >= 0
                    && (r.max() < 0 || r.max() >= Math.max(1, r.min()))
                    && valid(r.body(), owner, guarded, nullable, bound);
        } else if (ex instanceof Sep s) {
            return (nullableLoops || !nullable(s.element(), nullable) && !nullable(s.separator(), nullable))
                    && valid(s.element(), owner, guarded, nullable, bound)
                    && valid(s.separator(), owner, guarded, nullable, bound);
        } else if (ex instanceof Cap c) {
            return !(c.body() instanceof Cap) && valid(c.body(), owner, guarded, nullable, bound);
        } else if (ex instanceof Same s) {
            return bound.contains(s.name());
        }
        throw new AssertionError(ex);
    }

    private boolean reachableAll() {
        var seen = new boolean[rules.size()];
        var stack = new java.util.ArrayDeque<Integer>();
        stack.push(0);
        while (!stack.isEmpty()) {
            int r = stack.pop();
            if (seen[r]) continue;
            seen[r] = true;
            walk(rules.get(r).body(), e -> {
                if (e instanceof Ref ref && ref.rule() >= 0 && ref.rule() < rules.size()) stack.push(ref.rule());
            });
        }
        for (boolean b : seen) if (!b) return false;
        return true;
    }

    /** Number of expression nodes; the shrinker minimizes this first. */
    public int size() {
        int[] n = {0};
        for (Rule r : rules) walk(r.body(), e -> n[0]++);
        return n[0] + rules.size();
    }

    /** Short feature tags used to report the generated coverage. */
    public Map<String, Integer> features() {
        var counts = new LinkedHashMap<String, Integer>();
        for (Rule r : rules) walk(r.body(), e -> {
            String tag = e instanceof Lit ? "literal"
                    : e instanceof Tok t ? (t.token().nullable ? "lookahead/eof" : "token")
                    : e instanceof Ref ? "reference"
                    : e instanceof Seq s ? (s.items().stream().anyMatch(i -> i.field() != null) ? "field-capture" : "sequence")
                    : e instanceof Alt ? "choice"
                    : e instanceof Opt ? "optional"
                    : e instanceof Rep ? "repeat"
                    : e instanceof Sep ? "separated"
                    : e instanceof Cap ? "CAPTURE"
                    : "SAME_AS";
            counts.merge(tag, 1, Integer::sum);
        });
        for (Rule r : rules) if (r.mapped()) counts.merge("@mapping", 1, Integer::sum);
        return counts;
    }
}
