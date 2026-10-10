package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
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

    @Query("""
            select run from AgentRunEntity run
            where run.userId = :userId
              and run.sessionId = :sessionId
              and run.runId <> :currentRunId
              and run.status = :status
              and run.stopReason = :stopReason
              and run.completedAt is not null
              and run.finalContent is not null
              and run.contextSnapshotId is not null
            order by run.completedAt desc, run.runId desc
            """)
    List<AgentRunEntity> findConversationMemoryCandidates(
            @Param("userId") Long userId,
            @Param("sessionId") String sessionId,
            @Param("currentRunId") String currentRunId,
            @Param("status") AgentRunStatus status,
            @Param("stopReason") AgentStopReason stopReason,
            Pageable pageable
    );
}
