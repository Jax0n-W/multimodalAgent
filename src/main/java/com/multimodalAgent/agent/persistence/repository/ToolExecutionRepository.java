package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ToolExecutionRepository extends JpaRepository<ToolExecutionEntity, Long> {

    List<ToolExecutionEntity> findByRunIdOrderByCreatedAtAscIdAsc(String runId);

    List<ToolExecutionEntity> findByToolCallId(String toolCallId);

    Optional<ToolExecutionEntity> findByIdempotencyKey(String idempotencyKey);
}
