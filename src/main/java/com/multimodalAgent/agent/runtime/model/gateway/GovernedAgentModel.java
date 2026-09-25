package com.multimodalAgent.agent.runtime.model.gateway;

import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelTurn;

import java.util.Optional;

/** AgentModel variant that receives the Runtime iteration without exposing provider details. */
public interface GovernedAgentModel extends AgentModel {

    ModelTurn generate(AgentModelRequest request, int iteration);

    default Optional<ModelIdentity> modelIdentity() {
        return Optional.empty();
    }

    @Override
    default ModelTurn generate(AgentModelRequest request) {
        return generate(request, 1);
    }
}
