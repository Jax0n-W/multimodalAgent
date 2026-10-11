package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.persistence.repository.ChatRuntimeSessionRepository;
import com.multimodalAgent.agent.repository.HighRiskNotificationIntentRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
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
    @MockBean private OpenAiCompatibleStreamingClient runtimeProvider;
    @MockBean private AlertNotifier alertNotifier;
    @MockBean private ExcelReportWriter excelReportWriter;

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
        verify(runtimeProvider, never()).stream(any());
    }
}
