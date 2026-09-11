package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentStepRepository extends JpaRepository<AgentStepEntity, Long> {

    List<AgentStepEntity> findByRunIdOrderByStepIndexAsc(String runId);
}
