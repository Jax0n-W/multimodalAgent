package com.multimodalAgent.agent.coordination.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.coordination.RunLeaseState;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.watchdog.LeaseRenewalScheduler;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdog;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributorFailureCoordinationIntegrationTest {

    @Test
    void contributorFailureBeforeCoreMustStillStopWatchdogReleaseAndCloseSession() {
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        AtomicInteger modelCalls = new AtomicInteger();
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
                        ignored -> {
                            modelCalls.incrementAndGet();
                            return ModelTurn.finalAnswer("must not run");
                        },
                        toolExecutor,
                        TestModelToolDefinitionProjector.INSTANCE,
                        publisher
                ),
                RuntimeMiddlewareChain.empty()
        );
        FakeStore store = new FakeStore();
        ControlledScheduler scheduler = new ControlledScheduler();
        AtomicReference<RunLeaseSession> observedSession = new AtomicReference<>();
        CoordinatedAgentExecutionCoordinator coordinator =
                new CoordinatedAgentExecutionCoordinator(
                        store,
                        session -> {
                            observedSession.set(session);
                            return new RunLeaseWatchdog(
                                    store,
                                    session,
                                    scheduler,
                                    Duration.ofSeconds(2)
                            );
                        },
                        harness::execute,
                        (state, failureKind) -> {
                        }
                );
        IllegalStateException failure = new IllegalStateException("contributor failed");
        AgentExecutionRequest request = request().withRuntimeContextContributor(context -> {
            throw failure;
        });

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> coordinator.execute(request)
        );

        assertSame(failure, actual);
        assertEquals(0, modelCalls.get());
        assertTrue(publisher.events().isEmpty());
        assertEquals(1, scheduler.cancelCalls.get());
        assertEquals(1, store.releaseCalls.get());
        assertEquals(RunLeaseState.CLOSED, observedSession.get().state());
    }

    private AgentExecutionRequest request() {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-contributor-cleanup",
                        "session-contributor-cleanup",
                        List.of(AgentMessage.user("run")),
                        2,
                        Set.of(),
                        Set.of()
                ),
                "request-contributor-cleanup",
                7L
        );
    }

    private static final class ControlledScheduler implements LeaseRenewalScheduler {

        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public ScheduledRenewal scheduleWithFixedDelay(Runnable task, Duration interval) {
            return cancelCalls::incrementAndGet;
        }
    }

    private static final class FakeStore implements RunLeaseStore {

        private final AtomicInteger releaseCalls = new AtomicInteger();

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            return new RunLeaseAcquireResult.Acquired(
                    new RunLease(runId, "token-contributor-cleanup")
            );
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            return RunLeaseRenewResult.RENEWED;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            releaseCalls.incrementAndGet();
            return RunLeaseReleaseResult.RELEASED;
        }
    }
}
