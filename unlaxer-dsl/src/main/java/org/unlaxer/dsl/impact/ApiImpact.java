package org.unlaxer.dsl.impact;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.SkipAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.codegen.GrammarValidator;
import org.unlaxer.dsl.codegen.rust.RustApiSchema;

/** Read-only source API comparison. An empty change list is not a compatibility proof. */
public final class ApiImpact {
    private ApiImpact() {}
    public record Span(int start, int end) {
        public Span { if (start < 0 || end < start) throw new IllegalArgumentException("invalid span"); }
    }
    public record Location(String path, Span span, int line, int column) {}
    public record Origin(String rule, Span span) {}
    public record Field(String name, String type, String cardinality, Location generated) {}
    public record Parameter(String name, String type) {}
    public record Node(String name, String kind, List<Field> fields, List<String> parents,
                       List<String> variants, List<Origin> origins, Location generated) {
        public Node { fields = List.copyOf(fields); parents = List.copyOf(parents);
            variants = List.copyOf(variants); origins = List.copyOf(origins); }
    }
    public record Method(String name, String returnType, List<Parameter> parameters, boolean required,
                         List<Origin> origins, Location generated) {
        public Method { parameters = List.copyOf(parameters); origins = List.copyOf(origins); }
    }
    public record Snapshot(String grammar, String astType, String evaluatorType, List<Node> nodes,
                           List<Method> methods) {
        public Snapshot { nodes = nodes.stream().sorted(Comparator.comparing(Node::name)).toList();
            methods = methods.stream().sorted(Comparator.comparing(Method::name)).toList(); }
    }
    public record Change(String kind, String subject, String before, String after,
                         List<Origin> beforeOrigins, List<Origin> afterOrigins,
                         Location beforeGenerated, Location afterGenerated) {
        public Change { beforeOrigins = List.copyOf(beforeOrigins); afterOrigins = List.copyOf(afterOrigins); }
    }
    public record Diagnostic(String side, String code, String message) {}
    public record Report(String target, boolean ok, Snapshot before, Snapshot after,
                         List<Change> changes, List<Diagnostic> diagnostics) {
        public Report { changes = List.copyOf(changes); diagnostics = List.copyOf(diagnostics); }
        public boolean hasChanges() { return ok && !changes.isEmpty(); }
        public String toJson() {
            Map<String, Object> object = new LinkedHashMap<>();
            object.put("schemaVersion", 1); object.put("scope", "ast-semantics");
            object.put("target", target); object.put("ok", ok); object.put("hasChanges", hasChanges());
            object.put("before", before); object.put("after", after); object.put("changes", changes);
            object.put("diagnostics", diagnostics);
            return json(object);
        }
    }

    public static Report compare(String target, String beforeSource, String afterSource) {
        if (!List.of("java", "rust").contains(target)) throw new IllegalArgumentException("target must be java or rust");
        List<Diagnostic> diagnostics = new ArrayList<>();
        Snapshot before = trySchema(target, beforeSource, "before", diagnostics);
        Snapshot after = trySchema(target, afterSource, "after", diagnostics);
        boolean ok = diagnostics.isEmpty();
        return new Report(target, ok, before, after,
            ok ? changes(before, after) : List.of(), diagnostics);
    }

    private static Snapshot trySchema(String target, String source, String side, List<Diagnostic> diagnostics) {
        try { return schema(target, source); }
        catch (IllegalArgumentException | IllegalStateException error) {
            diagnostics.add(new Diagnostic(side, "I-SCHEMA", String.valueOf(error.getMessage())));
            return null;
        }
    }

    public static Snapshot schema(String target, String source) {
        if (!List.of("java", "rust").contains(target)) throw new IllegalArgumentException("target must be java or rust");
        UBNFSourceSnapshot snapshot = UBNFMapper.parseWithSource(source);
        if (snapshot.ast().grammars().size() != 1) throw new IllegalArgumentException("expected one grammar");
        var grammar = snapshot.ast().grammars().get(0);
        if (!grammar.imports().isEmpty()) throw new IllegalArgumentException("imports are not expanded by impact");
        GrammarValidator.validateWithoutClassLoading(grammar).stream()
            .filter(issue -> issue.severity().equals("ERROR")).findFirst()
            .ifPresent(issue -> { throw new IllegalArgumentException(issue.format()); });
        return target.equals("java") ? JavaApiSchema.build(snapshot) : RustApiSchema.build(snapshot);
    }

