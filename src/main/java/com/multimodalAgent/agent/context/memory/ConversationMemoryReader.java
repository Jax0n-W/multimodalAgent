package com.multimodalAgent.agent.context.memory;

import java.util.List;

/** Framework-neutral port for verified durable conversation history. */
public interface ConversationMemoryReader {

    List<ConversationTurn> read(ConversationMemoryQuery query);
}
