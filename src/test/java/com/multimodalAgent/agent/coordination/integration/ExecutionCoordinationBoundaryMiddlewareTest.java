package com.multimodalAgent.agent.coordination.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.coordination.RunLease;
import com.multimodalAgent.agent.coordination.RunLeaseFailureKind;
import com.multimodalAgent.agent.coordination.RunLeaseSession;
import com.multimodalAgent.agent.harness.AgentExecutionCoordinator;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.ToolStartedEvent;
import com.multimodalAgent.agent.runtime.event.ToolSucceededEvent;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.support.ScriptedAgentModel;
import com.multimodalAgent.agent.runtime.support.TestModelToolDefinitionProjector;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolArgumentResolver;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutor;
import com.multimodalAgent.agent.runtime.tool.ToolRegistry;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.policy.DefaultToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecision;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyEngine;
import jakarta.validation.Validation;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionCoordinationBoundaryMiddlewareTest {

    @Test
    void activeSessionAllowsModelToProceed() {
        RunLeaseSession session = session();
        AtomicInteger modelCalls = new AtomicInteger();
        AgentModel model = request -> {
            modelCalls.incrementAndGet();
            return ModelTurn.finalAnswer("done");
        };

        Observation observation = execute(model, List.of(), new DefaultToolPolicyEngine(), session);

        assertEquals(AgentStopReason.COMPLETED, observation.result().stopReason());
        assertEquals(1, modelCalls.get());
    }

    @Test
    void lostBeforeModelBlocksInvocationWithoutModelStarted() {
        RunLeaseSession session = session();
        session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);
        AtomicInteger modelCalls = new AtomicInteger();
        AgentModel model = request -> {
            modelCalls.incrementAndGet();
            return ModelTurn.finalAnswer("must not run");
        };

        Observation observation = execute(model, List.of(), new DefaultToolPolicyEngine(), session);

        assertEquals(AgentStopReason.INTERNAL_ERROR, observation.result().stopReason());
        assertEquals(0, modelCalls.get());
        assertFalse(types(observation.events()).contains(AgentEventType.MODEL_STARTED));
        assertFalse(types(observation.events()).contains(AgentEventType.MODEL_FAILED));
    }

    @Test
    void lossDuringModelPreservesModelCompletedAndStopsFutureWork() {
        RunLeaseSession session = session();
        AgentModel model = request -> {
            session.markLost(RunLeaseFailureKind.COORDINATION_UNAVAILABLE);
            return ModelTurn.finalAnswer("truthful model result");
        };

        Observation observation = execute(model, List.of(), new DefaultToolPolicyEngine(), session);

        assertEquals(AgentStopReason.INTERNAL_ERROR, observation.result().stopReason());
        assertTrue(types(observation.events()).contains(AgentEventType.MODEL_STARTED));
        assertTrue(types(observation.events()).contains(AgentEventType.MODEL_COMPLETED));
        assertFalse(types(observation.events()).contains(AgentEventType.MODEL_FAILED));
        assertFalse(types(observation.events()).contains(AgentEventType.RUN_COMPLETED));
    }

    @Test
    void lossAfterPolicyAllowBlocksToolBeforeToolStarted() {
        RunLeaseSession session = session();
        CountingTool tool = new CountingTool("tool-a", session, false);
        ToolPolicyEngine policy = request -> {
            session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);
            return ToolPolicyDecision.allow();
        };

        Observation observation = execute(
                new ScriptedAgentModel(toolTurn("call-a", "tool-a")),
                List.of(tool),
                policy,
                session
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, observation.result().stopReason());
        assertEquals(0, tool.executions.get());
        assertFalse(types(observation.events()).contains(AgentEventType.TOOL_STARTED));
        assertFalse(types(observation.events()).contains(AgentEventType.TOOL_FAILED));
    }

    @Test
    void lossDuringToolPreservesSuccessAndPreventsSecondToolFromStarting() {
        RunLeaseSession session = session();
        CountingTool first = new CountingTool("tool-a", session, true);
        CountingTool second = new CountingTool("tool-b", session, false);
        ModelTurn twoCalls = ModelTurn.toolCall(
                new ToolCall("call-a", "tool-a", Map.of("query", "a")),
                new ToolCall("call-b", "tool-b", Map.of("query", "b"))
        );

        Observation observation = execute(
                new ScriptedAgentModel(twoCalls),
                List.of(first, second),
                new DefaultToolPolicyEngine(),
                session
        );

        assertEquals(AgentStopReason.INTERNAL_ERROR, observation.result().stopReason());
        assertEquals(1, first.executions.get());
        assertEquals(0, second.executions.get());
        assertTrue(observation.events().stream()
                .filter(ToolSucceededEvent.class::isInstance)
                .map(ToolSucceededEvent.class::cast)
                .anyMatch(event -> event.toolCallId().equals("call-a")));
        assertFalse(observation.events().stream()
                .filter(ToolStartedEvent.class::isInstance)
                .map(ToolStartedEvent.class::cast)
                .anyMatch(event -> event.toolCallId().equals("call-b")));
        assertFalse(types(observation.events()).contains(AgentEventType.TOOL_FAILED));
        assertFalse(types(observation.events()).contains(AgentEventType.MODEL_FAILED));
    }

    private Observation execute(
            AgentModel model,
            List<? extends AgentTool<?, ?>> tools,
            ToolPolicyEngine policy,
            RunLeaseSession session
    ) {
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor toolExecutor = new ToolExecutor(
                new ToolRegistry(tools),
                new ToolArgumentResolver(
                        objectMapper,
                        Validation.buildDefaultValidatorFactory().getValidator()
                ),
                policy,
                objectMapper
        );
        AgentExecutionCoordinator coordinator = new AgentExecutionCoordinator(
                new AgentRunner(
                        model,
                        toolExecutor,
                        TestModelToolDefinitionProjector.INSTANCE,
                        publisher
                ),
                new RuntimeMiddlewareChain(List.of(
                        new ExecutionCoordinationBoundaryMiddleware()
                ))
        );
        AgentExecutionRequest request = new AgentExecutionRequest(
                new AgentRunSpec(
                        "run-boundary",
                        "session-boundary",
                        List.of(AgentMessage.user("run")),
                        3,
                        Set.of("tool-a", "tool-b"),
                        Set.of()
                ),
                "request-boundary",
                7L
        ).withRuntimeContextContributor(
                ExecutionCoordinationBoundaryMiddleware.sessionContributor(session)
        );
        AgentRunResult result = coordinator.execute(request);
        return new Observation(result, publisher.events());
    }

    private RunLeaseSession session() {
        return new RunLeaseSession(new RunLease("run-boundary", "token-boundary"));
    }

    private ModelTurn toolTurn(String callId, String toolName) {
        return ModelTurn.toolCall(new ToolCall(
                callId,
                toolName,
                Map.of("query", "value")
        ));
    }

    private List<AgentEventType> types(List<AgentEvent> events) {
        return events.stream().map(AgentEvent::type).toList();
    }

    private record Observation(AgentRunResult result, List<AgentEvent> events) {
    }

    private record ToolInput(@NotBlank String query) {
    }

    private static final class CountingTool implements AgentTool<ToolInput, String> {

        private final ToolDescriptor<ToolInput> descriptor;
        private final RunLeaseSession session;
        private final boolean loseDuringExecution;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(
                String name,
                RunLeaseSession session,
                boolean loseDuringExecution
        ) {
            this.descriptor = new ToolDescriptor<>(
                    name,
                    "Boundary test tool",
                    ToolInput.class,
                    ToolRisk.LOW,
                    true,
                    true,
                    false
            );
            this.session = session;
            this.loseDuringExecution = loseDuringExecution;
        }

        @Override
        public ToolDescriptor<ToolInput> descriptor() {
            return descriptor;
        }

        @Override
        public String execute(ToolInput input) {
            executions.incrementAndGet();
            if (loseDuringExecution) {
                session.markLost(RunLeaseFailureKind.EXPLICIT_LEASE_LOSS);
            }
            return input.query();
        }
    }
}
