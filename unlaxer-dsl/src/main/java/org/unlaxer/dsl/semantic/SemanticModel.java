package org.unlaxer.dsl.semantic;

import java.util.*;

/** Immutable, single-document nominal type and completion model. See docs/semantic-model.md. */
public final class SemanticModel {
    public static final String UNKNOWN = "?";
    public enum Compatibility { YES, UNKNOWN, NO }
    public enum TypeKind { BUILTIN, INTERFACE, RECORD }
    public record Span(int start, int end) {
        public Span { if (start < 0 || end < start) throw error("INVALID_SPAN", new int[]{start, end}); }
        public boolean contains(Span other) { return start <= other.start && other.end <= end; }
    }
    public record Field(String name, String type, Span span) {}
    public record Type(String id, TypeKind kind, List<String> supertypes, List<Field> fields, Span span) {
        public Type { supertypes = List.copyOf(supertypes); fields = List.copyOf(fields); }
    }
    public record Scope(String id, String parent, Span span) {}
    public record Symbol(String id, String name, String type, String scope, Span declaration, int visibleFrom) {}
    public record Signature(String id, String name, List<String> parameters, String result, Span span) {
        public Signature { parameters = List.copyOf(parameters); }
    }
    public record Argument(String type, Span span) {}
    public record Call(String id, List<String> signatures, List<Argument> arguments, Span span) {
        public Call { signatures = List.copyOf(signatures); arguments = List.copyOf(arguments); }
    }
    public record Completion(Symbol symbol, Compatibility compatibility, List<String> expectedTypes) {
        public Completion { expectedTypes = List.copyOf(expectedTypes); }
        public String reason() {
            return symbol.type() + " -> " + String.join(" | ", expectedTypes) + ": " + compatibility.name();
        }
    }
    public static final class ModelException extends IllegalArgumentException {
        private final String code;
        private final int start;
        private final int end;
        private ModelException(String code, int start, int end) {
            super(code + " at " + start + ".." + end);
            this.code = code; this.start = start; this.end = end;
        }
        public String code() { return code; }
        public int start() { return start; }
        public int end() { return end; }
    }
    private static ModelException error(String code, int[] span) { return new ModelException(code, span[0], span[1]); }
    private static ModelException error(String code, Span span) { return new ModelException(code, span.start(), span.end()); }
    private final String uri;
    private final long version;
    private final String source;
    private final int length;
    private final Map<String, Type> types;
    private final Map<String, Scope> scopes;
    private final Map<String, Symbol> symbols;
    private final Map<String, Signature> signatures;
    private final Map<String, Call> calls;
    private final String root;

