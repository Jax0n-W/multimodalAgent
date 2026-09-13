package com.multimodalAgent.agent.runtime.extension;

import java.util.Objects;

public record RuntimeAttributeKey<T>(String name, Class<T> valueType) {

    public RuntimeAttributeKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(valueType, "valueType must not be null");
    }

    public static <T> RuntimeAttributeKey<T> of(String name, Class<T> valueType) {
        return new RuntimeAttributeKey<>(name, valueType);
    }
}
