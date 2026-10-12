package com.multimodalAgent.agent.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiCompatibleStreamingClient;
import com.multimodalAgent.agent.adapter.model.springai.streaming.OpenAiStreamEvent;
import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.EmotionLabel;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.repository.AgentContextSnapshotRepository;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.ChatHistoryProjectionRepository;
import com.multimodalAgent.agent.persistence.repository.ChatRuntimeSessionRepository;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.HighRiskNotificationIntentRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.service.ChatService;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.knowledge.KnowledgeRetrievalStatus;
import com.multimodalAgent.agent.service.knowledge.SearchResult;
import com.multimodalAgent.agent.service.mcp.AlertNotifier;
import com.multimodalAgent.agent.service.mcp.ExcelReportWriter;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import com.multimodalAgent.agent.service.multimodal.MultimodalSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:p114-chat;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
        "multimodal-agent.ai.provider=ollama",
        "multimodal-agent.runtime.enabled=true",
        "multimodal-agent.runtime.chat-migration.enabled=true",
        "multimodal-agent.runtime.memory.enabled=true",
        "multimodal-agent.runtime.skills.enabled=true",
        "multimodal-agent.knowledge.use-chroma=false",
        "multimodal-agent.mcp.email.outbox-worker-enabled=false"
})
@AutoConfigureWebTestClient
class ChatMigrationIntegrationTest {

    @Autowired private WebTestClient webClient;
    @Autowired private ChatService chatService;
    @Autowired private AgentRunRepository runs;
    @Autowired private AgentContextSnapshotRepository snapshots;
    @Autowired private ChatRuntimeSessionRepository runtimeSessions;
    @Autowired private ChatHistoryProjectionRepository projections;
    @Autowired private ChatMessageRepository chatMessages;
    @Autowired private PsychologicalReportRepository reports;
    @Autowired private HighRiskNotificationIntentRepository notificationIntents;
    @Autowired private UserAccountRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ChatHistoryProjector projector;
    @Autowired private multimodalAgentProperties properties;

    @MockBean private OpenAiCompatibleStreamingClient provider;
    @MockBean private AiClient legacyAiClient;
    @MockBean private AgenticRagService ragService;
    @MockBean private AlertNotifier alertNotifier;
    @MockBean private ExcelReportWriter excelReportWriter;

    @BeforeEach
    void defaultRetrievalAndRecipients() {
        when(ragService.retrieve(anyString(), anyList())).thenReturn(AgenticRagResult.empty());
        properties.getMcp().getEmail().setRecipients(List.of("counselor@example.com"));
    }

    @Test
    void highRiskSafetyCommitPrecedesSuccessfulRetrieval() {
        String input = "我想自杀，ordering-success";
        AtomicBoolean durableBeforeRetrieval = new AtomicBoolean();
        doAnswer(invocation -> {
            var report = reports.findTop100ByOrderByCreatedAtDesc().stream()
                    .filter(value -> input.equals(value.getContent()))
                    .findFirst().orElseThrow();
            durableBeforeRetrieval.set(
                    report.getRiskLevel() == RiskLevel.HIGH
                            && notificationIntents.findByReportId(report.getId()).size() == 1
            );
            return AgenticRagResult.empty();
        }).when(ragService).retrieve(anyString(), anyList());
        when(provider.stream(any())).thenReturn(response("安全支持"));

        List<ChatStreamEvent> events = postChat(null, input);

        assertEquals("done", events.get(events.size() - 1).type());
        assertTrue(durableBeforeRetrieval.get());
    }

    @Test
    void highRiskOperationalRetrievalFailureKeepsSafetyFactsAndTrustedPolicy() throws Exception {
        String input = "我想自杀，retrieval-timeout";
        doThrow(new QueryTimeoutException("knowledge timeout"))
                .when(ragService).retrieve(anyString(), anyList());
        when(provider.stream(any())).thenReturn(response("请立即联系身边可信任的人"));

        List<ChatStreamEvent> events = postChat(null, input);

        assertEquals("done", events.get(events.size() - 1).type());
        var matching = reports.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(value -> input.equals(value.getContent()))
                .toList();
        assertEquals(1, matching.size());
        assertEquals(RiskLevel.HIGH, matching.get(0).getRiskLevel());
        assertEquals(1, notificationIntents.findByReportId(matching.get(0).getId()).size());
        AgentRunEntity run = runs.findBySessionIdOrderByCreatedAtDesc(
                events.get(0).sessionId()
        ).get(0);
        String context = snapshots.findById(run.getContextSnapshotId()).orElseThrow().getContextJson();
        assertTrue(context.contains("Safety level: HIGH"));
        verify(ragService, times(1)).retrieve(anyString(), anyList());
    }

