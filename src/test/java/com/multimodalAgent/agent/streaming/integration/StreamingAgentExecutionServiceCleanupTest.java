package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.coordination.RunLeaseLostException;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceException;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributes;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingAgentExecutionServiceCleanupTest {

    @Test
    void p6FinalizationFailurePreservesCallerExceptionAndRemovesActiveEntry() {
        assertPostAdmissionFailureCleanup(
                "p84-p6-failure", new ExecutionPersistenceException("finalization failed")
        );
    }

    @Test
    void p7LeaseFailurePreservesCallerExceptionAndRemovesActiveEntry() {
        assertPostAdmissionFailureCleanup(
                "p84-p7-failure", new RunLeaseLostException("p84-p7-failure")
        );
    }

    @Test
    void failedAdmissionNeverRegistersLocalControl() {
        String runId = "p84-admission-failure";
        ExecutionStreamHub hub = new ExecutionStreamHub();
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(hub);
        LocalExecutionControlRegistry controls = new LocalExecutionControlRegistry(publisher);
        ExecutionPersistenceException failure = new ExecutionPersistenceException("admission failed");
        StreamingAgentExecutionService service = new StreamingAgentExecutionService(
                request -> { throw failure; }, hub, publisher, controls
        );

        assertSame(failure, assertThrows(ExecutionPersistenceException.class,
                () -> service.execute(request(runId))));
        assertEquals(0, controls.activeCount());
        assertFalse(hub.isOpen(runId));
    }

    private void assertPostAdmissionFailureCleanup(String runId, RuntimeException failure) {
        ExecutionStreamHub hub = new ExecutionStreamHub();
        ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(hub);
        LocalExecutionControlRegistry controls = new LocalExecutionControlRegistry(publisher);
        StreamingAgentExecutionService service = new StreamingAgentExecutionService(
                request -> {
                    AgentRuntimeContext context = new AgentRuntimeContext(
                            runId, request.requestId(), request.runSpec().sessionId(),
                            request.userId(), null, request.cancellationContext(),
                            new RuntimeAttributes()
                    );
                    request.runtimeContextContributors().forEach(
                            contributor -> contributor.contribute(context)
                    );
                    assertEquals(1, controls.activeCount());
                    assertTrue(hub.isOpen(runId));
                    throw failure;
                },
                hub, publisher, controls
        );

        assertSame(failure, assertThrows(failure.getClass(),
                () -> service.execute(request(runId))));
        assertEquals(0, controls.activeCount());
        assertFalse(hub.isOpen(runId));
    }

    private AgentExecutionRequest request(String runId) {
        return new AgentExecutionRequest(
                new AgentRunSpec(runId, "p84-session", List.of(AgentMessage.user("hello")), 1),
                "p84-request", 1L
        );
    }
}
