package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiStreamEvent;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.repository.HighRiskNotificationIntentRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.ChatService;
import com.multimodalAgent.agent.service.HighRiskNotificationDispatcher;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.knowledge.KnowledgeIngestionService;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "multimodal-agent.ai.provider=ollama",
        "multimodal-agent.runtime.skills.enabled=false",
        "multimodal-agent.knowledge.use-chroma=false",
        "multimodal-agent.mcp.email.outbox-worker-enabled=false"
})
@Testcontainers(disabledWithoutDocker = true)
class HighRiskRetrievalOrderingMySqlIntegrationTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("high_risk_retrieval_ordering");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired private ChatService chatService;
    @Autowired private UserAccountRepository users;
    @Autowired private PsychologicalReportRepository reports;
    @Autowired private HighRiskNotificationIntentRepository intents;

    @MockBean private AiClient legacyAiClient;
    @MockBean private OpenAiCompatibleStreamingClient runtimeProvider;
    @MockBean private AgenticRagService ragService;
    @MockBean private KnowledgeIngestionService knowledgeIngestionService;
    @MockBean private HighRiskNotificationDispatcher notificationDispatcher;
    @MockBean private AlertNotifier alertNotifier;
    @MockBean private ExcelReportWriter excelReportWriter;

    @Test
    void mysqlCommitIsVisibleBeforeRetrievalAndSurvivesOperationalFailure() {
        String input = "我想自杀，mysql-ordering";
        AtomicBoolean durableBeforeRetrieval = new AtomicBoolean();
        doAnswer(invocation -> {
            var report = reports.findTop100ByOrderByCreatedAtDesc().stream()
                    .filter(value -> input.equals(value.getContent()))
                    .findFirst().orElseThrow();
            durableBeforeRetrieval.set(
                    report.getRiskLevel() == RiskLevel.HIGH
                            && intents.findByReportId(report.getId()).size() == 1
            );
            throw new QueryTimeoutException("knowledge timeout");
        }).when(ragService).retrieve(anyString(), anyList());
        when(legacyAiClient.stream(any())).thenReturn(Flux.just("安全支持"));
        when(runtimeProvider.stream(any())).thenReturn(response("safe runtime support"));
        var student = users.findByUsername("student").orElseThrow();

        List<ChatStreamEvent> events = chatService.streamChat(
                        student.getId(), new ChatRequest(null, input)
                ).map(org.springframework.http.codec.ServerSentEvent::data)
                .collectList().block(Duration.ofSeconds(30));

        assertNotNull(events);
        assertEquals("done", events.get(events.size() - 1).type());
        assertTrue(durableBeforeRetrieval.get());
        var matching = reports.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(value -> input.equals(value.getContent()))
                .toList();
        assertEquals(1, matching.size());
        assertEquals(1, intents.findByReportId(matching.get(0).getId()).size());
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
                        "mysql-unified-runtime-response",
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
