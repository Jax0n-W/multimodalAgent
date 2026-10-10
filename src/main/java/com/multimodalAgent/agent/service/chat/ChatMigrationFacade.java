package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.IntentType;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.harness.AgentExecutionRequest;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.AgentRunSpec;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import com.multimodalAgent.agent.runtime.budget.ExecutionBudget;
import com.multimodalAgent.agent.runtime.model.AgentMessage;
import com.multimodalAgent.agent.service.IntentClassifier;
import com.multimodalAgent.agent.service.PrivacySanitizer;
import com.multimodalAgent.agent.service.PsychologicalAssessmentService;
import com.multimodalAgent.agent.service.PsychologyAssessment;
import com.multimodalAgent.agent.service.ToolOrchestrationService;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.ai.PromptTemplates;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import com.multimodalAgent.agent.service.multimodal.MultimodalSignal;
import com.multimodalAgent.agent.streaming.integration.StreamingAgentExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Migrates student final-answer generation to the unified Agent Runtime. */
@Service
@ConditionalOnProperty(
        prefix = "multimodal-agent.runtime",
        name = "enabled",
        havingValue = "true"
)
public class ChatMigrationFacade {

    private static final Logger log = LoggerFactory.getLogger(ChatMigrationFacade.class);

    private final UserAccountRepository users;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final PsychologicalReportRepository reports;
    private final ChatRuntimeSessionRegistry sessionRegistry;
    private final IntentClassifier intentClassifier;
    private final PsychologicalAssessmentService assessmentService;
    private final AgenticRagService ragService;
    private final ToolOrchestrationService tools;
    private final PrivacySanitizer sanitizer;
    private final StreamingAgentExecutionService execution;
    private final ExecutionBudget budget;
    private final ChatHistoryProjector projector;

    public ChatMigrationFacade(
            UserAccountRepository users,
            ChatSessionRepository sessions,
            ChatMessageRepository messages,
            PsychologicalReportRepository reports,
            ChatRuntimeSessionRegistry sessionRegistry,
            IntentClassifier intentClassifier,
            PsychologicalAssessmentService assessmentService,
            AgenticRagService ragService,
            ToolOrchestrationService tools,
            PrivacySanitizer sanitizer,
            StreamingAgentExecutionService execution,
            ExecutionBudget budget,
            ChatHistoryProjector projector
    ) {
        this.users = users;
        this.sessions = sessions;
        this.messages = messages;
        this.reports = reports;
        this.sessionRegistry = sessionRegistry;
        this.intentClassifier = intentClassifier;
        this.assessmentService = assessmentService;
        this.ragService = ragService;
        this.tools = tools;
        this.sanitizer = sanitizer;
        this.execution = execution;
        this.budget = budget;
        this.projector = projector;
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> stream(
            Long userId,
            ChatRequest request,
            MultimodalAnalysis multimodal
    ) {
        return Mono.fromCallable(() -> prepare(userId, request, multimodal))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::execute);
    }

    private PreparedRuntimeChat prepare(
            Long userId,
            ChatRequest request,
            MultimodalAnalysis multimodal
    ) {
        String rawInput = request.message().trim();
        String requestInput = sanitizer.sanitize(rawInput);
        String classificationInput = sanitizer.sanitize(
                multimodal == null ? rawInput : multimodal.modelText()
        );
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatSession session = sessionRegistry.resolveOrCreate(
                user, request.sessionId(), rawInput
        );
        List<AiMessage> history = projectedHistory(session);

        IntentType intent = intentClassifier.classify(classificationInput, history);
        if (multimodal != null && multimodal.fusedAssessment().risk() == RiskLevel.HIGH) {
            intent = IntentType.RISK;
        } else if (multimodal != null
                && multimodal.fusedAssessment().risk() == RiskLevel.MEDIUM
                && intent == IntentType.CHAT) {
            intent = IntentType.CONSULT;
        }

        PsychologyAssessment assessment = null;
        AgenticRagResult rag = AgenticRagResult.empty();
        if (intent != IntentType.CHAT) {
            rag = ragService.retrieve(classificationInput, history);
            assessment = multimodal == null
                    ? assessmentService.assess(classificationInput, history)
                    : multimodal.fusedAssessment();
            if (intent == IntentType.RISK && assessment.risk() != RiskLevel.HIGH) {
                assessment = new PsychologyAssessment(
                        assessment.emotion(),
                        Math.max(assessment.emotionScore(), 4.0),
                        RiskLevel.HIGH,
                        assessment.confidence(),
                        assessment.summary()
                );
            }
        }
        RiskLevel risk = assessment == null ? RiskLevel.LOW : assessment.risk();

        saveUserMessage(user, session, rawInput);
        PsychologicalReport report = assessment == null ? null : saveReport(
                user, session, rawInput, intent, assessment, multimodal
        );
        if (report != null) {
            // Risk/report processing is independent of whether final-answer generation succeeds.
            tools.handleAsync(report.getId());
        }

        String runId = "chat-" + compactUuid();
        AgentRunSpec spec = new AgentRunSpec(
                runId,
                session.getPublicId(),
                List.of(AgentMessage.user(requestInput)),
                3,
                Set.of(),
                Set.of(),
                budget
        );
        AgentExecutionRequest executionRequest = new AgentExecutionRequest(
                spec,
                "chat-request-" + compactUuid(),
                userId
        ).withTrustedBusinessContext(List.of(AgentMessage.system(
                businessContext(intent, risk, rag, multimodal)
        )));
        return new PreparedRuntimeChat(userId, session.getPublicId(), runId, executionRequest);
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> execute(PreparedRuntimeChat prepared) {
        return Flux.create(sink -> {
            sink.next(event("meta", ChatStreamEvent.meta(prepared.sessionId())));
            Mono.fromRunnable(() -> run(prepared, sink))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            ignored -> { },
                            failure -> emitError(sink, prepared.sessionId())
                    );
        });
    }

