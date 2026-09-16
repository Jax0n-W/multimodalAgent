package com.multimodalAgent.agent.persistence.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseLostException;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.integration.CoordinatedAgentExecutionCoordinator;
import com.multimodalAgent.agent.coordination.integration.ExecutionCoordinationBoundaryMiddleware;
import com.multimodalAgent.agent.coordination.watchdog.DefaultRunLeaseWatchdogFactory;
import com.multimodalAgent.agent.coordination.watchdog.LeaseRenewalScheduler;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.ModelCompletedEvent;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionPersistenceCoordinationPrecedenceTest {

    @Test
    void persistenceFailureMustRemainPrimaryWhenSameModelAlsoLosesLease() {
        FailingHistoryStore historyStore = new FailingHistoryStore();
        ExecutionPersistenceComposition persistence =
                new ExecutionPersistenceComposition(historyStore);
        ControlledScheduler scheduler = new ControlledScheduler();
        FakeLeaseStore leaseStore = new FakeLeaseStore();
        AgentModel model = request -> {
            scheduler.tick();
            return ModelTurn.finalAnswer("model completed truthfully");
        };
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
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
                        toolExecutor,
                        TestModelToolDefinitionProjector.INSTANCE,
                        persistence.eventPublisher()
                ),
                new RuntimeMiddlewareChain(List.of(
                        persistence.boundaryMiddleware(),
                        new ExecutionCoordinationBoundaryMiddleware()
                ))
        );
        PersistentAgentExecutionCoordinator persistentCoordinator =
                persistence.persistentCoordinator(harness);
        CoordinatedAgentExecutionCoordinator coordinator =
                new CoordinatedAgentExecutionCoordinator(
                        leaseStore,
                        new DefaultRunLeaseWatchdogFactory(
                                leaseStore,
                                scheduler,
                                Duration.ofSeconds(2)
                        ),
                        persistentCoordinator
                );

        ExecutionPersistenceException failure = assertThrows(
                ExecutionPersistenceException.class,
                () -> coordinator.execute(request())
        );

        assertEquals("Could not persist MODEL_COMPLETED for run run-dual", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(RunLeaseLostException.class, failure.getSuppressed()[0]);
        assertEquals(1, historyStore.modelCompletedAttempts);
        assertEquals(1, leaseStore.releaseCalls);
    }

    private AgentExecutionRequest request() {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-dual",
                        "session-dual",
                        List.of(AgentMessage.user("run")),
                        2,
                        Set.of(),
                        Set.of()
                ),
                "request-dual",
                17L
        );
    }

    private static final class FailingHistoryStore implements ExecutionHistoryStore {

        private int modelCompletedAttempts;

        @Override
        public void admit(AgentExecutionRequest request) {
        }

        @Override
        public void record(AgentEvent event) {
            if (event instanceof ModelCompletedEvent) {
                modelCompletedAttempts++;
                throw new IllegalStateException("database unavailable");
            }
        }

        @Override
        public void finalizeRun(String runId, AgentRunResult result) {
            throw new AssertionError("finalize must not run after persistence failure");
        }
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
            return new RunLeaseAcquireResult.Acquired(new RunLease(runId, "token-dual"));
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
