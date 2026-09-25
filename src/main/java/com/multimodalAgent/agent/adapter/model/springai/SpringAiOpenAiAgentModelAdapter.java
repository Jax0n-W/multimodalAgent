package com.multimodalAgent.agent.adapter.model.springai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelToolDefinition;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Non-streaming OpenAI/Spring AI protocol adapter. Tool callbacks are definitions only: internal
 * Spring AI tool execution is disabled and every callback fails if invoked unexpectedly.
 */
abstract class AbstractSpringAiChatModelAgentAdapter implements AgentModel {

    private static final TypeReference<Map<String, Object>> ARGUMENT_MAP = new TypeReference<>() {
    };

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final SpringAiMessageMapper messageMapper;
    private final String providerName;

    protected AbstractSpringAiChatModelAgentAdapter(
            ChatModel chatModel,
            ObjectMapper objectMapper,
            String providerName
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.messageMapper = new SpringAiMessageMapper(objectMapper);
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("providerName must not be blank");
        }
        this.providerName = providerName;
    }

    @Override
    public ModelTurn generate(AgentModelRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(request.tools().stream().map(this::definitionOnlyCallback).toList())
                .internalToolExecutionEnabled(false)
                .build();
        ChatResponse response;
        try {
            response = chatModel.call(new Prompt(
                    messageMapper.toProviderMessages(request.messages()),
                    options
            ));
        } catch (RuntimeException exception) {
            throw new SpringAiModelAdapterException(providerName + " model invocation failed", exception);
        }
        return toModelTurn(response);
    }

    private ModelTurn toModelTurn(ChatResponse response) {
        if (response == null || response.getResult() == null) {
            throw new SpringAiModelAdapterException(providerName + " returned no generation");
        }
        Generation generation = response.getResult();
        AssistantMessage output = generation.getOutput();
        if (output == null) {
            throw new SpringAiModelAdapterException(providerName + " returned no assistant message");
        }

        String finishReason = finishReason(generation.getMetadata());
        List<AssistantMessage.ToolCall> providerCalls = output.getToolCalls() == null
                ? List.of()
                : output.getToolCalls();
        TokenUsage usage = tokenUsage(response);

        if (!providerCalls.isEmpty()) {
            requireFinishReason(finishReason, "TOOL_CALLS");
            List<ToolCall> runtimeCalls = providerCalls.stream()
                    .map(this::toRuntimeToolCall)
                    .toList();
            return new ModelTurn(
                    ModelFinishReason.TOOL_CALLS,
                    output.getText(),
                    runtimeCalls,
                    usage
            );
        }

        requireFinishReason(finishReason, "STOP");
        return new ModelTurn(
                ModelFinishReason.STOP,
                output.getText(),
                List.of(),
                usage
        );
    }

    private ToolCall toRuntimeToolCall(AssistantMessage.ToolCall providerCall) {
        if (providerCall == null
                || providerCall.id() == null || providerCall.id().isBlank()
                || providerCall.name() == null || providerCall.name().isBlank()) {
            throw new SpringAiModelAdapterException(
                    providerName + " returned an invalid tool call identity"
            );
        }
        if (providerCall.type() != null
                && !providerCall.type().isBlank()
                && !"function".equalsIgnoreCase(providerCall.type())) {
            throw new SpringAiModelAdapterException(
                    "Unsupported " + providerName + " tool call type: " + providerCall.type()
            );
        }
        try {
            Map<String, Object> arguments = objectMapper.readValue(
                    providerCall.arguments(),
                    ARGUMENT_MAP
            );
            return new ToolCall(providerCall.id(), providerCall.name(), arguments);
        } catch (Exception exception) {
            throw new SpringAiModelAdapterException(
                    providerName + " returned malformed JSON arguments for tool: "
                            + providerCall.name(),
                    exception
            );
        }
    }

    private ToolCallback definitionOnlyCallback(ModelToolDefinition definition) {
        ToolDefinition springDefinition;
        try {
            springDefinition = ToolDefinition.builder()
                    .name(definition.name())
                    .description(definition.description())
                    .inputSchema(objectMapper.writeValueAsString(definition.inputSchema()))
                    .build();
        } catch (Exception exception) {
            throw new SpringAiModelAdapterException(
                    "Tool schema could not be serialized: " + definition.name(),
                    exception
            );
        }
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return springDefinition;
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException(
                        "Provider-side tool execution is disabled; ToolExecutor owns execution"
                );
            }
        };
    }

    private String finishReason(ChatGenerationMetadata metadata) {
        String reason = metadata == null ? null : metadata.getFinishReason();
        if (reason == null || reason.isBlank()) {
            throw new SpringAiModelAdapterException(providerName + " returned no finish reason");
        }
        return reason.trim().toUpperCase(Locale.ROOT);
    }

    private void requireFinishReason(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new SpringAiModelAdapterException(
                    "Unsupported " + providerName + " finish reason: " + actual
            );
        }
    }

    private TokenUsage tokenUsage(ChatResponse response) {
        Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (usage == null || usage instanceof EmptyUsage) {
            return TokenUsage.UNKNOWN;
        }
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();
        if (promptTokens == null || completionTokens == null) {
            return TokenUsage.UNKNOWN;
        }
        return new TokenUsage(
                nonNegative(promptTokens),
                nonNegative(completionTokens)
        );
    }

    private long nonNegative(Integer value) {
        if (value < 0) {
            throw new SpringAiModelAdapterException(providerName + " returned negative token usage");
        }
        return value.longValue();
    }
}

public final class SpringAiOpenAiAgentModelAdapter
        extends AbstractSpringAiChatModelAgentAdapter {

    public SpringAiOpenAiAgentModelAdapter(ChatModel chatModel, ObjectMapper objectMapper) {
        super(chatModel, objectMapper, "OpenAI");
    }
}
