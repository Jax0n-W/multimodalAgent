package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.recovery.ToolReconciliationSupport;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractBindingException;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractBindingStore;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractConflictException;
import com.multimodalAgent.agent.recovery.ToolRecoveryContractSnapshot;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Persists one immutable execution-time recovery contract in an independent short transaction. */
@Component
public class JpaToolRecoveryContractBindingStore
        implements ToolRecoveryContractBindingStore {

    private final ToolExecutionRepository repository;

    public JpaToolRecoveryContractBindingStore(ToolExecutionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bind(
            String runId,
            String toolCallId,
            ToolRecoveryContractSnapshot contract
    ) {
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        Objects.requireNonNull(contract, "contract must not be null");
        ToolExecutionEntity execution = repository
                .findForRecoveryContractBinding(runId, toolCallId)
                .orElseThrow(() -> new ToolRecoveryContractBindingException(
                        "Tool execution not found for recovery contract binding: "
                                + runId + "/" + toolCallId
                ));
        if (!execution.getToolName().equals(contract.toolName())) {
            throw new ToolRecoveryContractConflictException(runId, toolCallId);
        }
        if (bindingAbsent(execution)) {
            execution.bindRecoveryContract(contract);
            repository.saveAndFlush(execution);
            return;
        }
        if (!bindingMatches(execution, contract)) {
            throw new ToolRecoveryContractConflictException(runId, toolCallId);
        }
    }

    private boolean bindingAbsent(ToolExecutionEntity execution) {
        return !execution.hasAnyRecoveryContractField();
    }

    private boolean bindingMatches(
            ToolExecutionEntity execution,
            ToolRecoveryContractSnapshot contract
    ) {
        return Objects.equals(execution.getRecoveryContractId(), contract.contractId())
                && Objects.equals(
                execution.getRecoveryContractSchemaVersion(),
                contract.schemaVersion()
        )
                && Objects.equals(
                execution.getRecoveryContractVersion(),
                contract.contractVersion()
        )
                && Objects.equals(
                execution.getReplaySemantics(),
                contract.replaySemantics().name()
        )
                && Objects.equals(
                execution.getReconciliationSupported(),
                contract.reconciliationSupport() == ToolReconciliationSupport.SUPPORTED
        )
                && Objects.equals(
                execution.getReconciliationStrategyId(),
                contract.reconciliationStrategyId().orElse(null)
        );
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
