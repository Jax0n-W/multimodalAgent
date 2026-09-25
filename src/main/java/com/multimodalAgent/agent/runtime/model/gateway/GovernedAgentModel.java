package com.multimodalAgent.agent.runtime.model.gateway;

import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelTurn;

/** AgentModel variant that receives the Runtime iteration without exposing provider details. */
public interface GovernedAgentModel extends AgentModel {

    ModelTurn generate(AgentModelRequest request, int iteration);

    @Override
    default ModelTurn generate(AgentModelRequest request) {
        return generate(request, 1);
    }
}
