package com.multimodalAgent.agent.recovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RecoveryCheckpointDomainTest {

    @Test
    void logicalIdentityIsDeterministicAndPositionSensitive() {
        String first = RecoveryCheckpointIds.stable(
                "run-1", 2, RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME, 1, "call-1"
        );
        String retry = RecoveryCheckpointIds.stable(
                "run-1", 2, RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME, 1, "call-1"
        );
        String next = RecoveryCheckpointIds.stable(
                "run-1", 3, RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME, 1, "call-2"
        );

        assertEquals(first, retry);
        assertNotEquals(first, next);
    }
}
