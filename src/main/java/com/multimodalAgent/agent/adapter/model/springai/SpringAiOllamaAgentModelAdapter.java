package com.multimodalAgent.agent.adapter.model.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;

/**
 * Adapter for a local Ollama model accessed through Ollama's OpenAI-compatible endpoint.
 *
 * <p>Spring AI 1.0.0's native {@code OllamaChatModel} discards the provider tool-call ID. The
 * compatible endpoint is therefore required to preserve the ID used by runtime approval, events,
 * trace correlation, and the second-round tool response.</p>
 */
public final class SpringAiOllamaAgentModelAdapter
        extends AbstractSpringAiChatModelAgentAdapter {

    public SpringAiOllamaAgentModelAdapter(ChatModel chatModel, ObjectMapper objectMapper) {
        super(chatModel, objectMapper, "Ollama");
    }
}
