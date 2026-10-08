package org.unlaxer.dsl.bootstrap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.SourceVersion;
import org.unlaxer.dsl.bootstrap.UBNFAST.BlockSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.GlobalSetting;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.KeyValuePair;
import org.unlaxer.dsl.bootstrap.UBNFAST.TokenDecl;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Span;

/** Pure-data, versioned adapter catalog. Building it never loads either target's implementation. */
public final class TokenAdapterRegistry {
    private static final Set<String> REQUIRED_FIELDS = Set.of("id", "version", "java", "rust");
    private static final Set<String> OPTIONAL_FIELDS = Set.of("accepts", "failure", "consumes", "context");
    /** Read-only context surface shared by the Java and Rust adapter entry points. */
    public static final Set<String> CONTEXT_ACCESSORS = Set.of(
        "source", "remaining", "position", "matchedPosition", "bindings");
    private static final Set<String> JAVA_RESTRICTED = Set.of("record", "var", "yield", "sealed", "permits");
    private static final Set<String> RUST_KEYWORDS = Set.of(
        "as", "async", "await", "break", "const", "continue", "crate", "dyn", "else", "enum", "extern",
        "false", "fn", "for", "if", "impl", "in", "let", "loop", "match", "mod", "move", "mut", "pub",
        "ref", "return", "self", "Self", "static", "struct", "super", "trait", "true", "type", "unsafe",
        "use", "where", "while", "abstract", "become", "box", "do", "final", "gen", "macro", "override",
        "priv", "try", "typeof", "unsized", "virtual", "yield", "union"
    );

    public record Descriptor(String id, int version, String javaClass, String rustPath,
                             String accepts, String failure, String consumes, Set<String> contextAccessors,
                             boolean builtin) {}
    public record Diagnostic(String code, Span span, String subject) {}
    public record Build(TokenAdapterRegistry registry, List<Diagnostic> diagnostics) {}
    private record Key(String id, int version) {}

    private final Map<Key, Descriptor> descriptors;

    private TokenAdapterRegistry(Map<Key, Descriptor> descriptors) {
        this.descriptors = Map.copyOf(descriptors);
    }

    public Optional<Descriptor> find(String id, int version) {
        return Optional.ofNullable(descriptors.get(new Key(id, version)));
    }

    public static Build build(GrammarDecl grammar, UBNFSourceSnapshot snapshot) {
        Map<Key, Descriptor> descriptors = new LinkedHashMap<>();
        builtin(descriptors, "tinyexpression.string", "org.unlaxer.tinyexpression.parser.StringLiteralParser");
        builtin(descriptors, "tinyexpression.code-start", "org.unlaxer.tinyexpression.parser.javalang.CodeStartParser");
        builtin(descriptors, "tinyexpression.code-end", "org.unlaxer.tinyexpression.parser.javalang.CodeEndParser");
        builtin(descriptors, "tinyexpression.long-code-block", "org.unlaxer.tinyexpression.parser.javalang.LongCodeBlockParser");
        List<Diagnostic> diagnostics = new ArrayList<>();

        for (GlobalSetting setting : grammar.settings()) {
            if (!setting.key().equals("tokenAdapter")) continue;
            Span span = span(snapshot, setting);
            if (!(setting.value() instanceof BlockSettingValue block)) {
                diagnostics.add(new Diagnostic("P-ADAPTER-DEFINITION", span, "tokenAdapter"));
                continue;
            }
            Map<String, String> fields = new HashMap<>();
            boolean invalid = false;
            for (KeyValuePair entry : block.entries()) {
                if ((!REQUIRED_FIELDS.contains(entry.key()) && !OPTIONAL_FIELDS.contains(entry.key()))
                    || fields.putIfAbsent(entry.key(), entry.value()) != null) invalid = true;
            }
            String id = fields.getOrDefault("id", "tokenAdapter");
            Integer version = version(fields.get("version"));
            Set<String> contextAccessors = contextAccessors(fields.get("context"));
            if (invalid || !fields.keySet().containsAll(REQUIRED_FIELDS) || !validId(id) || version == null
                || !validJavaClass(fields.get("java")) || !validRustPath(fields.get("rust"))
                || contextAccessors == null || !validContractText(fields.get("accepts"))
                || !validFailure(fields.get("failure")) || !validConsumes(fields.get("consumes"))) {
                diagnostics.add(new Diagnostic("P-ADAPTER-DEFINITION", span, id));
                continue;
            }
            Descriptor descriptor = new Descriptor(id, version, fields.get("java"), fields.get("rust"),
                fields.get("accepts"), fields.get("failure"), fields.get("consumes"), contextAccessors, false);
            if (descriptors.putIfAbsent(new Key(id, version), descriptor) != null) {
                diagnostics.add(new Diagnostic("P-ADAPTER-DUPLICATE", span, id));
            }
        }

        for (TokenDecl token : grammar.tokens()) {
            if (!(token instanceof TokenDecl.Adapter adapter)) continue;
            Span span = span(snapshot, token);
            Integer version = version(adapter.version());
            if (!validId(adapter.id()) || version == null) {
                diagnostics.add(new Diagnostic("P-ADAPTER-DEFINITION", span, adapter.id()));
            } else if (!descriptors.containsKey(new Key(adapter.id(), version))) {
                boolean knownId = descriptors.keySet().stream().anyMatch(key -> key.id().equals(adapter.id()));
                diagnostics.add(new Diagnostic(knownId ? "P-ADAPTER-VERSION" : "P-ADAPTER-UNKNOWN", span, adapter.id()));
            }
        }
        diagnostics.sort(Comparator.comparingInt((Diagnostic diagnostic) -> diagnostic.span() == null
                ? Integer.MAX_VALUE : diagnostic.span().start())
            .thenComparingInt(diagnostic -> diagnostic.span() == null ? Integer.MAX_VALUE : diagnostic.span().end())
            .thenComparing(Diagnostic::code).thenComparing(Diagnostic::subject));
        return new Build(new TokenAdapterRegistry(descriptors), List.copyOf(diagnostics));
    }

