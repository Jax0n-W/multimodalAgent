package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.Objects;

/**
 * Raw SSE client for an OpenAI-compatible {@code /v1/chat/completions} endpoint.
 *
 * <p>Spring AI 1.0.0's high-level OpenAI stream helper merges ToolCall fragments before exposing
 * them and does not retain every provider index. P8.2 reads the same OpenAI-compatible protocol
 * with Spring WebClient and Spring AI DTOs so interleaved ToolCall fragments remain correlatable.</p>
 */
public final class SpringWebClientOpenAiCompatibleStreamingClient
        implements OpenAiCompatibleStreamingClient {

    private static final String DONE = "[DONE]";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public SpringWebClientOpenAiCompatibleStreamingClient(
            String baseUrl,
            String apiKey,
            ObjectMapper objectMapper
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(stripTrailingSlash(baseUrl))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        this.webClient = builder.build();
    }

    @Override
    public Flux<OpenAiStreamEvent> stream(OpenAiApi.ChatCompletionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!Boolean.TRUE.equals(request.stream())) {
            throw new IllegalArgumentException("OpenAI-compatible request must enable streaming");
        }
        return webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .filter(frame -> frame != null && !frame.isBlank())
                .map(this::toEvent);
    }

    private OpenAiStreamEvent toEvent(String frame) {
        if (DONE.equals(frame.trim())) {
            return OpenAiStreamEvent.Done.INSTANCE;
        }
        try {
            return new OpenAiStreamEvent.Chunk(
                    objectMapper.readValue(frame, OpenAiApi.ChatCompletionChunk.class)
            );
        } catch (JsonProcessingException exception) {
            throw new SpringAiModelAdapterException(
                    "OpenAI-compatible provider returned a malformed streaming frame",
                    exception
            );
        }
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
