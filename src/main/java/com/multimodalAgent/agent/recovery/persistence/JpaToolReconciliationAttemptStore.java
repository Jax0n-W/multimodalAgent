package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.entity.ToolReconciliationAttemptEntity;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.persistence.repository.ToolReconciliationAttemptRepository;
import com.multimodalAgent.agent.recovery.ToolReconciliationAttempt;
import com.multimodalAgent.agent.recovery.ToolReconciliationAttemptStatus;
import com.multimodalAgent.agent.recovery.ToolReconciliationAttemptStore;
import com.multimodalAgent.agent.recovery.ToolReconciliationResult;
import com.multimodalAgent.agent.recovery.ToolReconciliationStart;
import com.multimodalAgent.agent.recovery.ToolReconciliationStartDisposition;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class JpaToolReconciliationAttemptStore
        implements ToolReconciliationAttemptStore {

    private final ToolExecutionRepository toolRepository;
    private final ToolReconciliationAttemptRepository attemptRepository;

    public JpaToolReconciliationAttemptStore(
            ToolExecutionRepository toolRepository,
            ToolReconciliationAttemptRepository attemptRepository
    ) {
        this.toolRepository = Objects.requireNonNull(
                toolRepository,
                "toolRepository must not be null"
        );
        this.attemptRepository = Objects.requireNonNull(
                attemptRepository,
                "attemptRepository must not be null"
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolReconciliationStart start(
            String runId,
            String toolCallId,
            ToolRecoveryContractSnapshot contract
    ) {
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        Objects.requireNonNull(contract, "contract must not be null");
        ToolExecutionEntity execution = requireLockedExecution(runId, toolCallId);
        validateContract(execution, contract);
        if (terminal(execution.getStatus())) {
            return ToolReconciliationStart.withoutAttempt(
                    ToolReconciliationStartDisposition.ALREADY_TERMINAL
            );
        }
        if (execution.getStatus() != ToolExecutionStatus.UNKNOWN) {
            return ToolReconciliationStart.withoutAttempt(
                    ToolReconciliationStartDisposition.NOT_AMBIGUOUS
            );
        }

        List<ToolReconciliationAttemptEntity> attempts = attemptRepository
                .findByToolExecutionIdOrderByAttemptNoDesc(execution.getExecutionId());
        for (ToolReconciliationAttemptEntity attempt : attempts) {
            if (attempt.getStatus() == ToolReconciliationAttemptStatus.COMPLETED
                    && attempt.getOutcome() != null
                    && attempt.getOutcome().decisive()) {
                return new ToolReconciliationStart(
                        ToolReconciliationStartDisposition.REUSED_DECISIVE,
                        java.util.Optional.of(attempt.snapshot())
                );
            }
        }
        for (ToolReconciliationAttemptEntity attempt : attempts) {
            if (attempt.getStatus() == ToolReconciliationAttemptStatus.STARTED) {
                return new ToolReconciliationStart(
                        ToolReconciliationStartDisposition.ALREADY_IN_PROGRESS,
                        java.util.Optional.of(attempt.snapshot())
                );
            }
        }

        long nextAttempt = attempts.isEmpty() ? 1L : attempts.get(0).getAttemptNo() + 1L;
        ToolReconciliationAttemptEntity attempt = new ToolReconciliationAttemptEntity(
                UUID.randomUUID().toString(),
                runId,
                execution.getExecutionId(),
                toolCallId,
                nextAttempt,
                contract.contractId(),
                contract.reconciliationStrategyId().orElseThrow(),
                Instant.now()
        );
        attemptRepository.saveAndFlush(attempt);
        return new ToolReconciliationStart(
                ToolReconciliationStartDisposition.STARTED,
                java.util.Optional.of(attempt.snapshot())
        );
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolReconciliationAttempt complete(
            String reconciliationId,
            ToolReconciliationResult result
    ) {
        requireText(reconciliationId, "reconciliationId");
        Objects.requireNonNull(result, "result must not be null");
        ToolReconciliationAttemptEntity reference = requireAttempt(reconciliationId);
        ToolExecutionEntity execution = requireLockedExecution(
                reference.getRunId(),
                reference.getToolCallId()
        );
        ToolReconciliationAttemptEntity attempt = requireLockedAttempt(reconciliationId);
        if (attempt.getStatus() != ToolReconciliationAttemptStatus.STARTED) {
            return attempt.snapshot();
        }
        if (terminal(execution.getStatus())) {
            attempt.supersede(Instant.now());
        } else {
            if (execution.getStatus() != ToolExecutionStatus.UNKNOWN) {
                throw new IllegalStateException(
                        "Tool execution is no longer an UNKNOWN ambiguity"
                );
            }
            attempt.complete(result, Instant.now());
        }
        attemptRepository.saveAndFlush(attempt);
        return attempt.snapshot();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolReconciliationAttempt fail(
            String reconciliationId,
            String errorCode,
            String errorMessage
    ) {
        requireText(reconciliationId, "reconciliationId");
        requireText(errorCode, "errorCode");
        requireText(errorMessage, "errorMessage");
        ToolReconciliationAttemptEntity reference = requireAttempt(reconciliationId);
        ToolExecutionEntity execution = requireLockedExecution(
                reference.getRunId(),
                reference.getToolCallId()
        );
        ToolReconciliationAttemptEntity attempt = requireLockedAttempt(reconciliationId);
        if (attempt.getStatus() != ToolReconciliationAttemptStatus.STARTED) {
            return attempt.snapshot();
        }
        if (terminal(execution.getStatus())) {
            attempt.supersede(Instant.now());
        } else {
            attempt.fail(errorCode, abbreviate(errorMessage, 1000), Instant.now());
        }
        attemptRepository.saveAndFlush(attempt);
        return attempt.snapshot();
    }

    private ToolExecutionEntity requireLockedExecution(String runId, String toolCallId) {
        return toolRepository.findForRecoveryMutation(runId, toolCallId)
                .orElseThrow(() -> new IllegalStateException(
                        "Tool execution not found for reconciliation: "
                                + runId + "/" + toolCallId
                ));
    }

    private ToolReconciliationAttemptEntity requireAttempt(String reconciliationId) {
        return attemptRepository.findByReconciliationId(reconciliationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Tool reconciliation attempt not found: " + reconciliationId
                ));
    }

    private ToolReconciliationAttemptEntity requireLockedAttempt(String reconciliationId) {
        return attemptRepository.findByReconciliationIdForUpdate(reconciliationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Tool reconciliation attempt not found: " + reconciliationId
                ));
    }

    private void validateContract(
            ToolExecutionEntity execution,
            ToolRecoveryContractSnapshot contract
    ) {
        if (!execution.getToolName().equals(contract.toolName())
                || !Objects.equals(execution.getRecoveryContractId(), contract.contractId())
                || !Boolean.TRUE.equals(execution.getReconciliationSupported())
                || !Objects.equals(
                execution.getReconciliationStrategyId(),
                contract.reconciliationStrategyId().orElse(null)
        )) {
            throw new IllegalStateException(
                    "Persisted tool recovery contract does not authorize reconciliation"
            );
        }
    }

    private boolean terminal(ToolExecutionStatus status) {
        return status == ToolExecutionStatus.SUCCEEDED
                || status == ToolExecutionStatus.FAILED
                || status == ToolExecutionStatus.BLOCKED
                || status == ToolExecutionStatus.CANCELLED;
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
