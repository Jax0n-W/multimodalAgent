package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.model.AgentStepStatus;
import com.multimodalAgent.agent.persistence.model.AgentStepType;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.AgentStepRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.recovery.ReliableToolOutcome;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeMaterialization;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeMaterializationStatus;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeMaterializationStore;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** Atomically repairs only ToolExecution and AgentStep from an exact durable outcome. */
@Component
public class JpaReliableToolOutcomeMaterializationStore
        implements ReliableToolOutcomeMaterializationStore {

    private final ToolExecutionRepository executionRepository;
    private final AgentStepRepository stepRepository;
    private final ReliableToolOutcomeStore outcomeStore;

    public JpaReliableToolOutcomeMaterializationStore(
            ToolExecutionRepository executionRepository,
            AgentStepRepository stepRepository,
            ReliableToolOutcomeStore outcomeStore
    ) {
        this.executionRepository = Objects.requireNonNull(
                executionRepository,
                "executionRepository must not be null"
        );
        this.stepRepository = Objects.requireNonNull(
                stepRepository,
                "stepRepository must not be null"
        );
        this.outcomeStore = Objects.requireNonNull(outcomeStore, "outcomeStore must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReliableToolOutcomeMaterialization materialize(String runId, String toolCallId) {
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        ToolExecutionEntity reference = executionRepository.findByRunIdAndToolCallId(
                runId,
                toolCallId
        ).orElseThrow(() -> new IllegalStateException(
                "Tool execution not found for reliable outcome materialization: "
                        + runId + "/" + toolCallId
        ));
        AgentStepEntity step = stepRepository.findByStepIdForRecoveryMutation(reference.getStepId())
                .orElseThrow(() -> new IllegalStateException(
                        "Tool step not found for reliable outcome materialization: "
                                + reference.getStepId()
                ));
        ToolExecutionEntity execution = executionRepository
                .findForRecoveryMutation(runId, toolCallId)
                .orElseThrow();
        Optional<ReliableToolOutcome> durable =
                outcomeStore.findByExecutionId(execution.getExecutionId());
        if (durable.isEmpty()) {
            return ReliableToolOutcomeMaterialization.withoutOutcome(
                    ReliableToolOutcomeMaterializationStatus.RELIABLE_OUTCOME_UNAVAILABLE
            );
        }
        ReliableToolOutcome outcome = durable.orElseThrow();
        if (!matches(execution, step, outcome)) {
            throw new IllegalStateException("Reliable tool outcome identity does not match execution");
        }

        if (execution.getStatus() == ToolExecutionStatus.SUCCEEDED) {
            if (step.getStatus() != AgentStepStatus.SUCCEEDED) {
                return ReliableToolOutcomeMaterialization.withoutOutcome(
                        ReliableToolOutcomeMaterializationStatus.CONFLICTING_TERMINAL
                );
            }
            return successful(
                    ReliableToolOutcomeMaterializationStatus.ALREADY_MATERIALIZED,
                    outcome
            );
        }
        if (execution.getStatus() == ToolExecutionStatus.FAILED
                || execution.getStatus() == ToolExecutionStatus.BLOCKED
                || execution.getStatus() == ToolExecutionStatus.CANCELLED) {
            return ReliableToolOutcomeMaterialization.withoutOutcome(
                    ReliableToolOutcomeMaterializationStatus.CONFLICTING_TERMINAL
            );
        }
        if (execution.getStatus() == ToolExecutionStatus.PLANNED) {
            return ReliableToolOutcomeMaterialization.withoutOutcome(
                    ReliableToolOutcomeMaterializationStatus.NOT_AMBIGUOUS
            );
        }
        if (step.getStatus() != AgentStepStatus.RUNNING
                && step.getStatus() != AgentStepStatus.SUCCEEDED) {
            return ReliableToolOutcomeMaterialization.withoutOutcome(
                    ReliableToolOutcomeMaterializationStatus.CONFLICTING_TERMINAL
            );
        }

        if (step.getStatus() == AgentStepStatus.RUNNING) {
            step.setStatus(AgentStepStatus.SUCCEEDED);
            step.setCompletedAt(outcome.recordedAt());
            stepRepository.saveAndFlush(step);
        }
        execution.setStatus(ToolExecutionStatus.SUCCEEDED);
        execution.setCompletedAt(outcome.recordedAt());
        executionRepository.saveAndFlush(execution);
        return successful(ReliableToolOutcomeMaterializationStatus.MATERIALIZED, outcome);
    }

    private boolean matches(
            ToolExecutionEntity execution,
            AgentStepEntity step,
            ReliableToolOutcome outcome
    ) {
        return step.getStepType() == AgentStepType.TOOL
                && step.getRunId().equals(execution.getRunId())
                && step.getStepId().equals(execution.getStepId())
                && outcome.executionId().equals(execution.getExecutionId())
                && outcome.runId().equals(execution.getRunId())
                && outcome.toolCallId().equals(execution.getToolCallId())
                && outcome.toolName().equals(execution.getToolName());
    }

    private ReliableToolOutcomeMaterialization successful(
            ReliableToolOutcomeMaterializationStatus status,
            ReliableToolOutcome outcome
    ) {
        return new ReliableToolOutcomeMaterialization(status, Optional.of(outcome));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
