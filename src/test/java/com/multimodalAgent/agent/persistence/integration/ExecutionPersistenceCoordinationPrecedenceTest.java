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
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.AgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.ModelCompletedEvent;
import com.multimodalAgent.agent.runtime.event.ToolSucceededEvent;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.trace.DecisionTrace;
import com.multimodalAgent.agent.runtime.trace.DecisionTraceBuilder;
import com.multimodalAgent.agent.runtime.trace.ToolExecutionOutcome;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void persistenceFailureThenLeaseLossMustHaveTheSamePrimaryPrecedence() {
        FailingHistoryStore historyStore = new FailingHistoryStore();
        ExecutionPersistenceComposition persistence =
                new ExecutionPersistenceComposition(historyStore);
        ControlledScheduler scheduler = new ControlledScheduler();
        FakeLeaseStore leaseStore = new FakeLeaseStore();
        List<AgentEvent> events = new ArrayList<>();
        AgentEventPublisher publisher = event -> {
            events.add(event);
            persistence.eventPublisher().publish(event);
            if (event instanceof ModelCompletedEvent) {
                scheduler.tick();
            }
        };
        AgentExecutionCoordinator harness = harness(
                request -> ModelTurn.finalAnswer("model completed truthfully"),
                List.of(),
                publisher,
                persistence
        );
        CoordinatedAgentExecutionCoordinator coordinator = coordinated(
                persistence,
                scheduler,
                leaseStore,
                harness
        );

        ExecutionPersistenceException failure = assertThrows(
                ExecutionPersistenceException.class,
                () -> coordinator.execute(request())
        );

        assertEquals("Could not persist MODEL_COMPLETED for run run-dual", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(RunLeaseLostException.class, failure.getSuppressed()[0]);
        assertTrue(events.stream().anyMatch(ModelCompletedEvent.class::isInstance));
        assertFalse(events.stream().anyMatch(event -> event.type() == AgentEventType.MODEL_FAILED));
    }

    @Test
    void toolSuccessPersistenceAndLeaseLossMustPreservePartialTruthAndStopThirdTool() {
        ToolSucceededFailingHistoryStore historyStore = new ToolSucceededFailingHistoryStore();
        ExecutionPersistenceComposition persistence =
                new ExecutionPersistenceComposition(historyStore);
        ControlledScheduler scheduler = new ControlledScheduler();
        FakeLeaseStore leaseStore = new FakeLeaseStore();
        List<AgentEvent> events = new ArrayList<>();
        AgentEventPublisher publisher = event -> {
            events.add(event);
            persistence.eventPublisher().publish(event);
            if (event instanceof ToolSucceededEvent succeeded
                    && succeeded.toolCallId().equals("call-b")) {
                scheduler.tick();
            }
        };
        CountingTool first = new CountingTool("tool-a");
        CountingTool second = new CountingTool("tool-b");
        CountingTool third = new CountingTool("tool-c");
        ModelTurn turn = ModelTurn.toolCall(
                new ToolCall("call-a", "tool-a", Map.of("query", "a")),
                new ToolCall("call-b", "tool-b", Map.of("query", "b")),
                new ToolCall("call-c", "tool-c", Map.of("query", "c"))
        );
        AgentExecutionCoordinator harness = harness(
                ignored -> turn,
                List.of(first, second, third),
                publisher,
                persistence
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
        AgentExecutionRequest toolRequest = new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-dual",
                        "session-dual",
                        List.of(AgentMessage.user("run")),
                        2,
                        Set.of("tool-a", "tool-b", "tool-c"),
                        Set.of()
                ),
                "request-dual",
                17L
        );

        ExecutionPersistenceException failure = assertThrows(
                ExecutionPersistenceException.class,
                () -> coordinator.execute(toolRequest)
        );

        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(RunLeaseLostException.class, failure.getSuppressed()[0]);
        assertEquals(1, first.executions.get());
        assertEquals(1, second.executions.get());
        assertEquals(0, third.executions.get());
        assertTrue(events.stream().anyMatch(event -> event instanceof ToolSucceededEvent succeeded
                && succeeded.toolCallId().equals("call-a")));
        assertTrue(events.stream().anyMatch(event -> event instanceof ToolSucceededEvent succeeded
                && succeeded.toolCallId().equals("call-b")));
        assertFalse(events.stream().anyMatch(event -> event.type() == AgentEventType.TOOL_FAILED));
        DecisionTrace trace = new DecisionTraceBuilder().build(events);
        assertEquals(
                List.of(
                        ToolExecutionOutcome.SUCCEEDED,
                        ToolExecutionOutcome.SUCCEEDED,
                        ToolExecutionOutcome.NOT_STARTED
                ),
                trace.orderedToolDecisions().stream()
                        .map(decision -> decision.executionOutcome())
                        .toList()
        );
    }

    private AgentExecutionCoordinator harness(
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            AgentEventPublisher publisher,
            ExecutionPersistenceComposition persistence
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        return new AgentExecutionCoordinator(
                new AgentRunner(
                        model,
                        toolExecutor,
                        TestModelToolDefinitionProjector.INSTANCE,
                        publisher
                ),
                new RuntimeMiddlewareChain(List.of(
                        persistence.boundaryMiddleware(),
                        new ExecutionCoordinationBoundaryMiddleware()
                ))
        );
    }

    private CoordinatedAgentExecutionCoordinator coordinated(
            ExecutionPersistenceComposition persistence,
            ControlledScheduler scheduler,
            FakeLeaseStore leaseStore,
            AgentExecutionCoordinator harness
    ) {
        PersistentAgentExecutionCoordinator persistentCoordinator =
                persistence.persistentCoordinator(harness);
        return new CoordinatedAgentExecutionCoordinator(
                leaseStore,
                new DefaultRunLeaseWatchdogFactory(
                        leaseStore,
                        scheduler,
                        Duration.ofSeconds(2)
                ),
                persistentCoordinator
        );
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

    private static final class ToolSucceededFailingHistoryStore implements ExecutionHistoryStore {

        @Override
        public void admit(AgentExecutionRequest request) {
        }

        @Override
        public void record(AgentEvent event) {
            if (event instanceof ToolSucceededEvent succeeded
                    && succeeded.toolCallId().equals("call-b")) {
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

    private record ToolInput(@NotBlank String query) {
    }

    private static final class CountingTool implements AgentTool<ToolInput, String> {

        private final ToolDescriptor<ToolInput> descriptor;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(String name) {
            this.descriptor = new ToolDescriptor<>(
                    name,
                    "Dual failure test tool",
                    ToolInput.class,
                    ToolRisk.LOW,
                    true,
                    true,
                    false
            );
        }

        @Override
        public ToolDescriptor<ToolInput> descriptor() {
            return descriptor;
        }

        @Override
        public String execute(ToolInput input) {
            executions.incrementAndGet();
            return input.query();
        }
    }
}
