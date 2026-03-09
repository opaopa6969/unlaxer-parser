package org.unlaxer.dsl.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared helpers for emitting parser-IR scope events from scope-tree metadata.
 */
public final class ParserIrScopeEvents {
    private static final Set<String> SUPPORTED_SCOPE_MODES = Set.of("lexical", "dynamic");

    private ParserIrScopeEvents() {}

    /**
     * Emits synthetic balanced scope events from node-id keyed scope modes.
     */
    public static List<Object> emitSyntheticEnterLeaveEvents(
        Map<String, String> scopeModeByNodeId,
        List<Object> nodes
    ) {
        if (scopeModeByNodeId == null || scopeModeByNodeId.isEmpty()) {
            return List.of();
        }
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        List<Object> out = new ArrayList<>();
        for (Object item : nodes) {
            if (!(item instanceof Map<?, ?> rawNode)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) rawNode;
            Object nodeIdObj = node.get("id");
            if (!(nodeIdObj instanceof String nodeId) || nodeId.isBlank()) {
                continue;
            }
            String mode = scopeModeByNodeId.get(nodeId);
            if (mode == null) {
                continue;
            }
            String normalizedMode = mode.trim().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_SCOPE_MODES.contains(normalizedMode)) {
                throw new IllegalArgumentException("unsupported scope mode: " + mode);
            }
            Map<String, Object> span = extractSpan(node);
            String scopeId = "scope:" + nodeId;
            out.add(buildScopeEvent("enterScope", scopeId, normalizedMode, span));
            out.add(buildScopeEvent("leaveScope", scopeId, normalizedMode, span));
        }
        return List.copyOf(out);
    }

    /**
     * Emits synthetic balanced scope events from grammar rule metadata.
     * Rule names are normalized into node ids as {@code {grammarName}::{ruleName}}.
     */
    public static List<Object> emitSyntheticEnterLeaveEventsForRules(
        String grammarName,
        Map<String, String> scopeModeByRuleName,
        List<Object> nodes
    ) {
        if (grammarName == null || grammarName.isBlank()) {
            throw new IllegalArgumentException("grammarName must not be blank");
        }
        if (scopeModeByRuleName == null || scopeModeByRuleName.isEmpty()) {
            return List.of();
        }
        Map<String, String> scopeModeByNodeId = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : scopeModeByRuleName.entrySet()) {
            String ruleName = entry.getKey();
            if (ruleName == null || ruleName.isBlank()) {
                continue;
            }
            String nodeId = grammarName + "::" + ruleName.trim();
            scopeModeByNodeId.put(nodeId, entry.getValue());
        }
        return emitSyntheticEnterLeaveEvents(scopeModeByNodeId, nodes);
    }

    /**
     * Emits synthetic balanced scope events from rule metadata values represented
     * as either strings or enums (for generated ScopeMode maps).
     */
    public static List<Object> emitSyntheticEnterLeaveEventsForRulesAnyMode(
        String grammarName,
        Map<String, ?> scopeModeByRuleName,
        List<Object> nodes
    ) {
        return emitSyntheticEnterLeaveEventsForRules(
            grammarName,
            toScopeModeByRuleName(scopeModeByRuleName),
            nodes
        );
    }

    /**
     * Emits synthetic balanced scope events from scope-id keyed metadata values represented
     * as either strings or enums. Scope ids must follow {@code scope:{nodeId}} format.
     */
    public static List<Object> emitSyntheticEnterLeaveEventsForScopeIdsAnyMode(
        Map<String, ?> scopeModeByScopeId,
        List<Object> nodes
    ) {
        return emitSyntheticEnterLeaveEvents(
            toScopeModeByNodeIdFromScopeId(scopeModeByScopeId),
            nodes
        );
    }

    /**
     * Converts mixed scope mode values (string/enum) into normalized rule->mode map.
     */
    public static Map<String, String> toScopeModeByRuleName(Map<String, ?> scopeModeByRuleName) {
        if (scopeModeByRuleName == null || scopeModeByRuleName.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : scopeModeByRuleName.entrySet()) {
            String ruleName = entry.getKey();
            if (ruleName == null || ruleName.isBlank()) {
                continue;
            }
            String normalizedMode = normalizeScopeMode(entry.getValue());
            out.put(ruleName.trim(), normalizedMode);
        }
        return Map.copyOf(out);
    }

    /**
     * Converts mixed scope mode values keyed by {@code scope:{nodeId}} into normalized nodeId->mode map.
     */
    public static Map<String, String> toScopeModeByNodeIdFromScopeId(Map<String, ?> scopeModeByScopeId) {
        if (scopeModeByScopeId == null || scopeModeByScopeId.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : scopeModeByScopeId.entrySet()) {
            String scopeId = entry.getKey();
            if (scopeId == null || scopeId.isBlank()) {
                continue;
            }
            String normalizedScopeId = scopeId.trim();
            if (!normalizedScopeId.startsWith("scope:") || normalizedScopeId.length() <= "scope:".length()) {
                throw new IllegalArgumentException("scopeId must match scope:{nodeId}: " + scopeId);
            }
            String nodeId = normalizedScopeId.substring("scope:".length());
            String normalizedMode = normalizeScopeMode(entry.getValue());
            out.put(nodeId, normalizedMode);
        }
        return Map.copyOf(out);
    }

    private static Map<String, Object> extractSpan(Map<String, Object> node) {
        Object spanObj = node.get("span");
        if (!(spanObj instanceof Map<?, ?> rawSpan)) {
            return defaultSpan();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> span = (Map<String, Object>) rawSpan;
        Object startObj = span.get("start");
        Object endObj = span.get("end");
        if (!(startObj instanceof Number startNum) || !(endObj instanceof Number endNum)) {
            return defaultSpan();
        }
        long start = startNum.longValue();
        long end = endNum.longValue();
        if (start < 0 || end < 0 || start > end) {
            return defaultSpan();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("start", start);
        out.put("end", end);
        return out;
    }

    private static Map<String, Object> defaultSpan() {
        Map<String, Object> span = new LinkedHashMap<>();
        span.put("start", 0L);
        span.put("end", 0L);
        return span;
    }

    private static Map<String, Object> buildScopeEvent(
        String event,
        String scopeId,
        String scopeMode,
        Map<String, Object> span
    ) {
        Map<String, Object> scopeEvent = new LinkedHashMap<>();
        scopeEvent.put("event", event);
        scopeEvent.put("scopeId", scopeId);
        scopeEvent.put("scopeMode", scopeMode);
        scopeEvent.put("span", span);
        return scopeEvent;
    }

    private static String normalizeScopeMode(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("scope mode value must not be null");
        }
        String text;
        if (value instanceof Enum<?> e) {
            text = e.name();
        } else {
            text = String.valueOf(value);
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_SCOPE_MODES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported scope mode: " + text);
        }
        return normalized;
    }
}
