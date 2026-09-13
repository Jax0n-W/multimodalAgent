package com.multimodalAgent.agent.runtime.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeMiddlewareConcurrencyTest {

    @Test
    void shouldIsolateContextsAcrossConcurrentRunsSharingMiddlewareInstances() throws Exception {
        RuntimeAttributeKey<String> requestMarker = RuntimeAttributeKey.of(
                "request-marker",
                String.class
        );
        CyclicBarrier barrier = new CyclicBarrier(2);
        Map<String, AgentRuntimeContext> runContexts = new ConcurrentHashMap<>();
        Map<String, AgentRuntimeContext> modelContexts = new ConcurrentHashMap<>();
        RuntimeMiddleware sharedMiddleware = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                context.attributes().put(requestMarker, context.requestId());
                runContexts.put(context.runId(), context);
                await(barrier);
                return next.proceed();
            }

            @Override
            public ModelTurn aroundModelCall(
                    AgentRuntimeContext context,
                    ModelCallMetadata metadata,
                    RuntimeInvocation<ModelTurn> next
            ) {
                modelContexts.put(context.runId(), context);
                return next.proceed();
            }
        };
        RuntimeMiddlewareChain sharedChain = new RuntimeMiddlewareChain(List.of(sharedMiddleware));
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
        AgentExecutionCoordinator coordinator = new AgentExecutionCoordinator(
                new AgentRunner(
                        request -> ModelTurn.finalAnswer("done"),
                        toolExecutor,
                        TestModelToolDefinitionProjector.INSTANCE
                ),
                sharedChain
        );
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<AgentRunResult> runA = executorService.submit(() ->
                    coordinator.execute(request("run-a", "request-a", "session-a", 1L)));
            Future<AgentRunResult> runB = executorService.submit(() ->
                    coordinator.execute(request("run-b", "request-b", "session-b", 2L)));

            assertEquals(AgentStopReason.COMPLETED, runA.get(10, TimeUnit.SECONDS).stopReason());
            assertEquals(AgentStopReason.COMPLETED, runB.get(10, TimeUnit.SECONDS).stopReason());
        } finally {
            executorService.shutdownNow();
        }

        AgentRuntimeContext contextA = runContexts.get("run-a");
        AgentRuntimeContext contextB = runContexts.get("run-b");
        assertNotSame(contextA, contextB);
        assertSame(contextA, modelContexts.get("run-a"));
        assertSame(contextB, modelContexts.get("run-b"));
        assertEquals("request-a", contextA.attributes().get(requestMarker).orElseThrow());
        assertEquals("request-b", contextB.attributes().get(requestMarker).orElseThrow());
        assertEquals("session-a", contextA.sessionId());
        assertEquals("session-b", contextB.sessionId());
        assertEquals(1L, contextA.userId());
        assertEquals(2L, contextB.userId());
    }

    @Test
    void shouldRejectCapturedNextFromAnotherThreadAfterInvocationEnds() throws Exception {
        AtomicReference<RuntimeInvocation<AgentRunResult>> captured = new AtomicReference<>();
        AtomicInteger coreCalls = new AtomicInteger();
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                captured.set(next);
                return next.proceed();
            }
        };
        RuntimeMiddlewareChain chain = new RuntimeMiddlewareChain(List.of(middleware));
        chain.aroundRun(
                AgentRuntimeContext.minimal("run-late", "session-late"),
                () -> {
                    coreCalls.incrementAndGet();
                    return completedResult();
                }
        );
        ExecutorService executorService = Executors.newSingleThreadExecutor();

        try {
            Future<RuntimeMiddlewareFailureException> failure = executorService.submit(() ->
                    assertThrows(
                            RuntimeMiddlewareFailureException.class,
                            () -> captured.get().proceed()
                    ));

            failure.get(10, TimeUnit.SECONDS);
        } finally {
            executorService.shutdownNow();
        }

        assertEquals(1, coreCalls.get());
    }

    private static AgentExecutionRequest request(
            String runId,
            String requestId,
            String sessionId,
            Long userId
    ) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        runId,
                        sessionId,
                        List.of(AgentMessage.user("run")),
                        1,
                        Set.of(),
                        Set.of()
                ),
                requestId,
                userId
        );
    }

    private static AgentRunResult completedResult() {
        return new AgentRunResult(
                "done",
                AgentStopReason.COMPLETED,
                1,
                List.of(),
                List.of(),
                TokenUsage.ZERO,
                null,
                null,
                null
        );
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Concurrent test barrier failed", exception);
        }
    }
}
