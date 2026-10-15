package com.multimodalAgent.agent.runtime.tool;

import com.multimodalAgent.agent.recovery.ToolRecoveryContract;
import com.multimodalAgent.agent.recovery.ToolReplaySemantics;

import java.time.Duration;
import java.util.Objects;

public record ToolDescriptor<I>(
        String name,
        String description,
        Class<I> inputType,
        ToolRisk risk,
        boolean readOnly,
        boolean idempotent,
        boolean requiresApproval,
        ToolRecoveryContract recoveryContract,
        Duration executionTimeout
) {

    public static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofSeconds(10);

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
                ToolRecoveryContract.defaults(readOnly, idempotent),
                DEFAULT_EXECUTION_TIMEOUT
        );
    }

    public ToolDescriptor(
            String name,
            String description,
            Class<I> inputType,
            ToolRisk risk,
            boolean readOnly,
            boolean idempotent,
            boolean requiresApproval,
            Duration executionTimeout
    ) {
        this(
                name,
                description,
                inputType,
                risk,
                readOnly,
                idempotent,
                requiresApproval,
                ToolRecoveryContract.defaults(readOnly, idempotent),
                executionTimeout
        );
    }

    public ToolDescriptor(
            String name,
            String description,
            Class<I> inputType,
            ToolRisk risk,
            boolean readOnly,
            boolean idempotent,
            boolean requiresApproval,
            ToolRecoveryContract recoveryContract
    ) {
        this(
                name,
                description,
                inputType,
                risk,
                readOnly,
                idempotent,
                requiresApproval,
                recoveryContract,
                DEFAULT_EXECUTION_TIMEOUT
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
        Objects.requireNonNull(executionTimeout, "executionTimeout must not be null");
        if (executionTimeout.isZero() || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("executionTimeout must be positive");
        }
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
