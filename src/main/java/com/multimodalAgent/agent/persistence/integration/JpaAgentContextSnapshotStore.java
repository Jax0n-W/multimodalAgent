package com.multimodalAgent.agent.persistence.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.context.AgentContextSnapshot;
import com.multimodalAgent.agent.context.AgentContextSnapshotFactory;
import com.multimodalAgent.agent.context.AgentContextSnapshotStore;
import com.multimodalAgent.agent.context.ContextAssemblyException;
import com.multimodalAgent.agent.persistence.entity.AgentContextSnapshotEntity;
import com.multimodalAgent.agent.persistence.repository.AgentContextSnapshotRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

@Component
public class JpaAgentContextSnapshotStore implements AgentContextSnapshotStore {

    private final AgentContextSnapshotRepository repository;
    private final AgentContextSnapshotFactory factory;

    public JpaAgentContextSnapshotStore(
            AgentContextSnapshotRepository repository,
            ObjectMapper objectMapper
    ) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.factory = new AgentContextSnapshotFactory(objectMapper);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AgentContextSnapshot persistIfAbsent(AgentContextSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        repository.insertIfAbsent(
                snapshot.snapshotId(),
                snapshot.schemaVersion(),
                snapshot.runId(),
                snapshot.sessionId(),
                snapshot.userId(),
                snapshot.contextHash(),
                snapshot.canonicalJson(),
                snapshot.createdAt()
        );
        AgentContextSnapshot stored = repository.findById(snapshot.snapshotId())
                .map(this::toSnapshot)
                .orElseThrow(() -> new ContextAssemblyException(
                        "Context snapshot identity conflict: " + snapshot.snapshotId()
                ));
        if (!snapshot.sameSemanticContent(stored)) {
            throw new ContextAssemblyException(
                    "Deterministic context snapshot ID collision: " + snapshot.snapshotId()
            );
        }
        return stored;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentContextSnapshot> findById(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) {
            throw new IllegalArgumentException("snapshotId must not be blank");
        }
        return repository.findById(snapshotId).map(this::toSnapshot);
    }

    private AgentContextSnapshot toSnapshot(AgentContextSnapshotEntity entity) {
        AgentContextSnapshot restored = factory.restore(
                entity.getSnapshotId(),
                entity.getCreatedAt(),
                entity.getContextJson()
        );
        if (restored.schemaVersion() != entity.getSchemaVersion()
                || !restored.runId().equals(entity.getRunId())
                || !restored.sessionId().equals(entity.getSessionId())
                || !restored.userId().equals(entity.getUserId())
                || !restored.contextHash().equals(entity.getContextHash())) {
            throw new ContextAssemblyException(
                    "Stored context metadata conflicts with canonical content: "
                            + entity.getSnapshotId()
            );
        }
        return restored;
    }
}
