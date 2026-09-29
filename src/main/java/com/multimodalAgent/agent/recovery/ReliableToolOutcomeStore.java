package com.multimodalAgent.agent.recovery;

import java.util.Optional;

/** Immutable persistence boundary for exact successful tool outcomes. */
public interface ReliableToolOutcomeStore {

    void persist(ReliableToolOutcome outcome);

    Optional<ReliableToolOutcome> findByExecutionId(String executionId);

    Optional<ReliableToolOutcome> findByRunIdAndToolCallId(String runId, String toolCallId);
}
