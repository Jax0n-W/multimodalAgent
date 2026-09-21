package com.multimodalAgent.agent.adapter.model.springai.streaming;

import org.springframework.ai.openai.api.OpenAiApi;
import reactor.core.publisher.Flux;

/** Provider transport port that preserves raw streaming delta semantics and the final DONE frame. */
@FunctionalInterface
public interface OpenAiCompatibleStreamingClient {

    Flux<OpenAiStreamEvent> stream(OpenAiApi.ChatCompletionRequest request);
}
