package com.multimodalAgent.agent.runtime.control;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionCheckpointTest {

    @Test
    void checkpointVocabularyMatchesTheFrozenSafeBoundaries() {
        assertEquals(
                List.of(
                        ExecutionCheckpoint.BEFORE_MODEL,
                        ExecutionCheckpoint.AFTER_MODEL,
                        ExecutionCheckpoint.BEFORE_TOOL_EXECUTION,
                        ExecutionCheckpoint.AFTER_TOOL_EXECUTION
                ),
                List.of(ExecutionCheckpoint.values())
        );
    }
}