    public SemanticModel(String uri, long version, String source, List<Type> types, List<Scope> scopes,
            List<Symbol> symbols, List<Signature> signatures, List<Call> calls) {
        this.uri = Objects.requireNonNull(uri);
        this.version = version;
        this.source = Objects.requireNonNull(source);
        this.length = source.codePointCount(0, source.length());
        Span document = new Span(0, length);
        if (uri.isEmpty() || version < 0) throw error("INVALID_DOCUMENT", document);
        if (source.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)) throw error("INVALID_SOURCE", document);
        this.types = index(types, Type::id, Type::span, "TYPE");
        this.scopes = index(scopes, Scope::id, Scope::span, "SCOPE");
        this.symbols = index(symbols, Symbol::id, Symbol::declaration, "SYMBOL");
        this.signatures = index(signatures, Signature::id, Signature::span, "SIGNATURE");
        this.calls = index(calls, Call::id, Call::span, "CALL");
        for (Type type : this.types.values()) {
            if (type.id().equals(UNKNOWN) || type.kind() == null) throw error("INVALID_TYPE", type.span());
            Set<String> parents = new HashSet<>();
            for (String parent : type.supertypes()) {
                Type target = this.types.get(parent);
                if (target == null) throw error("UNDEFINED_TYPE", type.span());
                if (type.kind() == TypeKind.BUILTIN || target.kind() != TypeKind.INTERFACE)
                    throw error("INVALID_SUPERTYPE", type.span());
                if (!parents.add(parent)) throw error("DUPLICATE_SUPERTYPE", type.span());
            }
            Set<String> fields = new HashSet<>();
            for (Field field : type.fields()) {
                checkSpan(field.span()); checkName(field.name(), field.span());
                if (!type.span().contains(field.span())) throw error("FIELD_OUTSIDE_TYPE", field.span());
                if (!fields.add(field.name())) throw error("DUPLICATE_FIELD", field.span());
                checkType(field.type(), field.span());
            }
        }
        for (Type type : this.types.values()) {
            var pending = new ArrayDeque<>(type.supertypes());
            var seen = new HashSet<String>();
            while (!pending.isEmpty()) {
                String next = pending.removeFirst();
                if (next.equals(type.id())) throw error("CYCLIC_TYPE", type.span());
                if (seen.add(next)) pending.addAll(this.types.get(next).supertypes());
            }
        }
        List<Scope> roots = this.scopes.values().stream().filter(scope -> scope.parent() == null).toList();
        if (roots.size() != 1 || !roots.get(0).span().equals(document)) throw error("INVALID_ROOT_SCOPE", document);
        this.root = roots.get(0).id();
        for (Scope scope : this.scopes.values()) {
            if (scope.parent() != null && scope.span().start() == scope.span().end()) throw error("EMPTY_SCOPE", scope.span());
            var seen = new HashSet<String>();
            Scope current = scope;
            while (current.parent() != null) {
                if (!seen.add(current.id())) throw error("CYCLIC_SCOPE", scope.span());
                Scope parent = this.scopes.get(current.parent());
                if (parent == null) throw error("UNDEFINED_SCOPE", scope.span());
                if (!parent.span().contains(current.span())) throw error("SCOPE_OUTSIDE_PARENT", current.span());
                current = parent;
            }
            for (Scope sibling : this.scopes.values()) {
                if (!scope.id().equals(sibling.id()) && Objects.equals(scope.parent(), sibling.parent())
                        && Math.max(scope.span().start(), sibling.span().start()) < Math.min(scope.span().end(), sibling.span().end()))
                    throw error("OVERLAPPING_SCOPES", scope.span());
            }
        }
        var declared = new HashMap<String, Set<String>>();
        for (Symbol symbol : this.symbols.values()) {
            checkName(symbol.name(), symbol.declaration()); checkType(symbol.type(), symbol.declaration());
            Scope scope = this.scopes.get(symbol.scope());
            if (scope == null) throw error("UNDEFINED_SCOPE", symbol.declaration());
            if (!scope.span().contains(symbol.declaration()) || symbol.visibleFrom() < scope.span().start()
                    || symbol.visibleFrom() > scope.span().end()) throw error("SYMBOL_OUTSIDE_SCOPE", symbol.declaration());
            if (!declared.computeIfAbsent(scope.id(), ignored -> new HashSet<>()).add(symbol.name()))
                throw error("DUPLICATE_SYMBOL_NAME", symbol.declaration());
        }
        for (Signature signature : this.signatures.values()) {
            checkName(signature.name(), signature.span());
            checkType(signature.result(), signature.span());
            for (String type : signature.parameters()) checkType(type, signature.span());
        }
        for (Call call : this.calls.values()) {
            var seen = new HashSet<String>();
            for (String id : call.signatures()) {
                if (!this.signatures.containsKey(id)) throw error("UNDEFINED_SIGNATURE", call.span());
                if (!seen.add(id)) throw error("DUPLICATE_CALL_SIGNATURE", call.span());
            }
            int end = call.span().start() - 1;
            for (Argument argument : call.arguments()) {
                checkSpan(argument.span()); checkType(argument.type(), argument.span());
                if (!call.span().contains(argument.span()) || argument.span().start() <= end)
                    throw error("INVALID_ARGUMENT_SPAN", argument.span());
                end = argument.span().end();
            }
        }
    }
    private <T> Map<String, T> index(List<T> items, java.util.function.Function<T, String> id,
            java.util.function.Function<T, Span> span, String kind) {
        var result = new LinkedHashMap<String, T>();
        for (T item : items) {
            checkSpan(span.apply(item)); checkName(id.apply(item), span.apply(item));
            if (result.putIfAbsent(id.apply(item), item) != null) throw error("DUPLICATE_" + kind, span.apply(item));
        }
        return Collections.unmodifiableMap(result);
    }
    private void checkSpan(Span span) {
        if (span == null) throw error("INVALID_SPAN", new int[]{0, 0});
        if (span.end() > length) throw error("SPAN_OUTSIDE_DOCUMENT", span);
    }
    private static void checkName(String name, Span span) {
        if (name == null || name.isEmpty()) throw error("EMPTY_NAME", span);
    }
    private void checkType(String id, Span span) {
        if (!UNKNOWN.equals(id) && !types.containsKey(id)) throw error("UNDEFINED_TYPE", span);
    }
    public String uri() { return uri; }
    public long version() { return version; }
    public String source() { return source; }
    public Map<String, Type> types() { return types; }
    public Map<String, Scope> scopes() { return scopes; }
    public Map<String, Symbol> symbols() { return symbols; }
    public Map<String, Signature> signatures() { return signatures; }
    public Map<String, Call> calls() { return calls; }

    public Compatibility isAssignable(String actual, String expected) {
        checkType(actual, new Span(0, 0)); checkType(expected, new Span(0, 0));
        if (UNKNOWN.equals(actual) || UNKNOWN.equals(expected)) return Compatibility.UNKNOWN;
        var pending = new ArrayDeque<String>(); pending.add(actual);
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String next = pending.removeFirst();
            if (next.equals(expected)) return Compatibility.YES;
            if (seen.add(next)) pending.addAll(types.get(next).supertypes());
        }
        return Compatibility.NO;
    }
    private static int compareText(String a, String b) {
        var left = a.codePoints().iterator(); var right = b.codePoints().iterator();
        while (left.hasNext() && right.hasNext()) {
            int comparison = Integer.compare(left.nextInt(), right.nextInt());
            if (comparison != 0) return comparison;
        }
        return Boolean.compare(left.hasNext(), right.hasNext());
    }
    private boolean at(Scope scope, int cursor) {
        return scope.span().start() <= cursor && (cursor < scope.span().end()
            || cursor == length && scope.span().end() == length);
    }
    /** Visible values, respecting lexical nesting, visibility start, and shadowing. */
    public List<Symbol> visibleSymbolsAt(int cursor) {
        if (cursor < 0 || cursor > length) throw error("INVALID_CURSOR", new int[]{cursor, cursor});
        Scope scope = scopes.get(root);
        while (true) {
            Scope parent = scope;
            Optional<Scope> child = scopes.values().stream()
                .filter(candidate -> parent.id().equals(candidate.parent()) && at(candidate, cursor)).findFirst();
            if (child.isEmpty()) break;
            scope = child.get();
        }
        var visible = new TreeMap<String, Symbol>(SemanticModel::compareText);
        while (scope != null) {
            for (Symbol symbol : symbols.values()) {
                if (symbol.scope().equals(scope.id()) && symbol.visibleFrom() <= cursor)
                    visible.putIfAbsent(symbol.name(), symbol);
            }
            scope = scope.parent() == null ? null : scopes.get(scope.parent());
        }
        return List.copyOf(visible.values());
    }
    private Call call(String id, int index) {
        Call call = calls.get(id);
        if (call == null) throw error("UNDEFINED_CALL", new Span(0, 0));
        if (index < 0 || index >= call.arguments().size()) throw error("INVALID_ARGUMENT_INDEX", call.span());
        return call;
    }
    public List<String> expectedTypes(String callId, int index) {
        Call call = call(callId, index);
        if (call.signatures().isEmpty()) return List.of(UNKNOWN);
        var expected = new TreeSet<String>(SemanticModel::compareText);
        for (String id : call.signatures()) {
            Signature signature = signatures.get(id);
            if (index >= signature.parameters().size() || call.arguments().size() > signature.parameters().size()) continue;
            boolean viable = true;
            for (int i = 0; i < call.arguments().size(); i++) {
                if (i != index && isAssignable(call.arguments().get(i).type(), signature.parameters().get(i)) == Compatibility.NO) {
                    viable = false; break;
                }
            }
            if (viable) expected.add(signature.parameters().get(index));
        }
        return List.copyOf(expected);
    }
    public List<Completion> completeArgument(String callId, int index, int cursor, long snapshotVersion, String prefix) {
        Call call = call(callId, index);
        if (snapshotVersion != version) throw error("STALE_SNAPSHOT", call.span());
        Span slot = call.arguments().get(index).span();
        if (cursor < slot.start() || cursor > slot.end()) throw error("CURSOR_OUTSIDE_ARGUMENT", slot);
        List<String> expected = expectedTypes(callId, index);
        var completions = new ArrayList<Completion>();
        for (Symbol symbol : visibleSymbolsAt(cursor)) {
            if (!symbol.name().startsWith(prefix)) continue;
            Compatibility best = Compatibility.NO;
            for (String type : expected) {
                Compatibility candidate = isAssignable(symbol.type(), type);
                if (candidate.ordinal() < best.ordinal()) best = candidate;
            }
            if (best != Compatibility.NO) completions.add(new Completion(symbol, best, expected));
        }
        completions.sort(Comparator.comparing(Completion::compatibility)
            .thenComparing(item -> item.symbol().name(), SemanticModel::compareText).thenComparing(item -> item.symbol().id(), SemanticModel::compareText));
        return List.copyOf(completions);
    }
}