    @Test
    void consultEvidenceIsFrozenIntoTheModelContextAsUntrustedData() throws Exception {
        when(ragService.retrieve(anyString(), anyList())).thenReturn(new AgenticRagResult(
                "校园知识检索",
                List.of("考试压力支持"),
                List.of(new SearchResult(
                        42L,
                        "academic.md",
                        "把复习任务拆成小步骤，并向学习支持中心求助。",
                        1.0
                )),
                "证据充分",
                true,
                KnowledgeRetrievalStatus.HYBRID
        ));
        when(provider.stream(any())).thenReturn(response("可以先拆分复习任务"));

        List<ChatStreamEvent> events = postChat(null, "最近考试压力很大，怎么安排复习？");

        assertEquals("done", events.get(events.size() - 1).type());
        AgentRunEntity run = runs.findBySessionIdOrderByCreatedAtDesc(
                events.get(0).sessionId()
        ).get(0);
        String context = snapshots.findById(run.getContextSnapshotId())
                .orElseThrow().getContextJson();
        assertTrue(context.contains("UNTRUSTED_EVIDENCE_DATA_V1"));
        assertTrue(context.contains("academic.md"));
        assertTrue(context.contains("把复习任务拆成小步骤"));
        assertTrue(context.contains("HYBRID"));
    }

    @Test
    void multimodalHighRiskCannotBeDowngradedByRetrievalFailure() {
        doThrow(new QueryTimeoutException("knowledge timeout"))
                .when(ragService).retrieve(anyString(), anyList());
        when(provider.stream(any())).thenReturn(response("多模态安全支持"));
        UserAccount student = users.findByUsername("student").orElseThrow();
        MultimodalAnalysis highRisk = analysis(
                "audio", EmotionLabel.HIGH_RISK, RiskLevel.HIGH, "urgent safety signal"
        );

        List<ChatStreamEvent> events = chatService.streamMultimodal(
                        student.getId(), new ChatRequest(null, "请分析我的状态"), highRisk
                ).map(org.springframework.http.codec.ServerSentEvent::data)
                .collectList().block(Duration.ofSeconds(20));

        assertNotNull(events);
        assertEquals("done", events.get(events.size() - 1).type());
        var report = reports.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(value -> value.getSession().getPublicId().equals(events.get(0).sessionId()))
                .findFirst().orElseThrow();
        assertEquals(RiskLevel.HIGH, report.getRiskLevel());
        assertEquals(1, notificationIntents.findByReportId(report.getId()).size());
    }

    @Test
    void multimodalHighRiskSuppressesMatchingOrdinarySkillByTrustedDecision() {
        when(provider.stream(any())).thenReturn(response("多模态安全支持"));
        UserAccount student = users.findByUsername("student").orElseThrow();
        MultimodalAnalysis highRisk = analysis(
                "audio", EmotionLabel.HIGH_RISK, RiskLevel.HIGH, "urgent safety signal"
        );

        List<ChatStreamEvent> events = chatService.streamMultimodal(
                        student.getId(), new ChatRequest(null, "我最近睡不着"), highRisk
                ).map(org.springframework.http.codec.ServerSentEvent::data)
                .collectList().block(Duration.ofSeconds(20));

        assertNotNull(events);
        assertEquals("done", events.get(events.size() - 1).type());
        AgentRunEntity run = runs.findBySessionIdOrderByCreatedAtDesc(
                events.get(0).sessionId()
        ).get(0);
        String context = snapshots.findById(run.getContextSnapshotId())
                .orElseThrow().getContextJson();
        assertTrue(context.contains("Safety level: HIGH"));
        assertFalse(context.contains("Trusted Skill:"));
        assertTrue(context.contains("我最近睡不着"));
    }

