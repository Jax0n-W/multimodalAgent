package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {

    Optional<AgentRunEntity> findByRunId(String runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from AgentRunEntity run where run.runId = :runId")
    Optional<AgentRunEntity> findByRunIdForRecoveryAppend(@Param("runId") String runId);

    boolean existsByRunIdAndUserId(String runId, Long userId);

    Optional<AgentRunEntity> findByRunIdAndUserId(String runId, Long userId);

    Optional<AgentRunEntity> findByRequestId(String requestId);

    List<AgentRunEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);

    List<AgentRunEntity> findByStatusOrderByCreatedAtAsc(AgentRunStatus status);
}
