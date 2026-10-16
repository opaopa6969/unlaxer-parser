package org.unlaxer.context;

import org.unlaxer.CapturedValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Context-owned copy-on-write binding state; independent of semantic and lexical scopes. */
final class CaptureBindings implements MutationAwareTransactionalState {
    private final ParseContext context;
    private Map<String, CapturedValue> values = Map.of();

    CaptureBindings(ParseContext context) {
        this.context = context;
        context.registerTransactionalState(this);
    }

    Optional<CapturedValue> get(String name) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(name, "name")));
    }

    void put(String name, CapturedValue value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        context.beforeTransactionalStateMutation(this);
        Map<String, CapturedValue> next = new HashMap<>(values);
        next.put(name, value);
        values = next;
        context.markMemoizationStateChanged();
    }

    @Override public Runnable checkpoint() {
        Map<String, CapturedValue> snapshot = values;
        return () -> values = snapshot;
    }
}