    @Test
    void hangingRetrievalStartsOnlyAfterSafetyTransactionCommitted() throws Exception {
        String input = "我想自杀，retrieval-hang";
        CountDownLatch retrievalEntered = new CountDownLatch(1);
        CountDownLatch releaseRetrieval = new CountDownLatch(1);
        doAnswer(invocation -> {
            retrievalEntered.countDown();
            if (!releaseRetrieval.await(10, TimeUnit.SECONDS)) {
                throw new QueryTimeoutException("test retrieval timeout");
            }
            return AgenticRagResult.empty();
        }).when(ragService).retrieve(anyString(), anyList());
        when(provider.stream(any())).thenReturn(response("安全支持"));
        UserAccount student = users.findByUsername("student").orElseThrow();

        CompletableFuture<List<ChatStreamEvent>> response = chatService.streamChat(
                        student.getId(), new ChatRequest(null, input)
                ).map(org.springframework.http.codec.ServerSentEvent::data)
                .collectList().toFuture();

        assertTrue(retrievalEntered.await(10, TimeUnit.SECONDS));
        var report = reports.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(value -> input.equals(value.getContent()))
                .findFirst().orElseThrow();
        assertEquals(RiskLevel.HIGH, report.getRiskLevel());
        assertEquals(1, notificationIntents.findByReportId(report.getId()).size());
        releaseRetrieval.countDown();
        List<ChatStreamEvent> events = response.get(20, TimeUnit.SECONDS);
        assertEquals("done", events.get(events.size() - 1).type());
    }

    @Test
    void safetyTransactionFailurePreventsRetrieval() {
        long reportCount = reports.count();
        long intentCount = notificationIntents.count();
        properties.getMcp().getEmail().setRecipients(List.of("x".repeat(241)));

        List<ChatStreamEvent> events;
        try {
            events = postChat(null, "我想自杀，transaction-failure");
        } finally {
            properties.getMcp().getEmail().setRecipients(List.of("counselor@example.com"));
        }

        assertEquals(List.of("error"), types(events));
        assertEquals(reportCount, reports.count());
        assertEquals(intentCount, notificationIntents.count());
        verify(ragService, never()).retrieve(anyString(), anyList());
    }

    @Test
    void studentHttpEntryUsesFullRuntimeAndSecondTurnUsesDurableMemory() throws Exception {
        when(provider.stream(any())).thenReturn(
                response("第一轮", "回答"),
                response("第二轮回答")
        );

        List<ChatStreamEvent> first = postChat(null, "请解释 Java 接口");
        assertEquals(List.of("meta", "token", "token", "done"), types(first));
        String sessionId = first.get(0).sessionId();
        assertTrue(runtimeSessions.existsById(sessionId));

        List<ChatStreamEvent> second = postChat(sessionId, "请继续用 Java 解释");
        assertEquals(List.of("meta", "token", "done"), types(second));
        assertEquals(sessionId, second.get(0).sessionId());

        List<AgentRunEntity> sessionRuns = runs.findBySessionIdOrderByCreatedAtDesc(sessionId);
        assertEquals(2, sessionRuns.size());
        assertTrue(sessionRuns.stream().allMatch(run ->
                run.getStopReason() == AgentStopReason.COMPLETED
                        && run.getContextSnapshotId() != null
                        && run.getRuntimeConfigSnapshotId() != null));
        JsonNode context = objectMapper.readTree(snapshots.findById(
                sessionRuns.get(0).getContextSnapshotId()
        ).orElseThrow().getContextJson());
        assertEquals(List.of(
                "agent-skills", "chat-business-context", "chat-untrusted-evidence",
                "conversation-memory", "request-messages"
        ), java.util.stream.StreamSupport.stream(
                        context.path("contributions").spliterator(), false)
                .map(node -> node.path("sourceId").asText())
                .toList());
        JsonNode memory = java.util.stream.StreamSupport.stream(
                        context.path("contributions").spliterator(), false)
                .filter(node -> "conversation-memory".equals(node.path("sourceId").asText()))
                .findFirst().orElseThrow();
        JsonNode request = java.util.stream.StreamSupport.stream(
                        context.path("contributions").spliterator(), false)
                .filter(node -> "request-messages".equals(node.path("sourceId").asText()))
                .findFirst().orElseThrow();
        assertEquals(2, memory.path("messageCount").asInt());
        assertEquals(1, request.path("messageCount").asInt());

        List<com.multimodalAgent.agent.domain.ChatMessage> history =
                chatMessages.findBySession_PublicIdOrderByCreatedAtAsc(sessionId);
        assertEquals(List.of(
                MessageRole.USER, MessageRole.ASSISTANT,
                MessageRole.USER, MessageRole.ASSISTANT
        ), history.stream().map(value -> value.getRole()).toList());
        assertEquals(2, projections.countBySessionId(sessionId));
        AgentRunEntity newest = sessionRuns.get(0);
        projector.projectCompleted(
                newest.getRunId(), sessionId, newest.getUserId(), newest.getFinalContent()
        );
        assertEquals(4, chatMessages.findBySession_PublicIdOrderByCreatedAtAsc(sessionId).size());
        verify(legacyAiClient, never()).stream(any());

        UserAccount other = createUser("isolated-", "password");
        long before = runs.count();
        List<ChatStreamEvent> rejected = postChat(
                sessionId, "请继续用 Java 解释", other.getUsername(), "password"
        );
        assertEquals(List.of("error"), types(rejected));
        assertEquals(before, runs.count());
    }

