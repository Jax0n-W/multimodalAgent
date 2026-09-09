package com.multimodalAgent.agent.runtime.tool.policy;

@FunctionalInterface
public interface ToolPolicyEngine {

    ToolPolicyDecision evaluate(ToolPolicyRequest request);
}
