package com.multimodalAgent.agent.runtime.model.gateway;

import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelTurn;

/** Optional provider-neutral capability used when an adapter can enforce streaming timeouts. */
public interface TimeoutAwareAgentModel extends AgentModel {

    ModelTurn generate(AgentModelRequest request, ModelTimeoutPolicy timeoutPolicy);

    @Override
    default ModelTurn generate(AgentModelRequest request) {
        throw new IllegalStateException("A timeout policy is required for this AgentModel");
    }
}
