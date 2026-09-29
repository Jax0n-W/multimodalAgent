package com.multimodalAgent.agent.runtime.tool;

import com.multimodalAgent.agent.recovery.ToolRecoveryContract;
import com.multimodalAgent.agent.recovery.ToolReplaySemantics;

import java.util.Objects;

public record ToolDescriptor<I>(
        String name,
        String description,
        Class<I> inputType,
        ToolRisk risk,
        boolean readOnly,
        boolean idempotent,
        boolean requiresApproval,
        ToolRecoveryContract recoveryContract
) {

    public ToolDescriptor(
            String name,
            String description,
            Class<I> inputType,
            ToolRisk risk,
            boolean readOnly,
            boolean idempotent,
            boolean requiresApproval
    ) {
        this(
                name,
                description,
                inputType,
                risk,
                readOnly,
                idempotent,
                requiresApproval,
                ToolRecoveryContract.defaults(readOnly, idempotent)
        );
    }

    public ToolDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tool name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Tool description must not be blank");
        }
        Objects.requireNonNull(inputType, "inputType must not be null");
        Objects.requireNonNull(risk, "risk must not be null");
        Objects.requireNonNull(recoveryContract, "recoveryContract must not be null");
        ToolReplaySemantics expected = readOnly
                ? ToolReplaySemantics.REPLAY_SAFE
                : idempotent
                ? ToolReplaySemantics.IDEMPOTENT
                : ToolReplaySemantics.NON_REPLAYABLE;
        if (recoveryContract.replaySemantics() != expected) {
            throw new IllegalArgumentException(
                    "Tool descriptor flags contradict recovery replay semantics"
            );
        }
    }
}
