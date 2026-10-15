package com.multimodalAgent.agent.runtime;

/** Controls ReAct behavior without changing a persisted run's budget or tool allow-list. */
public record AgentLoopPolicy(
        boolean reserveFinalModelTurn,
        boolean continueAfterToolFailure
) {

    public static AgentLoopPolicy legacy() {
        return new AgentLoopPolicy(false, false);
    }

    public static AgentLoopPolicy autonomous() {
        return new AgentLoopPolicy(true, true);
    }
}
