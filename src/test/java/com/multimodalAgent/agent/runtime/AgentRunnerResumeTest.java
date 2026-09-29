package com.multimodalAgent.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.budget.BudgetUsage;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.event.AgentEventType;
import com.multimodalAgent.agent.runtime.event.RecordingAgentEventPublisher;
import com.multimodalAgent.agent.runtime.extension.AgentRuntimeContext;
import com.multimodalAgent.agent.runtime.extension.RuntimeMiddlewareChain;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
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
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentRunnerResumeTest {

    @Test
    void executesOnlyPendingToolsBeforeTheNextModelWithoutSecondRunStarted() {
        List<String> executions = new ArrayList<>();
        ToolCall completed = new ToolCall("done", "probe", Map.of("value", "done"));
        ToolCall pending = new ToolCall("pending", "probe", Map.of("value", "pending"));
        List<AgentMessage> messages = List.of(
                AgentMessage.user("start"),
                AgentMessage.assistantToolCalls(List.of(completed, pending)),
                AgentMessage.toolResult("done", "probe", "done")
        );
        ScriptedAgentModel model = new ScriptedAgentModel(ModelTurn.finalAnswer("finished"));
        RecordingAgentEventPublisher events = new RecordingAgentEventPublisher();
        AgentRunner runner = runner(model, executions, events);
        AgentRunSpec spec = new AgentRunSpec(
                "resume-run", "resume-session", messages, 3,
                Set.of("probe"), Set.of(), ExecutionBudget.unlimited()
        );
        AgentResumeState state = new AgentResumeState(
                messages, Set.of("probe"), Set.of("done", "pending"),
                Set.of("done", "pending"), List.of(pending), 1, 4,
                new BudgetUsage(1, 1, 10, 2, 12, Optional.empty(), false)
        );

        AgentRuntimeContext context = AgentRuntimeContext.minimal(
                "resume-run", "resume-session"
        );
        AgentRunResult result = runner.resume(
                spec, state, context,
                RuntimeMiddlewareChain.empty()
        );

        assertEquals(List.of("pending"), executions);
        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(2, result.iterations());
        assertEquals(4, model.requests().get(0).size());
        assertFalse(events.events().stream().anyMatch(event ->
                event.type() == AgentEventType.RUN_STARTED
        ));
        assertEquals(2, context.attributes().get(
                com.multimodalAgent.agent.runtime.budget.BudgetRuntimeAttributes.USAGE
        ).orElseThrow().modelCalls());
        assertEquals(2, context.attributes().get(
                com.multimodalAgent.agent.runtime.budget.BudgetRuntimeAttributes.USAGE
        ).orElseThrow().toolCalls());
    }

    @Test
    void allCompletedToolsContinueDirectlyWithNextModel() {
        List<String> executions = new ArrayList<>();
        List<AgentMessage> messages = List.of(
                AgentMessage.user("start"),
                AgentMessage.assistantToolCalls(List.of(
                        new ToolCall("done", "probe", Map.of("value", "done"))
                )),
                AgentMessage.toolResult("done", "probe", "done")
        );
        ScriptedAgentModel model = new ScriptedAgentModel(ModelTurn.finalAnswer("finished"));
        AgentRunner runner = runner(model, executions, new RecordingAgentEventPublisher());
        AgentRunSpec spec = new AgentRunSpec(
                "resume-run-2", "resume-session", messages, 3,
                Set.of("probe"), Set.of(), ExecutionBudget.unlimited()
        );
        AgentResumeState state = new AgentResumeState(
                messages, Set.of("probe"), Set.of("done"), Set.of("done"), List.of(),
                1, 5, new BudgetUsage(1, 1, 0, 0, 0, Optional.empty(), false)
        );

        runner.resume(spec, state, AgentRuntimeContext.minimal(
                "resume-run-2", "resume-session"
        ), RuntimeMiddlewareChain.empty());

        assertEquals(List.of(), executions);
        assertEquals(1, model.requests().size());
    }

    private AgentRunner runner(
            ScriptedAgentModel model,
            List<String> executions,
            RecordingAgentEventPublisher events
    ) {
        ObjectMapper mapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(new ProbeTool(executions))),
                new ToolArgumentResolver(
                        mapper, Validation.buildDefaultValidatorFactory().getValidator()
                ),
                new DefaultToolPolicyEngine(), mapper
        );
        return new AgentRunner(
                model, executor, TestModelToolDefinitionProjector.INSTANCE, events
        );
    }

    private record Input(String value) { }

    private static final class ProbeTool implements AgentTool<Input, String> {
        private final List<String> executions;
        private ProbeTool(List<String> executions) { this.executions = executions; }
        @Override public ToolDescriptor<Input> descriptor() {
            return new ToolDescriptor<>(
                    "probe", "probe", Input.class, ToolRisk.LOW, true, true, false
            );
        }
        @Override public String execute(Input input) {
            executions.add(input.value());
            return input.value();
        }
    }
}
