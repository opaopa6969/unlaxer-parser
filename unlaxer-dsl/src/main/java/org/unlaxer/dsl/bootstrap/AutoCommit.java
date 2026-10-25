package org.unlaxer.dsl.bootstrap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Span;
import org.unlaxer.dsl.runtime.LexicalExpression;

/**
 * FIRST-set lint of cut-safe alternatives and the opt-in {@code @autoCommit} rewrite (#702).
 *
 * <p>The same algorithm runs in the native Rust frontend ({@code unlaxer_ubnf::auto_commit});
 * docs/auto-commit.md states it and argues why it is sound.
 *
 * <p>A candidate is an alternative {@code e1 e2 … en} (n ≥ 2) of a choice. Its remainder can be
 * committed when {@code e1} is not nullable and an ordinary failure of the alternative after
 * {@code e1} consumed a code point {@code c ∈ FIRST(e1)} cannot be followed by a successful
 * full-input parse: every later alternative must fail on {@code c}, and every construct enclosing
 * the choice that could resume parsing (choice, optional, repetition, separated list, the
 * continuation of a sequence, every reference site of the rule) must fail as well, up to the root
 * rule. Anything the analysis does not model makes the candidate ineligible.
 *
 * <p>The rewrite wraps every later element that is a plain rule or token reference as
 * {@code COMMIT(element)} (captures stay inside), which neither the Java nor the Rust CST sees. It
 * runs after parameterized rules are expanded and removes the annotation, so the rewritten AST
 * equals that of the grammar with the same {@code COMMIT} written by hand.
 *
 * <p>Under the grammar-wide built-in {@code @whitespace: javaStyle} (#728) the same rules apply with
 * code points read after the trivia that sequence boundaries skip; terminals and tokens whose FIRST
 * meets a code point that can start trivia (a space or {@code /}) are not modelled.
 */
public final class AutoCommit {
    private AutoCommit() {}

    /** The rule annotation that opts a rule into the rewrite. */
    public static final String ANNOTATION = "autoCommit";
    /** The code of every lint finding. */
    public static final String CODE = "I-AUTO-COMMIT";
    /** The code of the warning for an {@code @autoCommit} the analysis cannot use. */
    public static final String SKIPPED = "W-AUTO-COMMIT-SKIPPED";
    /** Walk states explored per candidate; a larger walk leaves the candidate ineligible. */
    public static final int STATE_BUDGET = 100_000;

    /** A wrapped reference and the code-point span of its element (empty without a snapshot). */
    public record Commit(String name, Optional<Span> span) {}

    /**
     * One cut-safe alternative. {@code span} is the alternative, {@code cut} the code point offset
     * right after its first element, {@code commits} the references {@code @autoCommit} wraps and
     * {@code inserted} whether the rule is annotated, so the frontend inserted them. Spans are
     * empty, and {@code cut} is -1, for an AST analysed without a source snapshot.
     */
    public record Finding(String rule, Optional<Span> span, int cut, List<Commit> commits, boolean inserted) {
        public Finding {
            commits = List.copyOf(commits);
        }

        public String code() { return CODE; }

        public String severity() { return "info"; }

        public String message() {
            return CODE + ": " + rule + ": alternative at CP " + span.map(s -> s.start() + ".." + s.end()).orElse("?")
                + " can commit " + String.join(", ", commits.stream().map(Commit::name).toList()) + " after CP " + cut;
        }
    }

    /**
     * An {@code @autoCommit} annotation that the frontend removes without inserting anything
     * because the analysis cannot apply to its rule. {@code span} is the annotation; {@code reason}
     * is {@code grammar} (outside the analysed subset), {@code opaque} or {@code unreachable}.
     */
    public record Warning(String rule, Optional<Span> span, String reason) {
        public String code() { return SKIPPED; }

        public String severity() { return "warning"; }

        public String message() {
            String why = switch (reason) {
                case "grammar" -> "the grammar is outside the analysed subset";
                case "opaque" -> "the rule has an annotation the analysis does not model";
                default -> "the rule is not reachable from the root";
            };
            return SKIPPED + ": " + rule + ": @autoCommit inserts nothing: " + why;
        }
    }

    /**
     * The source as written: parameterized rules expanded, but without the {@code @autoCommit}
     * rewrite. {@link #findings} and {@link #warnings} expect this tree; {@link UBNFMapper#parse}
     * returns the rewritten one, where the annotation is gone and inserted commits hide their
     * positions.
     */
    public static UBNFSourceSnapshot parseBeforeAutoCommit(String source) {
        return UBNFMapper.parseWithSourceBeforeAutoCommit(source);
    }

    /** The {@code W-AUTO-COMMIT-SKIPPED} warnings of every grammar of one source. */
    public static List<Warning> lintWarnings(String source) {
        UBNFSourceSnapshot snapshot = parseBeforeAutoCommit(source);
        List<Warning> out = new ArrayList<>();
        for (GrammarDecl grammar : snapshot.ast().grammars()) out.addAll(warnings(grammar, snapshot));
        return out;
    }

    /**
     * Whether {@code @autoCommit} analyses this grammar: it has an {@code @autoCommit} rule (pass the
     * tree of {@link #parseBeforeAutoCommit}) and is inside the analysed subset. Its other rules then
     * take part in the analysis unless an annotation makes them opaque (#715). The native
     * {@code auto_commit::analyses} is the same predicate.
     */
    public static boolean analyses(GrammarDecl grammar) {
        return grammar.rules().stream().anyMatch(AutoCommit::annotated) && Analysis.create(grammar) != null;
    }

