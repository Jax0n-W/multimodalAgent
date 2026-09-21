package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinition;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Objects;

/** Maps one provider-neutral AgentModelRequest to one OpenAI-compatible streaming request. */
final class OpenAiStreamingRequestFactory {

    private final OpenAiCompatibleStreamingOptions options;
    private final ObjectMapper objectMapper;

    OpenAiStreamingRequestFactory(
            OpenAiCompatibleStreamingOptions options,
            ObjectMapper objectMapper
    ) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    OpenAiApi.ChatCompletionRequest create(AgentModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<OpenAiApi.ChatCompletionMessage> messages = request.messages().stream()
                .map(this::message)
                .toList();
        List<OpenAiApi.FunctionTool> tools = request.tools().stream()
                .map(this::tool)
                .toList();

        OpenAiApi.ChatCompletionRequest base = new OpenAiApi.ChatCompletionRequest(
                messages,
                options.model(),
                options.temperature(),
                true
        );
        OpenAiChatOptions.Builder optionBuilder = OpenAiChatOptions.builder()
                .model(options.model())
                .temperature(options.temperature())
                .maxTokens(options.maxTokens())
                .N(1)
                .streamUsage(true);
        if (!tools.isEmpty()) {
            optionBuilder.tools(tools)
                    .toolChoice(OpenAiApi.ChatCompletionRequest.ToolChoiceBuilder.AUTO)
                    .parallelToolCalls(true);
        }
        return ModelOptionsUtils.merge(
                optionBuilder.build(),
                base,
                OpenAiApi.ChatCompletionRequest.class
        );
    }

    private OpenAiApi.ChatCompletionMessage message(AgentMessage message) {
        return switch (message.role()) {
            case SYSTEM -> simpleMessage(
                    message.content(),
                    OpenAiApi.ChatCompletionMessage.Role.SYSTEM
            );
            case USER -> simpleMessage(
                    message.content(),
                    OpenAiApi.ChatCompletionMessage.Role.USER
            );
            case ASSISTANT -> assistantMessage(message);
            case TOOL -> new OpenAiApi.ChatCompletionMessage(
                    message.content(),
                    OpenAiApi.ChatCompletionMessage.Role.TOOL,
                    message.toolName(),
                    message.toolCallId(),
                    null,
                    null,
                    null,
                    null
            );
        };
    }

    private OpenAiApi.ChatCompletionMessage simpleMessage(
            String content,
            OpenAiApi.ChatCompletionMessage.Role role
    ) {
        return new OpenAiApi.ChatCompletionMessage(content, role);
    }

    private OpenAiApi.ChatCompletionMessage assistantMessage(AgentMessage message) {
        List<OpenAiApi.ChatCompletionMessage.ToolCall> calls = message.toolCalls().isEmpty()
                ? null
                : message.toolCalls().stream()
                        .map(call -> new OpenAiApi.ChatCompletionMessage.ToolCall(
                                call.id(),
                                "function",
                                new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                        call.name(),
                                        arguments(call.arguments())
                                )
                        ))
                        .toList();
        return new OpenAiApi.ChatCompletionMessage(
                message.content(),
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                null,
                null,
                calls,
                null,
                null,
                null
        );
    }

    private OpenAiApi.FunctionTool tool(ModelToolDefinition definition) {
        OpenAiApi.FunctionTool.Function function = new OpenAiApi.FunctionTool.Function(
                definition.description(),
                definition.name(),
                definition.inputSchema(),
                null
        );
        return new OpenAiApi.FunctionTool(function);
    }

    private String arguments(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new SpringAiModelAdapterException(
                    "Runtime tool-call arguments could not be serialized",
                    exception
            );
        }
    }
}