    /** All contributing source rules, including shared mappings; null selects all rules. */
    public static List<Origin> origins(UBNFSourceSnapshot snapshot, String mappingName) {
        return snapshot.ast().grammars().get(0).rules().stream()
            .filter(rule -> mappingName == null || (!rule.annotations().stream().anyMatch(SkipAnnotation.class::isInstance)
                && rule.annotations().stream().filter(MappingAnnotation.class::isInstance)
                .map(MappingAnnotation.class::cast).anyMatch(mapping -> mapping.className().equals(mappingName)
                    || mapping.className().startsWith(mappingName + "."))))
            .map(rule -> {
                var span = snapshot.spanOf(rule).orElseThrow(() -> new IllegalStateException("missing rule origin"));
                return new Origin(rule.name(), new Span(span.start(), span.end()));
            }).sorted(Comparator.comparing(Origin::rule)).toList();
    }

    /** Convert compiler/string UTF-16 offsets to the public code-point location contract. */
    public static Location location(String path, String source, int start, int end) {
        if (start < 0 || end < start || end > source.length()) throw new IllegalStateException("missing generated declaration");
        int line = 1;
        for (int i = 0; i < start; i++) if (source.charAt(i) == '\n') line++;
        int lineStart = source.lastIndexOf('\n', start - 1) + 1;
        return new Location(path, new Span(source.codePointCount(0, start), source.codePointCount(0, end)),
            line, source.codePointCount(lineStart, start) + 1);
    }

    public static List<Change> changes(Snapshot before, Snapshot after) {
        List<Change> changes = new ArrayList<>();
        add(changes, "AST_TYPE_CHANGED", "ast", before.astType(), after.astType(), List.of(), List.of(), null, null);
        add(changes, "EVALUATOR_TYPE_CHANGED", "evaluator", before.evaluatorType(), after.evaluatorType(), List.of(), List.of(), null, null);
        Map<String, Node> oldNodes = byName(before.nodes(), Node::name), newNodes = byName(after.nodes(), Node::name);
        for (String name : keys(oldNodes, newNodes)) {
            Node old = oldNodes.get(name), next = newNodes.get(name);
            List<Origin> oldOrigins = old == null ? List.of() : old.origins(), newOrigins = next == null ? List.of() : next.origins();
            Location oldLocation = old == null ? null : old.generated(), newLocation = next == null ? null : next.generated();
            if (old == null || next == null) {
                add(changes, old == null ? "NODE_ADDED" : "NODE_REMOVED", name,
                    old == null ? null : old.kind(), next == null ? null : next.kind(), oldOrigins, newOrigins, oldLocation, newLocation);
                continue;
            }
            add(changes, "NODE_KIND_CHANGED", name, old.kind(), next.kind(), oldOrigins, newOrigins, oldLocation, newLocation);
            add(changes, "NODE_PARENTS_CHANGED", name, sortedNames(old.parents()), sortedNames(next.parents()), oldOrigins, newOrigins, oldLocation, newLocation);
            add(changes, "NODE_VARIANTS_CHANGED", name, sortedNames(old.variants()), sortedNames(next.variants()), oldOrigins, newOrigins, oldLocation, newLocation);
            add(changes, "NODE_RULES_CHANGED", name, sortedNames(oldOrigins.stream().map(Origin::rule).toList()),
                sortedNames(newOrigins.stream().map(Origin::rule).toList()), oldOrigins, newOrigins, oldLocation, newLocation);
            Map<String, Field> oldFields = byName(old.fields(), Field::name), newFields = byName(next.fields(), Field::name);
            if (oldFields.keySet().equals(newFields.keySet())) add(changes, "FIELD_ORDER_CHANGED", name,
                String.join(",", old.fields().stream().map(Field::name).toList()), String.join(",", next.fields().stream().map(Field::name).toList()),
                oldOrigins, newOrigins, oldLocation, newLocation);
            for (String fieldName : keys(oldFields, newFields)) {
                Field previous = oldFields.get(fieldName), current = newFields.get(fieldName);
                String subject = name + "." + fieldName;
                Location previousLocation = previous == null ? null : previous.generated(), currentLocation = current == null ? null : current.generated();
                if (previous == null || current == null) {
                    add(changes, previous == null ? "FIELD_ADDED" : "FIELD_REMOVED", subject,
                        previous == null ? null : previous.type(), current == null ? null : current.type(),
                        oldOrigins, newOrigins, previousLocation, currentLocation);
                } else {
                    add(changes, "FIELD_TYPE_CHANGED", subject, previous.type(), current.type(), oldOrigins, newOrigins, previousLocation, currentLocation);
                    add(changes, "FIELD_CARDINALITY_CHANGED", subject, previous.cardinality(), current.cardinality(), oldOrigins, newOrigins, previousLocation, currentLocation);
                }
            }
        }
        Map<String, Method> oldMethods = byName(before.methods(), Method::name), newMethods = byName(after.methods(), Method::name);
        for (String name : keys(oldMethods, newMethods)) {
            Method old = oldMethods.get(name), next = newMethods.get(name);
            List<Origin> oldOrigins = old == null ? List.of() : old.origins(), newOrigins = next == null ? List.of() : next.origins();
            Location oldLocation = old == null ? null : old.generated(), newLocation = next == null ? null : next.generated();
            add(changes, old == null ? "METHOD_ADDED" : next == null ? "METHOD_REMOVED" : "METHOD_SIGNATURE_CHANGED", name,
                old == null ? null : signature(old), next == null ? null : signature(next), oldOrigins, newOrigins, oldLocation, newLocation);
            if (old != null && next != null) add(changes, "METHOD_REQUIRED_CHANGED", name,
                Boolean.toString(old.required()), Boolean.toString(next.required()), oldOrigins, newOrigins, oldLocation, newLocation);
        }
        return changes.stream().sorted(Comparator.comparing(Change::kind).thenComparing(Change::subject)).toList();
    }