    /**
     * One warning per {@code @autoCommit} that cannot insert anything, in rule order. Pass the tree
     * of {@link #parseBeforeAutoCommit}: the rewritten tree no longer has the annotation.
     */
    public static List<Warning> warnings(GrammarDecl grammar, UBNFSourceSnapshot snapshot) {
        Analysis analysis = Analysis.create(grammar);
        List<Warning> out = new ArrayList<>();
        for (int index = 0; index < grammar.rules().size(); index++) {
            RuleDecl rule = grammar.rules().get(index);
            for (Annotation annotation : rule.annotations()) {
                if (!(annotation instanceof SimpleAnnotation simple && simple.name().equals(ANNOTATION))) continue;
                String reason;
                if (analysis == null) reason = "grammar";
                else if (analysis.opaque[index]) reason = "opaque";
                else if (!analysis.reachable[index]) reason = "unreachable";
                else continue;
                out.add(new Warning(rule.name(), snapshot == null ? Optional.empty() : snapshot.spanOf(annotation), reason));
            }
        }
        return out;
    }

    /**
     * Lint every grammar of one source. The findings describe the source as written, before any
     * {@code @autoCommit} rewrite; positions are those of this single input.
     */
    public static List<Finding> lint(String source) {
        UBNFSourceSnapshot snapshot = parseBeforeAutoCommit(source);
        List<Finding> out = new ArrayList<>();
        for (GrammarDecl grammar : snapshot.ast().grammars()) out.addAll(findings(grammar, snapshot));
        return out;
    }

    /**
     * Findings of one expanded grammar, in rule order and then source order. Pass the tree of
     * {@link #parseBeforeAutoCommit}: in the rewritten tree the inserted commits are not modelled,
     * so its alternatives are no longer reported.
     */
    public static List<Finding> findings(GrammarDecl grammar, UBNFSourceSnapshot snapshot) {
        Analysis analysis = Analysis.create(grammar);
        if (analysis == null) return List.of();
        List<Finding> out = new ArrayList<>();
        for (Candidate candidate : analysis.candidates(rule -> true)) {
            RuleDecl rule = grammar.rules().get(candidate.rule);
            SequenceBody sequence = candidate.sequence;
            List<AnnotatedElement> elements = sequence.elements();
            List<Commit> commits = new ArrayList<>();
            for (int k : candidate.wraps) {
                commits.add(new Commit(((RuleRefElement) elements.get(k).element()).name(),
                    snapshot == null ? Optional.empty() : snapshot.spanOf(elements.get(k))));
            }
            int cut = snapshot == null ? -1 : snapshot.spanOf(elements.get(0)).map(Span::end).orElse(-1);
            out.add(new Finding(rule.name(), snapshot == null ? Optional.empty() : snapshot.spanOf(sequence), cut,
                commits, annotated(rule)));
        }
        return out;
    }

    static UBNFFile apply(UBNFFile file, UBNFSourceSnapshot.Builder origins) {
        List<GrammarDecl> grammars = new ArrayList<>();
        boolean changed = false;
        for (GrammarDecl grammar : file.grammars()) {
            GrammarDecl rewritten = apply(grammar, origins);
            changed |= rewritten != grammar;
            grammars.add(rewritten);
        }
        return changed ? copy(origins, new UBNFFile(List.copyOf(grammars)), file) : file;
    }

    /** Insert the commits of annotated rules and remove the annotation. */
    public static GrammarDecl apply(GrammarDecl grammar) {
        return apply(grammar, null);
    }

    static GrammarDecl apply(GrammarDecl grammar, UBNFSourceSnapshot.Builder origins) {
        if (grammar.rules().stream().noneMatch(AutoCommit::annotated)) return grammar;
        Analysis analysis = Analysis.create(grammar);
        // Only annotated rules are examined: the walk of each candidate is global.
        List<Candidate> plans = analysis == null ? List.of()
            : analysis.candidates(rule -> annotated(grammar.rules().get(rule)));
        List<RuleDecl> rules = new ArrayList<>();
        for (int index = 0; index < grammar.rules().size(); index++) {
            RuleDecl rule = grammar.rules().get(index);
            if (!annotated(rule)) {
                rules.add(rule);
                continue;
            }
            final int ruleIndex = index;
            List<Candidate> wraps = plans.stream().filter(c -> c.rule == ruleIndex).toList();
            RuleBody body = wraps.isEmpty() ? rule.body() : new Rewriter(wraps, origins).body(rule.body());
            List<Annotation> annotations = rule.annotations().stream()
                .filter(a -> !(a instanceof SimpleAnnotation simple && simple.name().equals(ANNOTATION))).toList();
            rules.add(copy(origins, new RuleDecl(annotations, rule.name(), body), rule));
        }
        return copy(origins, new GrammarDecl(grammar.name(), grammar.imports(), grammar.settings(), grammar.tokens(),
            List.copyOf(rules)), grammar);
    }