    @Test
    void highRiskFactAndEscalationSurviveFinalModelFailure() {
        when(legacyAiClient.complete(any())).thenReturn("{}");
        when(provider.stream(any())).thenReturn(Flux.error(new IllegalStateException("provider down")));

        List<ChatStreamEvent> events = postChat(null, "我想自杀");

        assertEquals(List.of("meta", "error"), types(events));
        String sessionId = events.get(0).sessionId();
        AgentRunEntity run = runs.findBySessionIdOrderByCreatedAtDesc(sessionId).get(0);
        assertEquals(AgentStopReason.MODEL_ERROR, run.getStopReason());
        var report = reports.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(value -> value.getSession().getPublicId().equals(sessionId))
                .findFirst().orElseThrow();
        assertEquals(RiskLevel.HIGH, report.getRiskLevel());
        assertEquals(1, notificationIntents.findByReportId(report.getId()).size());
        assertEquals(List.of(MessageRole.USER),
                chatMessages.findBySession_PublicIdOrderByCreatedAtAsc(sessionId).stream()
                        .map(value -> value.getRole()).toList());
        assertFalse(projections.existsById(run.getRunId()));
    }

    @Test
    void multimodalEvidenceIsSanitizedAndRunsThroughUnifiedRuntime() throws Exception {
        UserAccount student = users.findByUsername("student").orElseThrow();
        when(provider.stream(any())).thenReturn(
                response("音频支持"), response("图像支持"), response("安全支持")
        );
        when(legacyAiClient.complete(any())).thenReturn("{}");

        MultimodalAnalysis audio = analysis(
                "audio", EmotionLabel.ANXIETY, RiskLevel.LOW,
                "Whisper transcript with phone 13800138000"
        );
        MultimodalAnalysis image = analysis(
                "visual", EmotionLabel.NORMAL, RiskLevel.LOW,
                "MediaPipe detected a tense expression"
        );
        MultimodalAnalysis highRisk = analysis(
                "audio", EmotionLabel.HIGH_RISK, RiskLevel.HIGH,
                "urgent safety signal"
        );

        for (MultimodalAnalysis value : List.of(audio, image, highRisk)) {
            List<ChatStreamEvent> events = chatService.streamMultimodal(
                    student.getId(), new ChatRequest(null, "请分析我的状态"), value
            ).map(org.springframework.http.codec.ServerSentEvent::data)
                    .collectList().block(Duration.ofSeconds(20));
            assertNotNull(events);
            assertEquals("done", events.get(events.size() - 1).type());
            String sessionId = events.get(0).sessionId();
            AgentRunEntity run = runs.findBySessionIdOrderByCreatedAtDesc(sessionId).get(0);
            String json = snapshots.findById(run.getContextSnapshotId())
                    .orElseThrow().getContextJson();
            assertTrue(json.contains("UNTRUSTED_EVIDENCE_DATA_V1"));
            assertFalse(json.contains("Trusted Multimodal Context"));
            assertFalse(json.contains("13800138000"));
            assertFalse(json.contains("fusedScore"));
            JsonNode snapshot = objectMapper.readTree(json);
            assertFalse(java.util.stream.StreamSupport.stream(
                            snapshot.path("messages").spliterator(), false)
                    .filter(node -> "SYSTEM".equals(node.path("role").asText()))
                    .anyMatch(node -> node.path("content").asText()
                            .contains("MediaPipe detected")));
        }
        verify(legacyAiClient, never()).stream(any());
    }

