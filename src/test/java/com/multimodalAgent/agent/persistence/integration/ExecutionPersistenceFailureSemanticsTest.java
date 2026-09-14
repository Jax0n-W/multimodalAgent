package com.multimodalAgent.agent.persistence.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.RunStoppedEvent;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
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
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPersistenceFailureSemanticsTest {

    @Test
    void preRunAdmissionFailureMustPreventCoreFromStarting() {
        FaultInjectingStore store = FaultInjectingStore.failAdmission();
        CountingModel model = new CountingModel(ModelTurn.finalAnswer("unused"));
        Harness harness = harness(store, model, List.of());

        assertThrows(ExecutionPersistenceException.class, () ->
                harness.coordinator().execute(request("admission", Set.of()))
        );

        assertEquals(0, model.calls());
        assertTrue(harness.events().events().isEmpty());
    }

    @Test
    void modelCompletedPersistenceFailureMustPreserveCompletionAndPreventToolStart() {
        FaultInjectingStore store = FaultInjectingStore.failOn(AgentEventType.MODEL_COMPLETED);
        CountingModel model = new CountingModel(
                ModelTurn.toolCall(call("call-after-model", "safe_tool"))
        );
        CountingTool tool = new CountingTool();
        Harness harness = harness(store, model, List.of(tool));

        assertThrows(ExecutionPersistenceException.class, () ->
                harness.coordinator().execute(request("model-completed", Set.of(tool.name())))
        );

        assertEquals(1, model.calls());
        assertEquals(0, tool.executions());
        assertEvent(harness, AgentEventType.MODEL_COMPLETED);
        assertNoEvent(harness, AgentEventType.MODEL_FAILED);
        assertNoEvent(harness, AgentEventType.TOOL_STARTED);
        assertInfrastructureStop(harness);
    }

    @Test
    void toolSucceededPersistenceFailureMustPreserveSuccessAndStopNextToolAndModel() {
        FaultInjectingStore store = FaultInjectingStore.failOn(AgentEventType.TOOL_SUCCEEDED);
        CountingModel model = new CountingModel(
                ModelTurn.toolCall(
                        call("call-a", "safe_tool"),
                        call("call-b", "safe_tool")
                ),
                ModelTurn.finalAnswer("must not run")
        );
        CountingTool tool = new CountingTool();
        Harness harness = harness(store, model, List.of(tool));

        assertThrows(ExecutionPersistenceException.class, () ->
                harness.coordinator().execute(request("tool-succeeded", Set.of(tool.name())))
        );

        assertEquals(1, model.calls());
        assertEquals(1, tool.executions());
        assertEvent(harness, AgentEventType.TOOL_SUCCEEDED);
        assertNoEvent(harness, AgentEventType.TOOL_FAILED);
        long started = harness.events().events().stream()
                .filter(event -> event.type() == AgentEventType.TOOL_STARTED)
                .count();
        assertEquals(1, started);
        assertInfrastructureStop(harness);
    }

    @Test
    void runCompletedPersistenceFailureMustLeaveRunCompletedAsFinalCoreTruth() {
        FaultInjectingStore store = FaultInjectingStore.failOn(AgentEventType.RUN_COMPLETED);
        CountingModel model = new CountingModel(ModelTurn.finalAnswer("done"));
        Harness harness = harness(store, model, List.of());

        assertThrows(ExecutionPersistenceException.class, () ->
                harness.coordinator().execute(request("run-completed", Set.of()))
        );

        assertEquals(1, model.calls());
        List<AgentEvent> events = harness.events().events();
        assertEquals(AgentEventType.RUN_COMPLETED, events.get(events.size() - 1).type());
        assertNoEvent(harness, AgentEventType.RUN_STOPPED);
    }

    @Test
    void modelStartedPersistenceFailureMustNotPreventCurrentModelInvocation() {
        FaultInjectingStore store = FaultInjectingStore.failOn(AgentEventType.MODEL_STARTED);
        CountingModel model = new CountingModel(ModelTurn.finalAnswer("completed core call"));
        Harness harness = harness(store, model, List.of());

        assertThrows(ExecutionPersistenceException.class, () ->
                harness.coordinator().execute(request("model-started", Set.of()))
        );

        assertEquals(1, model.calls());
        assertEvent(harness, AgentEventType.MODEL_STARTED);
        assertEvent(harness, AgentEventType.MODEL_COMPLETED);
        assertNoEvent(harness, AgentEventType.MODEL_FAILED);
        assertInfrastructureStop(harness);
    }

    @Test
    void toolStartedPersistenceFailureMustLetCurrentToolFinishThenStopFutureWork() {
        FaultInjectingStore store = FaultInjectingStore.failOn(AgentEventType.TOOL_STARTED);
        CountingModel model = new CountingModel(
                ModelTurn.toolCall(
                        call("call-start-a", "safe_tool"),
                        call("call-start-b", "safe_tool")
                ),
                ModelTurn.finalAnswer("must not run")
        );
        CountingTool tool = new CountingTool();
        Harness harness = harness(store, model, List.of(tool));

        assertThrows(ExecutionPersistenceException.class, () ->
                harness.coordinator().execute(request("tool-started", Set.of(tool.name())))
        );

        assertEquals(1, model.calls());
        assertEquals(1, tool.executions());
        assertEvent(harness, AgentEventType.TOOL_STARTED);
        assertEvent(harness, AgentEventType.TOOL_SUCCEEDED);
        assertNoEvent(harness, AgentEventType.TOOL_FAILED);
        assertInfrastructureStop(harness);
    }

    @Test
    void finalProjectionFailureMustRemainVisibleAfterRunCompleted() {
        FaultInjectingStore store = FaultInjectingStore.failFinalization();
        CountingModel model = new CountingModel(ModelTurn.finalAnswer("done"));
        Harness harness = harness(store, model, List.of());

        assertThrows(ExecutionPersistenceException.class, () ->
                harness.coordinator().execute(request("finalization", Set.of()))
        );

        List<AgentEvent> events = harness.events().events();
        assertEquals(AgentEventType.RUN_COMPLETED, events.get(events.size() - 1).type());
        assertNoEvent(harness, AgentEventType.RUN_STOPPED);
    }

    private Harness harness(
            ExecutionHistoryStore store,
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools
    ) {
        ExecutionPersistenceFailureRegistry failures =
                new ExecutionPersistenceFailureRegistry();
        ExecutionPersistenceEventPublisher persistencePublisher =
                new ExecutionPersistenceEventPublisher(store, failures);
        RecordingAgentEventPublisher recordingPublisher = new RecordingAgentEventPublisher();
        AgentEventPublisher publisher = event -> {
            recordingPublisher.publish(event);
            persistencePublisher.publish(event);
        };
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        RuntimeMiddlewareChain middleware = new RuntimeMiddlewareChain(List.of(
                new ExecutionPersistenceBoundaryMiddleware(failures)
        ));
        AgentRunner runner = new AgentRunner(
                model,
                executor,
                TestModelToolDefinitionProjector.INSTANCE,
                publisher
        );
        AgentExecutionCoordinator coreCoordinator =
                new AgentExecutionCoordinator(runner, middleware);
        return new Harness(
                new PersistentAgentExecutionCoordinator(coreCoordinator, store, failures),
                recordingPublisher
        );
    }

    private AgentExecutionRequest request(String suffix, Set<String> allowedTools) {
        return new AgentExecutionRequest(
                new AgentRunSpec(
                        "p6-failure-" + suffix,
                        "p6-failure-session",
                        List.of(AgentMessage.user("test failure boundary")),
                        3,
                        allowedTools,
                        Set.of()
                ),
                "p6-failure-request-" + suffix,
                6002L
        );
    }

    private static ToolCall call(String id, String name) {
        return new ToolCall(id, name, Map.of("query", "Redis Sentinel"));
    }

    private void assertEvent(Harness harness, AgentEventType type) {
        assertTrue(harness.events().events().stream().anyMatch(event -> event.type() == type));
    }

    private void assertNoEvent(Harness harness, AgentEventType type) {
        assertFalse(harness.events().events().stream().anyMatch(event -> event.type() == type));
    }

    private void assertInfrastructureStop(Harness harness) {
        RunStoppedEvent stopped = assertInstanceOf(
                RunStoppedEvent.class,
                harness.events().events().get(harness.events().events().size() - 1)
        );
        assertEquals(AgentStopReason.INTERNAL_ERROR, stopped.stopReason());
        assertTrue(stopped.stopReason() != AgentStopReason.MODEL_ERROR);
        assertTrue(stopped.stopReason() != AgentStopReason.TOOL_ERROR);
    }

    private record Harness(
            PersistentAgentExecutionCoordinator coordinator,
            RecordingAgentEventPublisher events
    ) {
    }

    private record ToolInput(@NotBlank String query) {
    }

    private static final class CountingTool implements AgentTool<ToolInput, String> {

        private int executions;

        @Override
        public ToolDescriptor<ToolInput> descriptor() {
            return new ToolDescriptor<>(
                    "safe_tool",
                    "P6 persistence failure fixture",
                    ToolInput.class,
                    ToolRisk.LOW,
                    true,
                    true,
                    false
            );
        }

        @Override
        public String execute(ToolInput input) {
            executions++;
            return "tool succeeded";
        }

        private int executions() {
            return executions;
        }
    }

    private static final class CountingModel implements AgentModel {

        private final Deque<ModelTurn> turns;
        private int calls;

        private CountingModel(ModelTurn... turns) {
            this.turns = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public ModelTurn generate(AgentModelRequest request) {
            calls++;
            ModelTurn turn = turns.pollFirst();
            if (turn == null) {
                throw new IllegalStateException("No model turn remains");
            }
            return turn;
        }

        private int calls() {
            return calls;
        }
    }

    private static final class FaultInjectingStore implements ExecutionHistoryStore {

        private final AgentEventType failureEvent;
        private final boolean failAdmission;
        private final boolean failFinalization;
        private final List<AgentEvent> recorded = new ArrayList<>();

        private FaultInjectingStore(
                AgentEventType failureEvent,
                boolean failAdmission,
                boolean failFinalization
        ) {
            this.failureEvent = failureEvent;
            this.failAdmission = failAdmission;
            this.failFinalization = failFinalization;
        }

        private static FaultInjectingStore failOn(AgentEventType type) {
            return new FaultInjectingStore(type, false, false);
        }

        private static FaultInjectingStore failAdmission() {
            return new FaultInjectingStore(null, true, false);
        }

        private static FaultInjectingStore failFinalization() {
            return new FaultInjectingStore(null, false, true);
        }

        @Override
        public void admit(AgentExecutionRequest request) {
            if (failAdmission) {
                throw new IllegalStateException("admission write failed");
            }
        }

        @Override
        public void record(AgentEvent event) {
            if (event.type() == failureEvent) {
                throw new IllegalStateException("event write failed: " + event.type());
            }
            recorded.add(event);
        }

        @Override
        public void finalizeRun(String runId, AgentRunResult result) {
            if (failFinalization) {
                throw new IllegalStateException("final projection failed");
            }
        }
    }
}
