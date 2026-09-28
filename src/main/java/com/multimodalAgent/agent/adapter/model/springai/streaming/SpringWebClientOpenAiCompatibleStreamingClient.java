package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;
import com.multimodalAgent.agent.runtime.model.gateway.ModelProviderException;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.client.PrematureCloseException;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    private static final int MAX_ERROR_BODY_BYTES = 16 * 1024;
    private static final Set<String> CONTEXT_LIMIT_CODES = Set.of(
            "context_length_exceeded",
            "context_window_exceeded",
            "max_context_length_exceeded",
            "prompt_too_long"
    );

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

    SpringWebClientOpenAiCompatibleStreamingClient(
            WebClient webClient,
            ObjectMapper objectMapper
    ) {
        this.webClient = Objects.requireNonNull(webClient, "webClient must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
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
                .exchangeToFlux(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToFlux(String.class)
                        : httpFailure(response).flatMapMany(Flux::error))
                .filter(frame -> frame != null && !frame.isBlank())
                .map(this::toEvent)
                .onErrorMap(
                        exception -> !(exception instanceof ModelProviderException),
                        this::transportFailure
                );
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
                    ModelFailureKind.MALFORMED_RESPONSE,
                    "OpenAI-compatible provider returned a malformed streaming frame",
                    exception
            );
        }
    }

    private Mono<ModelProviderException> httpFailure(ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        return readErrorBody(response)
                .defaultIfEmpty("")
                .map(body -> new SpringAiModelAdapterException(
                        classifyHttpFailure(status, body),
                        "OpenAI-compatible provider request failed with HTTP " + status.value()
                ));
    }

    private Mono<String> readErrorBody(ClientResponse response) {
        return DataBufferUtils.join(
                        response.bodyToFlux(DataBuffer.class),
                        MAX_ERROR_BODY_BYTES
                )
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return new String(bytes, StandardCharsets.UTF_8);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .onErrorReturn("");
    }

    private ModelFailureKind classifyHttpFailure(HttpStatusCode status, String body) {
        if (status.value() == 429) {
            return ModelFailureKind.RATE_LIMITED;
        }
        if (status.is5xxServerError()) {
            return ModelFailureKind.PROVIDER_UNAVAILABLE;
        }
        if (status.is4xxClientError()) {
            if (providerError(body).filter(ProviderErrorResponse::contextTooLarge).isPresent()) {
                return ModelFailureKind.CONTEXT_TOO_LARGE;
            }
            return ModelFailureKind.INVALID_REQUEST;
        }
        return ModelFailureKind.PROVIDER_ERROR;
    }

    private Optional<ProviderErrorResponse> providerError(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            if (!error.isObject()) {
                return Optional.empty();
            }
            String code = textual(error.get("code"));
            String type = textual(error.get("type"));
            if (code == null && type == null) {
                return Optional.empty();
            }
            return Optional.of(new ProviderErrorResponse(code, type));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }

    private String textual(JsonNode value) {
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private ModelProviderException transportFailure(Throwable failure) {
        ModelFailureKind kind = isAvailabilityFailure(failure)
                ? ModelFailureKind.PROVIDER_UNAVAILABLE
                : ModelFailureKind.PROVIDER_ERROR;
        return new SpringAiModelAdapterException(
                kind,
                "OpenAI-compatible provider transport failed",
                failure
        );
    }

    private boolean isAvailabilityFailure(Throwable failure) {
        if (AbortedException.isConnectionReset(failure)) {
            return true;
        }
        Throwable current = failure;
        while (current != null) {
            if (current instanceof UnknownHostException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof ClosedChannelException
                    || current instanceof UnresolvedAddressException
                    || current instanceof PrematureCloseException) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private record ProviderErrorResponse(String code, String type) {

        private boolean contextTooLarge() {
            return CONTEXT_LIMIT_CODES.contains(normalize(code))
                    || CONTEXT_LIMIT_CODES.contains(normalize(type));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
