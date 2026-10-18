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
 */
public final class ParameterizedRules {
    /** Total atomic elements of all expanded instances (the generators' 65536-visit limit). */
    public static final int MAX_EXPANDED_ELEMENTS = 65536;
    /** Nesting of one expanded instance body (the native frontend's nesting limit). */
    public static final int MAX_EXPANDED_NESTING = 128;
    static final Set<String> RESERVED = Set.of("CAPTURE", "SAME_AS", "CAPTURE_SCOPE", "ERROR");
    /** NUL never occurs in an identifier, so no user reference can match a placeholder. */
    private static final String CALL_PREFIX = "\0call";

    private ParameterizedRules() {}

    record Call(String name, List<RuleBody> args) {}

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

    /**
     * Names defined as {@code Name(params) ::=} (opening parenthesis adjacent) anywhere in
     * the file. Only these names make an adjacent {@code Name(} a call, so a grammar
     * without such definitions parses exactly as before.
     */
    static Set<String> prescan(String source) {
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
        Set<String> names = new HashSet<>();
        for (int t = 0; t + 1 < tokens.size(); t++) {
            int[] name = tokens.get(t), open = tokens.get(t + 1);
            if (name[0] != 0 || open[0] != '(' || open[1] != name[2]) continue;
            int j = t + 2;
            boolean ok = true;
            while (true) {
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

    static UBNFFile expand(UBNFFile file, IdentityHashMap<RuleDecl, List<String>> params,
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

    private record Def(int rule, List<String> params) {}

    private static final class Grammar {
        final GrammarDecl grammar;
        final List<RuleDecl> rules;
        final IdentityHashMap<RuleDecl, List<String>> params;
        final List<Call> calls;
        final UBNFSourceSnapshot.Builder origins;
        final Map<String, Def> defs = new HashMap<>();
        final Set<String> known = new HashSet<>();
        final List<List<Object[]>> edges = new ArrayList<>(); // {target rule index, span}
        // expansion state
        final List<RuleDecl> instances = new ArrayList<>();
        final Map<String, String> byKey = new HashMap<>();
        final Set<String> names = new HashSet<>();
        int total;

        Grammar(GrammarDecl grammar, IdentityHashMap<RuleDecl, List<String>> params, List<Call> calls,
                UBNFSourceSnapshot.Builder origins) {
            this.grammar = grammar;
            this.rules = grammar.rules();
            this.params = params;
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
                boolean duplicate = tokens.contains(name);
                for (int other = 0; other < rules.size(); other++)
                    duplicate |= other != index && rules.get(other).name().equals(name);
                if (duplicate) throw error(span(rule), "E-PARAM-DUPLICATE: rule " + name + " is defined more than once");
                for (int k = 0; k < list.size(); k++) {
                    String parameter = list.get(k);
                    if (list.subList(0, k).contains(parameter)) throw error(span(rule),
                        "E-PARAM-DUPLICATE: parameter " + parameter + " is declared more than once in rule " + name);
                    boolean shadow = tokens.contains(parameter);
                    for (RuleDecl other : rules) shadow |= other.name().equals(parameter);
                    if (shadow) throw error(span(rule), "E-PARAM-SHADOW: parameter " + parameter + " of rule " + name
                        + " has the same name as a rule or token");
                }
                defs.put(name, new Def(index, list));
            }
            known.addAll(tokens);
            for (RuleDecl rule : rules) known.add(rule.name());
            for (int index = 0; index < rules.size(); index++) edges.add(new ArrayList<>());
            for (int index = 0; index < rules.size(); index++) {
                RuleDecl rule = rules.get(index);
                Def def = defs.get(rule.name());
                boolean owner = def != null && def.rule() == index;
                List<String> list = params.get(rule);
                checkBody(rule.body(), new Scope(owner ? index : -1, list == null ? List.of() : list, null, span(rule)));
            }
            rejectCycles();

            Map<String, AtomicElement> empty = Map.of();
            List<RuleDecl> expanded = new ArrayList<>();
            for (RuleDecl rule : rules) {
                if (params.containsKey(rule)) continue;
                RuleBody body = body(rule.body(), empty);
                expanded.add(body == rule.body() ? rule
                    : copy(origins, new RuleDecl(rule.annotations(), rule.name(), body), rule));
            }
            expanded.addAll(instances);
            return copy(origins, new GrammarDecl(grammar.name(), grammar.imports(), grammar.settings(),
                grammar.tokens(), List.copyOf(expanded)), grammar);
        }

        record Scope(int owner, List<String> params, UBNFSourceSnapshot.Span call, UBNFSourceSnapshot.Span rule) {}

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
                    Scope inner = new Scope(scope.owner(), scope.params(), at, scope.rule());
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
                }
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

        RuleBody body(RuleBody body, Map<String, AtomicElement> env) {
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

        SequenceBody sequence(SequenceBody sequence, Map<String, AtomicElement> env) {
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

        AtomicElement atomic(AtomicElement element, Map<String, AtomicElement> env) {
            if (element instanceof RuleRefElement ref) {
                if (ref.namespace().isPresent()) return ref;
                int index = callIndex(ref);
                if (index >= 0) {
                    Call call = calls.get(index);
                    List<RuleBody> args = new ArrayList<>();
                    for (RuleBody argument : call.args()) args.add(body(argument, env));
                    return copy(origins, new RuleRefElement(instance(call.name(), args, span(ref))), ref);
                }
                AtomicElement replacement = env.get(ref.name());
                return replacement != null ? replacement : ref;
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
            Map<String, AtomicElement> env = new HashMap<>();
            for (int i = 0; i < args.size(); i++) env.put(def.params().get(i), replacement(args.get(i)));
            int[] measured = measureBody(rule.body(), env);
            if (measured[1] > MAX_EXPANDED_NESTING) throw error(at, "E-PARAM-LIMIT: expansion of rule " + callee
                + " nests " + measured[1] + " levels; the limit is " + MAX_EXPANDED_NESTING);
            total += measured[0];
            if (total > MAX_EXPANDED_ELEMENTS) throw error(at,
                "E-PARAM-LIMIT: expanded parameterized rules exceed " + MAX_EXPANDED_ELEMENTS + " elements");
            byKey.put(key.toString(), name);
            names.add(name);
            int slot = instances.size();
            instances.add(null);
            RuleBody body = body(rule.body(), env);
            instances.set(slot, synthetic(origins, new RuleDecl(List.of(), name, body), rule));
            return name;
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

    /** {size in atomic elements, nesting in bodies}, identical to the Rust measure. */
    private static int[] measureBody(RuleBody body, Map<String, AtomicElement> env) {
        int size = 0, depth = 0;
        for (SequenceBody sequence : alternatives(body)) {
            for (AnnotatedElement element : sequence.elements()) {
                int[] measured = measureAtomic(element.element(), env);
                size += measured[0];
                depth = Math.max(depth, measured[1]);
            }
        }
        return new int[] {size, depth + 1};
    }

    private static int[] measureAtomic(AtomicElement element, Map<String, AtomicElement> env) {
        if (element instanceof RuleRefElement ref) {
            if (ref.namespace().isEmpty() && callIndex(ref) < 0 && env.containsKey(ref.name()))
                return measureAtomic(env.get(ref.name()), Map.of());
            return new int[] {1, 0};
        }
        RuleBody body = element instanceof UBNFAST.TransparentElement transparent ? transparent.body()
            : element instanceof OptionalElement optional ? optional.body()
            : element instanceof RepeatElement repeat ? repeat.body() : null;
        if (body != null) {
            int[] measured = measureBody(body, env);
            return new int[] {measured[0] + 1, measured[1]};
        }
        AtomicElement inner = element instanceof OneOrMoreElement more ? more.body()
            : element instanceof BoundedRepeatElement bounded ? bounded.body() : null;
        if (inner != null) {
            int[] measured = measureAtomic(inner, env);
            return new int[] {measured[0] + 1, measured[1]};
        }
        if (element instanceof SeparatedElement separated) {
            int[] a = measureAtomic(separated.element(), env), b = measureAtomic(separated.separator(), env);
            return new int[] {a[0] + b[0] + 1, Math.max(a[1], b[1])};
        }
        return new int[] {1, 0};
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
