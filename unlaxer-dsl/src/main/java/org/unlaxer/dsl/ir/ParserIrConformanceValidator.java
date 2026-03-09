package org.unlaxer.dsl.ir;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.HashMap;

/**
 * Lightweight runtime contract checks for parser IR adapters.
 */
public final class ParserIrConformanceValidator {
    private static final Set<String> SCOPE_EVENTS = Set.of("enterScope", "leaveScope", "define", "use");
    private static final Set<String> SCOPE_MODES = Set.of("lexical", "dynamic");

    private ParserIrConformanceValidator() {}

    public static void validate(ParserIrDocument document) {
        Objects.requireNonNull(document, "document");
        Map<String, Object> payload = document.payload();

        String irVersion = readString(payload, "irVersion");
        if (irVersion.isBlank()) {
            throw new IllegalArgumentException("irVersion must not be blank");
        }
        String source = readString(payload, "source");
        if (source.trim().isEmpty()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        List<Object> nodes = readArray(payload, "nodes");
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes must not be empty");
        }
        readArray(payload, "diagnostics");
        Map<String, Map<String, Object>> nodesById = new LinkedHashMap<>();
        for (Object item : nodes) {
            if (!(item instanceof Map<?, ?> rawNode)) {
                throw new IllegalArgumentException("node must be object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) rawNode;
            String nodeId = readString(node, "id");
            if (nodesById.put(nodeId, node) != null) {
                throw new IllegalArgumentException("duplicate node id: " + nodeId);
            }
            readString(node, "kind");
            Map<String, Object> span = readObject(node, "span");
            long start = readLong(span, "start");
            long end = readLong(span, "end");
            if (start < 0 || end < 0) {
                throw new IllegalArgumentException("span.start and span.end must be non-negative");
            }
            if (start > end) {
                throw new IllegalArgumentException("span.start <= span.end required");
            }
        }
        validateParentChildLinks(nodesById);
        validateAnnotationTargets(payload, nodesById.keySet());
        validateScopeEvents(payload);
    }

    private static void validateParentChildLinks(Map<String, Map<String, Object>> nodesById) {
        for (Map.Entry<String, Map<String, Object>> entry : nodesById.entrySet()) {
            String nodeId = entry.getKey();
            Map<String, Object> node = entry.getValue();
            if (node.containsKey("parentId")) {
                String parentId = readString(node, "parentId");
                if (!nodesById.containsKey(parentId)) {
                    throw new IllegalArgumentException("unknown parentId: " + parentId);
                }
                Map<String, Object> parent = nodesById.get(parentId);
                if (!parent.containsKey("children")) {
                    throw new IllegalArgumentException("missing parent children link: parent=" + parentId + " child=" + nodeId);
                }
                List<Object> children = readArray(parent, "children");
                if (!children.contains(nodeId)) {
                    throw new IllegalArgumentException("missing parent children link: parent=" + parentId + " child=" + nodeId);
                }
            }
            if (node.containsKey("children")) {
                List<Object> children = readArray(node, "children");
                Set<String> seen = new HashSet<>();
                for (Object childObj : children) {
                    if (!(childObj instanceof String childId) || childId.isBlank()) {
                        throw new IllegalArgumentException("invalid child id type");
                    }
                    if (!seen.add(childId)) {
                        throw new IllegalArgumentException("duplicate child id: " + childId);
                    }
                    if (!nodesById.containsKey(childId)) {
                        throw new IllegalArgumentException("unknown child id: " + childId);
                    }
                }
            }
        }
    }

    private static void validateAnnotationTargets(Map<String, Object> payload, Set<String> nodeIds) {
        if (!payload.containsKey("annotations")) {
            return;
        }
        List<Object> annotations = readArray(payload, "annotations");
        for (Object item : annotations) {
            if (!(item instanceof Map<?, ?> rawAnnotation)) {
                throw new IllegalArgumentException("annotation must be object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> annotation = (Map<String, Object>) rawAnnotation;
            String targetId = readString(annotation, "targetId");
            if (!nodeIds.contains(targetId)) {
                throw new IllegalArgumentException("unknown annotation targetId: " + targetId);
            }
        }
    }

    private static void validateScopeEvents(Map<String, Object> payload) {
        if (!payload.containsKey("scopeEvents")) {
            return;
        }
        List<Object> scopeEvents = readArray(payload, "scopeEvents");
        Set<String> knownScopeIds = new HashSet<>();
        for (Object item : scopeEvents) {
            if (!(item instanceof Map<?, ?> rawEvent)) {
                throw new IllegalArgumentException("scopeEvent must be object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) rawEvent;
            String eventName = readString(event, "event");
            if (!SCOPE_EVENTS.contains(eventName)) {
                throw new IllegalArgumentException("unsupported scope event: " + eventName);
            }
            String scopeId = readString(event, "scopeId");
            Map<String, Object> span = readObject(event, "span");
            long start = readLong(span, "start");
            long end = readLong(span, "end");
            if (start < 0 || end < 0) {
                throw new IllegalArgumentException("scopeEvent span.start and span.end must be non-negative");
            }
            if (start > end) {
                throw new IllegalArgumentException("scopeEvent span.start <= span.end required");
            }
            knownScopeIds.add(scopeId);

            if ("define".equals(eventName) || "use".equals(eventName)) {
                if (!event.containsKey("symbol")) {
                    throw new IllegalArgumentException(eventName + " requires symbol");
                }
            }
            if ("define".equals(eventName) && !event.containsKey("kind")) {
                throw new IllegalArgumentException("define requires kind");
            }
            if ("use".equals(eventName) && event.containsKey("kind")) {
                throw new IllegalArgumentException("use must not include kind");
            }
            if (event.containsKey("scopeMode")) {
                String scopeMode = readString(event, "scopeMode").trim();
                if (!SCOPE_MODES.contains(scopeMode)) {
                    throw new IllegalArgumentException("unsupported scopeMode: " + scopeMode);
                }
                if ("define".equals(eventName) || "use".equals(eventName)) {
                    throw new IllegalArgumentException(eventName + " must not include scopeMode");
                }
            }
            if ("enterScope".equals(eventName) || "leaveScope".equals(eventName)) {
                if (event.containsKey("symbol") || event.containsKey("kind") || event.containsKey("targetScopeId")) {
                    throw new IllegalArgumentException("enter/leave must not include symbol/kind/targetScopeId");
                }
            }
        }

        Set<String> openScopes = new HashSet<>();
        Deque<String> scopeStack = new ArrayDeque<>();
        Map<String, String> scopeModeByScopeId = new HashMap<>();
        for (Object item : scopeEvents) {
            @SuppressWarnings("unchecked")
            Map<String, Object> event = (Map<String, Object>) item;
            String eventName = readString(event, "event");
            String scopeId = readString(event, "scopeId");
            if ("enterScope".equals(eventName)) {
                if (!openScopes.add(scopeId)) {
                    throw new IllegalArgumentException("duplicate enterScope for scopeId: " + scopeId);
                }
                if (event.containsKey("scopeMode")) {
                    scopeModeByScopeId.put(scopeId, readString(event, "scopeMode").trim());
                }
                scopeStack.push(scopeId);
                continue;
            }
            if ("define".equals(eventName) || "use".equals(eventName)) {
                if (!openScopes.contains(scopeId)) {
                    throw new IllegalArgumentException("scope order violated for scopeId: " + scopeId + " event=" + eventName);
                }
                if ("use".equals(eventName) && event.containsKey("targetScopeId")) {
                    String targetScopeId = readString(event, "targetScopeId");
                    if (!knownScopeIds.contains(targetScopeId)) {
                        throw new IllegalArgumentException("unknown targetScopeId: " + targetScopeId);
                    }
                }
                continue;
            }
            if ("leaveScope".equals(eventName)) {
                if (!openScopes.contains(scopeId)) {
                    throw new IllegalArgumentException("scope balance violated for scopeId: " + scopeId);
                }
                String expected = scopeStack.peek();
                if (!scopeId.equals(expected)) {
                    throw new IllegalArgumentException(
                        "scope nesting violated: expected leaveScope for scopeId: " + expected + " but got: " + scopeId);
                }
                if (event.containsKey("scopeMode")) {
                    String leavingMode = readString(event, "scopeMode").trim();
                    String enteredMode = scopeModeByScopeId.get(scopeId);
                    if (enteredMode != null && !enteredMode.equals(leavingMode)) {
                        throw new IllegalArgumentException(
                            "scopeMode mismatch for scopeId: " + scopeId + " enter=" + enteredMode + " leave=" + leavingMode);
                    }
                }
                scopeStack.pop();
                openScopes.remove(scopeId);
            }
        }
        if (!scopeStack.isEmpty()) {
            throw new IllegalArgumentException("scope balance violated: unclosed scopes " + scopeStack);
        }
    }

    private static String readString(Map<String, Object> obj, String key) {
        Object value = obj.get(key);
        if (!(value instanceof String stringValue)) {
            throw new IllegalArgumentException("missing or invalid string key: " + key);
        }
        return stringValue;
    }

    private static long readLong(Map<String, Object> obj, String key) {
        Object value = obj.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("missing or invalid number key: " + key);
    }

    private static List<Object> readArray(Map<String, Object> obj, String key) {
        Object value = obj.get(key);
        if (!(value instanceof List<?> arrayValue)) {
            throw new IllegalArgumentException("missing or invalid array key: " + key);
        }
        @SuppressWarnings("unchecked")
        List<Object> casted = (List<Object>) arrayValue;
        return casted;
    }

    private static Map<String, Object> readObject(Map<String, Object> obj, String key) {
        Object value = obj.get(key);
        if (!(value instanceof Map<?, ?> rawObject)) {
            throw new IllegalArgumentException("missing or invalid object key: " + key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> casted = (Map<String, Object>) rawObject;
        return casted;
    }
}