    public static TokenAdapterRegistry requireValid(GrammarDecl grammar) {
        Build build = build(grammar, null);
        if (!build.diagnostics().isEmpty()) {
            Diagnostic first = build.diagnostics().get(0);
            throw new IllegalArgumentException(first.code() + ": " + first.subject());
        }
        return build.registry();
    }

    public static boolean isBuiltin(String id, int version) {
        return version == 1 && Set.of("tinyexpression.string", "tinyexpression.code-start",
            "tinyexpression.code-end", "tinyexpression.long-code-block").contains(id);
    }

    private static void builtin(Map<Key, Descriptor> descriptors, String id, String javaClass) {
        descriptors.put(new Key(id, 1), new Descriptor(id, 1, javaClass, null,
            null, null, null, Set.of(), true));
    }

    private static Set<String> contextAccessors(String raw) {
        if (raw == null || raw.isBlank()) return Set.of();
        Set<String> names = new java.util.LinkedHashSet<>();
        for (String name : raw.split(",", -1)) {
            String normalized = name.trim();
            if (!CONTEXT_ACCESSORS.contains(normalized) || !names.add(normalized)) return null;
        }
        return Set.copyOf(names);
    }

    private static boolean validContractText(String text) {
        return text == null || !text.isBlank();
    }

    private static boolean validFailure(String failure) {
        return failure == null || Set.of("no-consume", "may-consume").contains(failure);
    }

    private static boolean validConsumes(String consumes) {
        return consumes == null || Set.of("always", "maybe", "never").contains(consumes);
    }

    private static Span span(UBNFSourceSnapshot snapshot, Object node) {
        return snapshot == null ? null : snapshot.spanOf(node).orElse(null);
    }

    private static Integer version(String raw) {
        if (raw == null || !raw.matches("[0-9]+")) return null;
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");
    }

    private static boolean validJavaClass(String name) {
        if (name == null) return false;
        String[] parts = name.split("\\.", -1);
        if (parts.length < 2) return false;
        for (String part : parts) {
            if (!part.matches("[_A-Za-z][_A-Za-z0-9]*") || part.equals("_")
                || SourceVersion.isKeyword(part) || JAVA_RESTRICTED.contains(part)) return false;
        }
        return true;
    }

    private static boolean validRustPath(String path) {
        if (path == null) return false;
        String[] parts = path.split("::", -1);
        if (parts.length < 2) return false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (!part.matches("[_A-Za-z][_A-Za-z0-9]*") || part.equals("_")
                || (RUST_KEYWORDS.contains(part) && !(i == 0 && Set.of("crate", "self", "super").contains(part)))) {
                return false;
            }
        }
        return true;
    }
}
