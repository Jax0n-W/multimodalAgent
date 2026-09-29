package com.multimodalAgent.agent.recovery;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Appends one exact recovered tool result to a new safe continuation checkpoint. */
public final class ReliableToolOutcomeCheckpointAdvancer {

    private final RecoveryAuthorityGuard authorityGuard;
    private final RecoveryCheckpointStore checkpointStore;
    private final Clock clock;

    public ReliableToolOutcomeCheckpointAdvancer(
            RecoveryAuthorityGuard authorityGuard,
            RecoveryCheckpointStore checkpointStore,
            Clock clock
    ) {
        this.authorityGuard = Objects.requireNonNull(
                authorityGuard,
                "authorityGuard must not be null"
        );
        this.checkpointStore = Objects.requireNonNull(
                checkpointStore,
                "checkpointStore must not be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public RecoveryCheckpoint advance(
            RecoveryCheckpoint latest,
            ReliableToolOutcome outcome,
            RecoveredBudgetUsage recoveredBudget
    ) {
        Objects.requireNonNull(latest, "latest must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(recoveredBudget, "recoveredBudget must not be null");
        if (!latest.runId().equals(outcome.runId())) {
            throw new IllegalArgumentException("Checkpoint and outcome run identity must match");
        }
        authorityGuard.assertAuthority(latest.runId());
        if (!latest.seenToolCallIds().contains(outcome.toolCallId())) {
            throw new IllegalStateException("Recovered tool call is absent from checkpoint state");
        }
        RecoveryCheckpoint existing = matchingCheckpoint(latest, outcome);
        if (existing != null) {
            return existing;
        }

        long sequence = Math.addExact(latest.sequence(), 1L);
        List<AgentMessage> messages = new ArrayList<>(latest.messages());
        messages.add(AgentMessage.toolResult(
                outcome.toolCallId(),
                outcome.toolName(),
                outcome.modelVisibleResult()
        ));
        LinkedHashSet<String> toolsUsed = new LinkedHashSet<>(latest.toolsUsed());
        toolsUsed.add(outcome.toolName());
        RecoveryCheckpoint advanced = new RecoveryCheckpoint(
                RecoveryCheckpointIds.stable(
                        latest.runId(),
                        sequence,
                        RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME,
                        latest.iteration(),
                        outcome.toolCallId()
                ),
                latest.runId(),
                sequence,
                latest.iteration(),
                RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME,
                List.copyOf(messages),
                List.copyOf(toolsUsed),
                latest.seenToolCallIds(),
                recoveredBudget.toCheckpoint(),
                latest.approvedToolCallIds(),
                latest.runtimeConfigSnapshotId(),
                clock.instant(),
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
        checkpointStore.persist(advanced);
        return advanced;
    }

    private RecoveryCheckpoint matchingCheckpoint(
            RecoveryCheckpoint latest,
            ReliableToolOutcome outcome
    ) {
        for (AgentMessage message : latest.messages()) {
            if (message.role() == AgentMessageRole.TOOL
                    && outcome.toolCallId().equals(message.toolCallId())) {
                if (!outcome.toolName().equals(message.toolName())
                        || !outcome.modelVisibleResult().equals(message.content())) {
                    throw new IllegalStateException(
                            "Checkpoint contains a conflicting result for recovered tool call"
                    );
                }
                return latest;
            }
        }
        return null;
    }
}
