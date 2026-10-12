package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.persistence.repository.ChatRuntimeSessionRepository;
import com.multimodalAgent.agent.repository.HighRiskNotificationIntentRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p114-legacy;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.runtime.enabled=true",
        "multimodal-agent.runtime.chat-migration.enabled=false",
        "multimodal-agent.knowledge.use-chroma=false",
        "multimodal-agent.mcp.email.outbox-worker-enabled=false"
})
@AutoConfigureWebTestClient
class ChatMigrationLegacyModeTest {

    @Autowired private WebTestClient webClient;
    @Autowired private ChatRuntimeSessionRepository runtimeSessions;
    @Autowired private PsychologicalReportRepository reports;
    @Autowired private HighRiskNotificationIntentRepository notificationIntents;
    @MockBean private AiClient legacyAiClient;
    @MockBean private AgenticRagService ragService;
    @MockBean private OpenAiCompatibleStreamingClient runtimeProvider;
    @MockBean private AlertNotifier alertNotifier;
    @MockBean private ExcelReportWriter excelReportWriter;

    @BeforeEach
    void defaultRetrieval() {
        when(ragService.retrieve(anyString(), anyList())).thenReturn(AgenticRagResult.empty());
    }

    @Test
    void disabledFlagKeepsNewChatOnLegacyPath() {
        when(legacyAiClient.stream(any())).thenReturn(Flux.just("legacy-answer"));

        List<ChatStreamEvent> events = webClient.post()
                .uri("/api/chat/stream")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue(Map.of("message", "请解释 Java 接口"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ChatStreamEvent.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(20));

        assertNotNull(events);
        assertEquals(List.of("meta", "token", "done"),
                events.stream().map(ChatStreamEvent::type).toList());
        assertEquals(0, runtimeSessions.count());
        verify(legacyAiClient).stream(any());
        verify(runtimeProvider, never()).stream(any());
    }

    @Test
    void legacyHighRiskPathPersistsDurableNotificationIntent() {
        AtomicBoolean durableBeforeRetrieval = new AtomicBoolean();
        doAnswer(invocation -> {
            var report = reports.findTop100ByOrderByCreatedAtDesc().stream()
                    .filter(value -> "我想自杀".equals(value.getContent()))
                    .findFirst().orElseThrow();
            durableBeforeRetrieval.set(
                    notificationIntents.findByReportId(report.getId()).size() == 1
            );
            return AgenticRagResult.empty();
        }).when(ragService).retrieve(anyString(), anyList());
        when(legacyAiClient.complete(any())).thenReturn("{}");
        when(legacyAiClient.stream(any())).thenReturn(Flux.just("请立即联系身边可信任的人"));

        List<ChatStreamEvent> events = webClient.post()
                .uri("/api/chat/stream")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue(Map.of("message", "我想自杀"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ChatStreamEvent.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(20));

        assertNotNull(events);
        assertEquals("done", events.get(events.size() - 1).type());
        var report = reports.findTop100ByOrderByCreatedAtDesc().get(0);
        assertEquals(com.multimodalAgent.agent.domain.RiskLevel.HIGH, report.getRiskLevel());
        assertEquals(1, notificationIntents.findByReportId(report.getId()).size());
        org.junit.jupiter.api.Assertions.assertTrue(durableBeforeRetrieval.get());
        verify(runtimeProvider, never()).stream(any());
    }

    @Test
    void legacyHighRiskOperationalRetrievalFailureKeepsDurableSafetyFacts() {
        String input = "我想自杀，legacy-retrieval-timeout";
        doThrow(new QueryTimeoutException("knowledge timeout"))
                .when(ragService).retrieve(anyString(), anyList());
        when(legacyAiClient.stream(any())).thenReturn(Flux.just("请立即联系身边可信任的人"));

        List<ChatStreamEvent> events = post(input);

        assertEquals("done", events.get(events.size() - 1).type());
        var matching = reports.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(value -> input.equals(value.getContent()))
                .toList();
        assertEquals(1, matching.size());
        assertEquals(RiskLevel.HIGH, matching.get(0).getRiskLevel());
        assertEquals(1, notificationIntents.findByReportId(matching.get(0).getId()).size());
    }

    @Test
    void nonHighOperationalRetrievalFailureDoesNotCreateHighRiskIntent() {
        String input = "最近有点焦虑，ordinary-retrieval-timeout";
        when(legacyAiClient.complete(any())).thenReturn("CONSULT");
        doThrow(new QueryTimeoutException("knowledge timeout"))
                .when(ragService).retrieve(anyString(), anyList());

        List<ChatStreamEvent> events = post(input);

        assertEquals(List.of("error"), events.stream().map(ChatStreamEvent::type).toList());
        var report = reports.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(value -> input.equals(value.getContent()))
                .findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertTrue(report.getRiskLevel() != RiskLevel.HIGH);
        assertEquals(0, notificationIntents.findByReportId(report.getId()).size());
    }

    @Test
    void highRiskSecurityRetrievalFailureFailsClosedAfterSafetyCommit() {
        String input = "我想自杀，legacy-retrieval-denied";
        doThrow(new AccessDeniedException("retrieval denied"))
                .when(ragService).retrieve(anyString(), anyList());

        List<ChatStreamEvent> events = post(input);

        assertEquals(List.of("error"), events.stream().map(ChatStreamEvent::type).toList());
        var report = reports.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(value -> input.equals(value.getContent()))
                .findFirst().orElseThrow();
        assertEquals(RiskLevel.HIGH, report.getRiskLevel());
        assertEquals(1, notificationIntents.findByReportId(report.getId()).size());
        verify(legacyAiClient, never()).stream(any());
    }

    private List<ChatStreamEvent> post(String message) {
        List<ChatStreamEvent> events = webClient.post()
                .uri("/api/chat/stream")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue(Map.of("message", message))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ChatStreamEvent.class)
                .getResponseBody()
                .collectList()
                .block(Duration.ofSeconds(20));
        assertNotNull(events);
        return events;
    }
}
