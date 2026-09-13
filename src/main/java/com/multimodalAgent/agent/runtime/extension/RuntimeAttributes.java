package com.multimodalAgent.agent.runtime.extension;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RuntimeAttributes {

    private final ConcurrentMap<RuntimeAttributeKey<?>, Object> values = new ConcurrentHashMap<>();

    public <T> Optional<T> put(RuntimeAttributeKey<T> key, T value) {
        requireValue(key, value);
        Object previous = values.put(key, value);
        return Optional.ofNullable(previous).map(key.valueType()::cast);
    }

    public <T> Optional<T> get(RuntimeAttributeKey<T> key) {
        Objects.requireNonNull(key, "key must not be null");
        return Optional.ofNullable(values.get(key)).map(key.valueType()::cast);
    }

    public <T> Optional<T> remove(RuntimeAttributeKey<T> key) {
        Objects.requireNonNull(key, "key must not be null");
        return Optional.ofNullable(values.remove(key)).map(key.valueType()::cast);
    }

    public boolean contains(RuntimeAttributeKey<?> key) {
        Objects.requireNonNull(key, "key must not be null");
        return values.containsKey(key);
    }

    public Map<RuntimeAttributeKey<?>, Object> snapshot() {
        return Map.copyOf(values);
    }

    private <T> void requireValue(RuntimeAttributeKey<T> key, T value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (!key.valueType().isInstance(value)) {
            throw new IllegalArgumentException(
                    "Attribute " + key.name() + " requires " + key.valueType().getName()
            );
        }
    }
}
