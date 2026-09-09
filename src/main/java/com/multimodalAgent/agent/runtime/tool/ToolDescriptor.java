package com.multimodalAgent.agent.runtime.tool;

import java.util.Objects;

public record ToolDescriptor<I>(
        String name,
        String description,
        Class<I> inputType,
        ToolRisk risk,
        boolean readOnly,
        boolean idempotent,
        boolean requiresApproval
) {

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool description must not be blank");
        }
        Objects.requireNonNull(inputType, "inputType must not be null");
        Objects.requireNonNull(risk, "risk must not be null");
    }
}
