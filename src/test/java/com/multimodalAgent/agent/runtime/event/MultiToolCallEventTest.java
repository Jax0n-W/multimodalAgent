package com.multimodalAgent.agent.runtime.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;
import com.multimodalAgent.agent.runtime.trace.DecisionTrace;
import com.multimodalAgent.agent.runtime.trace.DecisionTraceBuilder;
import com.multimodalAgent.agent.runtime.trace.ToolDecisionTrace;
import com.multimodalAgent.agent.runtime.trace.ToolExecutionOutcome;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class MultiToolCallEventTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final DecisionTraceBuilder TRACE_BUILDER = new DecisionTraceBuilder();

    @Test
    void shouldPublishAllRequestsBeforeExecutingMultipleTools() {
        CountingTool toolA = new CountingTool("tool_a", false, false);
        CountingTool toolB = new CountingTool("tool_b", false, false);
        Observation observation = observe(
                new ScriptedAgentModel(
                        ModelTurn.toolCall(
                                call("call-a", "tool_a"),
                                call("call-b", "tool_b")
                        ),
                        ModelTurn.finalAnswer("done")
                ),
                List.of(toolA, toolB),
                Set.of("tool_a", "tool_b"),
                Set.of()
        );

        assertEquals(List.of(
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED,
                AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED,
                AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED,
                AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_SUCCEEDED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.RUN_COMPLETED
        ), types(observation.events()));
        assertToolCorrelation(observation.events().subList(3, 5), List.of("call-a", "call-b"), 1);
        assertToolCorrelation(observation.events().subList(5, 9), List.of("call-a"), 1);
        assertToolCorrelation(observation.events().subList(9, 13), List.of("call-b"), 1);
        assertEquals(1, toolA.executionCount());
        assertEquals(1, toolB.executionCount());

        DecisionTrace trace = TRACE_BUILDER.build(observation.events());
        assertEquals(2, trace.toolCallCount());
        assertEquals(2, trace.iterations());
        assertEquals(List.of("call-a", "call-b"), trace.orderedToolDecisions().stream()
                .map(ToolDecisionTrace::toolCallId)
                .toList());
        assertEquals(List.of(ToolExecutionOutcome.SUCCEEDED, ToolExecutionOutcome.SUCCEEDED),
                trace.orderedToolDecisions().stream()
                        .map(ToolDecisionTrace::executionOutcome)
                        .toList());
    }

    @Test
    void shouldLeaveLaterRequestedToolNotStartedWhenFirstToolFails() {
        CountingTool toolA = new CountingTool("tool_a", false, true);
        CountingTool toolB = new CountingTool("tool_b", false, false);
        Observation observation = observe(
                new ScriptedAgentModel(ModelTurn.toolCall(
                        call("call-a", "tool_a"),
                        call("call-b", "tool_b")
                )),
                List.of(toolA, toolB),
                Set.of("tool_a", "tool_b"),
                Set.of()
        );

        assertEquals(List.of(
                AgentEventType.RUN_STARTED,
                AgentEventType.MODEL_STARTED,
                AgentEventType.MODEL_COMPLETED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_REQUESTED,
                AgentEventType.TOOL_VALIDATED,
                AgentEventType.TOOL_POLICY_EVALUATED,
                AgentEventType.TOOL_STARTED,
                AgentEventType.TOOL_FAILED,
                AgentEventType.RUN_STOPPED
        ), types(observation.events()));
        assertEquals(AgentStopReason.TOOL_ERROR, observation.result().stopReason());
        assertEquals(1, toolA.executionCount());
        assertEquals(0, toolB.executionCount());
        assertFalse(observation.events().subList(5, observation.events().size()).stream()
                .anyMatch(event -> "call-b".equals(toolCallId(event))));

        DecisionTrace trace = TRACE_BUILDER.build(observation.events());
        assertEquals(2, trace.toolCallCount());
        assertEquals(ToolExecutionOutcome.FAILED,
                trace.orderedToolDecisions().get(0).executionOutcome());
        assertEquals(ToolExecutionOutcome.NOT_STARTED,
                trace.orderedToolDecisions().get(1).executionOutcome());
    }

    @Test
    void shouldPublishAllowAndExecuteApprovedToolCall() {
        CountingTool approvalTool = new CountingTool("approval_tool", true, false);
        Observation observation = observe(
                new ScriptedAgentModel(
                        ModelTurn.toolCall(call("approved-call", "approval_tool")),
                        ModelTurn.finalAnswer("approved")
                ),
                List.of(approvalTool),
                Set.of("approval_tool"),
                Set.of("approved-call")
        );

        ToolPolicyEvaluatedEvent policyEvent = observation.events().stream()
                .filter(ToolPolicyEvaluatedEvent.class::isInstance)
                .map(ToolPolicyEvaluatedEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(ToolPolicyDecisionType.ALLOW, policyEvent.decision());
        assertFalse(types(observation.events()).contains(AgentEventType.RUN_WAITING_APPROVAL));
        assertEquals(1, approvalTool.executionCount());

        ToolDecisionTrace decision = TRACE_BUILDER.build(observation.events())
                .orderedToolDecisions()
                .get(0);
        assertEquals(ToolPolicyDecisionType.ALLOW, decision.policyDecision());
        assertEquals(ToolExecutionOutcome.SUCCEEDED, decision.executionOutcome());
    }

    private Observation observe(
            ScriptedAgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            Set<String> allowedTools,
            Set<String> approvedToolCallIds
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(objectMapper, VALIDATOR),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        AgentRunResult result = new AgentRunner(model, executor, publisher).run(new AgentRunSpec(
                "run-multi",
                "session-multi",
                List.of(AgentMessage.user("run tools")),
                3,
                allowedTools,
                approvedToolCallIds
        ));
        return new Observation(result, publisher.events());
    }

    private static ToolCall call(String callId, String toolName) {
        return new ToolCall(callId, toolName, Map.of("query", "value"));
    }

    private static List<AgentEventType> types(List<AgentEvent> events) {
        return events.stream().map(AgentEvent::type).toList();
    }

    private static void assertToolCorrelation(
            List<AgentEvent> events,
            List<String> expectedCallIds,
            int iteration
    ) {
        assertEquals(expectedCallIds, events.stream()
                .map(MultiToolCallEventTest::toolCallId)
                .distinct()
                .toList());
        events.forEach(event -> assertEquals(iteration, event.iteration()));
    }

    private static String toolCallId(AgentEvent event) {
        if (event instanceof ToolRequestedEvent toolEvent) {
            return toolEvent.toolCallId();
        }
        if (event instanceof ToolValidatedEvent toolEvent) {
            return toolEvent.toolCallId();
        }
        if (event instanceof ToolPolicyEvaluatedEvent toolEvent) {
            return toolEvent.toolCallId();
        }
        if (event instanceof ToolStartedEvent toolEvent) {
            return toolEvent.toolCallId();
        }
        if (event instanceof ToolSucceededEvent toolEvent) {
            return toolEvent.toolCallId();
        }
        if (event instanceof ToolFailedEvent toolEvent) {
            return toolEvent.toolCallId();
        }
        return null;
    }

    private record Observation(AgentRunResult result, List<AgentEvent> events) {
    }

    private record TestToolInput(@NotBlank String query) {
    }

    private static final class CountingTool implements AgentTool<TestToolInput, String> {

        private final ToolDescriptor<TestToolInput> descriptor;
        private final boolean fail;
        private int executionCount;

        private CountingTool(String name, boolean requiresApproval, boolean fail) {
            this.descriptor = new ToolDescriptor<>(
                    name,
                    "Test tool " + name,
                    TestToolInput.class,
                    requiresApproval ? ToolRisk.MEDIUM : ToolRisk.LOW,
                    !requiresApproval,
                    true,
                    requiresApproval
            );
            this.fail = fail;
        }

        @Override
        public ToolDescriptor<TestToolInput> descriptor() {
            return descriptor;
        }

        @Override
        public String execute(TestToolInput input) {
            executionCount++;
            if (fail) {
                throw new IllegalStateException("tool failed");
            }
            return descriptor.name() + ":" + input.query();
        }

        private int executionCount() {
            return executionCount;
        }
    }
}
