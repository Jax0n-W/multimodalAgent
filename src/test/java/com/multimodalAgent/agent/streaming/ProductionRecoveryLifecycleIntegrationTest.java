package com.multimodalAgent.agent.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiStreamEvent;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdog;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdogFactory;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotFactory;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfig;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.recovery.BudgetCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCandidate;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointBoundary;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointStore;
import com.multimodalAgent.agent.recovery.RecoveryEngineResult;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RunStoppedEvent;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.stream.ControlEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.stream.RuntimeEventPayload;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import com.multimodalAgent.agent.streaming.integration.StreamingRuntimeComposition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p10h-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.ai.provider=ollama",
        "multimodal-agent.runtime.enabled=true",
        "multimodal-agent.knowledge.use-chroma=false"
})
@AutoConfigureWebTestClient
class ProductionRecoveryLifecycleIntegrationTest {

    @Autowired private StreamingRuntimeComposition composition;
    @Autowired private AgentRunRepository runs;
    @Autowired private RecoveryCheckpointStore checkpoints;
    @Autowired private ExecutionConfigSnapshotStore snapshots;
    @Autowired private ResolvedModelConfig modelConfig;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ExecutionStreamHub hub;
    @Autowired private LocalExecutionControlRegistry controls;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private WebTestClient client;

    @MockBean private OpenAiCompatibleStreamingClient provider;
    @MockBean private RunLeaseStore leases;
    @MockBean private RunLeaseWatchdogFactory watchdogFactory;

    @Test
    void productionRecoverySegmentStreamsAcceptsHttpCancelAndFinalizesExistingRun()
            throws Exception {
        UserAccount owner = user();
        String runId = "p10h-" + UUID.randomUUID();
        String sessionId = "p10h-session-" + UUID.randomUUID();
        ResolvedExecutionConfig config = new ResolvedExecutionConfig(
                ResolvedExecutionConfig.CURRENT_SCHEMA_VERSION,
                modelConfig,
                new ResolvedExecutionConfig.RuntimeConfig(3, List.of()),
                ExecutionBudget.unlimited()
        );
        ExecutionConfigSnapshot snapshot = snapshots.persistIfAbsent(
                new ExecutionConfigSnapshotFactory(objectMapper).create(config)
        );
        AgentRunEntity originalRun = new AgentRunEntity(
                runId,
                "p10h-request-" + UUID.randomUUID(),
                owner.getId(),
                sessionId,
                AgentRunStatus.RUNNING,
                AgentRunPhase.MODEL_RUNNING
        );
        originalRun.setCurrentIteration(0);
        originalRun.setRuntimeConfigSnapshotId(snapshot.snapshotId());
        runs.saveAndFlush(originalRun);
        checkpoints.persist(new RecoveryCheckpoint(
                "p10h-checkpoint-" + UUID.randomUUID(),
                runId,
                1,
                0,
                RecoveryCheckpointBoundary.ITERATION_BOUNDARY,
                List.of(AgentMessage.user("resume this run")),
                List.of(),
                Set.of(),
                BudgetCheckpoint.EMPTY,
                Set.of(),
                snapshot.snapshotId(),
                Instant.now(),
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        ));
        long runCountBefore = runs.count();
        RunLease lease = new RunLease(runId, "p10h-lease-token");
        when(leases.tryAcquire(runId)).thenReturn(
                new RunLeaseAcquireResult.Acquired(lease)
        );
        when(leases.release(lease)).thenReturn(RunLeaseReleaseResult.RELEASED);
        AtomicBoolean watchdogCancelled = new AtomicBoolean();
        when(watchdogFactory.create(any())).thenAnswer(invocation ->
                new RunLeaseWatchdog(
                        leases,
                        invocation.getArgument(0),
                        (task, interval) -> () -> watchdogCancelled.set(true),
                        Duration.ofHours(1)
                ));
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        when(provider.stream(any())).thenReturn(Flux.defer(() -> {
            providerEntered.countDown();
            await(releaseProvider);
            return Flux.just(
                    textChunk(
                            "recovered production answer",
                            OpenAiApi.ChatCompletionFinishReason.STOP
                    ),
                    OpenAiStreamEvent.Done.INSTANCE
            );
        }));

        CompletableFuture<List<RecoveryEngineResult>> recovering =
                CompletableFuture.supplyAsync(() -> composition.recoveryScanner()
                        .orElseThrow()
                        .scan());
        ExecutionStreamSubscription subscription = null;
        Disposable receiver = null;
        try {
            assertTrue(providerEntered.await(10, TimeUnit.SECONDS));
            assertTrue(hub.isOpen(runId));
            assertEquals(1, controls.activeCount());
            subscription = hub.subscribe(runId);
            BlockingQueue<ExecutionStreamEvent> events = new LinkedBlockingQueue<>();
            receiver = subscription.events().subscribe(events::offer);

            client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                    .headers(headers -> headers.setBasicAuth(
                            owner.getUsername(), "p10h-password"
                    ))
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.result").isEqualTo("ACCEPTED");
            client.post().uri("/api/agent/runs/{runId}/cancel", runId)
                    .headers(headers -> headers.setBasicAuth(
                            owner.getUsername(), "p10h-password"
                    ))
                    .exchange().expectStatus().isOk()
                    .expectBody().jsonPath("$.result").isEqualTo("ALREADY_REQUESTED");
            releaseProvider.countDown();

            List<RecoveryEngineResult> results = recovering.get(20, TimeUnit.SECONDS);
            assertEquals(1, results.size());
            assertEquals(RecoveryEngineResult.Status.RESUMED, results.get(0).status());
            assertEquals(AgentStopReason.CANCELLED,
                    results.get(0).runResult().orElseThrow().stopReason());
            List<ExecutionStreamEvent> observed = takeUntilStopped(events);
            assertEquals(1, observed.stream()
                    .filter(event -> event.payload() instanceof ControlEvent)
                    .count());
            assertTrue(observed.stream().anyMatch(event ->
                    event.payload() instanceof ModelDelta delta
                            && delta.content().equals("recovered production answer")
            ));
            assertTrue(observed.stream().anyMatch(event ->
                    event.payload() instanceof RuntimeEventPayload payload
                            && payload.event().type() == AgentEventType.MODEL_COMPLETED
            ));
            assertTrue(observed.stream().anyMatch(event ->
                    event.payload() instanceof RuntimeEventPayload payload
                            && payload.event() instanceof RunStoppedEvent stopped
                            && stopped.stopReason() == AgentStopReason.CANCELLED
            ));
            assertFalse(observed.stream().anyMatch(event ->
                    event.payload() instanceof RuntimeEventPayload payload
                            && payload.event().type() == AgentEventType.RUN_STARTED
            ));
            for (int index = 1; index < observed.size(); index++) {
                assertTrue(observed.get(index).streamSequence()
                        > observed.get(index - 1).streamSequence());
            }
        } finally {
            releaseProvider.countDown();
            if (receiver != null) receiver.dispose();
            if (subscription != null) subscription.close();
        }

        AgentRunEntity finalized = runs.findByRunId(runId).orElseThrow();
        assertEquals(originalRun.getRunId(), finalized.getRunId());
        assertEquals(AgentRunStatus.CANCELLED, finalized.getStatus());
        assertEquals(AgentStopReason.CANCELLED, finalized.getStopReason());
        assertEquals(runCountBefore, runs.count());
        assertEquals(snapshot.snapshotId(), finalized.getRuntimeConfigSnapshotId());
        assertEquals(0, controls.activeCount());
        assertFalse(hub.isOpen(runId));
        verify(provider, times(1)).stream(any());
        verify(leases, times(1)).tryAcquire(runId);
        verify(leases, times(1)).release(lease);
        verify(watchdogFactory, times(1)).create(any());
        assertTrue(watchdogCancelled.get());
    }