    private static String signature(Method method) {
        return "(" + String.join(",", method.parameters().stream().map(p -> p.name() + ":" + p.type()).toList()) + ")->" + method.returnType();
    }
    private static String sortedNames(List<String> names) { return String.join(",", new TreeSet<>(names)); }
    private static <T> Map<String, T> byName(List<T> items, Function<T, String> name) {
        Map<String, T> result = new TreeMap<>();
        for (T item : items) if (result.putIfAbsent(name.apply(item), item) != null) throw new IllegalStateException("duplicate API name: " + name.apply(item));
        return result;
    }
    private static <T> TreeSet<String> keys(Map<String, T> first, Map<String, T> second) {
        TreeSet<String> result = new TreeSet<>(first.keySet()); result.addAll(second.keySet()); return result;
    }
    private static void add(List<Change> out, String kind, String subject, String before, String after,
                            List<Origin> beforeOrigins, List<Origin> afterOrigins, Location beforeGenerated, Location afterGenerated) {
        if (!java.util.Objects.equals(before, after)) out.add(new Change(kind, subject, before, after,
            beforeOrigins, afterOrigins, beforeGenerated, afterGenerated));
    }

    /** Serialize only this module's data records, never generated classes or user providers. */
    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '"' || ch == '\\') out.append('\\').append(ch);
                else if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                else out.append(ch);
            }
            return out.append('"').toString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(ApiImpact::json).toList()) + "]";
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream()
            .map(entry -> json(entry.getKey()) + ":" + json(entry.getValue())).toList()) + "}";
        if (!value.getClass().isRecord() || value.getClass().getEnclosingClass() != ApiImpact.class)
            throw new IllegalArgumentException("not an impact record");
        Map<String, Object> fields = new LinkedHashMap<>();
        try {
            for (var component : value.getClass().getRecordComponents()) fields.put(component.getName(), component.getAccessor().invoke(value));
        } catch (IllegalAccessException | InvocationTargetException error) { throw new IllegalStateException(error); }
        return json(fields);
    }
}
