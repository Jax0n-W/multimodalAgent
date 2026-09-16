package com.multimodalAgent.agent.coordination.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseLostException;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.watchdog.DefaultRunLeaseWatchdogFactory;
import com.multimodalAgent.agent.coordination.watchdog.LeaseRenewalScheduler;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.RunStoppedEvent;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoordinatedRuntimeLeaseLossIntegrationTest {

    @Test
    void coreStopsInternalButOuterCallerReceivesSpecificLeaseLoss() {
        ControlledScheduler scheduler = new ControlledScheduler();
        FakeLeaseStore leaseStore = new FakeLeaseStore();
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        AgentModel model = request -> {
            scheduler.tick();
            return ModelTurn.finalAnswer("truthful model result");
        };
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor tools = new ToolExecutor(
                new ToolRegistry(List.of()),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentExecutionCoordinator harness = new AgentExecutionCoordinator(
                new AgentRunner(
                        model,
                        tools,
                        TestModelToolDefinitionProjector.INSTANCE,
                        publisher
                ),
                new RuntimeMiddlewareChain(List.of(
                        new ExecutionCoordinationBoundaryMiddleware()
                ))
        );
        CoordinatedAgentExecutionCoordinator coordinator =
                new CoordinatedAgentExecutionCoordinator(
                        leaseStore,
                        new DefaultRunLeaseWatchdogFactory(
                                leaseStore,
                                scheduler,
                                Duration.ofSeconds(2)
                        ),
                        harness::execute,
                        (state, failureKind) -> {
                        }
                );

        assertThrows(RunLeaseLostException.class, () -> coordinator.execute(request()));

        List<AgentEventType> types = publisher.events().stream().map(AgentEvent::type).toList();
        assertTrue(types.contains(AgentEventType.MODEL_STARTED));
        assertTrue(types.contains(AgentEventType.MODEL_COMPLETED));
        assertFalse(types.contains(AgentEventType.MODEL_FAILED));
        RunStoppedEvent terminal = publisher.events().stream()
                .filter(RunStoppedEvent.class::isInstance)
                .map(RunStoppedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(AgentStopReason.INTERNAL_ERROR, terminal.stopReason());
        assertEquals(1, leaseStore.releaseCalls);
    }

    private AgentExecutionRequest request() {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-runtime-loss",
                        "session-runtime-loss",
                        List.of(AgentMessage.user("run")),
                        2,
                        Set.of(),
                        Set.of()
                ),
                "request-runtime-loss",
                23L
        );
    }

    private static final class ControlledScheduler implements LeaseRenewalScheduler {

        private Runnable task;

        @Override
        public ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval) {
            this.task = task;
            return () -> {
            };
        }

        private void tick() {
            task.run();
        }
    }

    private static final class FakeLeaseStore implements RunLeaseStore {

        private int releaseCalls;

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            return new RunLeaseAcquireResult.Acquired(
                    new RunLease(runId, "token-runtime-loss")
            );
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            return RunLeaseRenewResult.EXPLICIT_LEASE_LOSS;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            releaseCalls++;
            return RunLeaseReleaseResult.NO_LONGER_OWNER;
        }
    }
}