    private UserAccount user() {
        UserAccount user = new UserAccount();
        user.setUsername("p10h-owner-" + UUID.randomUUID());
        user.setDisplayName(user.getUsername());
        user.setPassword(passwords.encode("p10h-password"));
        user.setRoles(Set.of("ROLE_USER"));
        return users.save(user);
    }

    private List<ExecutionStreamEvent> takeUntilStopped(
            BlockingQueue<ExecutionStreamEvent> queue
    ) throws InterruptedException {
        List<ExecutionStreamEvent> events = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            ExecutionStreamEvent event = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            events.add(event);
            if (event.payload() instanceof RuntimeEventPayload payload
                    && payload.event() instanceof RunStoppedEvent) {
                return events;
            }
        }
        throw new AssertionError("RUN_STOPPED was not observed");
    }

    private OpenAiStreamEvent textChunk(
            String content,
            OpenAiApi.ChatCompletionFinishReason finishReason
    ) {
        OpenAiApi.ChatCompletionMessage message = new OpenAiApi.ChatCompletionMessage(
                content,
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
        );
        OpenAiApi.ChatCompletionChunk.ChunkChoice choice =
                new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                        finishReason,
                        0,
                        message,
                        null
                );
        return new OpenAiStreamEvent.Chunk(new OpenAiApi.ChatCompletionChunk(
                "p10h-response",
                List.of(choice),
                1L,
                "test-model",
                null,
                null,
                "chat.completion.chunk",
                null
        ));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("Provider was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
