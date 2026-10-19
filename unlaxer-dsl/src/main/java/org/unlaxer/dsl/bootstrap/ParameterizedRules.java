package org.unlaxer.dsl.bootstrap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BoundedRepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.CaptureScopeElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.ErrorElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OwnedCaptureElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ReplayElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.TerminalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;

/**
 * First-order parameterized rules (#701), expanded before any validator or backend.
 *
 * <p>Mirrors {@code rust/unlaxer-ubnf/src/params.rs} step for step: definition checks,
 * use checks, rejection of parameterized call cycles, then monomorphization into
 * ordinary zero-arity rules under a size limit. Existing validators run on the
 * expanded grammar only; checking the unexpanded grammar is unsound (lean4-peg CE-002:
 * {@code F(x) ::= x; S ::= F(S)}). See docs/parameterized-rules.md.
 *
 * <p>A value parameter {@code =p} (#704) is sugar over the existing owned captures: the
 * instance body becomes {@code CAPTURE_SCOPE(CAPTURE(F(=p), arg) body)} and each use of
 * {@code p} becomes {@code SAME_AS(F(=p))}. See docs/call-by-value-arguments.md.
 */
public final class ParameterizedRules {
    /** Total atomic elements of all expanded instances (the generators' 65536-visit limit). */
    public static final int MAX_EXPANDED_ELEMENTS = 65536;
    /**
     * Inline nesting of the expansion (the native frontend's nesting limit): the rule bodies
     * open at a call plus the nesting of the instance body or argument created there, as if
     * every instance were written at its call site. One fixed recursion budget on both hosts.
     */
    public static final int MAX_EXPANDED_NESTING = 128;
    static final Set<String> RESERVED = Set.of("CAPTURE", "SAME_AS", "CAPTURE_SCOPE", "ERROR");
    /** NUL never occurs in an identifier, so no user reference can match a placeholder. */
    private static final String CALL_PREFIX = "\0call";
    /** The hidden by-name parameter that carries a value argument into its CAPTURE. */
    private static final String VALUE_PREFIX = "\0value";

    private ParameterizedRules() {}

    record Call(String name, List<RuleBody> args) {}

    /** One declared parameter; {@code =name} declares a value parameter (#704). */
    record Param(String name, boolean byValue) {}

    /**
     * The owned capture name of value parameter {@code param} of {@code rule}. It contains
     * '(', '=' and ')', which no CAPTURE / SAME_AS name written in UBNF (an identifier) can
     * contain, so expansion never shares a binding with a user capture of any grammar.
     */
    static String captureName(String rule, String param) {
        return rule + "(=" + param + ")";
    }

    static String placeholder(int index) { return CALL_PREFIX + index; }

    private static int callIndex(RuleRefElement ref) {
        if (ref.namespace().isPresent() || !ref.name().startsWith(CALL_PREFIX)) return -1;
        return Integer.parseInt(ref.name().substring(CALL_PREFIX.length()));
    }

    /** Positioned rejection; offsets are Unicode code points, as in the native frontend. */
    public static final class ExpansionException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String diagnostic;
        private final int start, end, line, column;

        ExpansionException(String diagnostic, int start, int end, int line, int column) {
            super(diagnostic + " (line " + line + ", column " + column + ")");
            this.code = diagnostic.substring(0, diagnostic.indexOf(':'));
            this.diagnostic = diagnostic;
            this.start = start;
            this.end = end;
            this.line = line;
            this.column = column;
        }

