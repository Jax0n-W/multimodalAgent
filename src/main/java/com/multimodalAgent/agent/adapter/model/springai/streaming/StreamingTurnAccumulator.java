package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.ToolCall;
import org.springframework.ai.openai.api.OpenAiApi;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Accumulates one raw provider stream and constructs exactly one complete ModelTurn. */
final class StreamingTurnAccumulator {

    private final String providerName;
    private final ObjectMapper objectMapper;
    private final StringBuilder text = new StringBuilder();
    private final PartialToolCallAssembler toolCalls = new PartialToolCallAssembler();

    private OpenAiApi.ChatCompletionFinishReason finishReason;
    private TokenUsage usage;
    private boolean usageProvided;
    private boolean done;

    StreamingTurnAccumulator(String providerName, ObjectMapper objectMapper) {
        if (providerName == null || providerName.isBlank()) {
            throw new IllegalArgumentException("providerName must not be blank");
        }
        this.providerName = providerName;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    void accept(OpenAiStreamEvent event, Consumer<String> textDeltaObserver) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(textDeltaObserver, "textDeltaObserver must not be null");
        if (event == OpenAiStreamEvent.Done.INSTANCE) {
            if (done) {
                throw failure("returned more than one DONE frame");
            }
            done = true;
            return;
        }
        if (done) {
            throw failure("returned data after the DONE frame");
        }
        acceptChunk(((OpenAiStreamEvent.Chunk) event).value(), textDeltaObserver);
    }

    ModelTurn complete() {
        if (!done) {
            throw failure("ended before the DONE frame");
        }
        if (finishReason == null) {
            throw failure("ended without a finish reason");
        }
        TokenUsage completedUsage = usageProvided ? usage : TokenUsage.ZERO;
        return switch (finishReason) {
            case STOP -> stopTurn(completedUsage);
            case TOOL_CALL, TOOL_CALLS -> toolCallTurn(completedUsage);
            default -> throw failure("used unsupported finish reason " + finishReason);
        };
    }

    boolean usageProvided() {
        return usageProvided;
    }

    private void acceptChunk(
            OpenAiApi.ChatCompletionChunk chunk,
            Consumer<String> textDeltaObserver
    ) {
        captureUsage(chunk.usage());
        List<OpenAiApi.ChatCompletionChunk.ChunkChoice> choices = chunk.choices();
        if (choices == null) {
            throw failure("returned a chunk with null choices");
        }
        if (choices.isEmpty()) {
            return;
        }
        if (choices.size() != 1) {
            throw failure("returned multiple completion choices");
        }

        OpenAiApi.ChatCompletionChunk.ChunkChoice choice = choices.get(0);
        if (choice == null || choice.index() == null || choice.index() != 0) {
            throw failure("returned an unsupported completion choice index");
        }
        if (finishReason != null) {
            throw failure("returned completion data after a terminal finish reason");
        }

        OpenAiApi.ChatCompletionMessage delta = choice.delta();
        if (delta != null) {
            String content = delta.content();
            if (content != null && !content.isEmpty()) {
                text.append(content);
                textDeltaObserver.accept(content);
            }
            toolCalls.accept(delta.toolCalls());
        }
        if (choice.finishReason() != null) {
            finishReason = choice.finishReason();
        }
    }

    private ModelTurn stopTurn(TokenUsage completedUsage) {
        if (!toolCalls.isEmpty()) {
            throw failure("finished with STOP after emitting tool-call fragments");
        }
        return new ModelTurn(
                ModelFinishReason.STOP,
                text.toString(),
                List.of(),
                completedUsage
        );
    }

    private ModelTurn toolCallTurn(TokenUsage completedUsage) {
        List<ToolCall> completedCalls = toolCalls.assemble(objectMapper, providerName);
        if (completedCalls.isEmpty()) {
            throw failure("finished with TOOL_CALLS without a complete tool call");
        }
        return new ModelTurn(
                ModelFinishReason.TOOL_CALLS,
                text.toString(),
                completedCalls,
                completedUsage
        );
    }

    private void captureUsage(OpenAiApi.Usage providerUsage) {
        if (providerUsage == null) {
            return;
        }
        if (usageProvided) {
            throw failure("returned more than one usage chunk");
        }
        Integer promptTokens = providerUsage.promptTokens();
        Integer completionTokens = providerUsage.completionTokens();
        if (promptTokens == null || completionTokens == null) {
            throw failure("returned incomplete token usage");
        }
        try {
            usage = new TokenUsage(promptTokens.longValue(), completionTokens.longValue());
            usageProvided = true;
        } catch (IllegalArgumentException exception) {
            throw new SpringAiModelAdapterException(
                    providerName + " returned invalid token usage",
                    exception
            );
        }
    }

    private SpringAiModelAdapterException failure(String detail) {
        return new SpringAiModelAdapterException(
                providerName + " streaming response was incomplete or invalid: " + detail
        );
    }
}
