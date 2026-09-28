package com.multimodalAgent.agent.recovery;

import java.util.List;
import java.util.Optional;

/** Append-only persistence boundary for durable recovery state. */
public interface RecoveryCheckpointStore {

    void persist(RecoveryCheckpoint checkpoint);

    Optional<RecoveryCheckpoint> findLatestByRunId(String runId);

    Optional<RecoveryCheckpoint> findByCheckpointId(String checkpointId);

    List<RecoveryCheckpoint> listByRunId(String runId);
}
