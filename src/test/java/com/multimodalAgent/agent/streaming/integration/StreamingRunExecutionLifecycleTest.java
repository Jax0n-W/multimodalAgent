package com.multimodalAgent.agent.streaming.integration;

import com.multimodalAgent.agent.coordination.RunLeaseLostException;
import com.multimodalAgent.agent.harness.RecoveryExecutionRequest;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceException;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.AgentResumeState;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.BudgetUsage;
import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributes;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.stream.ControlEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.ExecutionStreamSubscription;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamingRunExecutionLifecycleTest {

    @Test
    void recoveredSegmentAcceptsOneCancellationAndCleansStreamAndControl()
            throws Exception {
        Fixture fixture = new Fixture();
        String runId = "recovery-cancel";
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                fixture.lifecycle.executeRecovery(request(runId), observed -> {
                    AgentRuntimeContext context = fixture.enter(observed);
                    entered.countDown();
                    await(release);
                    return result(context.cancellationContext().isCancellationRequested()
                            ? AgentStopReason.CANCELLED
                            : AgentStopReason.COMPLETED);
                })
        );

        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertTrue(fixture.hub.isOpen(runId));
        assertEquals(1, fixture.controls.activeCount());
        ExecutionStreamSubscription subscription = fixture.hub.subscribe(runId);
        BlockingQueue<ExecutionStreamEvent> events = new LinkedBlockingQueue<>();
        var receiver = subscription.events().subscribe(events::offer);
        try {
            assertEquals(CancelRequestResult.ACCEPTED,
                    fixture.controls.requestCancel(runId));
            assertEquals(CancelRequestResult.ALREADY_REQUESTED,
                    fixture.controls.requestCancel(runId));
            release.countDown();

            assertEquals(AgentStopReason.CANCELLED,
                    running.get(10, TimeUnit.SECONDS).stopReason());
            ExecutionStreamEvent event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals(1L, event.streamSequence());
            assertTrue(event.payload() instanceof ControlEvent);
            assertEquals(0, fixture.controls.activeCount());
            assertFalse(fixture.hub.isOpen(runId));
        } finally {
            release.countDown();
            receiver.dispose();
            subscription.close();
        }
    }

    @Test
    void remoteNodeOnlyAcknowledgesAfterRecoveredOwnerAcceptsCommand()
            throws Exception {
        Fixture owner = new Fixture();
        Fixture remoteNode = new Fixture();
        String runId = "recovery-remote-cancel";
        long userId = 77L;
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                owner.lifecycle.executeRecovery(request(runId), observed -> {
                    AgentRuntimeContext context = owner.enter(observed);
                    entered.countDown();
                    await(release);
                    return result(context.cancellationContext().isCancellationRequested()
                            ? AgentStopReason.CANCELLED
                            : AgentStopReason.COMPLETED);
                })
        );
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        AgentRunEntity durableRun = new AgentRunEntity(
                runId,
                "recovery-request",
                userId,
                "recovery-session",
                AgentRunStatus.RUNNING,
                AgentRunPhase.MODEL_RUNNING
        );
        AgentRunRepository runs = mock(AgentRunRepository.class);
        when(runs.findByRunIdAndUserId(runId, userId))
                .thenReturn(Optional.of(durableRun));
        AtomicInteger commands = new AtomicInteger();
        RemoteCancellationDispatcher dispatcher = commandRunId -> {
            commands.incrementAndGet();
            return Optional.of(owner.controls.requestCancel(commandRunId));
        };
        LocalRunCancellationService cancellation = new LocalRunCancellationService(
                runs,
                remoteNode.controls,
                dispatcher
        );
        try {
            assertEquals(CancelRequestResult.ACCEPTED,
                    cancellation.cancel(runId, userId).orElseThrow());
            assertEquals(CancelRequestResult.ALREADY_REQUESTED,
                    cancellation.cancel(runId, userId).orElseThrow());
            assertEquals(2, commands.get());
            release.countDown();
            assertEquals(AgentStopReason.CANCELLED,
                    running.get(10, TimeUnit.SECONDS).stopReason());
        } finally {
            release.countDown();
        }
        assertEquals(0, owner.controls.activeCount());
        assertEquals(0, remoteNode.controls.activeCount());
    }

    @Test
    void validationFailureBeforeRuntimeContributorNeverOpensSegment() {
        Fixture fixture = new Fixture();
        String runId = "recovery-validation-failure";
        ExecutionPersistenceException failure =
                new ExecutionPersistenceException("existing run validation failed");

        assertSame(failure, assertThrows(ExecutionPersistenceException.class, () ->
                fixture.lifecycle.executeRecovery(request(runId), ignored -> {
                    throw failure;
                })
        ));

        assertEquals(0, fixture.controls.activeCount());
        assertFalse(fixture.hub.isOpen(runId));
    }

    @Test
    void everyPostInitializationFailureCleansOwnedSegment() {
        List<RuntimeException> failures = List.of(
                new IllegalStateException("model execution failed"),
                new ExecutionPersistenceException("finalization failed"),
                new RunLeaseLostException("recovery-lease-lost")
        );
        for (int index = 0; index < failures.size(); index++) {
            Fixture fixture = new Fixture();
            String runId = "recovery-failure-" + index;
            RuntimeException failure = failures.get(index);

            assertSame(failure, assertThrows(failure.getClass(), () ->
                    fixture.lifecycle.executeRecovery(request(runId), observed -> {
                        fixture.enter(observed);
                        throw failure;
                    })
            ));

            assertEquals(0, fixture.controls.activeCount());
            assertFalse(fixture.hub.isOpen(runId));
        }
    }

    @Test
    void openFailureNeverClosesAnotherSegmentsStream() {
        Fixture fixture = new Fixture();
        String runId = "recovery-existing-stream";
        fixture.hub.openRun(runId);
        try {
            assertThrows(IllegalStateException.class, () ->
                    fixture.lifecycle.executeRecovery(
                            request(runId),
                            observed -> {
                                fixture.enter(observed);
                                return result(AgentStopReason.COMPLETED);
                            }
                    )
            );

            assertTrue(fixture.hub.isOpen(runId));
            assertEquals(0, fixture.controls.activeCount());
        } finally {
            fixture.hub.closeRun(runId);
        }
    }

    @Test
    void registrationFailureDoesNotRemoveAnotherSegmentsControl() {
        Fixture fixture = new Fixture();
        String runId = "recovery-existing-control";
        LocalExecutionControlRegistry.Entry existing = fixture.controls.create(runId);
        fixture.controls.register(existing);
        try {
            assertThrows(IllegalStateException.class, () ->
                    fixture.lifecycle.executeRecovery(
                            request(runId),
                            observed -> {
                                fixture.enter(observed);
                                return result(AgentStopReason.COMPLETED);
                            }
                    )
            );

            assertFalse(fixture.hub.isOpen(runId));
            assertEquals(1, fixture.controls.activeCount());
            assertEquals(CancelRequestResult.ACCEPTED,
                    fixture.controls.requestCancel(runId));
        } finally {
            fixture.controls.close(existing);
        }
    }

    private RecoveryExecutionRequest request(String runId) {
        List<AgentMessage> messages = List.of(AgentMessage.user("resume"));
        return new RecoveryExecutionRequest(
                new AgentRunSpec(runId, "recovery-session", messages, 2),
                new AgentResumeState(
                        messages,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        List.of(),
                        0,
                        1,
                        new BudgetUsage(0, 0, 0, 0, 0, Optional.empty(), false)
                ),
                "snapshot-1",
                com.multimodalAgent.agent.runtime.extension.CancellationContext.NONE,
                List.of()
        );
    }

    private static AgentRunResult result(AgentStopReason stopReason) {
        return new AgentRunResult(
                "done",
                stopReason,
                1,
                List.of(),
                List.of(AgentMessage.assistant("done")),
                TokenUsage.ZERO,
                null,
                null,
                null
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test release");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class Fixture {
        private final ExecutionStreamHub hub = new ExecutionStreamHub();
        private final ExecutionStreamPublisher publisher = new ExecutionStreamPublisher(hub);
        private final LocalExecutionControlRegistry controls =
                new LocalExecutionControlRegistry(publisher);
        private final StreamingRunExecutionLifecycle lifecycle =
                new StreamingRunExecutionLifecycle(hub, publisher, controls);

        private AgentRuntimeContext enter(RecoveryExecutionRequest request) {
            AgentRuntimeContext context = new AgentRuntimeContext(
                    request.runSpec().runId(),
                    null,
                    request.runSpec().sessionId(),
                    null,
                    request.runtimeConfigSnapshotId(),
                    request.cancellationContext(),
                    new RuntimeAttributes()
            );
            request.runtimeContextContributors().forEach(
                    contributor -> contributor.contribute(context)
            );
            return context;
        }
    }
}