    @Test
    void clientDisconnectDoesNotCancelOrDuplicateRuntimeRun() throws Exception {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(provider.stream(any())).thenReturn(Flux.defer(() -> {
            providerEntered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("provider was not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return response("disconnect-safe");
        }));

        Flux<ChatStreamEvent> body = webClient.post()
                .uri("/api/chat/stream")
                .headers(headers -> headers.setBasicAuth("student", "student123"))
                .bodyValue(Map.of("message", "请解释 Java 线程"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(ChatStreamEvent.class)
                .getResponseBody();
        ChatStreamEvent meta = body.take(1).blockFirst(Duration.ofSeconds(10));
        assertNotNull(meta);
        assertEquals("meta", meta.type());
        assertTrue(providerEntered.await(10, TimeUnit.SECONDS));
        release.countDown();

        AgentRunEntity completed = awaitCompleted(meta.sessionId());
        assertEquals(AgentStopReason.COMPLETED, completed.getStopReason());
        assertEquals(1, runs.findBySessionIdOrderByCreatedAtDesc(meta.sessionId()).size());
        assertTrue(projections.existsById(completed.getRunId()));
    }

    private List<ChatStreamEvent> postChat(String sessionId, String message) {
        return postChat(sessionId, message, "student", "student123");
    }

    private List<ChatStreamEvent> postChat(
            String sessionId,
            String message,
            String username,
            String password
    ) {
        Map<String, String> body = sessionId == null
                ? Map.of("message", message)
                : Map.of("sessionId", sessionId, "message", message);
        List<ChatStreamEvent> events = webClient.post()
                .uri("/api/chat/stream")
                .headers(headers -> headers.setBasicAuth(username, password))
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

    private List<String> types(List<ChatStreamEvent> events) {
        return events.stream().map(ChatStreamEvent::type).toList();
    }

    private AgentRunEntity awaitCompleted(String sessionId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            List<AgentRunEntity> found = runs.findBySessionIdOrderByCreatedAtDesc(sessionId);
            if (!found.isEmpty()
                    && found.get(0).getStopReason() == AgentStopReason.COMPLETED
                    && projections.existsById(found.get(0).getRunId())) {
                return found.get(0);
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("Runtime run did not complete after client disconnect");
    }

    private UserAccount createUser(String prefix, String password) {
        UserAccount user = new UserAccount();
        user.setUsername(prefix + UUID.randomUUID());
        user.setDisplayName("Other student");
        user.setPassword(passwordEncoder.encode(password));
        user.setRoles(Set.of("ROLE_USER"));
        return users.save(user);
    }

    private MultimodalAnalysis analysis(
            String modality,
            EmotionLabel emotion,
            RiskLevel risk,
            String evidence
    ) {
        MultimodalSignal signal = new MultimodalSignal(
                modality, emotion, risk == RiskLevel.HIGH ? 4.8 : 1.2, 0.9, evidence
        );
        PsychologyAssessment assessment = new PsychologyAssessment(
                emotion, signal.score(), risk, 0.9, "internal summary"
        );
        return new MultimodalAnalysis(
                "请分析我的状态",
                "请分析我的状态\n[backend] " + evidence,
                assessment,
                List.of(signal),
                "internal fused summary 4.8",
                "{\"fusedScore\":4.8}"
        );
    }

    private Flux<OpenAiStreamEvent> response(String... chunks) {
        java.util.ArrayList<OpenAiStreamEvent> events = new java.util.ArrayList<>();
        for (int index = 0; index < chunks.length; index++) {
            OpenAiApi.ChatCompletionFinishReason finish = index == chunks.length - 1
                    ? OpenAiApi.ChatCompletionFinishReason.STOP
                    : null;
            OpenAiApi.ChatCompletionMessage message = new OpenAiApi.ChatCompletionMessage(
                    chunks[index], OpenAiApi.ChatCompletionMessage.Role.ASSISTANT
            );
            OpenAiApi.ChatCompletionChunk.ChunkChoice choice =
                    new OpenAiApi.ChatCompletionChunk.ChunkChoice(
                            finish, 0, message, null
                    );
            events.add(new OpenAiStreamEvent.Chunk(new OpenAiApi.ChatCompletionChunk(
                    "chat-migration-response", List.of(choice), 1L, "test-model",
                    null, null, "chat.completion.chunk", null
            )));
        }
        events.add(OpenAiStreamEvent.Done.INSTANCE);
        return Flux.fromIterable(events);
    }
}
