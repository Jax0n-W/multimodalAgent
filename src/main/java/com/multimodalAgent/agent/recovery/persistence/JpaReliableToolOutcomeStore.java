package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.entity.ToolExecutionOutcomeEntity;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionOutcomeRepository;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.recovery.ReliableToolOutcome;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeConflictException;
import com.multimodalAgent.agent.recovery.ReliableToolOutcomeStore;
import com.multimodalAgent.agent.runtime.tool.ToolOutcomeRecorder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Durable adapter used both by Runtime recording and recovery reads. */
@Component
public class JpaReliableToolOutcomeStore
        implements ReliableToolOutcomeStore, ToolOutcomeRecorder {

    private final ToolExecutionRepository executionRepository;
    private final ToolExecutionOutcomeRepository outcomeRepository;

    public JpaReliableToolOutcomeStore(
            ToolExecutionRepository executionRepository,
            ToolExecutionOutcomeRepository outcomeRepository
    ) {
        this.executionRepository = Objects.requireNonNull(
                executionRepository,
                "executionRepository must not be null"
        );
        this.outcomeRepository = Objects.requireNonNull(
                outcomeRepository,
                "outcomeRepository must not be null"
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(
            String runId,
            String toolCallId,
            String toolName,
            String modelVisibleResult
    ) {
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        requireText(toolName, "toolName");
        Objects.requireNonNull(modelVisibleResult, "modelVisibleResult must not be null");
        ToolExecutionEntity execution = requireExecution(runId, toolCallId);
        validateExecutionIdentity(execution, execution.getExecutionId(), toolName);
        requireRecordableStatus(execution);
        persistLocked(ReliableToolOutcome.capture(
                execution.getExecutionId(),
                runId,
                toolCallId,
                toolName,
                modelVisibleResult,
                Instant.now()
        ));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(ReliableToolOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        ToolExecutionEntity execution = requireExecution(outcome.runId(), outcome.toolCallId());
        validateExecutionIdentity(execution, outcome.executionId(), outcome.toolName());
        requireRecordableStatus(execution);
        persistLocked(outcome);
    }

    private void persistLocked(ReliableToolOutcome outcome) {
        Optional<ToolExecutionOutcomeEntity> existing =
                outcomeRepository.findByExecutionId(outcome.executionId());
        if (existing.isPresent()) {
            if (!toDomain(existing.get()).sameExactOutcome(outcome)) {
                throw new ReliableToolOutcomeConflictException(outcome.executionId());
            }
            return;
        }
        outcomeRepository.saveAndFlush(new ToolExecutionOutcomeEntity(
                outcome.executionId(),
                outcome.runId(),
                outcome.toolCallId(),
                outcome.toolName(),
                outcome.modelVisibleResult(),
                outcome.payloadHash(),
                outcome.recordedAt(),
                outcome.schemaVersion()
        ));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReliableToolOutcome> findByExecutionId(String executionId) {
        requireText(executionId, "executionId");
        return outcomeRepository.findByExecutionId(executionId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReliableToolOutcome> findByRunIdAndToolCallId(
            String runId,
            String toolCallId
    ) {
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        return outcomeRepository.findByRunIdAndToolCallId(runId, toolCallId)
                .map(this::toDomain);
    }

    private ToolExecutionEntity requireExecution(String runId, String toolCallId) {
        return executionRepository.findForRecoveryMutation(runId, toolCallId)
                .orElseThrow(() -> new IllegalStateException(
                        "Started tool execution not found for reliable outcome: "
                                + runId + "/" + toolCallId
                ));
    }

    private void validateExecutionIdentity(
            ToolExecutionEntity execution,
            String executionId,
            String toolName
    ) {
        if (!execution.getExecutionId().equals(executionId)
                || !execution.getToolName().equals(toolName)) {
            throw new IllegalStateException("Reliable tool outcome identity does not match execution");
        }
    }

    private void requireRecordableStatus(ToolExecutionEntity execution) {
        ToolExecutionStatus status = execution.getStatus();
        if (status != ToolExecutionStatus.STARTED
                && status != ToolExecutionStatus.UNKNOWN
                && status != ToolExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "Reliable outcome cannot be recorded for tool status " + status
            );
        }
    }

    private ReliableToolOutcome toDomain(ToolExecutionOutcomeEntity entity) {
        return new ReliableToolOutcome(
                entity.getExecutionId(),
                entity.getRunId(),
                entity.getToolCallId(),
                entity.getToolName(),
                entity.getResultPayload(),
                entity.getPayloadHash(),
                entity.getRecordedAt(),
                entity.getSchemaVersion()
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
