package com.multimodalAgent.agent.runtime.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-neutral JSON Schema exposed to a model for one callable tool.
 */
public record ModelToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema
) {

    public ModelToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool definition name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool definition description must not be blank");
        }
        if (inputSchema == null) {
            throw new IllegalArgumentException("Tool definition inputSchema must not be null");
        }
        inputSchema = immutableMap(inputSchema);
    }

    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
