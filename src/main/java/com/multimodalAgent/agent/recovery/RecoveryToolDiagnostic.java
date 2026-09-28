package com.multimodalAgent.agent.recovery;

import java.util.Objects;

public record RecoveryToolDiagnostic(
        String toolCallId,
        String toolName,
        RecoveryToolStatus durableStatus
) {

    public RecoveryToolDiagnostic {
        requireText(toolCallId, "toolCallId");
        requireText(toolName, "toolName");
        Objects.requireNonNull(durableStatus, "durableStatus must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