        public String code() { return code; }
        /** The message without position, identical to the native Rust diagnostic message. */
        public String diagnostic() { return diagnostic; }
        public int start() { return start; }
        public int end() { return end; }
        public int line() { return line; }
        public int column() { return column; }
    }

    // =========================================================================
    // Prescan: which adjacent NAME( spellings are calls
    // =========================================================================

    /** A {@code grammar Name { ... }} block in code points and its parameterized rule names. */
    record Region(int start, int end, Set<String> names) {}

    /**
     * For each grammar block in source order, the names defined there as
     * {@code Name(params) ::=} (opening parenthesis adjacent). Only these names make an
     * adjacent {@code Name(} of the same grammar a call, so a grammar without such
     * definitions parses exactly as before. Mirrors the native Rust prescan.
     */
    static List<Region> prescan(String source) {
        int[] text = source.codePoints().toArray();
        List<int[]> tokens = new ArrayList<>(); // {kind, start, end}; kind: 0 ident, 1 define, else the symbol
        int i = 0;
        while (i < text.length) {
            int c = text[i];
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0b || c == 0x0c) { i++; continue; }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                while (i < text.length && text[i] != '\n' && text[i] != '\r') i++;
                continue;
            }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                i += 2;
                while (i < text.length && !(text[i] == '*' && i + 1 < text.length && text[i + 1] == '/')) i++;
                i += 2;
                continue;
            }
            if (c == '\'' || c == '"') {
                i++;
                while (i < text.length && text[i] != c) i += text[i] == '\\' ? 2 : 1;
                i++;
                tokens.add(new int[] {'\'', 0, 0});
                continue;
            }
            if (c < 128 && (Character.isLetter(c) || c == '_')) {
                int start = i;
                while (i < text.length && text[i] < 128 && (Character.isLetterOrDigit(text[i]) || text[i] == '_')) i++;
                tokens.add(new int[] {0, start, i});
                continue;
            }
            if (c == ':' && i + 2 < text.length && text[i + 1] == ':' && text[i + 2] == '=') {
                tokens.add(new int[] {1, i, i + 3});
                i += 3;
                continue;
            }
            tokens.add(new int[] {c, i, i + 1});
            i++;
        }
        List<Region> regions = new ArrayList<>();
        int t = 0;
        while (t < tokens.size()) {
            int[] word = tokens.get(t);
            boolean header = word[0] == 0 && "grammar".equals(new String(text, word[1], word[2] - word[1]))
                && t + 2 < tokens.size() && tokens.get(t + 1)[0] == 0 && tokens.get(t + 2)[0] == '{';
            if (!header) { t++; continue; }
            int start = t + 3, end = t + 2, depth = 0;
            while (end < tokens.size()) {
                int kind = tokens.get(end)[0];
                if (kind == '{') depth++;
                else if (kind == '}' && --depth == 0) break;
                end++;
            }
            int last = Math.min(end, tokens.size());
            int from = tokens.get(t)[1];
            int to = end < tokens.size() ? tokens.get(end)[2] : text.length;
            regions.add(new Region(from, to, definitions(text, tokens.subList(start, last))));
            t = end + 1;
        }
        return regions;
    }

    static boolean any(List<Region> regions) {
        for (Region region : regions) if (!region.names().isEmpty()) return true;
        return false;
    }

    private static Set<String> definitions(int[] text, List<int[]> tokens) {
        Set<String> names = new HashSet<>();
        for (int t = 0; t + 1 < tokens.size(); t++) {
            int[] name = tokens.get(t), open = tokens.get(t + 1);
            if (name[0] != 0 || open[0] != '(' || open[1] != name[2]) continue;
            int j = t + 2;
            boolean ok = true;
            while (true) {
                if (j < tokens.size() && tokens.get(j)[0] == '=') j++;
                if (j >= tokens.size() || tokens.get(j)[0] != 0) { ok = false; break; }
                j++;
                if (j < tokens.size() && tokens.get(j)[0] == ',') j++;
                else break;
            }
            if (ok && j + 1 < tokens.size() && tokens.get(j)[0] == ')' && tokens.get(j + 1)[0] == 1) {
                names.add(new String(text, name[1], name[2] - name[1]));
            }
        }
        return names;
    }

    // =========================================================================
    // Expansion
    // =========================================================================

    static UBNFFile expand(UBNFFile file, IdentityHashMap<RuleDecl, List<Param>> params,
            List<Call> calls, UBNFSourceSnapshot.Builder origins) {
        if (calls.isEmpty() && params.isEmpty()) return file;
        List<GrammarDecl> grammars = new ArrayList<>();
        boolean changed = false;
        for (GrammarDecl grammar : file.grammars()) {
            GrammarDecl expanded = new Grammar(grammar, params, calls, origins).expand();
            changed |= expanded != grammar;
            grammars.add(expanded);
        }
        if (!changed) return file;
        return copy(origins, new UBNFFile(List.copyOf(grammars)), file);
    }

    private static <T> T copy(UBNFSourceSnapshot.Builder origins, T node, Object from) {
        var origin = origins.origins.get(from);
        if (origin != null) origins.origins.put(node, origin);
        return node;
    }

    private static <T> T synthetic(UBNFSourceSnapshot.Builder origins, T node, Object from) {
        return origins.derive(node, from, UBNFSourceSnapshot.Kind.SYNTHETIC);
    }

    /**
     * For a rule with value parameters, {@code captures} names each value parameter's
     * binding (null for a by-name parameter) and {@code template} is the body
     * {@code CAPTURE_SCOPE(CAPTURE(F(=p), <hidden p>) ... body)} that instances expand.
     */
    private record Def(int rule, List<String> params, List<String> captures, RuleBody template) {}

    /**
     * A substituted argument with its measured size and nesting (never re-walked).
     * {@code atUse}: a value parameter's SAME_AS, which takes the origin of each use.
     */
    private record Rep(AtomicElement element, long size, long depth, boolean atUse, Found capture) {}

    /** An argument CAPTURE (#704): its name and span. */
    private record Found(String name, UBNFSourceSnapshot.Span span) {}

    private static long saturating(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }

    private static final class Grammar {
        final GrammarDecl grammar;
        final List<RuleDecl> rules;
        final IdentityHashMap<RuleDecl, List<String>> params = new IdentityHashMap<>();
        final IdentityHashMap<RuleDecl, List<Param>> declared;
        final List<Call> calls;
        final UBNFSourceSnapshot.Builder origins;
        final Map<String, Def> defs = new HashMap<>();
        final Set<String> known = new HashSet<>();
        final List<List<Object[]>> edges = new ArrayList<>(); // {target rule index, span}
        // expansion state
        final List<RuleDecl> instances = new ArrayList<>();
        final Map<String, String> byKey = new HashMap<>();
        final Set<String> names = new HashSet<>();
        /** Atomic elements materialized so far: call arguments and instance bodies. */
        long total;
        /** Rule bodies open in the expansion recursion, across instance boundaries. */
        int level;
        /** Code-point spans of the user CAPTUREs written inside call arguments (#704). */
        final Set<UBNFSourceSnapshot.Span> argCaptures = new HashSet<>();
        /** Rules with value parameters whose call arguments are being expanded, innermost last. */
        final List<Integer> scoped = new ArrayList<>();
        /** Per instance, the first argument CAPTURE its body reaches (null value: none). */
        final Map<String, Found> instanceCaptures = new HashMap<>();

        Grammar(GrammarDecl grammar, IdentityHashMap<RuleDecl, List<Param>> declared, List<Call> calls,
                UBNFSourceSnapshot.Builder origins) {
            this.grammar = grammar;
            this.rules = grammar.rules();
            this.declared = declared;
            for (RuleDecl rule : rules) {
                List<Param> list = declared.get(rule);
                if (list != null) params.put(rule, list.stream().map(Param::name).toList());
            }
            this.calls = calls;
            this.origins = origins;
        }

        UBNFSourceSnapshot.Span span(Object node) {
            var origin = origins.origins.get(node);
            return origin == null ? new UBNFSourceSnapshot.Span(0, 0) : origin.span();
        }

        ExpansionException error(UBNFSourceSnapshot.Span span, String message) {
            int[] at = lineColumn(origins.source, span.start());
            return new ExpansionException(message, span.start(), span.end(), at[0], at[1]);
        }

        String position(UBNFSourceSnapshot.Span span) {
            int[] at = lineColumn(origins.source, span.start());
            return "line " + at[0] + ", column " + at[1];
        }

        GrammarDecl expand() {
            boolean any = false;
            for (RuleDecl rule : rules) any |= params.containsKey(rule);
            if (!any && calls.isEmpty()) return grammar;
            Set<String> tokens = new HashSet<>();
            for (TokenDecl token : grammar.tokens()) tokens.add(token.name());
            Map<String, Integer> ruleCounts = new HashMap<>();
            for (RuleDecl rule : rules) ruleCounts.merge(rule.name(), 1, Integer::sum);
            for (int index = 0; index < rules.size(); index++) {
                RuleDecl rule = rules.get(index);
                List<String> list = params.get(rule);
                if (list == null) continue;
                String name = rule.name();
                if (RESERVED.contains(name)) throw error(span(rule),
                    "E-PARAM-RESERVED: parameterized rule name " + name + " is reserved by a builtin call");
                if (!rule.annotations().isEmpty()) throw error(span(rule),
                    "E-PARAM-ANNOTATION: parameterized rule " + name
                        + " cannot have annotations; only transparent rules take parameters");
                if (tokens.contains(name) || ruleCounts.get(name) > 1) throw error(span(rule), "E-PARAM-DUPLICATE: rule " + name + " is defined more than once");
                Set<String> seen = new HashSet<>();
                for (String parameter : list) {
                    if (!seen.add(parameter)) throw error(span(rule),
                        "E-PARAM-DUPLICATE: parameter " + parameter + " is declared more than once in rule " + name);
                    if (tokens.contains(parameter) || ruleCounts.containsKey(parameter)) throw error(span(rule), "E-PARAM-SHADOW: parameter " + parameter + " of rule " + name
                        + " has the same name as a rule or token");
                }
                List<Param> parameters = declared.get(rule);
                if (parameters.stream().anyMatch(Param::byValue)) {
                    List<String> captures = new ArrayList<>();
                    for (Param parameter : parameters) captures.add(parameter.byValue() ? captureName(name, parameter.name()) : null);
                    defs.put(name, new Def(index, list, java.util.Collections.unmodifiableList(captures), valueTemplate(rule, captures)));
                } else {
                    defs.put(name, new Def(index, list, null, null));
                }
            }
            known.addAll(tokens);
            for (RuleDecl rule : rules) known.add(rule.name());
            for (int index = 0; index < rules.size(); index++) edges.add(new ArrayList<>());
            for (int index = 0; index < rules.size(); index++) {
                RuleDecl rule = rules.get(index);
                Def def = defs.get(rule.name());
                boolean owner = def != null && def.rule() == index;
                List<String> list = params.get(rule);
                // The value parameter names of a definition that owns its name (#704).
                List<String> values = new ArrayList<>();
                if (owner && def.captures() != null)
                    for (int i = 0; i < def.params().size(); i++) if (def.captures().get(i) != null) values.add(def.params().get(i));
                checkBody(rule.body(), new Scope(owner ? index : -1, list == null ? List.of() : list, null, span(rule),
                    index, values.isEmpty() ? -1 : index, values));
            }
            rejectCycles();

            for (Call call : calls) for (RuleBody argument : call.args()) collectCaptures(argument);
            Map<String, Rep> empty = Map.of();
            List<RuleDecl> expanded = new ArrayList<>();
            for (RuleDecl rule : rules) {
                if (params.containsKey(rule)) continue;
                RuleBody body = body(rule.body(), empty);
                expanded.add(body == rule.body() ? rule
                    : copy(origins, new RuleDecl(rule.annotations(), rule.name(), body), rule));
            }
            // A written reference that only an expansion name satisfies would reach the
            // instance (and its value bindings) from outside the call that created it.
            for (Object[] reference : unknown) {
                String name = (String) reference[1];
                if (names.contains(name)) throw error((UBNFSourceSnapshot.Span) reference[2], "E-PARAM-UNDEFINED: rule "
                    + rules.get((Integer) reference[0]).name() + " references '" + name
                    + "', which only names a rule created by parameterized expansion");
            }
            expanded.addAll(instances);
            return copy(origins, new GrammarDecl(grammar.name(), grammar.imports(), grammar.settings(),
                grammar.tokens(), List.copyOf(expanded)), grammar);
        }

        /**
         * {@code scoped}: the rule with value parameters whose CAPTURE_SCOPE will enclose this
         * position (the definition being walked, or the callee whose arguments are walked);
         * {@code values}: the value parameter names of the definition being walked (#704).
         */
        record Scope(int owner, List<String> params, UBNFSourceSnapshot.Span call, UBNFSourceSnapshot.Span rule,
                int index, int scoped, List<String> values) {}

        /** Undefined references outside parameterized definitions: {rule index, name, span}. */
        final List<Object[]> unknown = new ArrayList<>();

        void checkBody(RuleBody body, Scope scope) {
            for (SequenceBody sequence : alternatives(body)) {
                for (AnnotatedElement element : sequence.elements()) {
                    if ((scope.owner() >= 0 || scope.call() != null)
                            && (element.captureName().isPresent() || element.typeofConstraint().isPresent())) {
                        throw error(scope.call() != null ? scope.call() : scope.rule(),
                            "E-PARAM-CAPTURE: captures and @typeof are not allowed in parameterized rule bodies or call arguments");
                    }
                    checkAtomic(element.element(), scope);
                }
            }
        }

        void checkAtomic(AtomicElement element, Scope scope) {
            if (element instanceof RuleRefElement ref) {
                if (ref.namespace().isPresent()) return;
                int index = callIndex(ref);
                String name = ref.name();
                if (index >= 0) {
                    Call call = calls.get(index);
                    var at = span(ref);
                    Def def = defs.get(call.name());
                    if (def == null) throw error(at, "E-PARAM-UNDEFINED: parameterized rule " + call.name()
                        + " is not defined in grammar " + grammar.name());
                    if (def.params().size() != call.args().size()) throw error(at, "E-PARAM-ARITY: rule " + call.name()
                        + " expects " + def.params().size() + " argument(s), got " + call.args().size()
                        + "; defined at " + position(span(rules.get(def.rule()))));
                    if (scope.owner() >= 0) edges.get(scope.owner()).add(new Object[] {def.rule(), at});
                    Scope inner = new Scope(scope.owner(), scope.params(), at, scope.rule(), scope.index(),
                        def.captures() != null ? def.rule() : scope.scoped(), scope.values());
                    for (RuleBody argument : call.args()) checkBody(argument, inner);
                } else if (scope.params().contains(name)) {
                    return;
                } else if (defs.containsKey(name)) {
                    Def def = defs.get(name);
                    throw error(span(ref), "E-PARAM-ARITY: rule " + name + " expects " + def.params().size()
                        + " argument(s), got 0; defined at " + position(span(rules.get(def.rule()))));
                } else if (scope.owner() >= 0 && !known.contains(name)) {
                    throw error(span(ref), "E-PARAM-UNDEFINED: rule " + rules.get(scope.owner()).name()
                        + " references undefined parameter, rule or token '" + name + "'");
                } else if (scope.owner() < 0 && !known.contains(name)) {
                    unknown.add(new Object[] {scope.index(), name, span(ref)});
                }
            } else if (element instanceof ReplayElement replay && scope.values().contains(replay.name())) {
                throw error(span(replay), "E-PARAM-SHADOW: SAME_AS(" + replay.name() + ") in rule "
                    + rules.get(scope.index()).name() + " names value parameter " + replay.name() + "; write "
                    + replay.name() + " to replay its value");
            } else if (element instanceof OwnedCaptureElement capture && scope.scoped() >= 0) {
                throw error(span(capture), "E-PARAM-CAPTURE: CAPTURE(" + capture.name()
                    + ", ...) cannot appear in the body or call arguments of rule " + rules.get(scope.scoped()).name()
                    + ", whose value parameters restore every capture when it returns");
            } else if (element instanceof UBNFAST.TransparentElement transparent) {
                checkBody(transparent.body(), scope);
            } else if (element instanceof OptionalElement optional) {
                checkBody(optional.body(), scope);
            } else if (element instanceof RepeatElement repeat) {
                checkBody(repeat.body(), scope);
            } else if (element instanceof OneOrMoreElement more) {
                checkAtomic(more.body(), scope);
            } else if (element instanceof BoundedRepeatElement bounded) {
                checkAtomic(bounded.body(), scope);
            } else if (element instanceof SeparatedElement separated) {
                checkAtomic(separated.element(), scope);
                checkAtomic(separated.separator(), scope);
            }
        }

        /** Depth-first in definition order; edges keep body traversal order (as in Rust). */
        void rejectCycles() {
            int[] state = new int[rules.size()];
            List<Integer> roots = new ArrayList<>();
            for (Def def : defs.values()) roots.add(def.rule());
            roots.sort(null);
            for (int root : roots) {
                if (state[root] != 0) continue;
                state[root] = 1;
                List<int[]> stack = new ArrayList<>();
                stack.add(new int[] {root, 0});
                while (!stack.isEmpty()) {
                    int[] top = stack.get(stack.size() - 1);
                    int node = top[0];
                    if (top[1] == edges.get(node).size()) {
                        state[node] = 2;
                        stack.remove(stack.size() - 1);
                        continue;
                    }
                    Object[] edge = edges.get(node).get(top[1]++);
                    int target = (Integer) edge[0];
                    if (state[target] == 0) {
                        state[target] = 1;
                        stack.add(new int[] {target, 0});
                    } else if (state[target] == 1) {
                        int from = 0;
                        while (stack.get(from)[0] != target) from++;
                        List<String> path = new ArrayList<>();
                        for (int k = from; k < stack.size(); k++) path.add(rules.get(stack.get(k)[0]).name());
                        path.add(rules.get(target).name());
                        throw error((UBNFSourceSnapshot.Span) edge[1], "E-PARAM-CYCLE: parameterized call cycle "
                            + String.join(" -> ", path) + "; expansion would not terminate");
                    }
                }
            }
        }

        RuleBody body(RuleBody body, Map<String, Rep> env) {
            // An error aborts the whole expansion, so the level is restored only on success.
            level++;
            RuleBody expanded = expandBody(body, env);
            level--;
            return expanded;
        }

        private RuleBody expandBody(RuleBody body, Map<String, Rep> env) {
            if (body instanceof SequenceBody sequence) return sequence(sequence, env);
            ChoiceBody choice = (ChoiceBody) body;
            List<SequenceBody> alternatives = new ArrayList<>();
            boolean changed = false;
            for (SequenceBody sequence : choice.alternatives()) {
                SequenceBody expanded = sequence(sequence, env);
                changed |= expanded != sequence;
                alternatives.add(expanded);
            }
            return changed ? copy(origins, new ChoiceBody(List.copyOf(alternatives)), choice) : choice;
        }

        SequenceBody sequence(SequenceBody sequence, Map<String, Rep> env) {
            List<AnnotatedElement> elements = new ArrayList<>();
            boolean changed = false;
            for (AnnotatedElement element : sequence.elements()) {
                AtomicElement atomic = atomic(element.element(), env);
                if (atomic == element.element()) {
                    elements.add(element);
                    continue;
                }
                changed = true;
                AnnotatedElement rebuilt = copy(origins,
                    new AnnotatedElement(atomic, element.captureName(), element.typeofConstraint()), element);
                var capture = origins.captures.get(element);
                if (capture != null) origins.captures.put(rebuilt, capture);
                elements.add(rebuilt);
            }
            return changed ? copy(origins, new SequenceBody(List.copyOf(elements)), sequence) : sequence;
        }

        AtomicElement atomic(AtomicElement element, Map<String, Rep> env) {
            if (element instanceof RuleRefElement ref) {
                if (ref.namespace().isPresent()) return ref;
                int index = callIndex(ref);
                if (index >= 0) {
                    Call call = calls.get(index);
                    // Bound the arguments before materializing them or their key: a shared
                    // callee can still receive exponentially growing arguments.
                    long size = 0, nesting = 0;
                    for (RuleBody argument : call.args()) {
                        long[] measured = measureBody(argument, env);
                        size = saturating(size, measured[0]);
                        nesting = Math.max(nesting, measured[1]);
                    }
                    long inline = level + nesting;
                    if (inline > MAX_EXPANDED_NESTING) throw error(span(ref), "E-PARAM-LIMIT: argument of rule "
                        + call.name() + " nests " + inline + " levels; the limit is " + MAX_EXPANDED_NESTING);
                    charge(size, span(ref));
                    Def callee = defs.get(call.name());
                    boolean valueScope = callee.captures() != null;
                    if (valueScope) scoped.add(callee.rule());
                    List<RuleBody> args = new ArrayList<>();
                    for (RuleBody argument : call.args()) args.add(body(argument, env));
                    if (valueScope) scoped.remove(scoped.size() - 1);
                    return copy(origins, new RuleRefElement(instance(call.name(), args, span(ref))), ref);
                }
                Rep replacement = env.get(ref.name());
                // A by-name argument relayed into the arguments of a rule with value parameters
                // would run inside its CAPTURE_SCOPE (#704).
                if (replacement != null && !scoped.isEmpty() && replacement.capture() != null)
                    throw scopedCapture(replacement.capture(), rules.get(scoped.get(scoped.size() - 1)).name());
                if (replacement != null && replacement.atUse())
                    return synthetic(origins, new ReplayElement(((ReplayElement) replacement.element()).name()), ref);
                return replacement != null ? replacement.element() : ref;
            }
            if (element instanceof GroupElement group) {
                RuleBody body = body(group.body(), env);
                return body == group.body() ? group : copy(origins, new GroupElement(body), group);
            }
            if (element instanceof OwnedCaptureElement capture) {
                RuleBody body = body(capture.body(), env);
                return body == capture.body() ? capture : copy(origins, new OwnedCaptureElement(capture.name(), body), capture);
            }
            if (element instanceof CaptureScopeElement scope) {
                RuleBody body = body(scope.body(), env);
                return body == scope.body() ? scope : copy(origins, new CaptureScopeElement(body), scope);
            }
            if (element instanceof OptionalElement optional) {
                RuleBody body = body(optional.body(), env);
                return body == optional.body() ? optional : copy(origins, new OptionalElement(body), optional);
            }
            if (element instanceof RepeatElement repeat) {
                RuleBody body = body(repeat.body(), env);
                return body == repeat.body() ? repeat : copy(origins, new RepeatElement(body), repeat);
            }
            if (element instanceof OneOrMoreElement more) {
                AtomicElement inner = atomic(more.body(), env);
                return inner == more.body() ? more : copy(origins, new OneOrMoreElement(inner), more);
            }
            if (element instanceof BoundedRepeatElement bounded) {
                AtomicElement inner = atomic(bounded.body(), env);
                return inner == bounded.body() ? bounded
                    : copy(origins, new BoundedRepeatElement(inner, bounded.min(), bounded.max()), bounded);
            }
            if (element instanceof SeparatedElement separated) {
                AtomicElement item = atomic(separated.element(), env);
                AtomicElement separator = atomic(separated.separator(), env);
                return item == separated.element() && separator == separated.separator() ? separated
                    : copy(origins, new SeparatedElement(item, separator), separated);
            }
            return element;
        }

        ExpansionException scopedCapture(Found found, String rule) {
            return error(found.span(), "E-PARAM-CAPTURE: CAPTURE(" + found.name()
                + ", ...) cannot appear in the body or call arguments of rule " + rule
                + ", whose value parameters restore every capture when it returns");
        }

        void collectCaptures(RuleBody body) {
            for (SequenceBody sequence : alternatives(body))
                for (AnnotatedElement element : sequence.elements()) collectCaptures(element.element());
        }

        void collectCaptures(AtomicElement element) {
            if (element instanceof OwnedCaptureElement capture) {
                argCaptures.add(span(capture));
                collectCaptures(capture.body());
            } else if (element instanceof UBNFAST.TransparentElement transparent) collectCaptures(transparent.body());
            else if (element instanceof OptionalElement optional) collectCaptures(optional.body());
            else if (element instanceof RepeatElement repeat) collectCaptures(repeat.body());
            else if (element instanceof OneOrMoreElement more) collectCaptures(more.body());
            else if (element instanceof BoundedRepeatElement bounded) collectCaptures(bounded.body());
            else if (element instanceof SeparatedElement separated) {
                collectCaptures(separated.element());
                collectCaptures(separated.separator());
            }
        }

        Found firstCapture(RuleBody body) {
            for (SequenceBody sequence : alternatives(body))
                for (AnnotatedElement element : sequence.elements()) {
                    Found found = firstCapture(element.element());
                    if (found != null) return found;
                }
            return null;
        }

        /**
         * The first argument CAPTURE in traversal order, entering referenced instances through
         * their recorded result (ordinary rules are not entered), as in Rust.
         */
        Found firstCapture(AtomicElement element) {
            if (element instanceof OwnedCaptureElement capture) {
                if (argCaptures.contains(span(capture))) return new Found(capture.name(), span(capture));
                return firstCapture(capture.body());
            }
            if (element instanceof RuleRefElement ref) return ref.namespace().isPresent() ? null : instanceCaptures.get(ref.name());
            if (element instanceof UBNFAST.TransparentElement transparent) return firstCapture(transparent.body());
            if (element instanceof OptionalElement optional) return firstCapture(optional.body());
            if (element instanceof RepeatElement repeat) return firstCapture(repeat.body());
            if (element instanceof OneOrMoreElement more) return firstCapture(more.body());
            if (element instanceof BoundedRepeatElement bounded) return firstCapture(bounded.body());
            if (element instanceof SeparatedElement separated) {
                Found found = firstCapture(separated.element());
                return found != null ? found : firstCapture(separated.separator());
            }
            return null;
        }

        void charge(long size, UBNFSourceSnapshot.Span at) {
            total = saturating(total, size);
            if (total > MAX_EXPANDED_ELEMENTS) throw error(at,
                "E-PARAM-LIMIT: expanded parameterized rules exceed " + MAX_EXPANDED_ELEMENTS + " elements");
        }

        String instance(String callee, List<RuleBody> args, UBNFSourceSnapshot.Span at) {
            StringBuilder key = new StringBuilder(callee).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) key.append(',');
                writeBody(key, args.get(i));
            }
            key.append(')');
            String existing = byKey.get(key.toString());
            if (existing != null) return existing;
            String name = callee + "_" + String.format("%016x", fnv1a64(key.toString()));
            if (known.contains(name) || names.contains(name)) throw error(at, "E-PARAM-NAME-COLLISION: expanded rule name "
                + name + " collides with another rule, token or expansion");
            Def def = defs.get(callee);
            RuleDecl rule = rules.get(def.rule());
            Map<String, Rep> env = new HashMap<>();
            for (int i = 0; i < args.size(); i++) {
                AtomicElement element = replacement(args.get(i));
                long[] measured = measureAtomic(element, Map.of());
                Rep argument = new Rep(element, measured[0], measured[1], false, firstCapture(element));
                String capture = def.captures() == null ? null : def.captures().get(i);
                if (capture == null) {
                    env.put(def.params().get(i), argument);
                } else {
                    // The argument goes into the CAPTURE; every use of the parameter replays it.
                    env.put(VALUE_PREFIX + i, argument);
                    env.put(def.params().get(i), new Rep(new ReplayElement(capture), 1, 0, true, null));
                }
            }
            RuleBody template = def.template() == null ? rule.body() : def.template();
            long[] measured = measureBody(template, env);
            long inline = level + measured[1];
            if (inline > MAX_EXPANDED_NESTING) throw error(at, "E-PARAM-LIMIT: expansion of rule " + callee
                + " nests " + inline + " levels; the limit is " + MAX_EXPANDED_NESTING);
            charge(measured[0], at);
            byKey.put(key.toString(), name);
            names.add(name);
            int slot = instances.size();
            instances.add(null);
            RuleBody body = body(template, env);
            instanceCaptures.put(name, firstCapture(body));
            instances.set(slot, synthetic(origins, new RuleDecl(List.of(), name, body), rule));
            return name;
        }

        /**
         * {@code CAPTURE_SCOPE(CAPTURE(F(=p), <hidden p>) ... body)}, written once per definition
         * so that measuring and expanding an instance treat it as an ordinary body (as in Rust).
         * A one-alternative body is spliced after the captures, a choice is grouped. Every
         * synthetic node takes the definition body's span with a SYNTHETIC origin.
         */
        RuleBody valueTemplate(RuleDecl rule, List<String> captures) {
            RuleBody from = rule.body();
            List<AnnotatedElement> elements = new ArrayList<>();
            for (int i = 0; i < captures.size(); i++) {
                if (captures.get(i) == null) continue;
                AtomicElement hidden = synthetic(origins, new RuleRefElement(VALUE_PREFIX + i), from);
                elements.add(plain(synthetic(origins, new OwnedCaptureElement(captures.get(i), sequence(plain(hidden, from), from)), from), from));
            }
            List<SequenceBody> alternatives = alternatives(from);
            if (alternatives.size() == 1) elements.addAll(alternatives.get(0).elements());
            else elements.add(plain(synthetic(origins, new GroupElement(from), from), from));
            ChoiceBody scope = synthetic(origins, new ChoiceBody(List.of(synthetic(origins,
                new SequenceBody(List.copyOf(elements)), from))), from);
            return sequence(plain(synthetic(origins, new CaptureScopeElement(scope), from), from), from);
        }

        private AnnotatedElement plain(AtomicElement element, Object from) {
            return synthetic(origins, new AnnotatedElement(element, java.util.Optional.empty(), java.util.Optional.empty()), from);
        }

        private ChoiceBody sequence(AnnotatedElement element, Object from) {
            return synthetic(origins, new ChoiceBody(List.of(synthetic(origins, new SequenceBody(List.of(element)), from))), from);
        }

        /** A single plain element is substituted as itself; anything else keeps its grouping. */
        AtomicElement replacement(RuleBody argument) {
            List<SequenceBody> alternatives = alternatives(argument);
            if (alternatives.size() == 1 && alternatives.get(0).elements().size() == 1) {
                AnnotatedElement element = alternatives.get(0).elements().get(0);
                if (element.captureName().isEmpty() && element.typeofConstraint().isEmpty()) return element.element();
            }
            return synthetic(origins, new GroupElement(argument), argument);
        }
    }

    private static List<SequenceBody> alternatives(RuleBody body) {
        return body instanceof ChoiceBody choice ? choice.alternatives() : List.of((SequenceBody) body);
    }

    /**
     * {atomic elements, nesting in bodies} after substitution, saturating and identical to the
     * Rust measure. Calls count as one element (they become references); their arguments are
     * measured at the call.
     */
    private static long[] measureBody(RuleBody body, Map<String, Rep> env) {
        long size = 0, depth = 0;
        for (SequenceBody sequence : alternatives(body)) {
            for (AnnotatedElement element : sequence.elements()) {
                long[] measured = measureAtomic(element.element(), env);
                size = saturating(size, measured[0]);
                depth = Math.max(depth, measured[1]);
            }
        }
        return new long[] {size, depth + 1};
    }

    private static long[] measureAtomic(AtomicElement element, Map<String, Rep> env) {
        if (element instanceof RuleRefElement ref) {
            if (ref.namespace().isEmpty() && callIndex(ref) < 0 && env.containsKey(ref.name())) {
                Rep rep = env.get(ref.name());
                return new long[] {rep.size(), rep.depth()};
            }
            return new long[] {1, 0};
        }
        RuleBody body = element instanceof UBNFAST.TransparentElement transparent ? transparent.body()
            : element instanceof OptionalElement optional ? optional.body()
            : element instanceof RepeatElement repeat ? repeat.body() : null;
        if (body != null) {
            long[] measured = measureBody(body, env);
            return new long[] {saturating(measured[0], 1), measured[1]};
        }
        AtomicElement inner = element instanceof OneOrMoreElement more ? more.body()
            : element instanceof BoundedRepeatElement bounded ? bounded.body() : null;
        if (inner != null) {
            long[] measured = measureAtomic(inner, env);
            return new long[] {saturating(measured[0], 1), measured[1]};
        }
        if (element instanceof SeparatedElement separated) {
            long[] a = measureAtomic(separated.element(), env), b = measureAtomic(separated.separator(), env);
            return new long[] {saturating(saturating(a[0], b[0]), 1), Math.max(a[1], b[1])};
        }
        return new long[] {1, 0};
    }

    // =========================================================================
    // Canonical argument spelling (docs/parameterized-rules.md)
    // =========================================================================

    private static void writeBody(StringBuilder out, RuleBody body) {
        List<SequenceBody> alternatives = alternatives(body);
        for (int i = 0; i < alternatives.size(); i++) {
            if (i > 0) out.append('|');
            List<AnnotatedElement> elements = alternatives.get(i).elements();
            for (int j = 0; j < elements.size(); j++) {
                if (j > 0) out.append(' ');
                writeAtomic(out, elements.get(j).element());
            }
        }
    }

    private static void writeCodePoints(StringBuilder out, String text) {
        int[] points = text.codePoints().toArray();
        for (int i = 0; i < points.length; i++) {
            if (i > 0) out.append('.');
            out.append(Integer.toHexString(points[i]));
        }
    }

    private static void wrap(StringBuilder out, String tag, RuleBody body) {
        out.append(tag).append('(');
        writeBody(out, body);
        out.append(')');
    }

    private static void writeAtomic(StringBuilder out, AtomicElement element) {
        if (element instanceof TerminalElement terminal) {
            out.append("t(");
            writeCodePoints(out, terminal.value());
            out.append(')');
        } else if (element instanceof ErrorElement error) {
            out.append("e(");
            writeCodePoints(out, error.message());
            out.append(')');
        } else if (element instanceof RuleRefElement ref) {
            out.append("r(");
            ref.namespace().ifPresent(namespace -> out.append(namespace).append('.'));
            out.append(ref.name()).append(')');
        } else if (element instanceof ReplayElement replay) {
            out.append("y(").append(replay.name()).append(')');
        } else if (element instanceof GroupElement group) {
            wrap(out, "g", group.body());
        } else if (element instanceof OptionalElement optional) {
            wrap(out, "o", optional.body());
        } else if (element instanceof RepeatElement repeat) {
            wrap(out, "z", repeat.body());
        } else if (element instanceof CaptureScopeElement scope) {
            wrap(out, "k", scope.body());
        } else if (element instanceof OwnedCaptureElement capture) {
            out.append("c(").append(capture.name()).append(',');
            writeBody(out, capture.body());
            out.append(')');
        } else if (element instanceof OneOrMoreElement more) {
            out.append("p(");
            writeAtomic(out, more.body());
            out.append(')');
        } else if (element instanceof BoundedRepeatElement bounded) {
            out.append("b(").append(bounded.min()).append(',');
            out.append(bounded.max() == BoundedRepeatElement.UNBOUNDED ? "*" : Integer.toString(bounded.max()));
            out.append(',');
            writeAtomic(out, bounded.body());
            out.append(')');
        } else if (element instanceof SeparatedElement separated) {
            out.append("s(");
            writeAtomic(out, separated.element());
            out.append(',');
            writeAtomic(out, separated.separator());
            out.append(')');
        } else {
            throw new IllegalStateException("unhandled element " + element);
        }
    }

    static long fnv1a64(String key) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /** One-based line and code-point column; CR, LF and CRLF are one line break each. */
    static int[] lineColumn(String source, int codePointOffset) {
        int line = 1, column = 1, index = 0;
        boolean cr = false;
        for (int point = 0; point < codePointOffset && index < source.length(); point++) {
            int c = source.codePointAt(index);
            index += Character.charCount(c);
            if (c == '\r') { line++; column = 1; }
            else if (c == '\n' && cr) { }
            else if (c == '\n') { line++; column = 1; }
            else column++;
            cr = c == '\r';
        }
        return new int[] {line, column};
    }
}
