package com.multimodalAgent.agent.runtime.model;

@FunctionalInterface
public interface AgentModel {

    ModelTurn generate(AgentModelRequest request);
}
