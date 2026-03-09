package org.unlaxer.dsl.ir;

import java.util.Map;
import java.util.Objects;

/**
 * Input contract for external parser adapters.
 */
public record ParseRequest(String sourceId, String content, Map<String, Object> options) {
    public ParseRequest {
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        Objects.requireNonNull(content, "content");
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
