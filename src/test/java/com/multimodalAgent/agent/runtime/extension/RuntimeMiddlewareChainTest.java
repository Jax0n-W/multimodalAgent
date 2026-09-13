package com.multimodalAgent.agent.runtime.extension;

import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.tool.ToolResult;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeMiddlewareChainTest {

    private static final AgentRuntimeContext CONTEXT =
            AgentRuntimeContext.minimal("run-chain", "session-chain");

    @Test
    void shouldApplyAscendingOrderWithOnionSemanticsAroundRun() {
        List<String> calls = new ArrayList<>();
        RuntimeMiddlewareChain chain = orderedChain(calls);

        chain.aroundRun(CONTEXT, () -> {
            calls.add("core");
            return runResult();
        });

        assertEquals(List.of("m1-run-before", "m2-run-before", "core",
                "m2-run-after", "m1-run-after"), calls);
    }

    @Test
    void shouldApplyAscendingOrderWithOnionSemanticsAroundModel() {
        List<String> calls = new ArrayList<>();
        RuntimeMiddlewareChain chain = orderedChain(calls);

        chain.aroundModelCall(CONTEXT, new ModelCallMetadata(1, 1), () -> {
            calls.add("core");
            return ModelTurn.finalAnswer("answer");
        });

        assertEquals(List.of("m1-model-before", "m2-model-before", "core",
                "m2-model-after", "m1-model-after"), calls);
    }

    @Test
    void shouldApplyAscendingOrderWithOnionSemanticsAroundTool() {
        List<String> calls = new ArrayList<>();
        RuntimeMiddlewareChain chain = orderedChain(calls);

        chain.aroundToolExecution(
                CONTEXT,
                new ToolExecutionMetadata("call-1", "tool-1", 1),
                () -> {
                    calls.add("core");
                    return toolResult();
                }
        );

        assertEquals(List.of("m1-tool-before", "m2-tool-before", "core",
                "m2-tool-after", "m1-tool-after"), calls);
    }

    @Test
    void shouldKeepRegistrationOrderWhenOrdersAreEqual() {
        List<String> calls = new ArrayList<>();
        RuntimeMiddleware first = new RecordingMiddleware("first", 10, calls);
        RuntimeMiddleware second = new RecordingMiddleware("second", 10, calls);

        new RuntimeMiddlewareChain(List.of(first, second)).aroundRun(
                CONTEXT,
                () -> {
                    calls.add("core");
                    return runResult();
                }
        );

        assertEquals(List.of("first-run-before", "second-run-before", "core",
                "second-run-after", "first-run-after"), calls);
    }

    @Test
    void shouldRejectRunMiddlewareThatDoesNotProceed() {
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                return runResult();
            }
        };

        assertThrows(RuntimeMiddlewareFailureException.class, () ->
                new RuntimeMiddlewareChain(List.of(middleware))
                        .aroundRun(CONTEXT, RuntimeMiddlewareChainTest::runResult));
    }

    @Test
    void shouldRejectRunMiddlewareThatProceedsTwice() {
        AtomicInteger coreCalls = new AtomicInteger();
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                AgentRunResult result = next.proceed();
                next.proceed();
                return result;
            }
        };

        assertThrows(RuntimeMiddlewareFailureException.class, () ->
                new RuntimeMiddlewareChain(List.of(middleware)).aroundRun(CONTEXT, () -> {
                    coreCalls.incrementAndGet();
                    return runResult();
                }));
        assertEquals(1, coreCalls.get());
    }

    @Test
    void shouldRejectModelMiddlewareThatDoesNotProceed() {
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public ModelTurn aroundModelCall(
                    AgentRuntimeContext context,
                    ModelCallMetadata metadata,
                    RuntimeInvocation<ModelTurn> next
            ) {
                return ModelTurn.finalAnswer("fake");
            }
        };

        assertThrows(RuntimeMiddlewareFailureException.class, () ->
                new RuntimeMiddlewareChain(List.of(middleware)).aroundModelCall(
                        CONTEXT,
                        new ModelCallMetadata(1, 1),
                        () -> ModelTurn.finalAnswer("real")
                ));
    }

    @Test
    void shouldRejectToolMiddlewareThatProceedsTwice() {
        AtomicInteger coreCalls = new AtomicInteger();
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public ToolResult aroundToolExecution(
                    AgentRuntimeContext context,
                    ToolExecutionMetadata metadata,
                    RuntimeInvocation<ToolResult> next
            ) {
                ToolResult result = next.proceed();
                next.proceed();
                return result;
            }
        };

        assertThrows(RuntimeMiddlewareFailureException.class, () ->
                new RuntimeMiddlewareChain(List.of(middleware)).aroundToolExecution(
                        CONTEXT,
                        new ToolExecutionMetadata("call-1", "tool-1", 1),
                        () -> {
                            coreCalls.incrementAndGet();
                            return toolResult();
                        }
                ));
        assertEquals(1, coreCalls.get());
    }

    @Test
    void shouldRejectMiddlewareThatReplacesTheDownstreamResult() {
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public ModelTurn aroundModelCall(
                    AgentRuntimeContext context,
                    ModelCallMetadata metadata,
                    RuntimeInvocation<ModelTurn> next
            ) {
                next.proceed();
                return ModelTurn.finalAnswer("replacement");
            }
        };

        assertThrows(RuntimeMiddlewareFailureException.class, () ->
                new RuntimeMiddlewareChain(List.of(middleware)).aroundModelCall(
                        CONTEXT,
                        new ModelCallMetadata(1, 1),
                        () -> ModelTurn.finalAnswer("real")
                ));
    }

    @Test
    void shouldNotClassifyARealDownstreamFailureAsMiddlewareFailure() {
        IllegalStateException modelFailure = new IllegalStateException("model unavailable");
        RuntimeMiddleware transparent = new RuntimeMiddleware() {
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                new RuntimeMiddlewareChain(List.of(transparent)).aroundModelCall(
                        CONTEXT,
                        new ModelCallMetadata(1, 1),
                        () -> {
                            throw modelFailure;
                        }
                ));

        assertSame(modelFailure, thrown);
    }

    @Test
    void shouldPreserveDownstreamRuntimeMiddlewareExceptionWithoutOriginRelabeling() {
        RuntimeMiddlewareException downstreamFailure =
                new RuntimeMiddlewareException("downstream collision");

        RuntimeMiddlewareException thrown = assertThrows(RuntimeMiddlewareException.class, () ->
                new RuntimeMiddlewareChain(List.of(new RuntimeMiddleware() {
                })).aroundModelCall(
                        CONTEXT,
                        new ModelCallMetadata(1, 1),
                        () -> {
                            throw downstreamFailure;
                        }
                ));

        assertSame(downstreamFailure, thrown);
    }

    @Test
    void shouldMarkRuntimeMiddlewareExceptionThrownByMiddlewareAsMiddlewareOriginated() {
        RuntimeMiddlewareException middlewareFailure =
                new RuntimeMiddlewareException("middleware collision");
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                throw middlewareFailure;
            }
        };

        RuntimeMiddlewareFailureException thrown = assertThrows(
                RuntimeMiddlewareFailureException.class,
                () -> new RuntimeMiddlewareChain(List.of(middleware))
                        .aroundRun(CONTEXT, RuntimeMiddlewareChainTest::runResult)
        );

        assertSame(middlewareFailure, thrown.getCause());
    }

    @Test
    void shouldCloseCapturedNextWhenMiddlewareReturnsWithoutProceeding() {
        AtomicReference<RuntimeInvocation<AgentRunResult>> captured = new AtomicReference<>();
        AtomicInteger coreCalls = new AtomicInteger();
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                captured.set(next);
                return runResult();
            }
        };

        assertThrows(RuntimeMiddlewareFailureException.class, () ->
                new RuntimeMiddlewareChain(List.of(middleware)).aroundRun(CONTEXT, () -> {
                    coreCalls.incrementAndGet();
                    return runResult();
                }));
        assertThrows(RuntimeMiddlewareFailureException.class, () -> captured.get().proceed());
        assertEquals(0, coreCalls.get());
    }

    @Test
    void shouldCloseCapturedNextAfterSuccessfulMiddlewareInvocation() {
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

        new RuntimeMiddlewareChain(List.of(middleware)).aroundRun(CONTEXT, () -> {
            coreCalls.incrementAndGet();
            return runResult();
        });

        assertThrows(RuntimeMiddlewareFailureException.class, () -> captured.get().proceed());
        assertEquals(1, coreCalls.get());
    }

    @Test
    void shouldCloseCapturedNextWhenMiddlewareThrows() {
        AtomicReference<RuntimeInvocation<AgentRunResult>> captured = new AtomicReference<>();
        AtomicInteger coreCalls = new AtomicInteger();
        RuntimeMiddleware middleware = new RuntimeMiddleware() {
            @Override
            public AgentRunResult aroundRun(
                    AgentRuntimeContext context,
                    RuntimeInvocation<AgentRunResult> next
            ) {
                captured.set(next);
                throw new IllegalStateException("middleware failed");
            }
        };

        assertThrows(RuntimeMiddlewareFailureException.class, () ->
                new RuntimeMiddlewareChain(List.of(middleware)).aroundRun(CONTEXT, () -> {
                    coreCalls.incrementAndGet();
                    return runResult();
                }));
        assertThrows(RuntimeMiddlewareFailureException.class, () -> captured.get().proceed());
        assertEquals(0, coreCalls.get());
    }

    private RuntimeMiddlewareChain orderedChain(List<String> calls) {
        return new RuntimeMiddlewareChain(List.of(
                new RecordingMiddleware("m2", 20, calls),
                new RecordingMiddleware("m1", 10, calls)
        ));
    }

    private static AgentRunResult runResult() {
        return new AgentRunResult(
                "answer",
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

    private static ToolResult toolResult() {
        return ToolResult.success("result", ToolPolicyDecision.allow());
    }

    private static final class RecordingMiddleware implements RuntimeMiddleware {

        private final String name;
        private final int order;
        private final List<String> calls;

        private RecordingMiddleware(String name, int order, List<String> calls) {
            this.name = name;
            this.order = order;
            this.calls = calls;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public AgentRunResult aroundRun(
                AgentRuntimeContext context,
                RuntimeInvocation<AgentRunResult> next
        ) {
            calls.add(name + "-run-before");
            AgentRunResult result = next.proceed();
            calls.add(name + "-run-after");
            return result;
        }

        @Override
        public ModelTurn aroundModelCall(
                AgentRuntimeContext context,
                ModelCallMetadata metadata,
                RuntimeInvocation<ModelTurn> next
        ) {
            calls.add(name + "-model-before");
            ModelTurn result = next.proceed();
            calls.add(name + "-model-after");
            return result;
        }

        @Override
        public ToolResult aroundToolExecution(
                AgentRuntimeContext context,
                ToolExecutionMetadata metadata,
                RuntimeInvocation<ToolResult> next
        ) {
            calls.add(name + "-tool-before");
            ToolResult result = next.proceed();
            calls.add(name + "-tool-after");
            return result;
        }
    }
}
