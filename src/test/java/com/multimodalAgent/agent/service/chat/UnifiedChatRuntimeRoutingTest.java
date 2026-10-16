package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiStreamEvent;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:single-chat-runtime;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.ai.provider=ollama",
        "multimodal-agent.runtime.skills.enabled=false",
        "multimodal-agent.knowledge.use-chroma=false",
        "multimodal-agent.mcp.email.outbox-worker-enabled=false"
})
@AutoConfigureWebTestClient
class UnifiedChatRuntimeRoutingTest {

    @Autowired private WebTestClient webClient;
    @Autowired private AgentRunRepository runs;

    @MockBean private OpenAiCompatibleStreamingClient runtimeProvider;
    @MockBean private AiClient legacyAiClient;
    @MockBean private AgenticRagService ragService;
    @MockBean private AlertNotifier alertNotifier;
    @MockBean private ExcelReportWriter excelReportWriter;
    private final AtomicReference<OpenAiApi.ChatCompletionRequest> providerRequest =
            new AtomicReference<>();

    @BeforeEach
    void defaults() {
        when(ragService.retrieve(anyString(), anyList())).thenReturn(AgenticRagResult.empty());
        when(runtimeProvider.stream(any())).thenAnswer(invocation -> {
            providerRequest.set(invocation.getArgument(0));
            return response("runtime-answer");
        });
    }

    @Test
    void everyNewChatUsesRuntimeWithoutARoutingFlag() {
        long runCount = runs.count();
        List<ChatStreamEvent> events = post(Map.of("message", "请解释 Java 接口"));

        assertEquals(List.of("meta", "token", "done"),
                events.stream().map(ChatStreamEvent::type).toList());
        assertEquals(runCount + 1, runs.count());
        verify(runtimeProvider).stream(any());
        verify(legacyAiClient, never()).stream(any());
    }

    @Test
    void runtimeSessionRetainsConversationMemoryAcrossTurns() {
        List<ChatStreamEvent> firstTurn = post(Map.of("message", "请解释 Java 接口"));
        assertEquals("done", firstTurn.get(firstTurn.size() - 1).type());
        String sessionId = firstTurn.get(0).sessionId();

        List<ChatStreamEvent> nextTurn = post(Map.of(
                "sessionId", sessionId,
                "message", "继续下一轮"
        ));

        assertEquals("done", nextTurn.get(nextTurn.size() - 1).type());
        assertEquals(sessionId, nextTurn.get(0).sessionId());
        assertTrue(providerRequest.get().messages().stream()
                .map(message -> String.valueOf(message.content()))
                .anyMatch(content -> content.contains("runtime-answer")));
        assertEquals(2, runs.findBySessionIdOrderByCreatedAtDesc(sessionId).size());
        verify(runtimeProvider, times(2)).stream(any());
        verify(legacyAiClient, never()).stream(any());
    }

    private List<ChatStreamEvent> post(Map<String, String> body) {
        List<ChatStreamEvent> events = webClient.post()
                .uri("/api/chat/stream")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .returnResult(ChatStreamEvent.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(20));
        assertNotNull(events);
        return events;
    }

    private Flux<OpenAiStreamEvent> response(String content) {
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
                        "unified-runtime-response",
                        List.of(choice),
                        1L,
                        "test-model",
                        null,
                        null,
                        "chat.completion.chunk",
                        null
                )),
                OpenAiStreamEvent.Done.INSTANCE
        );
    }
}
