package com.multimodalAgent.agent.recovery.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.persistence.integration.ExecutionPersistenceException;
import com.multimodalAgent.agent.recovery.RecoveryCheckpoint;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointBoundary;
import com.multimodalAgent.agent.recovery.RecoveryCheckpointStore;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareFailureException;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.model.gateway.ModelIdentity;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryCheckpointRuntimeIntegrationTest {

    private static final ModelIdentity MODEL_IDENTITY = new ModelIdentity("test", "checkpoint-model");

    @Test
    void completedModelOutcomeCreatesExactSafeCheckpointWithBudget() {
        ModelTurn finalTurn = new ModelTurn(
                ModelFinishReason.STOP,
                "answer",
                List.of(),
                new TokenUsage(120, 30)
        );
        Harness harness = harness(new ScriptedAgentModel(finalTurn), List.of());

        AgentRunResult result = execute(harness, "model", Set.of(), Set.of(), budget());

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        RecoveryCheckpoint checkpoint = harness.store().checkpoints().get(0);
        assertEquals(RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME, checkpoint.boundary());
        assertEquals(result.messages(), checkpoint.messages());
        assertEquals(1, checkpoint.budgetUsage().modelCalls());
        assertEquals(150, checkpoint.budgetUsage().totalTokens());
        assertTrue(checkpoint.budgetUsage().cost().isPresent());
        assertEquals(AgentEventType.MODEL_COMPLETED, harness.store().terminalFacts().get(0));
    }

    @Test
    void toolRoundTripCapturesAssistantCallsExactResultToolStateAndIterationBoundary() {
        ToolCall call = new ToolCall("call-1", "knowledge_search", Map.of("query", "redis"));
        Harness harness = harness(
                new ScriptedAgentModel(
                        new ModelTurn(
                                ModelFinishReason.TOOL_CALLS,
                                "",
                                List.of(call),
                                new TokenUsage(20, 5)
                        ),
                        new ModelTurn(
                                ModelFinishReason.STOP,
                                "done",
                                List.of(),
                                new TokenUsage(25, 10)
                        )
                ),
                List.of(new TestTool(false, false))
        );

        AgentRunResult result = execute(
                harness,
                "tool",
                Set.of("knowledge_search"),
                Set.of("approved-historical"),
                budget()
        );

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(List.of(
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME,
                RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME,
                RecoveryCheckpointBoundary.ITERATION_BOUNDARY,
                RecoveryCheckpointBoundary.AFTER_MODEL_OUTCOME
        ), harness.store().checkpoints().stream().map(RecoveryCheckpoint::boundary).toList());
        RecoveryCheckpoint toolCheckpoint = harness.store().checkpoints().get(1);
        assertEquals(call, toolCheckpoint.messages().get(1).toolCalls().get(0));
        assertEquals("{\"value\":\"exact tool result\"}", toolCheckpoint.messages().get(2).content());
        assertEquals(Set.of("call-1"), toolCheckpoint.seenToolCallIds());
        assertEquals(List.of("knowledge_search"), toolCheckpoint.toolsUsed());
        assertEquals(Set.of("approved-historical"), toolCheckpoint.approvedToolCallIds());
        assertEquals(1, toolCheckpoint.budgetUsage().toolCalls());
        assertEquals("snapshot-tool", toolCheckpoint.runtimeConfigSnapshotId());
        assertEquals(List.of(
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_SUCCEEDED,
                AgentEventType.TOOL_SUCCEEDED,
                AgentEventType.MODEL_COMPLETED
        ), harness.store().terminalFacts());
    }

    @Test
    void waitingApprovalCreatesSafeCheckpointWithoutChargingUnstartedTool() {
        ToolCall call = new ToolCall("call-approval", "knowledge_search", Map.of("query", "redis"));
        Harness harness = harness(
                new ScriptedAgentModel(ModelTurn.toolCall(call)),
                List.of(new TestTool(false, true))
        );

        AgentRunResult result = execute(
                harness, "approval", Set.of("knowledge_search"), Set.of(), budget()
        );

        assertEquals(AgentStopReason.WAITING_APPROVAL, result.stopReason());
        RecoveryCheckpoint checkpoint = harness.store().checkpoints().get(1);
        assertEquals(RecoveryCheckpointBoundary.WAITING_APPROVAL, checkpoint.boundary());
        assertEquals(0, checkpoint.budgetUsage().toolCalls());
        assertEquals(result.messages(), checkpoint.messages());
        assertEquals(AgentEventType.RUN_WAITING_APPROVAL,
                harness.store().terminalFacts().get(1));
    }

    @Test
    void startedModelWithoutOutcomeDoesNotCreateCheckpoint() {
        AgentModel failing = request -> {
            throw new IllegalStateException("provider failed");
        };
        Harness harness = harness(failing, List.of());

        AgentRunResult result = execute(harness, "model-started", Set.of(), Set.of(), budget());

        assertEquals(AgentStopReason.MODEL_ERROR, result.stopReason());
        assertTrue(harness.events().events().stream()
                .anyMatch(event -> event.type() == AgentEventType.MODEL_STARTED));
        assertTrue(harness.store().checkpoints().isEmpty());
    }

    @Test
    void failedStartedToolIsCheckpointedOnlyAfterConfirmedFailureAndExactMessageExists() {
        ToolCall call = new ToolCall("call-failed", "knowledge_search", Map.of("query", "redis"));
        Harness harness = harness(
                new ScriptedAgentModel(ModelTurn.toolCall(call)),
                List.of(new TestTool(true, false))
        );

        AgentRunResult result = execute(
                harness, "tool-failed", Set.of("knowledge_search"), Set.of(), budget()
        );

        assertEquals(AgentStopReason.TOOL_ERROR, result.stopReason());
        RecoveryCheckpoint checkpoint = harness.store().checkpoints().get(1);
        assertEquals(RecoveryCheckpointBoundary.AFTER_TOOL_OUTCOME, checkpoint.boundary());
        assertEquals(1, checkpoint.budgetUsage().toolCalls());
        assertEquals(result.messages(), checkpoint.messages());
        assertEquals(AgentEventType.RUN_STOPPED, harness.store().terminalFacts().get(1));
    }

    @Test
    void unknownUsageRemainsUnknownRatherThanZeroCost() {
        Harness harness = harness(
                new ScriptedAgentModel(new ModelTurn(
                        ModelFinishReason.STOP, "answer", List.of(), TokenUsage.UNKNOWN
                )),
                List.of()
        );

        execute(harness, "unknown", Set.of(), Set.of(), budget());

        RecoveryCheckpoint checkpoint = harness.store().checkpoints().get(0);
        assertTrue(checkpoint.budgetUsage().unknownUsageObserved());
        assertFalse(checkpoint.budgetUsage().cost().isPresent());
        assertEquals(0, checkpoint.budgetUsage().totalTokens());
    }

    @Test
    void failedDurableTerminalFactPreventsCheckpointFromAdvancing() {
        AtomicInteger guardCalls = new AtomicInteger();
        Harness harness = harness(
                new ScriptedAgentModel(ModelTurn.finalAnswer("answer")),
                List.of(),
                runId -> {
                    if (guardCalls.incrementAndGet() >= 2) {
                        throw new ExecutionPersistenceException("terminal fact was not durable");
                    }
                }
        );

        assertThrows(
                RuntimeMiddlewareFailureException.class,
                () -> execute(harness, "ordering", Set.of(), Set.of(), budget())
        );
        assertTrue(harness.store().checkpoints().isEmpty());
    }

    private Harness harness(AgentModel model, List<AgentTool<?, ?>> tools) {
        return harness(model, tools, runId -> {
        });
    }

    private Harness harness(
            AgentModel model,
            List<AgentTool<?, ?>> tools,
            Consumer<String> durableHistoryGuard
    ) {
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        InspectingStore store = new InspectingStore(events);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        RecoveryCheckpointRuntimeMiddleware recoveryMiddleware =
                new RecoveryCheckpointRuntimeMiddleware(durableHistoryGuard);
        RuntimeMiddlewareChain chain = new RuntimeMiddlewareChain(List.of(recoveryMiddleware));
        AgentRunner runner = new AgentRunner(
                model,
                toolExecutor,
                TestModelToolDefinitionProjector.INSTANCE,
                events
        );
        AgentExecutionCoordinator core = new AgentExecutionCoordinator(runner, chain);
        RecoveryCheckpointingAgentExecutionCoordinator checkpointing =
                new RecoveryCheckpointingAgentExecutionCoordinator(
                        store,
                        Optional.of(MODEL_IDENTITY),
                        core::execute
                );
        return new Harness(checkpointing, store, events);
    }

    private AgentRunResult execute(
            Harness harness,
            String suffix,
            Set<String> allowedTools,
            Set<String> approvedToolCallIds,
            ExecutionBudget budget
    ) {
        return harness.coordinator().execute(new AgentExecutionRequest(
                new AgentRunSpec(
                        "recovery-" + suffix,
                        "session-" + suffix,
                        List.of(AgentMessage.user("hello")),
                        3,
                        allowedTools,
                        approvedToolCallIds,
                        budget
                ),
                "request-" + suffix,
                42L,
                "snapshot-" + suffix,
                com.multimodalAgent.agent.runtime.extension.CancellationContext.NONE
        ));
    }

    private ExecutionBudget budget() {
        return ExecutionBudget.builder()
                .pricing(new ModelPricing(
                        MODEL_IDENTITY,
                        new BigDecimal("1.00"),
                        new BigDecimal("2.00")
                ))
                .build();
    }

    private record Harness(
            RecoveryCheckpointingAgentExecutionCoordinator coordinator,
            InspectingStore store,
            RecordingAgentEventPublisher events
    ) {
    }

    private static final class InspectingStore implements RecoveryCheckpointStore {

        private final RecordingAgentEventPublisher events;
        private final Map<String, RecoveryCheckpoint> byId = new LinkedHashMap<>();
        private final List<AgentEventType> terminalFacts = new ArrayList<>();

        private InspectingStore(RecordingAgentEventPublisher events) {
            this.events = events;
        }

        @Override
        public void persist(RecoveryCheckpoint checkpoint) {
            AgentEventType lastEvent = events.events().get(events.events().size() - 1).type();
            assertFalse(lastEvent == AgentEventType.MODEL_STARTED);
            assertFalse(lastEvent == AgentEventType.TOOL_STARTED);
            terminalFacts.add(lastEvent);
            RecoveryCheckpoint existing = byId.putIfAbsent(checkpoint.checkpointId(), checkpoint);
            if (existing != null && !existing.equals(checkpoint)) {
                throw new IllegalStateException("conflicting checkpoint");
            }
        }

        @Override
        public Optional<RecoveryCheckpoint> findLatestByRunId(String runId) {
            return checkpoints().stream().filter(checkpoint -> checkpoint.runId().equals(runId))
                    .reduce((first, second) -> second);
        }

        @Override
        public Optional<RecoveryCheckpoint> findByCheckpointId(String checkpointId) {
            return Optional.ofNullable(byId.get(checkpointId));
        }

        @Override
        public List<RecoveryCheckpoint> listByRunId(String runId) {
            return checkpoints().stream().filter(checkpoint -> checkpoint.runId().equals(runId))
                    .toList();
        }

        List<RecoveryCheckpoint> checkpoints() {
            return List.copyOf(byId.values());
        }

        List<AgentEventType> terminalFacts() {
            return List.copyOf(terminalFacts);
        }
    }

    private record ToolInput(@NotBlank String query) {
    }

    private record ToolOutput(String value) {
    }

    private static final class TestTool implements AgentTool<ToolInput, ToolOutput> {

        private final boolean fail;
        private final boolean requiresApproval;

        private TestTool(boolean fail, boolean requiresApproval) {
            this.fail = fail;
            this.requiresApproval = requiresApproval;
        }

        @Override
        public ToolDescriptor<ToolInput> descriptor() {
            return new ToolDescriptor<>(
                    "knowledge_search",
                    "Recovery checkpoint test tool",
                    ToolInput.class,
                    ToolRisk.LOW,
                    true,
                    true,
                    requiresApproval
            );
        }

        @Override
        public ToolOutput execute(ToolInput input) {
            if (fail) {
                throw new IllegalStateException("tool failed");
            }
            return new ToolOutput("exact tool result");
        }
    }
}
