package com.multimodalAgent.agent.adapter.model.springai.streaming;

import org.springframework.ai.openai.api.OpenAiApi;

import java.util.Objects;

/** Raw OpenAI-compatible SSE lifecycle observed by the provider adapter. */
public sealed interface OpenAiStreamEvent
        permits OpenAiStreamEvent.Chunk, OpenAiStreamEvent.Done {

    record Chunk(OpenAiApi.ChatCompletionChunk value) implements OpenAiStreamEvent {

        public Chunk {
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    enum Done implements OpenAiStreamEvent {
        INSTANCE
    }
}
