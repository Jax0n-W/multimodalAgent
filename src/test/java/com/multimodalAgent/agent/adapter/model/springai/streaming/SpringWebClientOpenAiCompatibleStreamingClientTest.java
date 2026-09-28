package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelFinishReason;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;
import com.multimodalAgent.agent.runtime.model.gateway.ModelProviderException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringWebClientOpenAiCompatibleStreamingClientTest {

    @Test
    void consumesRawSseFramesWithoutLosingProviderToolCallIndex() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        OpenAiApi.ChatCompletionChunk providerChunk = toolChunk();
        String responseBody = "data: " + objectMapper.writeValueAsString(providerChunk)
                + "\n\ndata: [DONE]\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(
                exchange,
                responseBody,
                requestBody,
                authorization
        ));
        server.start();

        try {
            SpringWebClientOpenAiCompatibleStreamingClient client =
                    new SpringWebClientOpenAiCompatibleStreamingClient(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "test-key",
                            objectMapper
                    );

            List<OpenAiStreamEvent> events = client.stream(request()).collectList()
                    .block(Duration.ofSeconds(5));

            assertEquals(2, events.size());
            OpenAiStreamEvent.Chunk chunk = assertInstanceOf(
                    OpenAiStreamEvent.Chunk.class,
                    events.get(0)
            );
            assertEquals(
                    7,
                    chunk.value().choices().get(0).delta().toolCalls().get(0).index()
            );
            assertEquals(OpenAiStreamEvent.Done.INSTANCE, events.get(1));
            assertEquals("Bearer test-key", authorization.get());
            assertTrue(requestBody.get().contains("\"stream\":true"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void replaysRawToolCallWireFixtureThroughCompleteModelTurnAssembly() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        String responseBody;
        try (var fixture = Objects.requireNonNull(
                getClass().getResourceAsStream(
                        "/fixtures/openai-compatible-tool-call-stream.sse"
                ),
                "tool-call streaming fixture must exist"
        )) {
            responseBody = new String(fixture.readAllBytes(), StandardCharsets.UTF_8);
        }
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicInteger providerCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            providerCalls.incrementAndGet();
            respond(exchange, responseBody, requestBody, authorization);
        });
        server.start();

        try {
            SpringWebClientOpenAiCompatibleStreamingClient client =
                    new SpringWebClientOpenAiCompatibleStreamingClient(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "test-key",
                            objectMapper
                    );
            OpenAiCompatibleStreamingAgentModelAdapter adapter =
                    new OpenAiCompatibleStreamingAgentModelAdapter(
                            client,
                            new OpenAiCompatibleStreamingOptions(
                                    "fixture-provider",
                                    "fixture-model",
                                    0.0,
                                    128
                            ),
                            objectMapper
                    );

            ModelTurn turn = adapter.generate(new AgentModelRequest(
                    List.of(AgentMessage.user("Use the supplied tools")),
                    List.of()
            ));

            assertEquals(1, providerCalls.get());
            assertEquals(ModelFinishReason.TOOL_CALLS, turn.finishReason());
            assertEquals(2, turn.toolCalls().size());
            assertEquals("call-provider-001", turn.toolCalls().get(0).id());
            assertEquals("lookup", turn.toolCalls().get(0).name());
            assertEquals("Redis", turn.toolCalls().get(0).arguments().get("query"));
            assertEquals("call-provider-002", turn.toolCalls().get(1).id());
            assertEquals("summarize", turn.toolCalls().get(1).name());
            assertEquals("Java Agents", turn.toolCalls().get(1).arguments().get("topic"));
            assertTrue(requestBody.get().contains("\"stream\":true"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesMalformedSseJsonAsMalformedResponse() throws Exception {
        ModelProviderException failure = invokeAgainstServer(
                200,
                "text/event-stream",
                "data: {not-json}\n\n"
        );

        assertEquals(ModelFailureKind.MALFORMED_RESPONSE, failure.failureKind());
    }

    @ParameterizedTest
    @CsvSource({
            "429, RATE_LIMITED",
            "500, PROVIDER_UNAVAILABLE",
            "503, PROVIDER_UNAVAILABLE",
            "400, INVALID_REQUEST",
            "401, INVALID_REQUEST",
            "403, INVALID_REQUEST"
    })
    void classifiesHttpStatusWithoutDependingOnErrorMessage(
            int status,
            ModelFailureKind expected
    ) throws Exception {
        ModelProviderException failure = invokeAgainstServer(
                status,
                "application/json",
                "{\"error\":{\"message\":\"arbitrary provider text\"}}"
        );

        assertEquals(expected, failure.failureKind());
    }

    @Test
    void refinesStructuredContextLengthCodeWithoutParsingMessageText() throws Exception {
        ModelProviderException failure = invokeAgainstServer(
                400,
                "application/json",
                "{\"error\":{\"message\":\"request rejected\","
                        + "\"type\":\"invalid_request_error\","
                        + "\"code\":\"context_length_exceeded\"}}"
        );

        assertEquals(ModelFailureKind.CONTEXT_TOO_LARGE, failure.failureKind());
    }

    @Test
    void refinesStructuredContextLengthTypeWithoutParsingMessageText() throws Exception {
        ModelProviderException failure = invokeAgainstServer(
                422,
                "application/json",
                "{\"error\":{\"message\":\"request rejected\","
                        + "\"type\":\"context_window_exceeded\"}}"
        );

        assertEquals(ModelFailureKind.CONTEXT_TOO_LARGE, failure.failureKind());
    }

    @Test
    void contextWordsInFreeTextDoNotInventContextTooLargeClassification() throws Exception {
        ModelProviderException failure = invokeAgainstServer(
                422,
                "application/json",
                "{\"error\":{\"message\":\"context is too long\","
                        + "\"type\":\"invalid_request_error\",\"code\":\"unknown\"}}"
        );

        assertEquals(ModelFailureKind.INVALID_REQUEST, failure.failureKind());
    }

    @Test
    void malformedErrorBodyFallsBackToHttpStatusAndIsNotDisclosed() throws Exception {
        String sensitiveBody = "not-json-with-sensitive-provider-detail";

        ModelProviderException failure = invokeAgainstServer(
                503,
                "application/json",
                sensitiveBody
        );

        assertEquals(ModelFailureKind.PROVIDER_UNAVAILABLE, failure.failureKind());
        assertFalse(failure.getMessage().contains(sensitiveBody));
    }

    @Test
    void mapsOnlyKnownNetworkAvailabilityFailuresToProviderUnavailable() {
        assertTransportFailure(
                new UnknownHostException("provider.invalid"),
                ModelFailureKind.PROVIDER_UNAVAILABLE
        );
        assertTransportFailure(
                new ConnectException("connection refused"),
                ModelFailureKind.PROVIDER_UNAVAILABLE
        );
        assertTransportFailure(
                new SocketException("connection reset"),
                ModelFailureKind.PROVIDER_UNAVAILABLE
        );
        assertTransportFailure(
                PrematureCloseException.TEST_EXCEPTION,
                ModelFailureKind.PROVIDER_UNAVAILABLE
        );
        assertTransportFailure(
                new IllegalStateException("unknown transport failure"),
                ModelFailureKind.PROVIDER_ERROR
        );
    }

    private ModelProviderException invokeAgainstServer(
            int status,
            String contentType,
            String responseBody
    ) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> respond(
                exchange,
                status,
                contentType,
                responseBody
        ));
        server.start();
        try {
            SpringWebClientOpenAiCompatibleStreamingClient client =
                    new SpringWebClientOpenAiCompatibleStreamingClient(
                            "http://127.0.0.1:" + server.getAddress().getPort(),
                            "test-key",
                            new ObjectMapper()
                    );
            return assertThrows(
                    ModelProviderException.class,
                    () -> client.stream(request()).collectList().block(Duration.ofSeconds(5))
            );
        } finally {
            server.stop(0);
        }
    }

    private void assertTransportFailure(Throwable transportFailure, ModelFailureKind expected) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://unused.invalid")
                .exchangeFunction(request -> Mono.error(transportFailure))
                .build();
        SpringWebClientOpenAiCompatibleStreamingClient client =
                new SpringWebClientOpenAiCompatibleStreamingClient(
                        webClient,
                        new ObjectMapper()
                );

        ModelProviderException failure = assertThrows(
                ModelProviderException.class,
                () -> client.stream(request()).collectList().block(Duration.ofSeconds(5))
        );

        assertEquals(expected, failure.failureKind());
    }

    private void respond(
            HttpExchange exchange,
            String responseBody,
            AtomicReference<String> requestBody,
            AtomicReference<String> authorization
    ) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void respond(
            HttpExchange exchange,
            int status,
            String contentType,
            String responseBody
    ) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private OpenAiApi.ChatCompletionRequest request() {
        return new OpenAiApi.ChatCompletionRequest(
                List.of(new OpenAiApi.ChatCompletionMessage(
                        "hello",
                        OpenAiApi.ChatCompletionMessage.Role.USER
                )),
                "test-model",
                0.0,
                true
        );
    }

    private OpenAiApi.ChatCompletionChunk toolChunk() {
        OpenAiApi.ChatCompletionMessage.ToolCall toolCall =
                new OpenAiApi.ChatCompletionMessage.ToolCall(
                        7,
                        "call-7",
                        "function",
                        new OpenAiApi.ChatCompletionMessage.ChatCompletionFunction(
                                "lookup",
                                "{\"query\":\"Redis\"}"
                        )
                );
        OpenAiApi.ChatCompletionMessage delta = new OpenAiApi.ChatCompletionMessage(
                null,
                OpenAiApi.ChatCompletionMessage.Role.ASSISTANT,
                null,
                null,
                List.of(toolCall),
                null,
                null,
                null
        );
        OpenAiApi.ChatCompletionChunk.ChunkChoice choice =
                new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                        OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS,
                        0,
                        delta,
                        null
                );
        return new OpenAiApi.ChatCompletionChunk(
                "response-1",
                List.of(choice),
                1L,
                "test-model",
                null,
                null,
                "chat.completion.chunk",
                null
        );
    }
}
