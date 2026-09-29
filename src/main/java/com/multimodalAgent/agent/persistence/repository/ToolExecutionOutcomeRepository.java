package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.ToolExecutionOutcomeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ToolExecutionOutcomeRepository
        extends JpaRepository<ToolExecutionOutcomeEntity, Long> {

    Optional<ToolExecutionOutcomeEntity> findByExecutionId(String executionId);

    Optional<ToolExecutionOutcomeEntity> findByRunIdAndToolCallId(
            String runId,
            String toolCallId
    );
}
