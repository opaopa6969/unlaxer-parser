package org.unlaxer.dsl.ir;

import java.util.Map;
import java.util.Objects;

/**
 * Parser IR payload exchanged between adapters and downstream generators.
 */
public record ParserIrDocument(Map<String, Object> payload) {
    public ParserIrDocument {
        Objects.requireNonNull(payload, "payload");
        payload = Map.copyOf(payload);
    }
}
