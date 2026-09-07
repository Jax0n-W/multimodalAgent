package com.multimodalAgent.agent.runtime.support;

import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.ModelTurn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

public final class ScriptedAgentModel implements AgentModel {

    private final Deque<ModelTurn> turns;
    private final List<List<AgentMessage>> requests = new ArrayList<>();

    public ScriptedAgentModel(ModelTurn... turns) {
        if (turns == null || turns.length == 0) {
            throw new IllegalArgumentException("At least one scripted turn is required");
        }
        this.turns = new ArrayDeque<>(Arrays.asList(turns));
    }

    @Override
    public ModelTurn generate(List<AgentMessage> messages) {
        requests.add(List.copyOf(messages));
        ModelTurn turn = turns.pollFirst();
        if (turn == null) {
            throw new IllegalStateException("No scripted model turn remains");
        }
        return turn;
    }

    public List<List<AgentMessage>> requests() {
        return requests.stream().map(List::copyOf).toList();
    }
}
