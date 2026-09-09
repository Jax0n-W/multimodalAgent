package com.multimodalAgent.agent.runtime.tool.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentRunner;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentMessageRole;
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
import com.multimodalAgent.agent.tool.builtin.KnowledgeSearchInput;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolPolicyIntegrationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAllowPermittedToolAndCompleteTheAgentLoop() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        ScriptedAgentModel model = new ScriptedAgentModel(
                knowledgeSearchCall(Map.of("query", "Redis Sentinel", "topK", 5)),
                ModelTurn.finalAnswer("Redis Sentinel 支持自动故障转移。")
        );

        AgentRunResult result = runner(model, new DefaultToolPolicyEngine(), tool)
                .run(spec(Set.of("knowledge_search"), Set.of()));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(1, tool.executionCount());
        assertEquals(AgentMessageRole.TOOL, model.requests().get(1).get(2).role());
    }

    @Test
    void shouldDenyRegisteredToolMissingFromAllowedTools() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        ScriptedAgentModel model = new ScriptedAgentModel(
                knowledgeSearchCall(Map.of("query", "Redis Sentinel", "topK", 5))
        );

        AgentRunResult result = runner(model, new DefaultToolPolicyEngine(), tool)
                .run(spec(Set.of(), Set.of()));

        assertEquals(AgentStopReason.POLICY_BLOCKED, result.stopReason());
        assertEquals(ToolPolicyDecisionType.DENY, result.policyDecision().type());
        assertEquals(0, tool.executionCount());
    }

    @Test
    void shouldWaitWhenToolRequiresApproval() {
        CountingAppointmentTool tool = new CountingAppointmentTool();
        ScriptedAgentModel model = new ScriptedAgentModel(appointmentCall());

        AgentRunResult result = runner(model, new DefaultToolPolicyEngine(), tool)
                .run(spec(Set.of("appointment_create"), Set.of()));

        assertEquals(AgentStopReason.WAITING_APPROVAL, result.stopReason());
        assertEquals(ToolPolicyDecisionType.REQUIRE_APPROVAL, result.policyDecision().type());
        assertEquals(0, tool.executionCount());
    }

    @Test
    void shouldExecuteApprovalRequiredToolWhenCallIsApproved() {
        CountingAppointmentTool tool = new CountingAppointmentTool();
        ScriptedAgentModel model = new ScriptedAgentModel(
                appointmentCall(),
                ModelTurn.finalAnswer("预约已创建。")
        );

        AgentRunResult result = runner(model, new DefaultToolPolicyEngine(), tool)
                .run(spec(Set.of("appointment_create"), Set.of("call-1")));

        assertEquals(AgentStopReason.COMPLETED, result.stopReason());
        assertEquals(1, tool.executionCount());
    }

    @Test
    void shouldValidateArgumentsBeforeEvaluatingPolicy() {
        CountingKnowledgeSearchTool tool = new CountingKnowledgeSearchTool();
        CountingPolicyEngine policyEngine = new CountingPolicyEngine();
        ScriptedAgentModel model = new ScriptedAgentModel(
                knowledgeSearchCall(Map.of("query", "Redis Sentinel", "topK", -1))
        );

        AgentRunResult result = runner(model, policyEngine, tool)
                .run(spec(Set.of("knowledge_search"), Set.of()));

        assertEquals(AgentStopReason.TOOL_ERROR, result.stopReason());
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, result.toolErrorCode());
        assertEquals(0, policyEngine.evaluationCount());
        assertEquals(0, tool.executionCount());
    }

    private AgentRunner runner(
            ScriptedAgentModel model,
            ToolPolicyEngine policyEngine,
            AgentTool<?, ?>... tools
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolExecutor executor = new ToolExecutor(
                new ToolRegistry(List.of(tools)),
                new ToolArgumentResolver(objectMapper, VALIDATOR),
                policyEngine,
                objectMapper
        );
        return new AgentRunner(model, executor);
    }

    private AgentRunSpec spec(Set<String> allowedTools, Set<String> approvedToolCallIds) {
        return new AgentRunSpec(
                "run-001",
                "session-001",
                List.of(AgentMessage.user("请处理我的请求")),
                3,
                allowedTools,
                approvedToolCallIds
        );
    }

    private ModelTurn knowledgeSearchCall(Map<String, Object> arguments) {
        return ModelTurn.toolCall(new ToolCall(
                "call-1",
                "knowledge_search",
                arguments
        ));
    }

    private ModelTurn appointmentCall() {
        return ModelTurn.toolCall(new ToolCall(
                "call-1",
                "appointment_create",
                Map.of("subject", "心理咨询")
        ));
    }

    private static final class CountingPolicyEngine implements ToolPolicyEngine {

        private final ToolPolicyEngine delegate = new DefaultToolPolicyEngine();
        private int evaluationCount;

        @Override
        public ToolPolicyDecision evaluate(ToolPolicyRequest request) {
            evaluationCount++;
            return delegate.evaluate(request);
        }

        int evaluationCount() {
            return evaluationCount;
        }
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
            return "Redis Sentinel provides automatic failover.";
        }

        int executionCount() {
            return executionCount;
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
            return "Appointment created for " + input.subject();
        }

        int executionCount() {
            return executionCount;
        }
    }
}
