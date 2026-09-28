package com.multimodalAgent.agent.execution.config;

import java.util.Optional;

public interface ExecutionConfigSnapshotStore {

    ExecutionConfigSnapshot persistIfAbsent(ExecutionConfigSnapshot snapshot);

    Optional<ExecutionConfigSnapshot> findById(String snapshotId);
}
