package com.multimodalAgent.agent.recovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingAgentModelAdapter;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingOptions;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiStreamEvent;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseAcquireResult;
import com.multimodalAgent.agent.coordination.RunLeaseReleaseResult;
import com.multimodalAgent.agent.coordination.RunLeaseRenewResult;
import com.multimodalAgent.agent.coordination.RunLeaseStore;
import com.multimodalAgent.agent.coordination.integration.ExecutionCoordinationBoundaryMiddleware;
import com.multimodalAgent.agent.coordination.watchdog.RunLeaseWatchdog;
import com.multimodalAgent.agent.context.memory.ConversationMemoryReader;
import com.multimodalAgent.agent.adapter.model.springai.streaming.StreamingModelInvocationScope;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotFactory;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotRestorer;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfig;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.ExecutionHistoryStore;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceComposition;
import com.multimodalAgent.agent.persistence.integration.PersistentAgentExecutionCoordinator;
import com.multimodalAgent.agent.recovery.integration.RecoveryCheckpointRuntimeMiddleware;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RunStoppedEvent;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.ExecutionStreamSubscription;
import com.multimodalAgent.agent.streaming.bridge.AgentEventStreamBridge;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import com.multimodalAgent.agent.streaming.integration.RecoveryStreamingExecutionLifecycle;
import com.multimodalAgent.agent.streaming.integration.StreamingRunExecutionLifecycle;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import com.multimodalAgent.agent.stream.ControlEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.stream.RuntimeEventPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryEngineSuccessfulPathIntegrationTest {

    private static final String RUN_ID = "recovery-engine-success";
    private static final String SESSION_ID = "recovery-session";
    private static final String TOOL_CALL_ID = "durable-call";
    private static final String TOOL_NAME = "durable_tool";
    private static final Instant NOW = Instant.parse("2026-09-29T07:00:00Z");

    @Test
    void repairsCheckpointAndResumesTheExistingRunThroughFinalization() {
        AtomicInteger memoryReads = new AtomicInteger();
        ConversationMemoryReader countingMemoryReader = query -> {
            memoryReads.incrementAndGet();
            return List.of();
        };
        ObjectMapper objectMapper = new ObjectMapper();
        ResolvedModelConfig modelConfig = new ResolvedModelConfig(
                new ModelIdentity("test", "recovery-model"),
                BigDecimal.ZERO,
                128,
                new ModelTimeoutPolicy(Duration.ofSeconds(2), Duration.ofSeconds(2))
        );
        ResolvedExecutionConfig resolvedConfig = new ResolvedExecutionConfig(
                ResolvedExecutionConfig.CURRENT_SCHEMA_VERSION,
                modelConfig,
                new ResolvedExecutionConfig.RuntimeConfig(3, List.of(TOOL_NAME)),
                ExecutionBudget.builder().maxToolCalls(2).build()
        );
        ExecutionConfigSnapshot originalSnapshot =
                new ExecutionConfigSnapshotFactory(objectMapper).create(resolvedConfig);
        RecordingSnapshotStore snapshots = new RecordingSnapshotStore(originalSnapshot);
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        checkpoints.persist(initialCheckpoint(originalSnapshot.snapshotId()));
        ReliableToolOutcome exactOutcome = ReliableToolOutcome.capture(
                "durable-execution",
                RUN_ID,
                TOOL_CALL_ID,
                TOOL_NAME,
                "{\"receipt\":\"confirmed\"}",
                NOW.plusSeconds(1)
        );
        RecoveryEvidenceReader evidenceReader = runId -> evidence(
                originalSnapshot.snapshotId(),
                checkpoints.findLatestByRunId(runId).orElseThrow()
        );

        RecordingHistoryStore history = new RecordingHistoryStore(
                RUN_ID, originalSnapshot.snapshotId()
        );
        ExecutionPersistenceComposition persistenceComposition =
                new ExecutionPersistenceComposition(history);
        ExecutionStreamHub hub = new ExecutionStreamHub();
        ExecutionStreamPublisher streamPublisher = new ExecutionStreamPublisher(hub);
        LocalExecutionControlRegistry controls =
                new LocalExecutionControlRegistry(streamPublisher);
        AgentEventStreamBridge streamBridge = new AgentEventStreamBridge(streamPublisher);
        StreamingRunExecutionLifecycle streamingLifecycle =
                new StreamingRunExecutionLifecycle(
                        hub,
                        streamPublisher,
                        controls
                );
        ScriptedAgentModel model = new ScriptedAgentModel(
                ModelTurn.finalAnswer("recovered completion")
        );
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(List.of()),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentRunner runner = new AgentRunner(
                model,
                toolExecutor,
                TestModelToolDefinitionProjector.INSTANCE,
                event -> {
                    persistenceComposition.eventPublisher().publish(event);
                    streamBridge.publish(event);
                }
        );
        StreamingModelInvocationScope invocationScope =
                new StreamingModelInvocationScope();
        RuntimeMiddlewareChain middleware = new RuntimeMiddlewareChain(List.of(
                invocationScope,
                new ExecutionCoordinationBoundaryMiddleware(),
                new RecoveryCheckpointRuntimeMiddleware(
                        persistenceComposition::assertHealthy
                ),
                persistenceComposition.boundaryMiddleware()
        ));
        PersistentAgentExecutionCoordinator persistence =
                persistenceComposition.persistentCoordinator(
                        new AgentExecutionCoordinator(runner, middleware)
                );

        RecordingLeaseStore leases = new RecordingLeaseStore();
        RecoveryEngine engine = new RecoveryEngine(
                leases,
                session -> new RunLeaseWatchdog(
                        leases,
                        session,
                        (task, interval) -> () -> {
                        },
                        Duration.ofHours(1)
                ),
                evidenceReader,
                new RecoveryEligibilityEvaluator(),
                authority -> repairer(
                        authority,
                        evidenceReader,
                        checkpoints,
                        exactOutcome
                ),
                new BudgetRecoveryReconstructor(),
                snapshots,
                new ExecutionConfigSnapshotRestorer(objectMapper),
                modelConfig,
                checkpoints,
                new RecoveryStreamingExecutionLifecycle(
                        streamingLifecycle,
                        persistence
                )
        );

        RecoveryEngineResult result = engine.recover(
                new RecoveryCandidate(RUN_ID, SESSION_ID)
        );

        assertEquals(RecoveryEngineResult.Status.RESUMED, result.status());
        assertEquals(AgentStopReason.COMPLETED,
                result.runResult().orElseThrow().stopReason());
        assertEquals("recovered completion",
                result.runResult().orElseThrow().finalContent());
        assertEquals(1, leases.acquisitions);
        assertEquals(1, leases.releases);
        assertEquals(0, history.admissions);
        assertEquals(1, history.existingRunAssertions);
        assertEquals(1, history.finalizations);
        assertSame(result.runResult().orElseThrow(), history.finalResult);
        assertTrue(history.events.stream().allMatch(event -> event.runId().equals(RUN_ID)));
        assertFalse(history.events.stream().anyMatch(
                event -> event.type() == AgentEventType.RUN_STARTED
        ));
        assertFalse(history.events.stream().anyMatch(
                event -> event.type() == AgentEventType.TOOL_STARTED
        ));
        assertEquals(1, model.requests().size());
        assertEquals(TOOL_CALL_ID,
                model.requests().get(0).get(2).toolCallId());
        assertEquals(exactOutcome.modelVisibleResult(),
                model.requests().get(0).get(2).content());
        assertTrue(checkpoints.listByRunId(RUN_ID).stream().anyMatch(checkpoint ->
                checkpoint.boundary() == RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME
                        && checkpoint.messages().stream().anyMatch(message ->
                        TOOL_CALL_ID.equals(message.toolCallId())
                                && exactOutcome.modelVisibleResult().equals(message.content())
                )
        ));
        assertEquals(0, snapshots.persistCalls);
        assertSame(originalSnapshot, snapshots.snapshot);
        assertEquals(0, controls.activeCount());
        assertFalse(hub.isOpen(RUN_ID));
        assertNotNull(countingMemoryReader);
        assertEquals(0, memoryReads.get());
    }

    @Test
    void recoveredRunningSegmentStreamsAndCancelsWithoutSecondAdmissionOrRunStart()
            throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ResolvedModelConfig modelConfig = new ResolvedModelConfig(
                new ModelIdentity("test", "recovery-model"),
                BigDecimal.ZERO,
                128,
                new ModelTimeoutPolicy(Duration.ofSeconds(20), Duration.ofSeconds(20))
        );
        ResolvedExecutionConfig resolvedConfig = new ResolvedExecutionConfig(
                ResolvedExecutionConfig.CURRENT_SCHEMA_VERSION,
                modelConfig,
                new ResolvedExecutionConfig.RuntimeConfig(3, List.of()),
                ExecutionBudget.unlimited()
        );
        ExecutionConfigSnapshot snapshot =
                new ExecutionConfigSnapshotFactory(objectMapper).create(resolvedConfig);
        RecordingSnapshotStore snapshots = new RecordingSnapshotStore(snapshot);
        InMemoryCheckpointStore checkpoints = new InMemoryCheckpointStore();
        RecoveryCheckpoint checkpoint = iterationCheckpoint(snapshot.snapshotId());
        checkpoints.persist(checkpoint);
        RecoveryEvidenceReader evidenceReader = runId -> new RecoveryEvidence(
                RUN_ID,
                Optional.of(new RecoveryRunEvidence(
                        RUN_ID,
                        RecoveryRunStatus.RUNNING,
                        0,
                        Optional.of(snapshot.snapshotId())
                )),
                Optional.of(checkpoints.findLatestByRunId(runId).orElseThrow()),
                List.of(),
                List.of(),
                List.of()
        );
        RecordingHistoryStore history = new RecordingHistoryStore(
                RUN_ID, snapshot.snapshotId()
        );
        ExecutionPersistenceComposition persistenceComposition =
                new ExecutionPersistenceComposition(history);
        ExecutionStreamHub hub = new ExecutionStreamHub();
        ExecutionStreamPublisher streamPublisher = new ExecutionStreamPublisher(hub);
        LocalExecutionControlRegistry controls =
                new LocalExecutionControlRegistry(streamPublisher);
        StreamingRunExecutionLifecycle streamingLifecycle =
                new StreamingRunExecutionLifecycle(hub, streamPublisher, controls);
        AgentEventStreamBridge streamBridge = new AgentEventStreamBridge(streamPublisher);
        StreamingModelInvocationScope invocationScope =
                new StreamingModelInvocationScope();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        OpenAiCompatibleStreamingAgentModelAdapter model =
                new OpenAiCompatibleStreamingAgentModelAdapter(
                        request -> Flux.defer(() -> {
                            modelCalls.incrementAndGet();
                            providerEntered.countDown();
                            await(releaseProvider);
                            return Flux.just(
                                    textChunk(
                                            "recovered answer",
                                            OpenAiApi.ChatCompletionFinishReason.STOP
                                    ),
                                    OpenAiStreamEvent.Done.INSTANCE
                            );
                        }),
                        new OpenAiCompatibleStreamingOptions(
                                "test", "recovery-model", 0.0, 128
                        ),
                        objectMapper,
                        invocationScope
                );
        AgentRunner runner = new AgentRunner(
                model,
                new ToolExecutor(
                        new ToolRegistry(List.of()),
                        new ToolArgumentResolver(
                                objectMapper,
                                Validation.buildDefaultValidatorFactory().getValidator()
                        ),
                        new DefaultToolPolicyEngine(),
                        objectMapper
                ),
                TestModelToolDefinitionProjector.INSTANCE,
                event -> {
                    persistenceComposition.eventPublisher().publish(event);
                    streamBridge.publish(event);
                }
        );
        PersistentAgentExecutionCoordinator persistence =
                persistenceComposition.persistentCoordinator(
                        new AgentExecutionCoordinator(
                                runner,
                                new RuntimeMiddlewareChain(List.of(
                                        invocationScope,
                                        new ExecutionCoordinationBoundaryMiddleware(),
                                        new RecoveryCheckpointRuntimeMiddleware(
                                                persistenceComposition::assertHealthy
                                        ),
                                        persistenceComposition.boundaryMiddleware()
                                ))
                        )
                );
        RecordingLeaseStore leases = new RecordingLeaseStore();
        RecoveryEngine engine = new RecoveryEngine(
                leases,
                session -> new RunLeaseWatchdog(
                        leases,
                        session,
                        (task, interval) -> () -> {
                        },
                        Duration.ofHours(1)
                ),
                evidenceReader,
                new RecoveryEligibilityEvaluator(),
                authority -> {
                    throw new AssertionError("safe recovery must not create a repairer");
                },
                new BudgetRecoveryReconstructor(),
                snapshots,
                new ExecutionConfigSnapshotRestorer(objectMapper),
                modelConfig,
                checkpoints,
                new RecoveryStreamingExecutionLifecycle(
                        streamingLifecycle,
                        persistence
                )
        );

        CompletableFuture<RecoveryEngineResult> recovering =
                CompletableFuture.supplyAsync(() -> engine.recover(
                        new RecoveryCandidate(RUN_ID, SESSION_ID)
                ));
        ExecutionStreamSubscription subscription = null;
        Disposable receiver = null;
        try {
            assertTrue(providerEntered.await(10, TimeUnit.SECONDS));
            assertTrue(hub.isOpen(RUN_ID));
            assertEquals(1, controls.activeCount());
            subscription = hub.subscribe(RUN_ID);
            BlockingQueue<ExecutionStreamEvent> events = new LinkedBlockingQueue<>();
            receiver = subscription.events().subscribe(events::offer);

            assertEquals(CancelRequestResult.ACCEPTED, controls.requestCancel(RUN_ID));
            assertEquals(CancelRequestResult.ALREADY_REQUESTED,
                    controls.requestCancel(RUN_ID));
            releaseProvider.countDown();

            RecoveryEngineResult recovered = recovering.get(20, TimeUnit.SECONDS);
            assertEquals(RecoveryEngineResult.Status.RESUMED, recovered.status());
            assertEquals(AgentStopReason.CANCELLED,
                    recovered.runResult().orElseThrow().stopReason());
            List<ExecutionStreamEvent> observed = takeUntilRunStopped(events);
            assertEquals(1, observed.stream()
                    .filter(event -> event.payload() instanceof ControlEvent)
                    .count());
            assertTrue(observed.stream().anyMatch(event ->
                    event.payload() instanceof ModelDelta delta
                            && delta.content().equals("recovered answer")
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
            if (receiver != null) {
                receiver.dispose();
            }
            if (subscription != null) {
                subscription.close();
            }
        }

        assertEquals(1, leases.acquisitions);
        assertEquals(1, leases.releases);
        assertEquals(0, history.admissions);
        assertEquals(1, history.existingRunAssertions);
        assertEquals(1, history.finalizations);
        assertEquals(AgentStopReason.CANCELLED, history.finalResult.stopReason());
        assertFalse(history.events.stream().anyMatch(
                event -> event.type() == AgentEventType.RUN_STARTED
        ));
        assertEquals(1, modelCalls.get());
        assertEquals(0, controls.activeCount());
        assertFalse(hub.isOpen(RUN_ID));
        assertEquals(0, snapshots.persistCalls);
    }

    private RecoveryCheckpoint iterationCheckpoint(String snapshotId) {
        return new RecoveryCheckpoint(
                "checkpoint-iteration-boundary",
                RUN_ID,
                1,
                0,
                RecoveryCheckpointBoundary.ITERATION_BOUNDARY,
                List.of(AgentMessage.user("resume and answer")),
                List.of(),
                Set.of(),
                new BudgetCheckpoint(0, 0, 0, 0, 0, Optional.empty(), false),
                Set.of(),
                snapshotId,
                NOW,
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
    }

    private List<ExecutionStreamEvent> takeUntilRunStopped(
            BlockingQueue<ExecutionStreamEvent> events
    ) throws InterruptedException {
        List<ExecutionStreamEvent> observed = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            ExecutionStreamEvent event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            observed.add(event);
            if (event.payload() instanceof RuntimeEventPayload payload
                    && payload.event() instanceof RunStoppedEvent) {
                return observed;
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
                "recovery-response",
                List.of(choice),
                1L,
                "recovery-model",
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

    private RecoveryToolRepairer repairer(
            RecoveryAuthorityGuard authority,
            RecoveryEvidenceReader evidenceReader,
            RecoveryCheckpointStore checkpoints,
            ReliableToolOutcome outcome
    ) {
        ReliableToolOutcomeStore outcomes = new ReliableToolOutcomeStore() {
            @Override
            public void persist(ReliableToolOutcome value) {
                throw new AssertionError("recovery must not replace the durable outcome");
            }

            @Override
            public Optional<ReliableToolOutcome> findByExecutionId(String executionId) {
                return outcome.executionId().equals(executionId)
                        ? Optional.of(outcome) : Optional.empty();
            }

            @Override
            public Optional<ReliableToolOutcome> findByRunIdAndToolCallId(
                    String runId,
                    String toolCallId
            ) {
                return outcome.runId().equals(runId)
                        && outcome.toolCallId().equals(toolCallId)
                        ? Optional.of(outcome) : Optional.empty();
            }
        };
        ToolReconciliationAttemptStore attempts = new UnusedAttemptStore();
        ReliableToolOutcomeMaterializer materializer =
                new ReliableToolOutcomeMaterializer(
                        authority,
                        (runId, toolCallId) -> {
                            throw new AssertionError("successful fact must not rematerialize");
                        }
                );
        ToolReconciliationCoordinator reconciliation = new ToolReconciliationCoordinator(
                authority,
                (runId, toolCallId) -> {
                    throw new AssertionError("successful fact must not reconcile");
                },
                evidenceReader,
                new ToolAmbiguityPlanner(),
                new ToolReconcilerRegistry(List.of()),
                attempts
        );
        return new RecoveryToolRepairer(
                authority,
                evidenceReader,
                outcomes,
                materializer,
                checkpoints,
                attempts,
                reconciliation,
                new BudgetRecoveryReconstructor(),
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)
        );
    }

    private RecoveryEvidence evidence(String snapshotId, RecoveryCheckpoint checkpoint) {
        return new RecoveryEvidence(
                RUN_ID,
                Optional.of(new RecoveryRunEvidence(
                        RUN_ID,
                        RecoveryRunStatus.RUNNING,
                        1,
                        Optional.of(snapshotId)
                )),
                Optional.of(checkpoint),
                List.of(new RecoveryModelEvidence(
                        "model-step-1", 1, 1, RecoveryModelStatus.SUCCEEDED
                )),
                List.of(new RecoveryToolEvidence(
                        "durable-execution",
                        "tool-step-1",
                        1,
                        2,
                        TOOL_CALL_ID,
                        TOOL_NAME,
                        RecoveryToolStatus.SUCCEEDED,
                        true,
                        1,
                        Optional.empty()
                )),
                List.of()
        );
    }

    private RecoveryCheckpoint initialCheckpoint(String snapshotId) {
        return new RecoveryCheckpoint(
                "checkpoint-before-tool-result",
                RUN_ID,
                1,
                1,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME,
                List.of(
                        AgentMessage.user("complete the durable operation"),
                        AgentMessage.assistantToolCalls(List.of(new ToolCall(
                                TOOL_CALL_ID, TOOL_NAME, Map.of("id", "17")
                        )))
                ),
                List.of(),
                Set.of(TOOL_CALL_ID),
                new BudgetCheckpoint(1, 0, 0, 0, 0, Optional.empty(), false),
                Set.of(),
                snapshotId,
                NOW,
                RecoveryCheckpoint.CURRENT_SCHEMA_VERSION
        );
    }

    private static final class InMemoryCheckpointStore implements RecoveryCheckpointStore {

        private final List<RecoveryCheckpoint> values = new ArrayList<>();

        @Override
        public synchronized void persist(RecoveryCheckpoint checkpoint) {
            values.add(checkpoint);
        }

        @Override
        public synchronized Optional<RecoveryCheckpoint> findLatestByRunId(String runId) {
            return values.stream()
                    .filter(value -> value.runId().equals(runId))
                    .max(Comparator.comparingLong(RecoveryCheckpoint::sequence));
        }

        @Override
        public synchronized Optional<RecoveryCheckpoint> findByCheckpointId(
                String checkpointId
        ) {
            return values.stream()
                    .filter(value -> value.checkpointId().equals(checkpointId))
                    .findFirst();
        }

        @Override
        public synchronized List<RecoveryCheckpoint> listByRunId(String runId) {
            return values.stream().filter(value -> value.runId().equals(runId)).toList();
        }
    }

    private static final class RecordingSnapshotStore implements ExecutionConfigSnapshotStore {

        private final ExecutionConfigSnapshot snapshot;
        private int persistCalls;

        private RecordingSnapshotStore(ExecutionConfigSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public ExecutionConfigSnapshot persistIfAbsent(ExecutionConfigSnapshot value) {
            persistCalls++;
            return snapshot;
        }

        @Override
        public Optional<ExecutionConfigSnapshot> findById(String snapshotId) {
            return snapshot.snapshotId().equals(snapshotId)
                    ? Optional.of(snapshot) : Optional.empty();
        }
    }

    private static final class RecordingHistoryStore implements ExecutionHistoryStore {

        private final String expectedRunId;
        private final String expectedSnapshotId;
        private final List<AgentEvent> events = new ArrayList<>();
        private int admissions;
        private int existingRunAssertions;
        private int finalizations;
        private AgentRunResult finalResult;

        private RecordingHistoryStore(String expectedRunId, String expectedSnapshotId) {
            this.expectedRunId = expectedRunId;
            this.expectedSnapshotId = expectedSnapshotId;
        }

        @Override
        public void admit(AgentExecutionRequest request) {
            admissions++;
        }

        @Override
        public void record(AgentEvent event) {
            events.add(event);
        }

        @Override
        public void finalizeRun(String runId, AgentRunResult result) {
            assertEquals(expectedRunId, runId);
            finalizations++;
            finalResult = result;
        }

        @Override
        public void assertExistingRunning(String runId, String runtimeConfigSnapshotId) {
            assertEquals(expectedRunId, runId);
            assertEquals(expectedSnapshotId, runtimeConfigSnapshotId);
            existingRunAssertions++;
        }
    }

    private static final class RecordingLeaseStore implements RunLeaseStore {

        private int acquisitions;
        private int releases;

        @Override
        public RunLeaseAcquireResult tryAcquire(String runId) {
            acquisitions++;
            return new RunLeaseAcquireResult.Acquired(
                    new RunLease(runId, "lease-token-1")
            );
        }

        @Override
        public RunLeaseRenewResult renew(RunLease lease) {
            return RunLeaseRenewResult.RENEWED;
        }

        @Override
        public RunLeaseReleaseResult release(RunLease lease) {
            releases++;
            return RunLeaseReleaseResult.RELEASED;
        }
    }

    private static final class UnusedAttemptStore implements ToolReconciliationAttemptStore {

        @Override
        public ToolReconciliationStart start(
                String runId,
                String toolCallId,
                ToolRecoveryContractSnapshot contract
        ) {
            throw new AssertionError("successful fact must not start reconciliation");
        }

        @Override
        public ToolReconciliationAttempt complete(
                String reconciliationId,
                ToolReconciliationResult result
        ) {
            throw new AssertionError("successful fact must not complete reconciliation");
        }

        @Override
        public ToolReconciliationAttempt fail(
                String reconciliationId,
                String errorCode,
                String errorMessage
        ) {
            throw new AssertionError("successful fact must not fail reconciliation");
        }

        @Override
        public int abandonStarted(String runId, String toolCallId) {
            throw new AssertionError("successful fact must not abandon reconciliation");
        }
    }
}
