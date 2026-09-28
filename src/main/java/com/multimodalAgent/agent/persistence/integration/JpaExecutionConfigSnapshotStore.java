package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotException;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.persistence.entity.AgentRuntimeConfigSnapshotEntity;
import com.multimodalAgent.agent.persistence.repository.AgentRuntimeConfigSnapshotRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Component
public class JpaExecutionConfigSnapshotStore implements ExecutionConfigSnapshotStore {

    private final AgentRuntimeConfigSnapshotRepository repository;

    public JpaExecutionConfigSnapshotStore(AgentRuntimeConfigSnapshotRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ExecutionConfigSnapshot persistIfAbsent(ExecutionConfigSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        repository.insertIfAbsent(
                snapshot.snapshotId(),
                snapshot.schemaVersion(),
                snapshot.configHash(),
                snapshot.configJson(),
                Instant.now()
        );
        ExecutionConfigSnapshot stored = repository.findById(snapshot.snapshotId())
                .map(this::toSnapshot)
                .orElseThrow(() -> new ExecutionConfigSnapshotException(
                        "Snapshot identity conflicts with an existing configuration: "
                                + snapshot.snapshotId()
                ));
        if (!stored.equals(snapshot)) {
            throw new ExecutionConfigSnapshotException(
                    "Deterministic snapshot ID collision: " + snapshot.snapshotId()
            );
        }
        return stored;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ExecutionConfigSnapshot> findById(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return repository.findById(snapshotId).map(this::toSnapshot);
    }

    private ExecutionConfigSnapshot toSnapshot(AgentRuntimeConfigSnapshotEntity entity) {
        return new ExecutionConfigSnapshot(
                entity.getSnapshotId(),
                entity.getSchemaVersion(),
                entity.getConfigHash(),
                entity.getConfigJson()
        );
    }
}
