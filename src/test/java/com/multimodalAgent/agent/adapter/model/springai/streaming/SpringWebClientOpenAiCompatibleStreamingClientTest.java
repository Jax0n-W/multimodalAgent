package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