    static boolean annotated(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a -> a instanceof SimpleAnnotation simple && simple.name().equals(ANNOTATION));
    }

    private static <T> T copy(UBNFSourceSnapshot.Builder origins, T node, Object from) {
        return origins == null ? node : origins.copy(node, from);
    }

    /**
     * Pre-order body numbering identical to {@link Analysis#indexBody}. Commits are applied after
     * the children are visited, so the new COMMIT bodies are never numbered. Unchanged subtrees
     * keep their identity (and therefore their origin).
     */
    private static final class Rewriter {
        final List<Candidate> wraps;
        final UBNFSourceSnapshot.Builder origins;
        int ordinal;

        Rewriter(List<Candidate> wraps, UBNFSourceSnapshot.Builder origins) {
            this.wraps = wraps;
            this.origins = origins;
        }

        RuleBody body(RuleBody body) {
            int mine = ordinal++;
            List<SequenceBody> alternatives = alternatives(body);
            List<SequenceBody> rewritten = new ArrayList<>();
            boolean changed = false;
            for (int i = 0; i < alternatives.size(); i++) {
                SequenceBody sequence = alternatives.get(i);
                List<AnnotatedElement> elements = new ArrayList<>();
                boolean elementChanged = false;
                for (AnnotatedElement annotated : sequence.elements()) {
                    AtomicElement element = element(annotated.element());
                    if (element != annotated.element()) {
                        elementChanged = true;
                        elements.add(copy(origins, new AnnotatedElement(element, annotated.captureName(),
                            annotated.typeofConstraint()), annotated));
                    } else {
                        elements.add(annotated);
                    }
                }
                for (Candidate candidate : wraps) {
                    if (candidate.ordinal != mine || candidate.alternative != i) continue;
                    for (int k : candidate.wraps) {
                        elements.set(k, wrap(elements.get(k)));
                        elementChanged = true;
                    }
                }
                if (elementChanged) {
                    changed = true;
                    rewritten.add(copy(origins, new SequenceBody(List.copyOf(elements)), sequence));
                } else {
                    rewritten.add(sequence);
                }
            }
            if (!changed) return body;
            if (body instanceof SequenceBody) return rewritten.get(0);
            return copy(origins, new ChoiceBody(List.copyOf(rewritten)), body);
        }

        AnnotatedElement wrap(AnnotatedElement inner) {
            SequenceBody sequence = synthetic(new SequenceBody(List.of(inner)), inner);
            ChoiceBody body = synthetic(new ChoiceBody(List.of(sequence)), inner);
            CommitElement commit = synthetic(new CommitElement(body, Optional.empty()), inner);
            return synthetic(new AnnotatedElement(commit, Optional.empty(), Optional.empty()), inner);
        }

        <T> T synthetic(T node, Object from) {
            return origins == null ? node : origins.derive(node, from, UBNFSourceSnapshot.Kind.SYNTHETIC);
        }

        AtomicElement element(AtomicElement element) {
            AtomicElement result = element;
            if (element instanceof CatchElement caught) {
                RuleBody child = body(caught.child().body());
                RuleBody alternative = body(caught.alternative().body());
                if (child != caught.child().body() || alternative != caught.alternative().body()) {
                    result = new CatchElement(copy(origins, new GroupElement(child), caught.child()),
                        copy(origins, new GroupElement(alternative), caught.alternative()), caught.labels());
                }
            } else if (element instanceof GroupElement group) {
                RuleBody body = body(group.body());
                if (body != group.body()) result = new GroupElement(body);
            } else if (element instanceof OwnedCaptureElement capture) {
                RuleBody body = body(capture.body());
                if (body != capture.body()) result = new OwnedCaptureElement(capture.name(), body);
            } else if (element instanceof CaptureScopeElement scope) {
                RuleBody body = body(scope.body());
                if (body != scope.body()) result = new CaptureScopeElement(body);
            } else if (element instanceof CommitElement commit) {
                RuleBody body = body(commit.body());
                if (body != commit.body()) result = new CommitElement(body, commit.message());
            } else if (element instanceof LabelElement label) {
                RuleBody body = body(label.body());
                if (body != label.body()) result = new LabelElement(label.label(), body);
            } else if (element instanceof OptionalElement optional) {
                RuleBody body = body(optional.body());
                if (body != optional.body()) result = new OptionalElement(body);
            } else if (element instanceof RepeatElement repeat) {
                RuleBody body = body(repeat.body());
                if (body != repeat.body()) result = new RepeatElement(body);
            } else if (element instanceof OneOrMoreElement repeat) {
                AtomicElement inner = element(repeat.body());
                if (inner != repeat.body()) result = new OneOrMoreElement(inner);
            } else if (element instanceof BoundedRepeatElement repeat) {
                AtomicElement inner = element(repeat.body());
                if (inner != repeat.body()) result = BoundedQuantifiers.copy(new BoundedRepeatElement(inner, repeat.min(), repeat.max()), repeat);
            } else if (element instanceof SeparatedElement separated) {
                AtomicElement item = element(separated.element());
                AtomicElement separator = element(separated.separator());
                if (item != separated.element() || separator != separated.separator()) {
                    result = new SeparatedElement(item, separator);
                }
            }
            return result == element ? element : copy(origins, result, element);
        }
    }

    static List<SequenceBody> alternatives(RuleBody body) {
        return body instanceof ChoiceBody choice ? choice.alternatives() : List.of((SequenceBody) body);
    }

    /** ASCII exactly, everything above as one flag (the runtime FirstSet representation). */
    record Chars(long low, long high, boolean nonAscii) {
        static final Chars NONE = new Chars(0L, 0L, false);
        static final Chars ANY = new Chars(-1L, -1L, true);

        static Chars of(int codePoint) { return range(codePoint, codePoint); }

        static Chars range(int min, int max) {
            long low = 0L, high = 0L;
            for (int c = min; c <= max && c < 128; c++) {
                if (c < 64) low |= 1L << c;
                else high |= 1L << (c - 64);
            }
            return new Chars(low, high, max >= 128);
        }

        Chars union(Chars other) {
            return new Chars(low | other.low, high | other.high, nonAscii || other.nonAscii);
        }

        boolean meets(Chars other) {
            return (low & other.low) != 0L || (high & other.high) != 0L || nonAscii && other.nonAscii;
        }
    }

    /**
     * The code points a successful consuming match can start with, whether it can succeed without
     * consuming, and whether the construct is not modelled at all.
     */
    record First(Chars chars, boolean nullable, boolean unknown) {
        static final First BOTTOM = new First(Chars.NONE, false, false);
        static final First EPSILON = new First(Chars.NONE, true, false);
        static final First UNKNOWN = new First(Chars.ANY, true, true);

        static First consuming(Chars chars) { return new First(chars, false, false); }

        First orEmpty() { return new First(chars, true, unknown); }

        First choice(First other) {
            if (unknown || other.unknown) return UNKNOWN;
            return new First(chars.union(other.chars), nullable || other.nullable, false);
        }

        /** Chars at the start of a construct this prefix precedes. */
        Chars before(Chars after) { return nullable ? chars.union(after) : chars; }

        /** Whether the construct certainly fails at a position holding one of {@code chars}. */
        boolean excludes(Chars other) { return !unknown && !nullable && !chars.meets(other); }
    }

    private record Candidate(int rule, int ordinal, int alternative, SequenceBody sequence, List<Integer> wraps) {}

    private static final int BODY = 0, SEQ = 1, ELEM = 2;

    /** The space characters of javaStyle trivia (Java {@code SpaceParser}, Rust {@code skip}). */
    private static final String TRIVIA_SPACES = " \t\n\u000B\u000C\r";
    /** Code points that can start javaStyle trivia: its spaces and {@code /} of both comments. */
    static final Chars TRIVIA_START = Chars.of(' ').union(Chars.range('\t', '\r')).union(Chars.of('/'));

    /** Arena node. Parents are node ids; nothing is looked up by record identity or equality. */
    private static final class Node {
        final int kind;
        RuleBody body;
        SequenceBody sequence;
        AtomicElement element;
        /** BODY: the rule whose whole body this is, or -1 when {@code parentElem} owns it. */
        int rule = -1;
        /** BODY / ELEM: the enclosing element node, or -1. */
        int parentElem = -1;
        /** BODY: the rule that contains it and its pre-order number there. */
        int owner, ordinal;
        /** BODY: inside COMMIT, LABEL, CATCH, CAPTURE or CAPTURE_SCOPE. */
        boolean blocked;
        /** SEQ: the body node and the alternative index. */
        int parentBody = -1;
        /** ELEM: the sequence node and the element index, or the slot in {@code parentElem}. */
        int parentSeq = -1;
        int index, slot;
        /** BODY: its sequence nodes in alternative order. */
        final List<Integer> alternatives = new ArrayList<>();

        Node(int kind) { this.kind = kind; }
    }

    private record State(boolean next, int node, Chars chars) {}

    private static final class Analysis {
        final GrammarDecl grammar;
        final Map<String, Integer> rules = new HashMap<>();
        final Map<String, TokenDecl> tokens = new HashMap<>();
        final Set<String> ambiguous = new HashSet<>();
        final First[] table;
        final boolean[] opaque;
        final boolean[] reachable;
        final List<Node> nodes = new ArrayList<>();
        final Map<String, List<Integer>> sites = new HashMap<>();
        /** FIRST per element node (by identity), once {@code table} is final. */
        final Map<AtomicElement, First> memo = new java.util.IdentityHashMap<>();
        boolean frozen;
        /** The grammar skips javaStyle trivia at sequence boundaries (#728). */
        final boolean trivia;

        private Analysis(GrammarDecl grammar, boolean trivia) {
            this.grammar = grammar;
            this.trivia = trivia;
            table = new First[grammar.rules().size()];
            opaque = new boolean[grammar.rules().size()];
            reachable = new boolean[grammar.rules().size()];
        }

        /** {@code null} when the grammar is outside the modelled subset as a whole. */
        static Analysis create(GrammarDecl grammar) {
            if (!grammar.settings().stream().allMatch(Analysis::plainSetting)) return null;
            boolean ruleTrivia = grammar.rules().stream().flatMap(r -> r.annotations().stream())
                .anyMatch(a -> a instanceof WhitespaceAnnotation || a instanceof InterleaveAnnotation
                    || a instanceof LexicalContextAnnotation);
            if (ruleTrivia) return null;
            List<Integer> roots = new ArrayList<>();
            for (int r = 0; r < grammar.rules().size(); r++) {
                if (grammar.rules().get(r).annotations().stream().anyMatch(RootAnnotation.class::isInstance)) roots.add(r);
            }
            if (roots.size() != 1) return null;
            boolean spaced = grammar.settings().stream().anyMatch(setting -> setting.key().equals("whitespace")
                && setting.value() instanceof StringSettingValue value && javaStyle(value.value()));
            Analysis analysis = solve(grammar, spaced);
            // Left recursion is about the parser calling a rule again at the same position, so it
            // uses the FIRST sets of the input as written: a terminal that is unknown under trivia
            // (such as '/') still consumes (#728 review).
            if ((spaced ? solve(grammar, false) : analysis).leftRecursive()) return null;
            analysis.reach(roots.get(0));
            for (int index = 0; index < grammar.rules().size(); index++) {
                if (analysis.reachable[index]) {
                    analysis.indexBody(grammar.rules().get(index).body(), index, -1, index, new int[] {0}, false);
                }
            }
            return analysis;
        }

        /** Name tables and the least-fixpoint FIRST table, with or without the trivia rule. */
        private static Analysis solve(GrammarDecl grammar, boolean trivia) {
            Analysis analysis = new Analysis(grammar, trivia);
            for (int index = 0; index < grammar.rules().size(); index++) {
                if (analysis.rules.putIfAbsent(grammar.rules().get(index).name(), index) != null) {
                    analysis.ambiguous.add(grammar.rules().get(index).name());
                }
            }
            for (TokenDecl token : grammar.tokens()) {
                if (analysis.tokens.putIfAbsent(token.name(), token) != null || analysis.rules.containsKey(token.name())) {
                    analysis.ambiguous.add(token.name());
                }
            }
            for (int index = 0; index < grammar.rules().size(); index++) {
                analysis.opaque[index] = !grammar.rules().get(index).annotations().stream().allMatch(Analysis::transparent);
                analysis.table[index] = analysis.opaque[index] ? First.UNKNOWN : First.BOTTOM;
            }
            boolean changed = true;
            while (changed) {
                changed = false;
                for (int index = 0; index < grammar.rules().size(); index++) {
                    if (analysis.opaque[index]) continue;
                    First first = analysis.body(grammar.rules().get(index).body());
                    if (!first.equals(analysis.table[index])) {
                        analysis.table[index] = first;
                        changed = true;
                    }
                }
            }
            analysis.frozen = true;
            return analysis;
        }

        /**
         * Settings the analysis models: those that leave the code-point semantics of terminals and
         * references unchanged, and the built-in {@code @whitespace: javaStyle} (#728).
         */
        static boolean plainSetting(GlobalSetting setting) {
            if (!(setting.value() instanceof StringSettingValue value)) return false;
            return switch (setting.key()) {
                case "package", "ubnf", "memoSafeToken", "feature" -> true;
                case "whitespace" -> value.value().trim().equals("none") || javaStyle(value.value());
                default -> false;
            };
        }

        /**
         * The built-in {@code javaStyle} trivia, spelled so that every host reads it the same way:
         * ASCII case-insensitive after removing ASCII spaces at both ends. Both generators trim
         * more and compare case-insensitively, so whatever this accepts they read as javaStyle.
         */
        static boolean javaStyle(String style) {
            int start = 0, end = style.length();
            while (start < end && TRIVIA_SPACES.indexOf(style.charAt(start)) >= 0) start++;
            while (end > start && TRIVIA_SPACES.indexOf(style.charAt(end - 1)) >= 0) end--;
            String name = style.substring(start, end);
            return name.chars().allMatch(c -> c < 128) && name.equalsIgnoreCase("javaStyle");
        }

        /**
         * Under automatic whitespace a terminal or token whose FIRST meets the code points that can
         * start trivia is not modelled: it could consume trivia that the next sequence boundary
         * would have skipped (docs/auto-commit.md, 空白のある文法).
         */
        First atom(First first) {
            return trivia && !first.unknown() && first.chars().meets(TRIVIA_START) ? First.UNKNOWN : first;
        }

        /** Annotations that leave the rule's recognition as written; anything else makes it opaque. */
        static boolean transparent(Annotation annotation) {
            if (annotation instanceof SimpleAnnotation simple) return simple.name().equals(ANNOTATION);
            return annotation instanceof RootAnnotation || annotation instanceof MappingAnnotation
                || annotation instanceof DocAnnotation || annotation instanceof SkipAnnotation;
        }

        Integer ruleOf(String name) {
            return ambiguous.contains(name) ? null : rules.get(name);
        }

        First reference(RuleRefElement reference) {
            if (reference.namespace().isPresent() || ambiguous.contains(reference.name())) return First.UNKNOWN;
            Integer rule = rules.get(reference.name());
            if (rule != null) return table[rule];
            TokenDecl token = tokens.get(reference.name());
            return token == null ? First.UNKNOWN : atom(token(token, new ArrayList<>()));
        }

        First token(TokenDecl token, List<String> visiting) {
            if (token instanceof TokenDecl.Declarative declarative) {
                if (visiting.contains(token.name())) return First.UNKNOWN;
                visiting.add(token.name());
                First first = lexical(declarative.expression(), visiting);
                visiting.remove(visiting.size() - 1);
                return first;
            }
            if (token instanceof TokenDecl.CharRange range) return First.consuming(Chars.range(range.min(), range.max()));
            if (token instanceof TokenDecl.Any || token instanceof TokenDecl.Negation) return First.consuming(Chars.ANY);
            if (token instanceof TokenDecl.Empty) return First.EPSILON;
            return First.UNKNOWN;
        }

        First lexical(LexicalExpression expression, List<String> visiting) {
            switch (expression.op()) {
                case LITERAL:
                    return expression.text().isEmpty() ? First.EPSILON
                        : First.consuming(Chars.of(expression.text().codePointAt(0)));
                case ANY: case XID_IDENTIFIER: case EXCEPT:
                    return First.consuming(Chars.ANY);
                case RANGE:
                    return First.consuming(Chars.range(expression.min(), expression.max()));
                case SEQUENCE: {
                    First acc = First.EPSILON;
                    for (LexicalExpression child : expression.children()) {
                        if (!acc.nullable()) break;
                        First first = lexical(child, visiting);
                        if (first.unknown()) return First.UNKNOWN;
                        acc = new First(acc.chars().union(first.chars()), first.nullable(), false);
                    }
                    return acc;
                }
                case CHOICE: {
                    First acc = First.BOTTOM;
                    for (LexicalExpression child : expression.children()) acc = acc.choice(lexical(child, visiting));
                    return acc;
                }
                case REPEAT: {
                    First first = lexical(expression.children().get(0), visiting);
                    return expression.min() == 0 ? first.orEmpty() : first;
                }
                case REF: {
                    if (expression.text().contains(".") || ambiguous.contains(expression.text())) return First.UNKNOWN;
                    TokenDecl token = tokens.get(expression.text());
                    return token instanceof TokenDecl.Declarative ? token(token, visiting) : First.UNKNOWN;
                }
                default:
                    return First.UNKNOWN;
            }
        }

        First body(RuleBody body) {
            First acc = First.BOTTOM;
            for (SequenceBody sequence : alternatives(body)) acc = acc.choice(elements(sequence.elements(), sequence.elements().size()));
            return acc;
        }

        First elements(List<AnnotatedElement> elements, int end) {
            First acc = First.EPSILON;
            for (int i = 0; i < end; i++) {
                if (!acc.nullable()) break;
                First first = element(elements.get(i).element());
                if (first.unknown()) return First.UNKNOWN;
                acc = new First(acc.chars().union(first.chars()), first.nullable(), false);
            }
            return acc;
        }

        First element(AtomicElement element) {
            if (!frozen) return compute(element);
            First first = memo.get(element);
            if (first == null) {
                first = compute(element);
                memo.put(element, first);
            }
            return first;
        }

        First compute(AtomicElement element) {
            if (element instanceof TerminalElement terminal) {
                return terminal.value().isEmpty() ? First.EPSILON
                    : atom(First.consuming(Chars.of(terminal.value().codePointAt(0))));
            }
            if (element instanceof RuleRefElement reference) return reference(reference);
            if (element instanceof GroupElement group) return body(group.body());
            if (element instanceof OptionalElement optional) return body(optional.body()).orEmpty();
            if (element instanceof RepeatElement repeat) return body(repeat.body()).orEmpty();
            if (element instanceof OneOrMoreElement repeat) return element(repeat.body());
            if (element instanceof BoundedRepeatElement repeat) {
                First first = element(repeat.body());
                return repeat.min() == 0 ? first.orEmpty() : first;
            }
            if (element instanceof SeparatedElement separated) return element(separated.element());
            return First.UNKNOWN;
        }

        /** Whether some rule can call itself again without consuming input. */
        boolean leftRecursive() {
            List<List<Integer>> calls = new ArrayList<>();
            for (RuleDecl rule : grammar.rules()) {
                List<Integer> out = new ArrayList<>();
                leftBody(rule.body(), out);
                calls.add(out);
            }
            int[] state = new int[calls.size()]; // 0 unvisited, 1 on the stack, 2 done
            for (int start = 0; start < calls.size(); start++) {
                if (state[start] != 0) continue;
                ArrayDeque<int[]> stack = new ArrayDeque<>();
                stack.push(new int[] {start, 0});
                state[start] = 1;
                while (!stack.isEmpty()) {
                    int[] top = stack.peek();
                    List<Integer> callees = calls.get(top[0]);
                    if (top[1] < callees.size()) {
                        int callee = callees.get(top[1]++);
                        if (state[callee] == 0) {
                            state[callee] = 1;
                            stack.push(new int[] {callee, 0});
                        } else if (state[callee] == 1) {
                            return true;
                        }
                    } else {
                        state[top[0]] = 2;
                        stack.pop();
                    }
                }
            }
            return false;
        }

        void leftBody(RuleBody body, List<Integer> out) {
            for (SequenceBody sequence : alternatives(body)) {
                for (AnnotatedElement annotated : sequence.elements()) {
                    leftElement(annotated.element(), out);
                    First first = element(annotated.element());
                    if (!first.unknown() && !first.nullable()) break;
                }
            }
        }

        void leftElement(AtomicElement element, List<Integer> out) {
            if (element instanceof RuleRefElement reference) {
                if (reference.namespace().isEmpty()) {
                    Integer rule = ruleOf(reference.name());
                    if (rule != null) out.add(rule);
                }
            } else if (element instanceof CatchElement caught) {
                leftBody(caught.child().body(), out);
                leftBody(caught.alternative().body(), out);
            } else if (element instanceof TransparentElement transparent) {
                leftBody(transparent.body(), out);
            } else if (element instanceof OptionalElement optional) {
                leftBody(optional.body(), out);
            } else if (element instanceof RepeatElement repeat) {
                leftBody(repeat.body(), out);
            } else if (element instanceof OneOrMoreElement repeat) {
                leftElement(repeat.body(), out);
            } else if (element instanceof BoundedRepeatElement repeat) {
                leftElement(repeat.body(), out);
            } else if (element instanceof SeparatedElement separated) {
                leftElement(separated.element(), out);
                First first = element(separated.element());
                if (first.unknown() || first.nullable()) leftElement(separated.separator(), out);
            }
        }

        void reach(int root) {
            ArrayDeque<Integer> work = new ArrayDeque<>();
            work.push(root);
            reachable[root] = true;
            while (!work.isEmpty()) {
                int rule = work.pop();
                List<String> names = new ArrayList<>();
                references(grammar.rules().get(rule).body(), names);
                for (String name : names) {
                    Integer callee = ruleOf(name);
                    if (callee != null && !reachable[callee]) {
                        reachable[callee] = true;
                        work.push(callee);
                    }
                }
            }
        }

        static void references(RuleBody body, List<String> out) {
            for (SequenceBody sequence : alternatives(body)) {
                for (AnnotatedElement annotated : sequence.elements()) elementReferences(annotated.element(), out);
            }
        }

        static void elementReferences(AtomicElement element, List<String> out) {
            if (element instanceof RuleRefElement reference) {
                if (reference.namespace().isEmpty()) out.add(reference.name());
            } else if (element instanceof CatchElement caught) {
                references(caught.child().body(), out);
                references(caught.alternative().body(), out);
            } else if (element instanceof TransparentElement transparent) {
                references(transparent.body(), out);
            } else if (element instanceof OptionalElement optional) {
                references(optional.body(), out);
            } else if (element instanceof RepeatElement repeat) {
                references(repeat.body(), out);
            } else if (element instanceof OneOrMoreElement repeat) {
                elementReferences(repeat.body(), out);
            } else if (element instanceof BoundedRepeatElement repeat) {
                elementReferences(repeat.body(), out);
            } else if (element instanceof SeparatedElement separated) {
                elementReferences(separated.element(), out);
                elementReferences(separated.separator(), out);
            }
        }

        /** Pre-order numbering, identical to the rewrite. */
        void indexBody(RuleBody body, int rule, int parentElem, int ownerRule, int[] ordinal, boolean blocked) {
            int id = nodes.size();
            Node node = new Node(BODY);
            node.body = body;
            node.rule = rule;
            node.parentElem = parentElem;
            node.owner = ownerRule;
            node.ordinal = ordinal[0]++;
            node.blocked = blocked;
            nodes.add(node);
            List<SequenceBody> alternatives = alternatives(body);
            for (int i = 0; i < alternatives.size(); i++) {
                int seq = nodes.size();
                Node sequence = new Node(SEQ);
                sequence.sequence = alternatives.get(i);
                sequence.parentBody = id;
                sequence.index = i;
                nodes.add(sequence);
                node.alternatives.add(seq);
                List<AnnotatedElement> elements = alternatives.get(i).elements();
                for (int k = 0; k < elements.size(); k++) {
                    indexElement(elements.get(k).element(), seq, k, -1, 0, ownerRule, ordinal, blocked);
                }
            }
        }

        void indexElement(AtomicElement element, int parentSeq, int k, int parentElem, int slot, int ownerRule,
                int[] ordinal, boolean blocked) {
            int id = nodes.size();
            Node node = new Node(ELEM);
            node.element = element;
            node.parentSeq = parentSeq;
            node.index = k;
            node.parentElem = parentElem;
            node.slot = slot;
            nodes.add(node);
            if (element instanceof RuleRefElement reference) {
                if (reference.namespace().isEmpty()) sites.computeIfAbsent(reference.name(), n -> new ArrayList<>()).add(id);
            } else if (element instanceof CatchElement caught) {
                indexBody(caught.child().body(), -1, id, ownerRule, ordinal, true);
                indexBody(caught.alternative().body(), -1, id, ownerRule, ordinal, true);
            } else if (element instanceof GroupElement group) {
                indexBody(group.body(), -1, id, ownerRule, ordinal, blocked);
            } else if (element instanceof TransparentElement transparent) {
                indexBody(transparent.body(), -1, id, ownerRule, ordinal, true);
            } else if (element instanceof OptionalElement optional) {
                indexBody(optional.body(), -1, id, ownerRule, ordinal, blocked);
            } else if (element instanceof RepeatElement repeat) {
                indexBody(repeat.body(), -1, id, ownerRule, ordinal, blocked);
            } else if (element instanceof OneOrMoreElement repeat) {
                indexElement(repeat.body(), -1, 0, id, 0, ownerRule, ordinal, blocked);
            } else if (element instanceof BoundedRepeatElement repeat) {
                indexElement(repeat.body(), -1, 0, id, 0, ownerRule, ordinal, blocked);
            } else if (element instanceof SeparatedElement separated) {
                indexElement(separated.element(), -1, 0, id, 0, ownerRule, ordinal, blocked);
                indexElement(separated.separator(), -1, 0, id, 1, ownerRule, ordinal, blocked);
            }
        }

        /** Candidates of the rules {@code only} selects, in rule order and then source order. */
        List<Candidate> candidates(java.util.function.IntPredicate only) {
            List<Candidate> out = new ArrayList<>();
            for (int id = 0; id < nodes.size(); id++) {
                Node node = nodes.get(id);
                if (node.kind != BODY || node.blocked || opaque[node.owner] || !only.test(node.owner)) continue;
                List<SequenceBody> alternatives = alternatives(node.body);
                for (int alternative = 0; alternative < alternatives.size(); alternative++) {
                    List<AnnotatedElement> elements = alternatives.get(alternative).elements();
                    if (elements.size() < 2) continue;
                    First first = element(elements.get(0).element());
                    if (first.unknown() || first.nullable()) continue;
                    List<Integer> wraps = new ArrayList<>();
                    for (int k = 1; k < elements.size(); k++) if (wrappable(elements.get(k))) wraps.add(k);
                    if (wraps.isEmpty()) continue;
                    if (!safe(node.alternatives.get(alternative), first.chars())) continue;
                    out.add(new Candidate(node.owner, node.ordinal, alternative, alternatives.get(alternative), List.copyOf(wraps)));
                }
            }
            return out;
        }

        /** A plain reference to one rule or token of this grammar, without {@code @typeof}. */
        boolean wrappable(AnnotatedElement annotated) {
            return annotated.typeofConstraint().isEmpty() && annotated.element() instanceof RuleRefElement reference
                && reference.namespace().isEmpty() && !ambiguous.contains(reference.name())
                && (rules.containsKey(reference.name()) || tokens.containsKey(reference.name()));
        }

        /**
         * Whether an ordinary failure of alternative {@code seq}, at a position holding one of
         * {@code chars}, always ends in a failed full-input parse of the root rule.
         */
        boolean safe(int seq, Chars chars) {
            Set<State> seen = new HashSet<>();
            ArrayDeque<State> work = new ArrayDeque<>();
            work.push(new State(false, seq, chars));
            while (!work.isEmpty()) {
                State state = work.pop();
                if (!seen.add(state)) continue;
                if (seen.size() > STATE_BUDGET || !step(state, work)) return false;
            }
            return true;
        }

        /**
         * One transition. {@code next == false}: the node failed ordinarily at its start, holding one
         * of {@code chars}. {@code next == true}: the node succeeded and the next code point is one of
         * {@code chars}.
         */
        boolean step(State state, ArrayDeque<State> work) {
            boolean next = state.next();
            Chars chars = state.chars();
            Node node = nodes.get(state.node());
            if (node.kind == BODY) {
                if (node.rule >= 0) {
                    if (opaque[node.rule]) return false;
                    for (int site : sites.getOrDefault(grammar.rules().get(node.rule).name(), List.of())) {
                        work.push(new State(next, site, chars));
                    }
                    return true;
                }
                AtomicElement owner = nodes.get(node.parentElem).element;
                if (owner instanceof GroupElement) {
                    work.push(new State(next, node.parentElem, chars));
                } else if (owner instanceof OptionalElement) {
                    work.push(new State(true, node.parentElem, chars));
                } else if (owner instanceof RepeatElement) {
                    if (next && !body(node.body).excludes(chars)) return false;
                    work.push(new State(true, node.parentElem, chars));
                } else {
                    return false;
                }
                return true;
            }
            if (node.kind == SEQ) {
                if (!next) {
                    List<SequenceBody> alternatives = alternatives(nodes.get(node.parentBody).body);
                    for (int j = node.index + 1; j < alternatives.size(); j++) {
                        List<AnnotatedElement> later = alternatives.get(j).elements();
                        if (!elements(later, later.size()).excludes(chars)) return false;
                    }
                }
                work.push(new State(next, node.parentBody, chars));
                return true;
            }
            if (node.parentSeq >= 0) {
                List<AnnotatedElement> elements = nodes.get(node.parentSeq).sequence.elements();
                int k = node.index;
                if (!next) {
                    First prefix = elements(elements, k);
                    if (prefix.unknown()) return false;
                    work.push(new State(false, node.parentSeq, prefix.before(chars)));
                    return true;
                }
                if (k + 1 < elements.size()) {
                    First prefix = elements(elements, k + 1);
                    if (prefix.unknown()) return false;
                    boolean nullable = true;
                    for (int t = k + 1; t < elements.size(); t++) {
                        First first = element(elements.get(t).element());
                        if (first.unknown() || first.chars().meets(chars)) return false;
                        if (!first.nullable()) {
                            nullable = false;
                            break;
                        }
                    }
                    work.push(new State(false, node.parentSeq, prefix.before(chars)));
                    if (!nullable) return true;
                }
                work.push(new State(true, node.parentSeq, chars));
                return true;
            }
            int owner = node.parentElem;
            AtomicElement element = nodes.get(owner).element;
            if (element instanceof OneOrMoreElement repeat) return repeat(next, owner, repeat.body(), 1, chars, work);
            if (element instanceof BoundedRepeatElement repeat) return repeat(next, owner, repeat.body(), repeat.min(), chars, work);
            if (element instanceof SeparatedElement separated) {
                First separator = element(separated.separator());
                if (!next && node.slot == 0) {
                    if (separator.unknown()) return false;
                    work.push(new State(false, owner, chars));
                    work.push(new State(true, owner, separator.before(chars)));
                } else if (!next) {
                    work.push(new State(true, owner, chars));
                } else if (node.slot == 0) {
                    if (!separator.excludes(chars)) return false;
                    work.push(new State(true, owner, chars));
                } else {
                    if (!element(separated.element()).excludes(chars) || separator.unknown()) return false;
                    work.push(new State(true, owner, separator.before(chars)));
                }
                return true;
            }
            return false;
        }

        boolean repeat(boolean next, int owner, AtomicElement inner, int min, Chars chars, ArrayDeque<State> work) {
            First first = element(inner);
            if (first.unknown() || next && !first.excludes(chars)) return false;
            work.push(new State(true, owner, chars));
            // The repetition fails as a whole when fewer than min items matched: after a failed
            // item (!next) the count may be anything below, after a successful one at least 1.
            boolean belowMin = next ? min >= 2 : min >= 1;
            if (belowMin) work.push(new State(false, owner, first.chars().union(chars)));
            return true;
        }
    }
}
