package com.multimodalAgent.agent.runtime.model;

import java.util.List;

@FunctionalInterface
public interface AgentModel {

    ModelTurn generate(List<AgentMessage> messages);
}
