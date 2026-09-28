package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.AgentRecoveryCheckpointEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentRecoveryCheckpointRepository
        extends JpaRepository<AgentRecoveryCheckpointEntity, Long> {

    Optional<AgentRecoveryCheckpointEntity> findByCheckpointId(String checkpointId);

    Optional<AgentRecoveryCheckpointEntity> findFirstByRunIdOrderByCheckpointSequenceDesc(
            String runId
    );

    List<AgentRecoveryCheckpointEntity> findByRunIdOrderByCheckpointSequenceAsc(String runId);
}
