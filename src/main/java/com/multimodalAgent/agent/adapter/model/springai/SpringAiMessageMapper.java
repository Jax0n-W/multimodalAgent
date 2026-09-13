package com.multimodalAgent.agent.adapter.model.springai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class SpringAiMessageMapper {

    private final ObjectMapper objectMapper;

    SpringAiMessageMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    List<Message> toProviderMessages(List<AgentMessage> messages) {
        return messages.stream().map(this::toProviderMessage).toList();
    }

    private Message toProviderMessage(AgentMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> toAssistantMessage(message);
            case TOOL -> new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse(
                            message.toolCallId(),
                            message.toolName(),
                            message.content()
                    )
            ));
        };
    }

    private AssistantMessage toAssistantMessage(AgentMessage message) {
        if (message.toolCalls().isEmpty()) {
            return new AssistantMessage(message.content());
        }
        List<AssistantMessage.ToolCall> toolCalls = message.toolCalls().stream()
                .map(toolCall -> new AssistantMessage.ToolCall(
                        toolCall.id(),
                        "function",
                        toolCall.name(),
                        serializeArguments(toolCall.arguments())
                ))
                .toList();
        return new AssistantMessage(message.content(), Map.of(), toolCalls);
    }

    private String serializeArguments(Map<String, Object> arguments) {
        try {
            return objectMapper.writeValueAsString(arguments);
        } catch (JsonProcessingException exception) {
            throw new SpringAiModelAdapterException(
                    "Runtime tool-call arguments could not be serialized",
                    exception
            );
        }
    }
}
