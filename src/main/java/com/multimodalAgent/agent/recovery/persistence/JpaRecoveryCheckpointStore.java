package com.multimodalAgent.agent.recovery.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.persistence.entity.AgentRecoveryCheckpointEntity;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.repository.AgentRecoveryCheckpointRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRuntimeConfigSnapshotRepository;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointBoundary;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointConflictException;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointCorruptionException;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** JPA adapter for immutable, append-only recovery checkpoints. */
@Component
public class JpaRecoveryCheckpointStore implements RecoveryCheckpointStore {

    private final AgentRecoveryCheckpointRepository checkpointRepository;
    private final AgentRunRepository runRepository;
    private final AgentRuntimeConfigSnapshotRepository snapshotRepository;
    private final RecoveryCheckpointJsonCodec codec;

    public JpaRecoveryCheckpointStore(
            AgentRecoveryCheckpointRepository checkpointRepository,
            AgentRunRepository runRepository,
            AgentRuntimeConfigSnapshotRepository snapshotRepository,
            ObjectMapper objectMapper
    ) {
        this.checkpointRepository = Objects.requireNonNull(
                checkpointRepository,
                "checkpointRepository must not be null"
        );
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository must not be null");
        this.snapshotRepository = Objects.requireNonNull(
                snapshotRepository,
                "snapshotRepository must not be null"
        );
        this.codec = new RecoveryCheckpointJsonCodec(objectMapper);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(RecoveryCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint must not be null");
        validateReferences(checkpoint.runId(), checkpoint.runtimeConfigSnapshotId());
        String stateJson = codec.encode(checkpoint);
        Optional<AgentRecoveryCheckpointEntity> existing =
                checkpointRepository.findByCheckpointId(checkpoint.checkpointId());
        if (existing.isPresent()) {
            if (!identical(existing.get(), checkpoint, stateJson)) {
                throw new RecoveryCheckpointConflictException(checkpoint.checkpointId());
            }
            return;
        }
        checkpointRepository.saveAndFlush(new AgentRecoveryCheckpointEntity(
                checkpoint.checkpointId(),
                checkpoint.runId(),
                checkpoint.sequence(),
                checkpoint.schemaVersion(),
                checkpoint.iteration(),
                checkpoint.boundary().name(),
                stateJson,
                checkpoint.runtimeConfigSnapshotId(),
                checkpoint.createdAt()
        ));
    }

    private boolean identical(
            AgentRecoveryCheckpointEntity entity,
            RecoveryCheckpoint checkpoint,
            String stateJson
    ) {
        return entity.getCheckpointId().equals(checkpoint.checkpointId())
                && entity.getRunId().equals(checkpoint.runId())
                && entity.getCheckpointSequence() == checkpoint.sequence()
                && entity.getSchemaVersion() == checkpoint.schemaVersion()
                && entity.getIteration() == checkpoint.iteration()
                && entity.getBoundary().equals(checkpoint.boundary().name())
                && entity.getStateJson().equals(stateJson)
                && entity.getRuntimeConfigSnapshotId().equals(
                        checkpoint.runtimeConfigSnapshotId()
                )
                && entity.getCreatedAt().equals(checkpoint.createdAt());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecoveryCheckpoint> findLatestByRunId(String runId) {
        requireText(runId, "runId");
        return checkpointRepository.findFirstByRunIdOrderByCheckpointSequenceDesc(runId)
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RecoveryCheckpoint> findByCheckpointId(String checkpointId) {
        requireText(checkpointId, "checkpointId");
        return checkpointRepository.findByCheckpointId(checkpointId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecoveryCheckpoint> listByRunId(String runId) {
        requireText(runId, "runId");
        return checkpointRepository.findByRunIdOrderByCheckpointSequenceAsc(runId).stream()
                .map(this::toDomain)
                .toList();
    }

    private RecoveryCheckpoint toDomain(AgentRecoveryCheckpointEntity entity) {
        validateReferences(entity.getRunId(), entity.getRuntimeConfigSnapshotId());
        RecoveryCheckpointJsonCodec.DecodedState state = codec.decode(
                entity.getCheckpointId(), entity.getSchemaVersion(), entity.getStateJson()
        );
        RecoveryCheckpointBoundary boundary;
        try {
            boundary = RecoveryCheckpointBoundary.valueOf(entity.getBoundary());
        } catch (RuntimeException exception) {
            throw new RecoveryCheckpointCorruptionException(
                    "Invalid recovery checkpoint boundary for " + entity.getCheckpointId(),
                    exception
            );
        }
        try {
            return new RecoveryCheckpoint(
                    entity.getCheckpointId(),
                    entity.getRunId(),
                    entity.getCheckpointSequence(),
                    entity.getIteration(),
                    boundary,
                    state.messages(),
                    state.toolsUsed(),
                    state.seenToolCallIds(),
                    state.budgetUsage(),
                    state.approvedToolCallIds(),
                    entity.getRuntimeConfigSnapshotId(),
                    entity.getCreatedAt(),
                    entity.getSchemaVersion()
            );
        } catch (RuntimeException exception) {
            throw new RecoveryCheckpointCorruptionException(
                    "Invalid recovery checkpoint state for " + entity.getCheckpointId(),
                    exception
            );
        }
    }

    private void validateReferences(String runId, String snapshotId) {
        AgentRunEntity run = runRepository.findByRunId(runId).orElseThrow(() ->
                new RecoveryCheckpointCorruptionException(
                        "Recovery checkpoint run does not exist: " + runId
                )
        );
        if (!snapshotRepository.existsById(snapshotId)) {
            throw new RecoveryCheckpointCorruptionException(
                    "Recovery checkpoint config snapshot does not exist: " + snapshotId
            );
        }
        if (!snapshotId.equals(run.getRuntimeConfigSnapshotId())) {
            throw new RecoveryCheckpointCorruptionException(
                    "Recovery checkpoint config snapshot does not match AgentRun " + runId
            );
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
