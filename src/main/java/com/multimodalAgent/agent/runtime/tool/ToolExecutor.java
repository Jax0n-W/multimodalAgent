package com.multimodalAgent.agent.runtime.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyContext;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecision;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyDecisionType;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyEngine;
import com.multimodalAgent.agent.runtime.tool.policy.ToolPolicyRequest;

import java.util.Objects;

public final class ToolExecutor {

    private final ToolRegistry toolRegistry;
    private final ToolArgumentResolver argumentResolver;
    private final ToolPolicyEngine policyEngine;
    private final ObjectMapper objectMapper;

    public ToolExecutor( // 工具执行器
            ToolRegistry toolRegistry,
            ToolArgumentResolver argumentResolver,
            ToolPolicyEngine policyEngine,
            ObjectMapper objectMapper
    ) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry must not be null");
        this.argumentResolver = Objects.requireNonNull(argumentResolver, "argumentResolver must not be null");
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null"); // JSON对象映射器
    }

    public ToolResult execute(ToolCall toolCall, ToolPolicyContext policyContext) {
        Objects.requireNonNull(toolCall, "toolCall must not be null");
        Objects.requireNonNull(policyContext, "policyContext must not be null");
        AgentTool<?, ?> tool = toolRegistry.find(toolCall.name()).orElse(null);
        if (tool == null) {
            return ToolResult.failure(
                    ToolErrorCode.TOOL_NOT_FOUND,
                    "Unknown tool: " + toolCall.name()
            );
        }

        try {
            return executeTyped(tool, toolCall, policyContext);
        } catch (ToolValidationException exception) {
            return ToolResult.failure(ToolErrorCode.INVALID_ARGUMENTS, exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.failure(
                    ToolErrorCode.EXECUTION_FAILED,
                    "Tool execution failed: " + tool.name()
            );
        }
    }

    private <I, O> ToolResult executeTyped(
            AgentTool<I, O> tool,
            ToolCall toolCall,
            ToolPolicyContext policyContext
    ) {
        I input = argumentResolver.resolve(toolCall.arguments(), tool.descriptor().inputType());
        ToolPolicyDecision decision;
        try {
            decision = Objects.requireNonNull(
                    policyEngine.evaluate(new ToolPolicyRequest(
                            toolCall,
                            tool.descriptor(),
                            input,
                            policyContext
                    )),
                    "policy engine returned a null decision"
            );
        } catch (RuntimeException exception) {
            return ToolResult.policyBlocked(
                    ToolPolicyDecision.deny("Tool policy evaluation failed")
            );
        }

        if (decision.type() == ToolPolicyDecisionType.DENY) {
            return ToolResult.policyBlocked(decision);
        }
        if (decision.type() == ToolPolicyDecisionType.REQUIRE_APPROVAL) {
            return ToolResult.approvalRequired(decision);
        }

        O output = tool.execute(input);
        return ToolResult.success(serialize(output), decision);
    }

    private String serialize(Object output) {
        if (output == null) {
            return "null";
        }
        if (output instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tool output could not be serialized", exception);
        }
    }
}
