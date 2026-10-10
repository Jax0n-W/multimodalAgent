package com.multimodalAgent.agent.context.memory;

import com.multimodalAgent.agent.context.ContextAssemblyInput;
import com.multimodalAgent.agent.context.ContextContribution;
import com.multimodalAgent.agent.context.ContextSource;
import com.multimodalAgent.agent.runtime.model.AgentMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** P11.2 context source for verified, completed historical conversation turns. */
public final class ConversationMemorySource implements ContextSource {

    public static final String SOURCE_ID = "conversation-memory";
    public static final String SOURCE_VERSION = "1";
    public static final int ORDER = 0;

    private final ConversationMemoryReader reader;
    private final ConversationMemoryPolicy policy;

    public ConversationMemorySource(
            ConversationMemoryReader reader,
            ConversationMemoryPolicy policy
    ) {
        this.reader = Objects.requireNonNull(reader, "reader must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public String sourceVersion() {
        return SOURCE_VERSION;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ContextContribution load(ContextAssemblyInput input) {
        Objects.requireNonNull(input, "input must not be null");
        List<ConversationTurn> turns = reader.read(new ConversationMemoryQuery(
                input.userId(),
                input.sessionId(),
                input.runId(),
                policy.maxTurns()
        ));
        List<AgentMessage> messages = new ArrayList<>();
        for (ConversationTurn turn : policy.select(turns)) {
            if (!turn.userId().equals(input.userId())
                    || !turn.sessionId().equals(input.sessionId())
                    || turn.runId().equals(input.runId())) {
                throw new IllegalStateException("Conversation memory reader violated query isolation");
            }
            messages.add(AgentMessage.user(turn.userContent()));
            messages.add(AgentMessage.assistant(turn.assistantContent()));
        }
        return new ContextContribution(List.copyOf(messages));
    }
}
