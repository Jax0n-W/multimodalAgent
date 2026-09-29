package com.multimodalAgent.agent.recovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryScannerTest {

    @Test
    void submitsOnlyCandidatesSelectedByTheRunningRunStore() {
        RecoveryCandidate running = new RecoveryCandidate("run-1", "session-1");
        RecoveryCandidateStore store = () -> List.of(running);
        RecoveryEngine engine = mock(RecoveryEngine.class);
        RecoveryEngineResult expected = RecoveryEngineResult.of(
                RecoveryEngineResult.Status.ALREADY_ACTIVE
        );
        when(engine.recover(running)).thenReturn(expected);

        assertEquals(List.of(expected), new RecoveryScanner(store, engine).scan());
        verify(engine).recover(running);
    }
}
