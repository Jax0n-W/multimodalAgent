package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRunRepository extends JpaRepository<AgentRunEntity, Long> {

    Optional<AgentRunEntity> findByRunId(String runId);

    Optional<AgentRunEntity> findByRequestId(String requestId);

    List<AgentRunEntity> findBySessionIdOrderByCreatedAtDesc(String sessionId);
}
