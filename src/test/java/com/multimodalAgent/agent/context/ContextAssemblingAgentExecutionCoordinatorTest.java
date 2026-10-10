package com.multimodalAgent.agent.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshot;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotFactory;
import com.multimodalAgent.agent.execution.config.ExecutionConfigSnapshotStore;
import com.multimodalAgent.agent.execution.config.ResolvedExecutionConfigResolver;
import com.multimodalAgent.agent.execution.config.ResolvedModelConfig;
import com.multimodalAgent.agent.execution.config.SnapshottingAgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContextAssemblingAgentExecutionCoordinatorTest {

    @Test
    void persistsBeforeDelegatingAndRuntimeReceivesAssembledMessagesExactlyOnce() {
        List<String> order = new ArrayList<>();
        AtomicInteger loads = new AtomicInteger();
        AtomicReference<AgentContextSnapshot> persisted = new AtomicReference<>();
        AtomicReference<AgentExecutionRequest> delegated = new AtomicReference<>();
        AtomicReference<Set<String>> contextAllowedTools = new AtomicReference<>();
        ContextSource source = new ContextSource() {
            @Override public String sourceId() { return "request"; }
            @Override public String sourceVersion() { return "1"; }
            @Override public int order() { return 0; }
            @Override public ContextContribution load(ContextAssemblyInput input) {
                loads.incrementAndGet();
                contextAllowedTools.set(input.allowedTools());
                return new ContextContribution(input.requestMessages());
            }
        };
        AgentContextSnapshotStore store = new AgentContextSnapshotStore() {
            @Override
            public AgentContextSnapshot persistIfAbsent(AgentContextSnapshot snapshot) {
                order.add("context-persisted");
                persisted.set(snapshot);
                return snapshot;
            }

            @Override
            public Optional<AgentContextSnapshot> findById(String snapshotId) {
                return Optional.ofNullable(persisted.get());
            }
        };
        ContextAssemblingAgentExecutionCoordinator coordinator = new
                ContextAssemblingAgentExecutionCoordinator(
                assembler(List.of(source)),
                store,
                request -> {
                    order.add("durable-run");
                    delegated.set(request);
                    return completed();
                }
        );
        AgentExecutionRequest original = request();
        List<AgentMessage> originalMessages = original.runSpec().messages();

        coordinator.execute(original);

        assertEquals(List.of("context-persisted", "durable-run"), order);
        assertEquals(1, loads.get());
        assertEquals(Set.of("knowledge_search"), contextAllowedTools.get());
        assertNotNull(persisted.get());
        assertEquals(original.runSpec().messages(), delegated.get().runSpec().messages());
        assertEquals(persisted.get().snapshotId(), delegated.get().contextSnapshotId());
        assertEquals(originalMessages, original.runSpec().messages());
    }

    @Test
    void contextSnapshotPrecedesConfigurationSnapshotAndDurableExecution() {
        List<String> order = new ArrayList<>();
        AtomicReference<AgentContextSnapshot> contextSnapshot = new AtomicReference<>();
        AtomicReference<ExecutionConfigSnapshot> configSnapshot = new AtomicReference<>();
        AtomicReference<AgentExecutionRequest> executed = new AtomicReference<>();
        AgentContextSnapshotStore contextStore = new AgentContextSnapshotStore() {
            @Override
            public AgentContextSnapshot persistIfAbsent(AgentContextSnapshot snapshot) {
                order.add("context");
                contextSnapshot.set(snapshot);
                return snapshot;
            }

            @Override
            public Optional<AgentContextSnapshot> findById(String snapshotId) {
                return Optional.ofNullable(contextSnapshot.get());
            }
        };
        ExecutionConfigSnapshotStore configStore = new ExecutionConfigSnapshotStore() {
            @Override
            public ExecutionConfigSnapshot persistIfAbsent(ExecutionConfigSnapshot snapshot) {
                order.add("config");
                configSnapshot.set(snapshot);
                return snapshot;
            }

            @Override
            public Optional<ExecutionConfigSnapshot> findById(String snapshotId) {
                return Optional.ofNullable(configSnapshot.get());
            }
        };
        ResolvedModelConfig model = new ResolvedModelConfig(
                new ModelIdentity("ollama", "mindbridge"),
                new BigDecimal("0.35"),
                512,
                new ModelTimeoutPolicy(Duration.ofMinutes(2), Duration.ofSeconds(30))
        );
        SnapshottingAgentExecutionCoordinator snapshotting =
                new SnapshottingAgentExecutionCoordinator(
                        new ResolvedExecutionConfigResolver(model),
                        new ExecutionConfigSnapshotFactory(new ObjectMapper()),
                        configStore,
                        request -> {
                            order.add("execution");
                            executed.set(request);
                            return completed();
                        }
                );
        ContextAssemblingAgentExecutionCoordinator context =
                new ContextAssemblingAgentExecutionCoordinator(
                        assembler(List.of(new RequestMessageContextSource())),
                        contextStore,
                        snapshotting::execute
                );

        context.execute(request());

        assertEquals(List.of("context", "config", "execution"), order);
        assertEquals(contextSnapshot.get().snapshotId(), executed.get().contextSnapshotId());
        assertEquals(configSnapshot.get().snapshotId(),
                executed.get().runtimeConfigSnapshotId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void downstreamExecutionReceivesTheFrozenSnapshotMessages() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("level", 1);
        AgentMessage message = AgentMessage.assistantToolCalls(List.of(new ToolCall(
                "call-frozen",
                "example_tool",
                Map.of("options", nested)
        )));
        AtomicReference<AgentExecutionRequest> delegated = new AtomicReference<>();
        AgentContextSnapshotStore store = new AgentContextSnapshotStore() {
            @Override
            public AgentContextSnapshot persistIfAbsent(AgentContextSnapshot snapshot) {
                nested.put("level", 2);
                return snapshot;
            }

            @Override
            public Optional<AgentContextSnapshot> findById(String snapshotId) {
                return Optional.empty();
            }
        };
        ContextAssemblingAgentExecutionCoordinator coordinator =
                new ContextAssemblingAgentExecutionCoordinator(
                        assembler(List.of(sourceReturning(List.of(message)))),
                        store,
                        request -> {
                            delegated.set(request);
                            return completed();
                        }
                );

        coordinator.execute(request());

        Map<String, Object> frozenArguments = delegated.get().runSpec().messages().get(0)
                .toolCalls().get(0).arguments();
        Map<String, Object> frozenNested =
                (Map<String, Object>) frozenArguments.get("options");
        assertEquals(1, frozenNested.get("level"));
        assertThrows(UnsupportedOperationException.class,
                () -> frozenNested.put("level", 3));
    }

    @Test
    void assemblyFailurePreventsAdmissionRunStartAndModelInvocation() {
        AtomicInteger snapshotWrites = new AtomicInteger();
        AtomicInteger admissions = new AtomicInteger();
        AtomicInteger runStarts = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        ContextSource failing = new ContextSource() {
            @Override public String sourceId() { return "failed-source"; }
            @Override public String sourceVersion() { return "1"; }
            @Override public int order() { return 0; }
            @Override public ContextContribution load(ContextAssemblyInput input) {
                throw new IllegalStateException("unavailable");
            }
        };
        ContextAssemblingAgentExecutionCoordinator coordinator = new
                ContextAssemblingAgentExecutionCoordinator(
                assembler(List.of(failing)),
                new AgentContextSnapshotStore() {
                    @Override
                    public AgentContextSnapshot persistIfAbsent(AgentContextSnapshot snapshot) {
                        snapshotWrites.incrementAndGet();
                        return snapshot;
                    }

                    @Override
                    public Optional<AgentContextSnapshot> findById(String snapshotId) {
                        return Optional.empty();
                    }
                },
                request -> {
                    admissions.incrementAndGet();
                    runStarts.incrementAndGet();
                    modelCalls.incrementAndGet();
                    return completed();
                }
        );

        assertThrows(ContextAssemblyException.class, () -> coordinator.execute(request()));
        assertEquals(0, snapshotWrites.get());
        assertEquals(0, admissions.get());
        assertEquals(0, runStarts.get());
        assertEquals(0, modelCalls.get());
    }

    private AgentContextAssembler assembler(List<? extends ContextSource> sources) {
        return new AgentContextAssembler(
                sources,
                new AgentContextSnapshotFactory(new ObjectMapper()),
                Clock.fixed(Instant.parse("2026-09-30T02:00:00Z"), ZoneOffset.UTC)
        );
    }

    private ContextSource sourceReturning(List<AgentMessage> messages) {
        return new ContextSource() {
            @Override public String sourceId() { return "frozen-source"; }
            @Override public String sourceVersion() { return "1"; }
            @Override public int order() { return 0; }
            @Override public ContextContribution load(ContextAssemblyInput input) {
                return new ContextContribution(messages);
            }
        };
    }

    private AgentExecutionRequest request() {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "context-run",
                        "context-session",
                        List.of(AgentMessage.user("hello")),
                        3,
                        Set.of("knowledge_search"),
                        Set.of(),
                        ExecutionBudget.unlimited()
                ),
                "context-request",
                42L
        );
    }

    private AgentRunResult completed() {
        return new AgentRunResult(
                "done", AgentStopReason.COMPLETED, 1, List.of(),
                List.of(AgentMessage.assistant("done")), TokenUsage.ZERO,
                null, null, null
        );
    }
}
