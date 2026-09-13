package com.multimodalAgent.agent.adapter.model.springai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinition;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiOllamaAgentModelAdapterTest {

    @Test
    void shouldPreserveOllamaCompatibleToolCallIdAndDisableFrameworkExecution() {
        AtomicReference<Prompt> capturedPrompt = new AtomicReference<>();
        ChatModel chatModel = prompt -> {
            capturedPrompt.set(prompt);
            AssistantMessage output = new AssistantMessage("", Map.of(), List.of(
                    new AssistantMessage.ToolCall(
                            "ollama-call-1",
                            "function",
                            "knowledge_search",
                            "{\"query\":\"Redis Sentinel\"}"
                    )
            ));
            return new ChatResponse(List.of(new Generation(
                    output,
                    ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build()
            )));
        };
        SpringAiOllamaAgentModelAdapter adapter = new SpringAiOllamaAgentModelAdapter(
                chatModel,
                new ObjectMapper()
        );

        ModelTurn turn = adapter.generate(new AgentModelRequest(
                List.of(AgentMessage.user("Use the tool")),
                List.of(new ModelToolDefinition(
                        "knowledge_search",
                        "Search knowledge",
                        Map.of(
                                "type", "object",
                                "properties", Map.of("query", Map.of("type", "string")),
                                "required", List.of("query")
                        )
                ))
        ));

        assertEquals(ModelFinishReason.TOOL_CALLS, turn.finishReason());
        assertEquals("ollama-call-1", turn.toolCalls().get(0).id());
        assertEquals("knowledge_search", turn.toolCalls().get(0).name());
        assertEquals("Redis Sentinel", turn.toolCalls().get(0).arguments().get("query"));
        ToolCallingChatOptions options = (ToolCallingChatOptions) capturedPrompt.get().getOptions();
        assertFalse(options.getInternalToolExecutionEnabled());
        assertEquals(1, options.getToolCallbacks().size());
    }

    @Test
    void shouldClassifyTransportFailureAtTheOllamaModelBoundary() {
        ChatModel failingModel = prompt -> {
            throw new IllegalStateException("connection refused");
        };
        SpringAiOllamaAgentModelAdapter adapter = new SpringAiOllamaAgentModelAdapter(
                failingModel,
                new ObjectMapper()
        );

        SpringAiModelAdapterException exception = assertThrows(
                SpringAiModelAdapterException.class,
                () -> adapter.generate(new AgentModelRequest(
                        List.of(AgentMessage.user("answer")),
                        List.of()
                ))
        );

        assertTrue(exception.getMessage().contains("Ollama model invocation failed"));
    }
}
