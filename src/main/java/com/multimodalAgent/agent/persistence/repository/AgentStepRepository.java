package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentStepRepository extends JpaRepository<AgentStepEntity, Long> {

    List<AgentStepEntity> findByRunIdOrderByStepIndexAsc(String runId);

    Optional<AgentStepEntity> findByStepId(String stepId);

    long countByRunId(String runId);
}