    private void run(
            PreparedRuntimeChat prepared,
            reactor.core.publisher.FluxSink<ServerSentEvent<ChatStreamEvent>> sink
    ) {
        try {
            RuntimeChatStreamBridge bridge = new RuntimeChatStreamBridge(
                    prepared.sessionId(), sink
            );
            AgentRunResult result = execution.execute(prepared.request(), bridge::attach);
            bridge.awaitDrainAndVerify(result);
            if (result.stopReason() != AgentStopReason.COMPLETED) {
                emitError(sink, prepared.sessionId());
                return;
            }
            try {
                projector.projectCompleted(
                        prepared.runId(), prepared.sessionId(), prepared.userId(),
                        result.finalContent()
                );
            } catch (RuntimeException projectionFailure) {
                try {
                    projector.recordFailure(
                            prepared.runId(), prepared.sessionId(), prepared.userId(),
                            projectionFailure.getMessage()
                    );
                } catch (RuntimeException recordingFailure) {
                    projectionFailure.addSuppressed(recordingFailure);
                }
                log.error("Chat projection failed for completed run {}", prepared.runId(),
                        projectionFailure);
                emitError(sink, prepared.sessionId());
                return;
            }
            if (!sink.isCancelled()) {
                sink.next(event("done", ChatStreamEvent.done(prepared.sessionId())));
                sink.complete();
            }
        } catch (RuntimeException failure) {
            log.warn("Migrated chat execution failed for run {}: {}", prepared.runId(),
                    failure.getClass().getSimpleName());
            emitError(sink, prepared.sessionId());
        }
    }

    private void emitError(
            reactor.core.publisher.FluxSink<ServerSentEvent<ChatStreamEvent>> sink,
            String sessionId
    ) {
        if (!sink.isCancelled()) {
            sink.next(event(
                    "error",
                    ChatStreamEvent.error(sessionId, "模型响应失败，请稍后重试。本次请求不会自动重新执行。")
            ));
            sink.complete();
        }
    }

    private List<AiMessage> projectedHistory(ChatSession session) {
        List<ChatMessage> history = new ArrayList<>(
                messages.findTop20BySession_IdOrderByCreatedAtDesc(session.getId())
        );
        Collections.reverse(history);
        return history.stream()
                .filter(message -> message.getRole() != MessageRole.SYSTEM)
                .map(message -> message.getRole() == MessageRole.ASSISTANT
                        ? AiMessage.assistant(sanitizer.sanitize(message.getContent()))
                        : AiMessage.user(sanitizer.sanitize(message.getContent())))
                .toList();
    }

    private void saveUserMessage(UserAccount user, ChatSession session, String content) {
        ChatMessage message = new ChatMessage();
        message.setUser(user);
        message.setSession(session);
        message.setRole(MessageRole.USER);
        message.setContent(content);
        messages.save(message);
        session.touch();
        sessions.save(session);
    }

    private PsychologicalReport saveReport(
            UserAccount user,
            ChatSession session,
            String content,
            IntentType intent,
            PsychologyAssessment assessment,
            MultimodalAnalysis multimodal
    ) {
        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setSession(session);
        report.setContent(content);
        report.setIntent(intent);
        report.setEmotion(assessment.emotion());
        report.setEmotionScore(assessment.emotionScore());
        report.setRiskLevel(assessment.risk());
        report.setConfidence(assessment.confidence());
        report.setSummary(assessment.summary());
        if (multimodal != null) {
            report.setEmotionTags(multimodal.emotionTagsJson());
        }
        return reports.save(report);
    }

    private String businessContext(
            IntentType intent,
            RiskLevel risk,
            AgenticRagResult rag,
            MultimodalAnalysis multimodal
    ) {
        StringBuilder context = new StringBuilder();
        context.append("Trusted Chat Business Context\n")
                .append("The backend route and safety classification are authoritative for this run. ")
                .append("Never disclose internal route, risk labels, scores, or report data.\n")
                .append("Route: ").append(intent).append("\n")
                .append("Safety level: ").append(risk).append("\n\n")
                .append(PromptTemplates.answerSystemPrompt(
                        intent, risk, rag.contextBlock(), "学生"
                ).content());
        if (multimodal != null) {
            String modalities = String.join("、", multimodal.signals().stream()
                    .map(MultimodalSignal::modality)
                    .distinct()
                    .toList());
            String evidence = String.join("；", multimodal.signals().stream()
                    .map(MultimodalSignal::evidence)
                    .map(sanitizer::sanitize)
                    .toList());
            context.append("\n\nTrusted Multimodal Context\n")
                    .append("The backend processed: ")
                    .append(modalities.isBlank() ? "attachment" : modalities)
                    .append(". The model did not directly inspect the original files.\n")
                    .append("Use this sanitized evidence only when relevant: ")
                    .append(evidence.isBlank() ? "No additional evidence." : evidence)
                    .append("\nDo not expose backend scores or claim direct file perception.");
        }
        return context.toString();
    }

    private String compactUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private record PreparedRuntimeChat(
            Long userId,
            String sessionId,
            String runId,
            AgentExecutionRequest request
    ) {
    }
}
