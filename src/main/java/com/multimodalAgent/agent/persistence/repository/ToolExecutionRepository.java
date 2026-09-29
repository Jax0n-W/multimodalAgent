package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.ToolExecutionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ToolExecutionRepository extends JpaRepository<ToolExecutionEntity, Long> {

    List<ToolExecutionEntity> findByRunIdOrderByCreatedAtAscIdAsc(String runId);

    List<ToolExecutionEntity> findByToolCallId(String toolCallId);

    Optional<ToolExecutionEntity> findByRunIdAndToolCallId(String runId, String toolCallId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution
            from ToolExecutionEntity execution
            where execution.runId = :runId
              and execution.toolCallId = :toolCallId
            """)
    Optional<ToolExecutionEntity> findForRecoveryContractBinding(
            @Param("runId") String runId,
            @Param("toolCallId") String toolCallId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select execution
            from ToolExecutionEntity execution
            where execution.runId = :runId
              and execution.toolCallId = :toolCallId
            """)
    Optional<ToolExecutionEntity> findForRecoveryMutation(
            @Param("runId") String runId,
            @Param("toolCallId") String toolCallId
    );

    Optional<ToolExecutionEntity> findByIdempotencyKey(String idempotencyKey);
}
