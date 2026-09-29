package com.multimodalAgent.agent.recovery.persistence;

import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import com.multimodalAgent.agent.persistence.model.ToolExecutionStatus;
import com.multimodalAgent.agent.persistence.repository.ToolExecutionRepository;
import com.multimodalAgent.agent.recovery.ToolRecoveryStateStore;
import com.multimodalAgent.agent.recovery.ToolUnknownMaterializationResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Component
public class JpaToolRecoveryStateStore implements ToolRecoveryStateStore {

    private final ToolExecutionRepository repository;

    public JpaToolRecoveryStateStore(ToolExecutionRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ToolUnknownMaterializationResult materializeUnknown(
            String runId,
            String toolCallId
    ) {
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        ToolExecutionEntity execution = repository.findForRecoveryMutation(runId, toolCallId)
                .orElseThrow(() -> new IllegalStateException(
                        "Tool execution not found for UNKNOWN materialization: "
                                + runId + "/" + toolCallId
                ));
        if (execution.getStatus() == ToolExecutionStatus.STARTED) {
            execution.setStatus(ToolExecutionStatus.UNKNOWN);
            repository.saveAndFlush(execution);
            return ToolUnknownMaterializationResult.MATERIALIZED_UNKNOWN;
        }
        if (execution.getStatus() == ToolExecutionStatus.UNKNOWN) {
            return ToolUnknownMaterializationResult.ALREADY_UNKNOWN;
        }
        if (execution.getStatus() == ToolExecutionStatus.PLANNED) {
            return ToolUnknownMaterializationResult.NOT_AMBIGUOUS;
        }
        return ToolUnknownMaterializationResult.ALREADY_TERMINAL;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
