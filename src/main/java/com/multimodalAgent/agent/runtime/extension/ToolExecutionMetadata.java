package com.multimodalAgent.agent.runtime.extension;

import com.multimodalAgent.agent.recovery.ToolRecoveryContractSnapshot;

import java.util.Objects;
import java.util.Optional;

public record ToolExecutionMetadata(
        String toolCallId,
        String toolName,
        int iteration,
        Optional<ToolRecoveryContractSnapshot> recoveryContract
) {

    public ToolExecutionMetadata(String toolCallId, String toolName, int iteration) {
        this(toolCallId, toolName, iteration, Optional.empty());
    }

    public ToolExecutionMetadata(
            String toolCallId,
            String toolName,
            int iteration,
            ToolRecoveryContractSnapshot recoveryContract
    ) {
        this(toolCallId, toolName, iteration, Optional.of(recoveryContract));
    }

    public ToolExecutionMetadata {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName must not be blank");
        }
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        Objects.requireNonNull(recoveryContract, "recoveryContract must not be null");
        if (recoveryContract.isPresent()
                && !toolName.equals(recoveryContract.orElseThrow().toolName())) {
            throw new IllegalArgumentException(
                    "Tool metadata identity must match recovery contract"
            );
        }
    }
}
