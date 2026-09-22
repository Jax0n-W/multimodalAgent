package com.multimodalAgent.agent.streaming.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunPhase;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import com.multimodalAgent.agent.runtime.control.ExecutionControlState;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.ModelCompletedEvent;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeAttributes;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.stream.ControlEvent;
import com.multimodalAgent.agent.stream.ExecutionStreamEvent;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.ExecutionStreamSubscription;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import com.multimodalAgent.agent.streaming.integration.LocalRunCancellationService;
import com.multimodalAgent.agent.streaming.integration.RemoteCancellationDispatcher;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchInput;
import jakarta.validation.Validation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class RedisDistributedCancellationIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.2-alpine")
    ).withExposedPorts(6379);

    private Node owner;
    private Node requester;
    private String runId;

    @BeforeEach
    void startNodes() {
        String testId = UUID.randomUUID().toString();
        RedisCancellationProperties properties = new RedisCancellationProperties(
                true, "test:cancel:" + testId + ":command",
                "test:cancel:" + testId + ":ack", Duration.ofSeconds(5)
        );
        runId = "run-" + testId;
        owner = new Node(properties);
        requester = new Node(properties);
        assertTrue(owner.listener.isRunning());
        assertTrue(requester.listener.isRunning());
    }

    @AfterEach
    void closeNodes() throws Exception {
        if (requester != null) {
            requester.close();
        }
        if (owner != null) {
            owner.close();
        }
    }

    @Test
    void remoteCommandGetsOwnerAckAndOnlyOwnerPublishesControlEvent() throws Exception {
        LocalExecutionControlRegistry.Entry entry = owner.register(runId);
        ExecutionStreamSubscription subscription = owner.hub.subscribe(runId);
        BlockingQueue<ExecutionStreamEvent> events = new LinkedBlockingQueue<>();
        Disposable receiver = subscription.events().subscribe(events::offer);
        try {
            AgentRunRepository runs = mock(AgentRunRepository.class);
            long userId = 7L;
            when(runs.findByRunIdAndUserId(runId, userId)).thenReturn(Optional.of(
                    new AgentRunEntity(runId, "request-redis", userId, "session-redis",
                            AgentRunStatus.RUNNING, AgentRunPhase.RECEIVED)
            ));
            @SuppressWarnings("unchecked")
            ObjectProvider<RemoteCancellationDispatcher> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(requester.transport);
            LocalRunCancellationService apiNode = new LocalRunCancellationService(
                    runs, requester.controls, provider
            );
            assertTrue(apiNode.cancel(runId, userId + 1).isEmpty());
            assertEquals(CancelRequestResult.ACCEPTED,
                    apiNode.cancel(runId, userId).orElseThrow());
            assertEquals(ExecutionControlState.CANCEL_REQUESTED, entry.state());
            assertEquals(CancelRequestResult.ALREADY_REQUESTED,
                    apiNode.cancel(runId, userId).orElseThrow());
            ExecutionStreamEvent event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals(new ControlEvent(ExecutionControlState.CANCEL_REQUESTED),
                    event.payload());
            assertEquals(null, events.poll(200, TimeUnit.MILLISECONDS));
            assertEquals(0, requester.transport.pendingCount());
            assertEquals(0, owner.transport.pendingCount());
        } finally {
            receiver.dispose();
            subscription.close();
            owner.controls.close(entry);
            owner.hub.closeRun(runId);
        }
    }

    @Test
    void nonOwnerListenerDoesNotAckAndRequestTimesOut() {
        assertEquals(Optional.empty(), requester.transport.dispatch(runId));
        assertEquals(0, requester.transport.pendingCount());
        assertEquals(0, owner.transport.pendingCount());
    }

    @Test
    void thirtyTwoRemoteCancelsHaveOneAcceptedDecisionAndOneObservation() throws Exception {
        LocalExecutionControlRegistry.Entry entry = owner.register(runId);
        ExecutionStreamSubscription subscription = owner.hub.subscribe(runId);
        BlockingQueue<ExecutionStreamEvent> events = new LinkedBlockingQueue<>();
        Disposable receiver = subscription.events().subscribe(events::offer);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CancelRequestResult>> responses = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                responses.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return requester.transport.dispatch(runId).orElseThrow();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long accepted = 0;
            long repeated = 0;
            for (Future<CancelRequestResult> response : responses) {
                if (response.get(10, TimeUnit.SECONDS) == CancelRequestResult.ACCEPTED) {
                    accepted++;
                } else {
                    repeated++;
                }
            }
            assertEquals(1, accepted);
            assertEquals(31, repeated);
            assertNotNull(events.poll(5, TimeUnit.SECONDS));
            assertEquals(null, events.poll(200, TimeUnit.MILLISECONDS));
            assertEquals(0, requester.transport.pendingCount());
        } finally {
            start.countDown();
            pool.shutdownNow();
            receiver.dispose();
            subscription.close();
            owner.controls.close(entry);
            owner.hub.closeRun(runId);
        }
    }

    @Test
    void ownerTerminalDecisionAndRemoteCancelShareP84Linearization() {
        LocalExecutionControlRegistry.Entry terminalFirst = owner.register(runId);
        try {
            assertTrue(terminalFirst.trySealNormalCompletion());
            assertEquals(CancelRequestResult.ALREADY_TERMINAL,
                    requester.transport.dispatch(runId).orElseThrow());
        } finally {
            owner.controls.close(terminalFirst);
            owner.hub.closeRun(runId);
        }

        String secondRun = runId + "-cancel-first";
        LocalExecutionControlRegistry.Entry cancelFirst = owner.register(secondRun);
        try {
            assertEquals(CancelRequestResult.ACCEPTED,
                    requester.transport.dispatch(secondRun).orElseThrow());
            assertFalse(cancelFirst.trySealNormalCompletion());
        } finally {
            owner.controls.close(cancelFirst);
            owner.hub.closeRun(secondRun);
        }
    }

    @Test
    void remoteCancelAcceptedBeforeCoreNormalTerminalProducesCancelled() throws Exception {
        LocalExecutionControlRegistry.Entry entry = owner.register(runId);
        CountDownLatch modelCompleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                run(entry, request -> ModelTurn.finalAnswer("answer"), List.of(), events,
                        event -> {
                            if (event instanceof ModelCompletedEvent) {
                                modelCompleted.countDown();
                                await(release);
                            }
                        }));
        try {
            assertTrue(modelCompleted.await(5, TimeUnit.SECONDS));
            assertEquals(CancelRequestResult.ACCEPTED,
                    requester.transport.dispatch(runId).orElseThrow());
            release.countDown();
            assertEquals(AgentStopReason.CANCELLED,
                    running.get(5, TimeUnit.SECONDS).stopReason());
            assertFalse(has(events, AgentEventType.RUN_COMPLETED));
        } finally {
            release.countDown();
            owner.controls.close(entry);
            owner.hub.closeRun(runId);
        }
    }

    @Test
    void coreNormalTerminalSealedBeforeRemoteCommandRejectsCancellation() throws Exception {
        LocalExecutionControlRegistry.Entry entry = owner.register(runId);
        CountDownLatch runCompleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                run(entry, request -> ModelTurn.finalAnswer("answer"), List.of(), events,
                        event -> {
                            if (event.type() == AgentEventType.RUN_COMPLETED) {
                                runCompleted.countDown();
                                await(release);
                            }
                        }));
        try {
            assertTrue(runCompleted.await(5, TimeUnit.SECONDS));
            assertEquals(CancelRequestResult.ALREADY_TERMINAL,
                    requester.transport.dispatch(runId).orElseThrow());
            release.countDown();
            assertEquals(AgentStopReason.COMPLETED,
                    running.get(5, TimeUnit.SECONDS).stopReason());
        } finally {
            release.countDown();
            owner.controls.close(entry);
            owner.hub.closeRun(runId);
        }
    }

    @Test
    void remoteCancelDuringModelPreservesModelCompletionThenStops() throws Exception {
        LocalExecutionControlRegistry.Entry entry = owner.register(runId);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                run(entry, request -> {
                    started.countDown();
                    await(release);
                    return ModelTurn.finalAnswer("completed model");
                }, List.of(), events));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(CancelRequestResult.ACCEPTED,
                    requester.transport.dispatch(runId).orElseThrow());
            release.countDown();
            AgentRunResult result = running.get(5, TimeUnit.SECONDS);
            assertEquals(AgentStopReason.CANCELLED, result.stopReason());
            assertTrue(has(events, AgentEventType.MODEL_COMPLETED));
            assertFalse(has(events, AgentEventType.RUN_COMPLETED));
        } finally {
            release.countDown();
            owner.controls.close(entry);
            owner.hub.closeRun(runId);
        }
    }

    @Test
    void remoteCancelCannotRewriteStartedModelFailure() throws Exception {
        LocalExecutionControlRegistry.Entry entry = owner.register(runId);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                run(entry, request -> {
                    started.countDown();
                    await(release);
                    throw new IllegalStateException("model failed");
                }, List.of(), events));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(CancelRequestResult.ACCEPTED,
                    requester.transport.dispatch(runId).orElseThrow());
            release.countDown();
            assertEquals(AgentStopReason.MODEL_ERROR,
                    running.get(5, TimeUnit.SECONDS).stopReason());
            assertTrue(has(events, AgentEventType.MODEL_FAILED));
        } finally {
            release.countDown();
            owner.controls.close(entry);
            owner.hub.closeRun(runId);
        }
    }

    @Test
    void remoteCancelDuringToolPreservesSuccessAndBlocksFutureModel() throws Exception {
        LocalExecutionControlRegistry.Entry entry = owner.register(runId);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger modelCalls = new AtomicInteger();
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                run(entry, request -> {
                    modelCalls.incrementAndGet();
                    return ModelTurn.toolCall(toolCall());
                }, List.of(tool(input -> {
                    started.countDown();
                    await(release);
                    return "completed tool";
                })), events));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(CancelRequestResult.ACCEPTED,
                    requester.transport.dispatch(runId).orElseThrow());
            release.countDown();
            AgentRunResult result = running.get(5, TimeUnit.SECONDS);
            assertEquals(AgentStopReason.CANCELLED, result.stopReason());
            assertEquals(List.of("knowledge_search"), result.toolsUsed());
            assertEquals(1, modelCalls.get());
            assertTrue(has(events, AgentEventType.TOOL_SUCCEEDED));
        } finally {
            release.countDown();
            owner.controls.close(entry);
            owner.hub.closeRun(runId);
        }
    }

    @Test
    void remoteCancelCannotRewriteStartedToolFailure() throws Exception {
        LocalExecutionControlRegistry.Entry entry = owner.register(runId);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<AgentEvent> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                run(entry, request -> ModelTurn.toolCall(toolCall()),
                        List.of(tool(input -> {
                            started.countDown();
                            await(release);
                            throw new IllegalStateException("tool failed");
                        })), events));
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(CancelRequestResult.ACCEPTED,
                    requester.transport.dispatch(runId).orElseThrow());
            release.countDown();
            assertEquals(AgentStopReason.TOOL_ERROR,
                    running.get(5, TimeUnit.SECONDS).stopReason());
            assertTrue(has(events, AgentEventType.TOOL_FAILED));
        } finally {
            release.countDown();
            owner.controls.close(entry);
            owner.hub.closeRun(runId);
        }
    }

    private AgentRunResult run(
            LocalExecutionControlRegistry.Entry control,
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            List<AgentEvent> events
    ) {
        return run(control, model, tools, events, event -> { });
    }

    private AgentRunResult run(
            LocalExecutionControlRegistry.Entry control,
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            List<AgentEvent> events,
            java.util.function.Consumer<AgentEvent> observer
    ) {
        ObjectMapper mapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(
                        mapper, Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(), mapper
        );
        AgentRunner runner = new AgentRunner(model, executor,
                TestModelToolDefinitionProjector.INSTANCE, event -> {
                    events.add(event);
                    observer.accept(event);
                });
        AgentRunSpec spec = new AgentRunSpec(
                runId, "distributed-session", List.of(AgentMessage.user("question")),
                3, tools.stream().map(AgentTool::name).collect(java.util.stream.Collectors.toSet()),
                Set.of()
        );
        AgentRuntimeContext context = new AgentRuntimeContext(
                runId, null, spec.sessionId(), null, null, control, new RuntimeAttributes()
        );
        return runner.run(spec, context, RuntimeMiddlewareChain.empty());
    }

    private AgentTool<KnowledgeSearchInput, String> tool(
            java.util.function.Function<KnowledgeSearchInput, String> action
    ) {
        return new AgentTool<>() {
            private final ToolDescriptor<KnowledgeSearchInput> descriptor =
                    new ToolDescriptor<>("knowledge_search", "Search", KnowledgeSearchInput.class,
                            ToolRisk.LOW, true, true, false);

            @Override
            public ToolDescriptor<KnowledgeSearchInput> descriptor() {
                return descriptor;
            }

            @Override
            public String execute(KnowledgeSearchInput input) {
                return action.apply(input);
            }
        };
    }

    private ToolCall toolCall() {
        return new ToolCall("call-1", "knowledge_search", Map.of("query", "question"));
    }

    private boolean has(List<AgentEvent> events, AgentEventType type) {
        return events.stream().anyMatch(event -> event.type() == type);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Operation was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static final class Node implements AutoCloseable {

        private final LettuceConnectionFactory connection;
        private final ExecutionStreamHub hub = new ExecutionStreamHub();
        private final LocalExecutionControlRegistry controls;
        private final RedisDistributedCancellationTransport transport;
        private final RedisMessageListenerContainer listener;

        private Node(RedisCancellationProperties properties) {
            RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
                    REDIS.getHost(), REDIS.getMappedPort(6379)
            );
            connection = new LettuceConnectionFactory(
                    server, LettuceClientConfiguration.builder()
                            .commandTimeout(Duration.ofSeconds(2))
                            .shutdownTimeout(Duration.ZERO).build()
            );
            connection.afterPropertiesSet();
            StringRedisTemplate redis = new StringRedisTemplate(connection);
            redis.afterPropertiesSet();
            controls = new LocalExecutionControlRegistry(new ExecutionStreamPublisher(hub));
            RedisCancellationConfiguration configuration = new RedisCancellationConfiguration();
            transport = configuration.redisDistributedCancellationTransport(
                    redis, new ObjectMapper(), controls, properties
            );
            listener = configuration.cancellationMessageListenerContainer(
                    redis, transport, properties
            );
            listener.afterPropertiesSet();
            listener.start();
        }

        private LocalExecutionControlRegistry.Entry register(String runId) {
            hub.openRun(runId);
            LocalExecutionControlRegistry.Entry entry = controls.create(runId);
            controls.register(entry);
            return entry;
        }

        @Override
        public void close() throws Exception {
            listener.stop();
            listener.destroy();
            connection.destroy();
        }
    }
}
