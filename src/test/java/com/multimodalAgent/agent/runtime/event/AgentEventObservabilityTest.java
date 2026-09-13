package com.multimodalAgent.agent.runtime.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolErrorCode;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;
import com.multimodalAgent.agent.runtime.trace.DecisionTrace;
import com.multimodalAgent.agent.runtime.trace.DecisionTraceBuilder;
import com.multimodalAgent.agent.runtime.trace.ToolDecisionTrace;
import com.multimodalAgent.agent.runtime.trace.ToolExecutionOutcome;
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchInput;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.multimodalAgent.agent.runtime.support.TraceAssertions.assertNoEvent;
import static com.multimodalAgent.agent.runtime.support.TraceAssertions.assertNoEventsAfterRunTerminal;
import static com.multimodalAgent.agent.runtime.support.TraceAssertions.assertSingleRunTerminal;

class AgentEventObservabilityTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final DecisionTraceBuilder TRACE_BUILDER = new DecisionTraceBuilder();

    @Test
    void shouldRecordDirectAnswerLifecycleWithStableMetadata() {
        Observation observation = observe(
                new ScriptedAgentModel(ModelTurn.finalAnswer("Redis Sentinel 是高可用组件。")),
                List.of(),
                Set.of(),
                Set.of(),
                3
        );

        assertTypes(observation.events(),
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.RUN_COMPLETED
        );
        assertSequenceAndMetadata(observation.events(), "run-001");
        assertEquals(0, observation.events().get(0).iteration());
        assertEquals(1, observation.events().get(1).iteration());
        assertThrows(UnsupportedOperationException.class, observation.events()::clear);
        assertNoEvent(observation.events(), AgentEventType.RUN_STOPPED,
                AgentEventType.RUN_WAITING_APPROVAL, AgentEventType.TOOL_REQUESTED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());

        DecisionTrace trace = trace(observation);
        assertEquals(1, trace.modelCallCount());
        assertEquals(0, trace.toolCallCount());
        assertEquals(AgentStopReason.COMPLETED, trace.stopReason());
        assertFalse(trace.waitingApproval());
    }

    @Test
    void shouldRecordSuccessfulToolLifecycleAndBuildTrace() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        Observation observation = observe(
                new ScriptedAgentModel(
                        knowledgeCall("call-1", Map.of("query", "Redis Sentinel", "topK", 5)),
                        ModelTurn.finalAnswer("Redis Sentinel 支持自动故障转移。")
                ),
                List.of(tool),
                Set.of("knowledge_search"),
                Set.of(),
                3
        );

        assertTypes(observation.events(),
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED,
                AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.RUN_COMPLETED
        );
        assertSequenceAndMetadata(observation.events(), "run-001");
        assertToolCorrelation(observation.events().subList(3, 8), "call-1", "knowledge_search", 1);
        assertEquals(1, tool.executionCount());

        DecisionTrace trace = trace(observation);
        assertEquals(2, trace.iterations());
        assertEquals(2, trace.modelCallCount());
        assertEquals(1, trace.toolCallCount());
        ToolDecisionTrace decision = trace.orderedToolDecisions().get(0);
        assertEquals("call-1", decision.toolCallId());
        assertEquals("knowledge_search", decision.toolName());
        assertEquals(1, decision.iteration());
        assertEquals(ToolPolicyDecisionType.ALLOW, decision.policyDecision());
        assertEquals(ToolExecutionOutcome.SUCCEEDED, decision.executionOutcome());
        assertNull(decision.errorCode());
    }

    @Test
    void shouldRecordValidationFailureBeforePolicyOrExecution() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        Observation observation = observe(
                new ScriptedAgentModel(
                        knowledgeCall("call-invalid", Map.of("query", "Redis Sentinel", "topK", -1))
                ),
                List.of(tool),
                Set.of("knowledge_search"),
                Set.of(),
                3
        );

        assertTypes(observation.events(),
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_VALIDATION_FAILED,
                AgentEventType.RUN_STOPPED
        );
        assertEquals(0, tool.executionCount());
        assertEquals(AgentStopReason.TOOL_ERROR, observation.result().stopReason());
        assertNoEvent(observation.events(), AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED, AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED, AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());

        ToolDecisionTrace decision = trace(observation).orderedToolDecisions().get(0);
        assertNull(decision.policyDecision());
        assertEquals(ToolExecutionOutcome.VALIDATION_FAILED, decision.executionOutcome());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, decision.errorCode());
    }

    @Test
    void shouldRecordUnknownToolWithoutValidationPolicyOrStart() {
        Observation observation = observe(
                new ScriptedAgentModel(ModelTurn.toolCall(
                        new ToolCall("call-unknown", "unknown_tool", Map.of())
                )),
                List.of(),
                Set.of("unknown_tool"),
                Set.of(),
                3
        );

        assertTypes(observation.events(),
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_FAILED,
                AgentEventType.RUN_STOPPED
        );
        ToolFailedEvent failed = (ToolFailedEvent) observation.events().get(4);
        assertEquals(ToolErrorCode.TOOL_NOT_FOUND, failed.errorCode());
        assertNoEvent(observation.events(), AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED, AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED, AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());

        ToolDecisionTrace decision = trace(observation).orderedToolDecisions().get(0);
        assertNull(decision.policyDecision());
        assertEquals(ToolExecutionOutcome.FAILED, decision.executionOutcome());
        assertEquals(ToolErrorCode.TOOL_NOT_FOUND, decision.errorCode());
    }

    @Test
    void shouldRecordPolicyDenyWithoutStartingTool() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        Observation observation = observe(
                new ScriptedAgentModel(
                        knowledgeCall("call-denied", Map.of("query", "Redis Sentinel", "topK", 5))
                ),
                List.of(tool),
                Set.of(),
                Set.of(),
                3
        );

        assertTypes(observation.events(),
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED,
                AgentEventType.RUN_STOPPED
        );
        assertEquals(0, tool.executionCount());
        assertEquals(AgentStopReason.POLICY_BLOCKED, observation.result().stopReason());
        assertNoEvent(observation.events(), AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED, AgentEventType.TOOL_FAILED,
                AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());

        ToolDecisionTrace decision = trace(observation).orderedToolDecisions().get(0);
        assertEquals(ToolPolicyDecisionType.DENY, decision.policyDecision());
        assertEquals(ToolExecutionOutcome.BLOCKED, decision.executionOutcome());
    }

    @Test
    void shouldRecordWaitingApprovalWithoutToolFailureOrStart() {
        CountingAppointmentTool tool = new CountingAppointmentTool();
        Observation observation = observe(
                new ScriptedAgentModel(ModelTurn.toolCall(new ToolCall(
                        "call-approval",
                        "appointment_create",
                        Map.of("subject", "心理咨询")
                ))),
                List.of(tool),
                Set.of("appointment_create"),
                Set.of(),
                3
        );

        assertTypes(observation.events(),
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED,
                AgentEventType.RUN_WAITING_APPROVAL
        );
        assertEquals(0, tool.executionCount());
        assertNoEvent(observation.events(), AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED, AgentEventType.TOOL_FAILED,
                AgentEventType.RUN_COMPLETED, AgentEventType.RUN_STOPPED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());

        DecisionTrace trace = trace(observation);
        ToolDecisionTrace decision = trace.orderedToolDecisions().get(0);
        assertEquals(ToolPolicyDecisionType.REQUIRE_APPROVAL, decision.policyDecision());
        assertEquals(ToolExecutionOutcome.NOT_STARTED, decision.executionOutcome());
        assertTrue(trace.waitingApproval());
        assertEquals(AgentStopReason.WAITING_APPROVAL, trace.stopReason());
    }

    @Test
    void shouldRecordToolExecutionFailure() {
        Observation observation = observe(
                new ScriptedAgentModel(
                        knowledgeCall("call-failing", Map.of("query", "Redis Sentinel", "topK", 5))
                ),
                List.of(new ThrowingKnowledgeSearchTool()),
                Set.of("knowledge_search"),
                Set.of(),
                3
        );

        assertTypes(observation.events(),
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED,
                AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_FAILED,
                AgentEventType.RUN_STOPPED
        );
        ToolFailedEvent failed = (ToolFailedEvent) observation.events().get(7);
        assertEquals(ToolErrorCode.EXECUTION_FAILED, failed.errorCode());
        assertEquals(AgentStopReason.TOOL_ERROR, observation.result().stopReason());
        assertNoEvent(observation.events(), AgentEventType.TOOL_SUCCEEDED,
                AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        assertEquals(ToolExecutionOutcome.FAILED,
                trace(observation).orderedToolDecisions().get(0).executionOutcome());
    }

    @Test
    void shouldRecordModelFailureAndStopRun() {
        AgentModel failingModel = messages -> {
            throw new IllegalStateException("model unavailable");
        };
        Observation observation = observe(
                failingModel,
                List.of(),
                Set.of(),
                Set.of(),
                3
        );

        assertTypes(observation.events(),
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_FAILED,
                AgentEventType.RUN_STOPPED
        );
        assertEquals(AgentStopReason.MODEL_ERROR, observation.result().stopReason());
        assertNoEvent(observation.events(), AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_STARTED, AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        DecisionTrace trace = trace(observation);
        assertEquals(1, trace.errors().size());
        assertEquals("MODEL_ERROR", trace.errors().get(0).errorCode());
    }

    @Test
    void shouldStopAtMaximumIterationsWithOneTerminalEvent() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        Observation observation = observe(
                new ScriptedAgentModel(
                        knowledgeCall("call-1", Map.of("query", "one", "topK", 1)),
                        knowledgeCall("call-2", Map.of("query", "two", "topK", 1))
                ),
                List.of(tool),
                Set.of("knowledge_search"),
                Set.of(),
                2
        );

        assertEquals(AgentStopReason.MAX_ITERATIONS, observation.result().stopReason());
        assertEquals(AgentEventType.RUN_STOPPED,
                observation.events().get(observation.events().size() - 1).type());
        assertEquals(1, terminalEventCount(observation.events()));
        assertFalse(types(observation.events()).contains(AgentEventType.RUN_COMPLETED));
        assertNoEvent(observation.events(), AgentEventType.MODEL_FAILED,
                AgentEventType.TOOL_FAILED, AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(observation.events());
        assertNoEventsAfterRunTerminal(observation.events());
        assertEquals(AgentStopReason.MAX_ITERATIONS, trace(observation).stopReason());
    }

    @Test
    void shouldRejectToolCallIdReusedAcrossModelIterations() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        Observation observation = observe(
                new ScriptedAgentModel(
                        knowledgeCall("reused-call", Map.of("query", "one", "topK", 1)),
                        knowledgeCall("reused-call", Map.of("query", "two", "topK", 1))
                ),
                List.of(tool),
                Set.of("knowledge_search"),
                Set.of(),
                3
        );

        assertEquals(AgentStopReason.MODEL_ERROR, observation.result().stopReason());
        assertEquals(2, observation.result().iterations());
        assertEquals(1, tool.executionCount());
        assertTypes(observation.events(),
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED,
                AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_FAILED,
                AgentEventType.RUN_STOPPED
        );
        assertEquals(AgentStopReason.MODEL_ERROR, trace(observation).stopReason());
    }

    @Test
    void shouldIsolatePublisherFailureFromBusinessResult() {
        AgentEventPublisher throwingPublisher = event -> {
            throw new IllegalStateException("event sink unavailable");
        };
        AgentRunResult result = runner(
                new ScriptedAgentModel(ModelTurn.finalAnswer("正常答案")),
                List.of(),
                throwingPublisher
        ).run(spec(Set.of(), Set.of(), 3));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals("正常答案", result.finalContent());
        assertEquals(1, result.iterations());
    }

    private Observation observe(
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            Set<String> allowedTools,
            Set<String> approvedToolCallIds,
            int maxIterations
    ) {
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        AgentRunResult result = runner(model, tools, publisher)
                .run(spec(allowedTools, approvedToolCallIds, maxIterations));
        return new Observation(result, publisher.events());
    }

    private AgentRunner runner(
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            AgentEventPublisher publisher
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(objectMapper, VALIDATOR),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        return new AgentRunner(model, executor, publisher);
    }

    private AgentRunSpec spec(
            Set<String> allowedTools,
            Set<String> approvedToolCallIds,
            int maxIterations
    ) {
        return new AgentRunSpec(
                "run-001",
                "session-001",
                List.of(AgentMessage.user("请处理我的请求")),
                maxIterations,
                allowedTools,
                approvedToolCallIds
        );
    }

    private static ModelTurn knowledgeCall(String callId, Map<String, Object> arguments) {
        return ModelTurn.toolCall(new ToolCall(callId, "knowledge_search", arguments));
    }

    private DecisionTrace trace(Observation observation) {
        DecisionTrace trace = TRACE_BUILDER.build(observation.events());
        assertEquals(observation.result().stopReason(), trace.stopReason());
        assertEquals(observation.result().iterations(), trace.iterations());
        assertEquals(observation.events().size(), trace.eventCount());
        return trace;
    }

    private void assertTypes(List<AgentEvent> events, AgentEventType... expectedTypes) {
        assertEquals(List.of(expectedTypes), types(events));
        assertEquals(1, terminalEventCount(events));
    }

    private List<AgentEventType> types(List<AgentEvent> events) {
        return events.stream().map(AgentEvent::type).toList();
    }

    private void assertSequenceAndMetadata(List<AgentEvent> events, String runId) {
        Set<String> eventIds = new HashSet<>();
        for (int index = 0; index < events.size(); index++) {
            AgentEvent event = events.get(index);
            assertEquals(index + 1L, event.sequence());
            assertEquals(runId, event.runId());
            assertTrue(eventIds.add(event.eventId()));
            assertFalse(event.eventId().isBlank());
            assertNotNull(event.occurredAt());
        }
    }

    private void assertToolCorrelation(
            List<AgentEvent> events,
            String toolCallId,
            String toolName,
            int iteration
    ) {
        for (AgentEvent event : events) {
            if (event instanceof ToolRequestedEvent toolEvent) {
                assertEquals(toolCallId, toolEvent.toolCallId());
                assertEquals(toolName, toolEvent.toolName());
            } else if (event instanceof ToolValidatedEvent toolEvent) {
                assertEquals(toolCallId, toolEvent.toolCallId());
                assertEquals(toolName, toolEvent.toolName());
            } else if (event instanceof ToolPolicyEvaluatedEvent toolEvent) {
                assertEquals(toolCallId, toolEvent.toolCallId());
                assertEquals(toolName, toolEvent.toolName());
            } else if (event instanceof ToolStartedEvent toolEvent) {
                assertEquals(toolCallId, toolEvent.toolCallId());
                assertEquals(toolName, toolEvent.toolName());
            } else if (event instanceof ToolSucceededEvent toolEvent) {
                assertEquals(toolCallId, toolEvent.toolCallId());
                assertEquals(toolName, toolEvent.toolName());
            }
            assertEquals(iteration, event.iteration());
        }
    }

    private long terminalEventCount(List<AgentEvent> events) {
        return events.stream()
                .map(AgentEvent::type)
                .filter(type -> type == AgentEventType.RUN_COMPLETED
                        || type == AgentEventType.RUN_STOPPED
                        || type == AgentEventType.RUN_WAITING_APPROVAL)
                .count();
    }

    private record Observation(AgentRunResult result, List<AgentEvent> events) {
    }

    private static final class CountingKnowledgeSearchTool
            implements AgentTool<KnowledgeSearchInput, String> {

        private static final ToolDescriptor<KnowledgeSearchInput> DESCRIPTOR = new ToolDescriptor<>(
                "knowledge_search",
                "Search the test knowledge base",
                KnowledgeSearchInput.class,
                ToolRisk.LOW,
                true,
                true,
                false
        );

        private int executionCount;

        @Override
        public ToolDescriptor<KnowledgeSearchInput> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public String execute(KnowledgeSearchInput input) {
            executionCount++;
            return "result for " + input.query();
        }

        int executionCount() {
            return executionCount;
        }
    }

    private static final class ThrowingKnowledgeSearchTool
            implements AgentTool<KnowledgeSearchInput, String> {

        @Override
        public ToolDescriptor<KnowledgeSearchInput> descriptor() {
            return CountingKnowledgeSearchTool.DESCRIPTOR;
        }

        @Override
        public String execute(KnowledgeSearchInput input) {
            throw new IllegalStateException("tool unavailable");
        }
    }

    private record AppointmentInput(@NotBlank String subject) {
    }

    private static final class CountingAppointmentTool
            implements AgentTool<AppointmentInput, String> {

        private static final ToolDescriptor<AppointmentInput> DESCRIPTOR = new ToolDescriptor<>(
                "appointment_create",
                "Create a counselling appointment",
                AppointmentInput.class,
                ToolRisk.MEDIUM,
                false,
                true,
                true
        );

        private int executionCount;

        @Override
        public ToolDescriptor<AppointmentInput> descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public String execute(AppointmentInput input) {
            executionCount++;
            return "created";
        }

        int executionCount() {
            return executionCount;
        }
    }
}
