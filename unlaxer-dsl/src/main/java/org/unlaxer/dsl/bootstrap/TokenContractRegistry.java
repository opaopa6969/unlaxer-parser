package org.unlaxer.dsl.bootstrap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.unlaxer.dsl.bootstrap.UBNFAST.BlockSettingValue;
import org.unlaxer.dsl.bootstrap.UBNFAST.GlobalSetting;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.KeyValuePair;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot.Span;

/** Format-2, source-visible contracts for both built-in and host-bound tokens. */
public final class TokenContractRegistry {
    private static final Set<String> FIELDS = Set.of("token", "accepts", "failure", "consumes", "context");

    public record Contract(String token, String accepts, String failure, String consumes,
                           Set<String> contextAccessors) {}
    public record Diagnostic(String code, Span span, String subject) {}
    public record Build(Map<String, Contract> contracts, List<Diagnostic> diagnostics) {}

    private TokenContractRegistry() {}

    public static Build build(GrammarDecl grammar, UBNFSourceSnapshot snapshot) {
        Set<String> tokenNames = grammar.tokens().stream().map(token -> token.name())
            .collect(java.util.stream.Collectors.toSet());
        boolean format2 = grammar.settings().stream().anyMatch(setting -> setting.key().equals("ubnf")
            && setting.value() instanceof UBNFAST.StringSettingValue value && value.value().equals("v2"));
        Map<String, Contract> contracts = new HashMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        for (GlobalSetting setting : grammar.settings()) {
            if (!setting.key().equals("tokenContract")) continue;
            Span span = span(snapshot, setting);
            if (!format2) {
                diagnostics.add(new Diagnostic("P-TOKEN-CONTRACT-VERSION", span, "tokenContract"));
                continue;
            }
            if (!(setting.value() instanceof BlockSettingValue block)) {
                diagnostics.add(new Diagnostic("P-TOKEN-CONTRACT-DEFINITION", span, "tokenContract"));
                continue;
            }
            Map<String, String> fields = new HashMap<>();
            boolean invalid = false;
            for (KeyValuePair entry : block.entries()) {
                if (!FIELDS.contains(entry.key()) || fields.putIfAbsent(entry.key(), entry.value()) != null) invalid = true;
            }
            String token = fields.getOrDefault("token", "tokenContract");
            Set<String> context = contextAccessors(fields.get("context"));
            if (invalid || !fields.keySet().equals(FIELDS) || !tokenNames.contains(token)
                || fields.get("accepts").isBlank() || !Set.of("no-consume", "may-consume").contains(fields.get("failure"))
                || !Set.of("always", "maybe", "never").contains(fields.get("consumes"))
                || context == null) {
                diagnostics.add(new Diagnostic("P-TOKEN-CONTRACT-DEFINITION", span, token));
                continue;
            }
            Contract contract = new Contract(token, fields.get("accepts"), fields.get("failure"),
                fields.get("consumes"), context);
            if (contracts.putIfAbsent(token, contract) != null) {
                diagnostics.add(new Diagnostic("P-TOKEN-CONTRACT-DUPLICATE", span, token));
            }
        }
        diagnostics.sort(Comparator.comparingInt((Diagnostic diagnostic) -> diagnostic.span() == null
            ? Integer.MAX_VALUE : diagnostic.span().start()).thenComparing(Diagnostic::code));
        return new Build(Map.copyOf(contracts), List.copyOf(diagnostics));
    }

    private static Set<String> contextAccessors(String raw) {
        if (raw == null) return null;
        if (raw.isBlank()) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (String name : raw.split(",", -1)) {
            String normalized = name.trim();
            if (!TokenAdapterRegistry.CONTEXT_ACCESSORS.contains(normalized) || !names.add(normalized)) return null;
        }
        return Set.copyOf(names);
    }

    private static Span span(UBNFSourceSnapshot snapshot, Object node) {
        return snapshot == null ? null : snapshot.spanOf(node).orElse(null);
    }
}
