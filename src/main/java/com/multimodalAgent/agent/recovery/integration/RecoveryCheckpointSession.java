package com.multimodalAgent.agent.recovery.integration;

import com.multimodalAgent.agent.recovery.BudgetCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointBoundary;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointIds;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointStore;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.budget.BudgetUsage;
import com.multimodalAgent.agent.runtime.budget.ModelCostCalculator;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.tool.ToolResult;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Mutable execution-scoped mirror of the continuation state owned by AgentRunner. */
public final class RecoveryCheckpointSession {

    private final AgentRunSpec spec;
    private final String runtimeConfigSnapshotId;
    private final Optional<ModelIdentity> modelIdentity;
    private final RecoveryCheckpointStore store;
    private final Clock clock;
    private final ModelCostCalculator costCalculator = new ModelCostCalculator();
    private final List<AgentMessage> messages;
    private final LinkedHashSet<String> toolsUsed = new LinkedHashSet<>();
    private final LinkedHashSet<String> seenToolCallIds = new LinkedHashSet<>();
    private long checkpointSequence;
    private long modelCalls;
    private long toolCalls;
    private long inputTokens;
    private long outputTokens;
    private long totalTokens;
    private BigDecimal knownCost = BigDecimal.ZERO;
    private boolean unknownUsageObserved;

    RecoveryCheckpointSession(
            AgentRunSpec spec,
            String runtimeConfigSnapshotId,
            Optional<ModelIdentity> modelIdentity,
            RecoveryCheckpointStore store,
            Clock clock
    ) {
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
        if (runtimeConfigSnapshotId == null || runtimeConfigSnapshotId.isBlank()) {
            throw new IllegalArgumentException("runtimeConfigSnapshotId must not be blank");
        }
        this.runtimeConfigSnapshotId = runtimeConfigSnapshotId;
        this.modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.messages = new ArrayList<>(spec.messages());
    }

    public RecoveryCheckpointSession(
            AgentRunSpec spec,
            RecoveryCheckpoint checkpoint,
            BudgetUsage recoveredUsage,
            Optional<ModelIdentity> modelIdentity,
            RecoveryCheckpointStore store,
            Clock clock
    ) {
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        if (!spec.runId().equals(checkpoint.runId())) {
            throw new IllegalArgumentException("Checkpoint run identity mismatch");
        }
        this.runtimeConfigSnapshotId = checkpoint.runtimeConfigSnapshotId();
        this.modelIdentity = Objects.requireNonNull(modelIdentity, "modelIdentity must not be null");
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.messages = new ArrayList<>(checkpoint.messages());
        this.toolsUsed.addAll(checkpoint.toolsUsed());
        this.seenToolCallIds.addAll(checkpoint.seenToolCallIds());
        this.checkpointSequence = checkpoint.sequence();
        apply(Objects.requireNonNull(recoveredUsage, "recoveredUsage must not be null"));
    }

    synchronized void iterationBoundary(int completedIteration) {
        persist(RecoveryCheckpointBoundary.ITERATION_BOUNDARY, completedIteration, "iteration");
    }

    synchronized void afterModelOutcome(int iteration, ModelTurn turn) {
        Objects.requireNonNull(turn, "turn must not be null");
        modelCalls = Math.addExact(modelCalls, 1L);
        account(turn.tokenUsage());
        if (turn.finishReason() == ModelFinishReason.TOOL_CALLS) {
            messages.add(AgentMessage.assistantToolCalls(turn.toolCalls()));
            for (ToolCall toolCall : turn.toolCalls()) {
                seenToolCallIds.add(toolCall.id());
            }
        } else {
            messages.add(AgentMessage.assistant(turn.content()));
        }
        persist(RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME, iteration, "model");
    }

    synchronized void afterToolOutcome(int iteration, String toolCallId, String toolName, ToolResult result) {
        Objects.requireNonNull(result, "result must not be null");
        toolCalls = Math.addExact(toolCalls, 1L);
        messages.add(AgentMessage.toolResult(toolCallId, toolName, result.messageForModel()));
        toolsUsed.add(toolName);
        persist(RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME, iteration, toolCallId);
    }

    synchronized void afterRun(AgentRunResult result, BudgetUsage exactBudgetUsage) {
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(exactBudgetUsage, "exactBudgetUsage must not be null");
        messages.clear();
        messages.addAll(result.messages());
        toolsUsed.clear();
        toolsUsed.addAll(result.toolsUsed());
        apply(exactBudgetUsage);

        switch (result.stopReason()) {
            case WAITING_APPROVAL -> persist(
                    RecoveryCheckpointBoundary.WAITING_APPROVAL,
                    result.iterations(),
                    "approval"
            );
            case TOOL_ERROR -> persist(
                    RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME,
                    result.iterations(),
                    "terminal-tool-error"
            );
            default -> {
                // Other terminal states either already have a safe outcome checkpoint or are
                // intentionally not classified as resumable by P10.1.
            }
        }
    }

    private void account(TokenUsage usage) {
        if (!usage.isComplete()) {
            unknownUsageObserved = true;
            return;
        }
        inputTokens = Math.addExact(inputTokens, usage.inputTokens());
        outputTokens = Math.addExact(outputTokens, usage.outputTokens());
        totalTokens = Math.addExact(totalTokens, usage.totalTokens());
        if (spec.budget().pricing().isPresent() && modelIdentity.isPresent()) {
            costCalculator.calculate(
                    modelIdentity.get(), usage, spec.budget().pricing().get()
            ).ifPresent(value -> knownCost = knownCost.add(value));
        }
    }

    private void apply(BudgetUsage usage) {
        modelCalls = usage.modelCalls();
        toolCalls = usage.toolCalls();
        inputTokens = usage.inputTokens();
        outputTokens = usage.outputTokens();
        totalTokens = usage.totalTokens();
        knownCost = usage.cost().orElse(BigDecimal.ZERO);
        unknownUsageObserved = usage.unknownUsageObserved();
    }

    private BudgetCheckpoint budgetCheckpoint() {
        Optional<BigDecimal> cost = spec.budget().pricing().isPresent()
                && modelIdentity.isPresent()
                && spec.budget().pricing().get().identity().equals(modelIdentity.get())
                && !unknownUsageObserved
                ? Optional.of(knownCost)
                : Optional.empty();
        return new BudgetCheckpoint(
                modelCalls, toolCalls, inputTokens, outputTokens, totalTokens,
                cost, unknownUsageObserved
        );
    }

    private void persist(
            RecoveryCheckpointBoundary boundary,
            int iteration,
            String discriminator
    ) {
        long sequence = Math.addExact(checkpointSequence, 1L);
        RecoveryCheckpoint checkpoint = new RecoveryCheckpoint(
                RecoveryCheckpointIds.stable(
                        spec.runId(), sequence, boundary, iteration, discriminator
                ),
                spec.runId(),
                sequence,
                iteration,
                boundary,
                List.copyOf(messages),
                List.copyOf(toolsUsed),
                Set.copyOf(seenToolCallIds),
                budgetCheckpoint(),
                spec.approvedToolCallIds(),
                runtimeConfigSnapshotId,
                clock.instant(),
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
        store.persist(checkpoint);
        checkpointSequence = sequence;
    }
}
