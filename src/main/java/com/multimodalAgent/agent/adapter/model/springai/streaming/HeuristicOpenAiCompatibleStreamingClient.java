package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.ai.AiMessage;
import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

/** In-process model transport used by the explicit mock provider while retaining the Runtime. */
public final class HeuristicOpenAiCompatibleStreamingClient
        implements OpenAiCompatibleStreamingClient {

    private final AiClient model;

    public HeuristicOpenAiCompatibleStreamingClient(AiClient model) {
        this.model = Objects.requireNonNull(model, "model must not be null");
    }

    @Override
    public Flux<OpenAiStreamEvent> stream(OpenAiApi.ChatCompletionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String content = model.complete(request.messages().stream()
                .map(this::toAiMessage)
                .toList());
        OpenAiApi.ChatCompletionMessage message = new OpenAiApi.ChatCompletionMessage(
                content,
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
        );
        OpenAiApi.ChatCompletionChunk.ChunkChoice choice =
                new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                        OpenAiApi.ChatCompletionFinishReason.STOP,
                        0,
                        message,
                        null
                );
        return Flux.just(
                new OpenAiStreamEvent.Chunk(new OpenAiApi.ChatCompletionChunk(
                        "local-heuristic-runtime",
                        List.of(choice),
                        1L,
                        request.model(),
                        null,
                        null,
                        "chat.completion.chunk",
                        null
                )),
                OpenAiStreamEvent.Done.INSTANCE
        );
    }

    private AiMessage toAiMessage(OpenAiApi.ChatCompletionMessage message) {
        String content = message.content() == null ? "" : message.content();
        return switch (message.role()) {
            case SYSTEM -> AiMessage.system(content);
            case ASSISTANT -> AiMessage.assistant(content);
            case USER, TOOL -> AiMessage.user(content);
        };
    }
}
