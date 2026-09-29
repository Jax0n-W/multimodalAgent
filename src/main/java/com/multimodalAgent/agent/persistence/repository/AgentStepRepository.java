package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.AgentStepEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentStepRepository extends JpaRepository<AgentStepEntity, Long> {

    List<AgentStepEntity> findByRunIdOrderByStepIndexAsc(String runId);

    Optional<AgentStepEntity> findByStepId(String stepId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select step from AgentStepEntity step where step.stepId = :stepId")
    Optional<AgentStepEntity> findByStepIdForRecoveryMutation(@Param("stepId") String stepId);

    long countByRunId(String runId);
}
