package com.multimodalAgent.agent.execution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SnapshottingAgentExecutionCoordinatorTest {

    @Test
    void persistsAndAttachesSnapshotBeforeDelegatingToP7AndP6() {
        AtomicReference<ExecutionConfigSnapshot> persisted = new AtomicReference<>();
        AtomicReference<AgentExecutionRequest> delegated = new AtomicReference<>();
        SnapshottingAgentExecutionCoordinator coordinator = coordinator(
                new ExecutionConfigSnapshotStore() {
                    @Override
                    public ExecutionConfigSnapshot persistIfAbsent(ExecutionConfigSnapshot snapshot) {
                        persisted.set(snapshot);
                        return snapshot;
                    }

                    @Override
                    public Optional<ExecutionConfigSnapshot> findById(String snapshotId) {
                        return Optional.ofNullable(persisted.get());
                    }
                },
                request -> {
                    delegated.set(request);
                    return completed();
                }
        );

        AgentRunResult result = coordinator.execute(request());

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertNotNull(persisted.get());
        assertEquals(
                persisted.get().snapshotId(),
                delegated.get().runtimeConfigSnapshotId()
        );
    }

    @Test
    void snapshotPersistenceFailurePreventsAdmissionRuntimeProviderAndToolWork() {
        AtomicInteger durableAdmissions = new AtomicInteger();
        AtomicInteger runtimeStarts = new AtomicInteger();
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger toolSideEffects = new AtomicInteger();
        SnapshottingAgentExecutionCoordinator coordinator = coordinator(
                new ExecutionConfigSnapshotStore() {
                    @Override
                    public ExecutionConfigSnapshot persistIfAbsent(ExecutionConfigSnapshot snapshot) {
                        throw new ExecutionConfigSnapshotException("snapshot database unavailable");
                    }

                    @Override
                    public Optional<ExecutionConfigSnapshot> findById(String snapshotId) {
                        return Optional.empty();
                    }
                },
                request -> {
                    durableAdmissions.incrementAndGet();
                    runtimeStarts.incrementAndGet();
                    providerCalls.incrementAndGet();
                    toolSideEffects.incrementAndGet();
                    return completed();
                }
        );

        ExecutionConfigSnapshotException failure = assertThrows(
                ExecutionConfigSnapshotException.class,
                () -> coordinator.execute(request())
        );

        assertEquals("snapshot database unavailable", failure.getMessage());
        assertEquals(0, durableAdmissions.get());
        assertEquals(0, runtimeStarts.get());
        assertEquals(0, providerCalls.get());
        assertEquals(0, toolSideEffects.get());
    }

    private SnapshottingAgentExecutionCoordinator coordinator(
            ExecutionConfigSnapshotStore store,
            java.util.function.Function<AgentExecutionRequest, AgentRunResult> delegate
    ) {
        ResolvedModelConfig model = new ResolvedModelConfig(
                new ModelIdentity("ollama", "mindbridge"),
                new BigDecimal("0.35"),
                512,
                new ModelTimeoutPolicy(Duration.ofMinutes(2), Duration.ofSeconds(30))
        );
        return new SnapshottingAgentExecutionCoordinator(
                new ResolvedExecutionConfigResolver(model),
                new ExecutionConfigSnapshotFactory(new ObjectMapper()),
                store,
                delegate
        );
    }

    private AgentExecutionRequest request() {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "snapshot-guard-run",
                        "snapshot-guard-session",
                        List.of(AgentMessage.user("test")),
                        3,
                        Set.of("knowledge_search"),
                        Set.of(),
                        ExecutionBudget.unlimited()
                ),
                "snapshot-guard-request",
                9001L
        );
    }

    private AgentRunResult completed() {
        return new AgentRunResult(
                "done",
                AgentStopReason.COMPLETED,
                1,
                List.of(),
                List.of(AgentMessage.assistant("done")),
                TokenUsage.ZERO,
                null,
                null,
                null
        );
    }
}
