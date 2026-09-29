package com.multimodalAgent.agent.persistence.integration;

import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.harness.RecoveryExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentResumeState;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.BudgetUsage;
import com.multimodalAgent.agent.runtime.extension.CancellationContext;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistentResumeExistingTest {

    @Test
    void validatesAndFinalizesExistingRunWithoutSecondAdmission() {
        AgentExecutionCoordinator delegate = mock(AgentExecutionCoordinator.class);
        CountingStore store = new CountingStore();
        PersistentAgentExecutionCoordinator coordinator = new PersistentAgentExecutionCoordinator(
                delegate, store, new ExecutionPersistenceFailureRegistry()
        );
        AgentRunSpec spec = new AgentRunSpec(
                "existing-run", "session", List.of(AgentMessage.user("x")), 2
        );
        AgentResumeState state = new AgentResumeState(
                spec.messages(), Set.of(), Set.of(), Set.of(), List.of(), 1, 2,
                new BudgetUsage(1, 0, 0, 0, 0, Optional.empty(), false)
        );
        RecoveryExecutionRequest request = new RecoveryExecutionRequest(
                spec, state, "exec-config-v1-" + "a".repeat(64),
                CancellationContext.NONE, List.of()
        );
        AgentRunResult result = new AgentRunResult(
                "done", AgentStopReason.COMPLETED, 2, List.of(),
                List.of(AgentMessage.user("x"), AgentMessage.assistant("done")),
                TokenUsage.ZERO, null, null, null
        );
        when(delegate.resume(request)).thenReturn(result);

        assertEquals(result, coordinator.resumeExisting(request));
        assertEquals(0, store.admissions);
        assertEquals(1, store.validations);
        assertEquals(1, store.finalizations);
    }

    private static final class CountingStore implements ExecutionHistoryStore {
        private int admissions;
        private int validations;
        private int finalizations;
        @Override public void admit(AgentExecutionRequest request) { admissions++; }
        @Override public void record(AgentEvent event) { }
        @Override public void finalizeRun(String runId, AgentRunResult result) { finalizations++; }
        @Override public void assertExistingRunning(String runId, String snapshotId) {
            validations++;
        }
    }
}
