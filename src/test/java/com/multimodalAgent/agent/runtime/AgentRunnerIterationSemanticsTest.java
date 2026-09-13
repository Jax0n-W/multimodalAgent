package com.multimodalAgent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.event.RunStoppedEvent;
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
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.multimodalAgent.agent.runtime.support.TraceAssertions.assertNoEvent;
import static com.multimodalAgent.agent.runtime.support.TraceAssertions.assertNoEventsAfterRunTerminal;
import static com.multimodalAgent.agent.runtime.support.TraceAssertions.assertSingleRunTerminal;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentRunnerIterationSemanticsTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldUseExactlyOneModelInvocationWhenMaximumIsOne() {
        assertMaximumIterationBoundary(1);
    }

    @Test
    void shouldUseExactlyTwoModelInvocationsWhenMaximumIsTwo() {
        assertMaximumIterationBoundary(2);
    }

    @Test
    void shouldUseExactlyThreeModelInvocationsWhenMaximumIsThree() {
        assertMaximumIterationBoundary(3);
    }

    private void assertMaximumIterationBoundary(int maxIterations) {
        RepeatingToolCallModel model = new RepeatingToolCallModel();
        CountingTool tool = new CountingTool();
        RecordingAgentEventPublisher publisher = new RecordingAgentEventPublisher();
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(tool)),
                new ToolArgumentResolver(objectMapper, VALIDATOR),
                new DefaultToolPolicyEngine(),
                objectMapper
        );
        AgentRunner runner = new AgentRunner(
                model,
                executor,
                TestModelToolDefinitionProjector.INSTANCE,
                publisher
        );

        AgentRunResult result = runner.run(new AgentRunSpec(
                "run-max-" + maxIterations,
                "session-max",
                List.of(AgentMessage.user("keep using the tool")),
                maxIterations,
                Set.of("test_tool"),
                Set.of()
        ));

        assertEquals(AgentStopReason.MAX_ITERATIONS, result.stopReason());
        assertEquals(maxIterations, result.iterations());
        assertEquals(maxIterations, model.invocations());
        assertEquals(maxIterations, tool.executions());
        assertEquals(maxIterations, count(publisher.events(), AgentEventType.MODEL_STARTED));
        assertEquals(maxIterations, count(publisher.events(), AgentEventType.MODEL_COMPLETED));
        assertEquals(AgentStopReason.MAX_ITERATIONS,
                ((RunStoppedEvent) publisher.events().get(publisher.events().size() - 1)).stopReason());
        assertNoEvent(publisher.events(), AgentEventType.MODEL_FAILED,
                AgentEventType.TOOL_FAILED, AgentEventType.RUN_COMPLETED);
        assertSingleRunTerminal(publisher.events());
        assertNoEventsAfterRunTerminal(publisher.events());
    }

    private static long count(List<AgentEvent> events, AgentEventType type) {
        return events.stream().filter(event -> event.type() == type).count();
    }

    private static final class RepeatingToolCallModel implements AgentModel {

        private int invocations;

        @Override
        public ModelTurn generate(AgentModelRequest request) {
            invocations++;
            return ModelTurn.toolCall(new ToolCall(
                    "call-" + invocations,
                    "test_tool",
                    Map.of("query", "iteration-" + invocations)
            ));
        }

        private int invocations() {
            return invocations;
        }
    }

    private record TestInput(@NotBlank String query) {
    }

    private static final class CountingTool implements AgentTool<TestInput, String> {

        private int executions;

        @Override
        public ToolDescriptor<TestInput> descriptor() {
            return new ToolDescriptor<>(
                    "test_tool",
                    "Test iteration accounting",
                    TestInput.class,
                    ToolRisk.LOW,
                    true,
                    true,
                    false
            );
        }

        @Override
        public String execute(TestInput input) {
            executions++;
            return input.query();
        }

        private int executions() {
            return executions;
        }
    }
}
