package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.config.multimodalAgentProperties;
import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.IntentType;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.PsychologicalReport;
import com.multimodalAgent.agent.domain.RiskLevel;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.dto.ChatRequest;
import com.multimodalAgent.agent.dto.ChatStreamEvent;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.PsychologicalReportRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.service.ai.AiClient;
import com.multimodalAgent.agent.service.ai.AiMessage;
import com.multimodalAgent.agent.service.ai.PromptTemplates;
import com.multimodalAgent.agent.service.knowledge.AgenticRagResult;
import com.multimodalAgent.agent.service.knowledge.AgenticRagService;
import com.multimodalAgent.agent.service.knowledge.KnowledgeService;
import com.multimodalAgent.agent.service.memory.ShortTermMemoryService;
import com.multimodalAgent.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import com.multimodalAgent.agent.service.multimodal.MultimodalAnalysis;
import com.multimodalAgent.agent.service.multimodal.MultimodalSignal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
/**
 * 学生聊天主流程服务。
 *
 * <p>负责会话管理、Redis 短期记忆、MySQL 长期记忆、意图分类、RAG 检索、模型流式调用和后台报告触发。</p>
 */
public class ChatService {

    private final UserAccountRepository userAccountRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final PsychologicalReportRepository reportRepository;
    private final multimodalAgentProperties properties;
    private final IntentClassifier intentClassifier;
    private final PsychologicalAssessmentService assessmentService;
    private final KnowledgeService knowledgeService;
    private final AgenticRagService agenticRagService;
    private final ToolOrchestrationService toolOrchestrationService;
    private final PrivacySanitizer privacySanitizer;
    private final ShortTermMemoryService shortTermMemoryService;
    private final AiClient aiClient;

    public ChatService(
            UserAccountRepository userAccountRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository,
            PsychologicalReportRepository reportRepository,
            multimodalAgentProperties properties,
            IntentClassifier intentClassifier,
            PsychologicalAssessmentService assessmentService,
            KnowledgeService knowledgeService,
            AgenticRagService agenticRagService,
            ToolOrchestrationService toolOrchestrationService,
            PrivacySanitizer privacySanitizer,
            ShortTermMemoryService shortTermMemoryService,
            AiClient aiClient
    ) {
        this.userAccountRepository = userAccountRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.reportRepository = reportRepository;
        this.properties = properties;
        this.intentClassifier = intentClassifier;
        this.assessmentService = assessmentService;
        this.knowledgeService = knowledgeService;
        this.agenticRagService = agenticRagService;
        this.toolOrchestrationService = toolOrchestrationService;
        this.privacySanitizer = privacySanitizer;
        this.shortTermMemoryService = shortTermMemoryService;
        this.aiClient = aiClient;
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(Long userId, ChatRequest request) {
        // 聊天接口使用 SSE 流式返回；数据库读写放到 boundedElastic，避免阻塞响应线程。
        return Mono.fromCallable(() -> prepare(userId, request, null))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::streamPrepared)
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(null, "服务暂时不可用：" + exception.getMessage()))));
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamMultimodal(Long userId, ChatRequest request, MultimodalAnalysis analysis) {
        return Mono.fromCallable(() -> prepare(userId, request, analysis))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(this::streamPrepared)
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(null, "服务暂时不可用：" + exception.getMessage()))));
    }

    private PreparedConversation prepare(Long userId, ChatRequest request, MultimodalAnalysis multimodalAnalysis) {
        String input = request.message().trim();
        String modelInput = privacySanitizer.sanitize(multimodalAnalysis == null ? input : multimodalAnalysis.modelText());
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        ChatSession session = resolveSession(user, request.sessionId(), input);
        List<AiMessage> previousHistory = recentModelHistory(session);
        saveMessage(user, session, MessageRole.USER, input);
        if (multimodalAnalysis != null) {
            saveMultimodalMemory(user, session, multimodalAnalysis);
        }

        List<AiMessage> modelHistory = withCurrentUser(previousHistory, modelInput);
        IntentType intent = intentClassifier.classify(modelInput, modelHistory);
        if (multimodalAnalysis != null && multimodalAnalysis.fusedAssessment().risk() == RiskLevel.HIGH) {
            intent = IntentType.RISK;
        } else if (multimodalAnalysis != null && multimodalAnalysis.fusedAssessment().risk() == RiskLevel.MEDIUM && intent == IntentType.CHAT) {
            intent = IntentType.CONSULT;
        }
        PsychologyAssessment assessment = null;
        AgenticRagResult ragResult = AgenticRagResult.empty();
        PsychologicalReport report = null;

        // 普通聊天不进入心理评估和 RAG，避免把学习/生活问题强行变成测评。
        if (intent != IntentType.CHAT) {
            ragResult = agenticRagService.retrieve(modelInput, modelHistory);
            assessment = multimodalAnalysis == null
                    ? assessmentService.assess(modelInput, modelHistory)
                    : multimodalAnalysis.fusedAssessment();
            // 明确危险意图优先按高风险处理，防止模型评估偏保守导致预警漏触发。
            if (intent == IntentType.RISK && assessment.risk() != RiskLevel.HIGH) {
                assessment = new PsychologyAssessment(
                        assessment.emotion(),
                        Math.max(assessment.emotionScore(), 4.0),
                        RiskLevel.HIGH,
                        assessment.confidence(),
                        assessment.summary());
            }
            report = saveReport(user, session, input, intent, assessment, multimodalAnalysis);
        }

        RiskLevel riskLevel = assessment == null ? RiskLevel.LOW : assessment.risk();
        List<AiMessage> messages = buildMessages(user, intent, riskLevel, ragResult, modelHistory);
        Long reportId = report == null ? null : report.getId();
        return new PreparedConversation(user, session, intent, riskLevel, messages, reportId);
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> streamPrepared(PreparedConversation prepared) {
        StringBuilder assistantReply = new StringBuilder();
        Flux<ServerSentEvent<ChatStreamEvent>> meta = Flux.just(event(
                "meta",
                ChatStreamEvent.meta(prepared.session().getPublicId())));

        Flux<ServerSentEvent<ChatStreamEvent>> tokens = aiClient.stream(prepared.messages())
                .doOnNext(assistantReply::append)
                .map(token -> event("token", ChatStreamEvent.token(prepared.session().getPublicId(), token)))
                .timeout(Duration.ofSeconds(45))
                .onErrorResume(exception -> Flux.just(event(
                        "error",
                        ChatStreamEvent.error(prepared.session().getPublicId(), "模型响应超时或失败，请稍后重试。"))))
                .switchIfEmpty(Flux.just(event(
                        "error",
                        ChatStreamEvent.error(prepared.session().getPublicId(), "模型没有返回内容，请稍后重试。"))));

        Mono<ServerSentEvent<ChatStreamEvent>> done = Mono.fromCallable(() -> {
            if (!assistantReply.isEmpty()) {
                saveMessage(prepared.user(), prepared.session(), MessageRole.ASSISTANT, assistantReply.toString());
            }
            // 工具链在模型回复完成后异步执行，不打断学生端正在进行的对话体验。
            if (prepared.reportId() != null) {
                toolOrchestrationService.handleAsync(prepared.reportId());
            }
            return event("done", ChatStreamEvent.done(prepared.session().getPublicId()));
        }).subscribeOn(Schedulers.boundedElastic());

        return meta.concatWith(tokens).concatWith(done);
    }

    private ChatSession resolveSession(UserAccount user, String publicId, String input) {
        if (publicId != null && !publicId.isBlank()) {
            return chatSessionRepository.findByPublicIdAndUser_Id(publicId, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        }
        ChatSession session = new ChatSession();
        session.setPublicId(UUID.randomUUID().toString().replace("-", ""));
        session.setUser(user);
        session.setTitle(input.length() > 36 ? input.substring(0, 36) : input);
        return chatSessionRepository.save(session);
    }

    private void saveMessage(UserAccount user, ChatSession session, MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setUser(user);
        message.setSession(session);
        message.setRole(role);
        message.setContent(content);
        chatMessageRepository.save(message);
        session.touch();
        chatSessionRepository.save(session);
        shortTermMemoryService.append(session.getPublicId(), role, content);
    }

    private void saveMultimodalMemory(UserAccount user, ChatSession session, MultimodalAnalysis analysis) {
        saveMessage(user, session, MessageRole.SYSTEM, multimodalMemory(analysis));
    }

    private String multimodalMemory(MultimodalAnalysis analysis) {
        String modalities = String.join("、", analysis.signals().stream()
                .map(MultimodalSignal::modality)
                .distinct()
                .toList());
        String evidence = String.join("；", analysis.signals().stream()
                .map(signal -> signal.modality() + "=" + signal.evidence())
                .toList());
        return """
                【多模态分析记忆】
                用户本轮上传了%s，后端已完成多模态情绪分析。后续如果用户追问“你是否根据图片/语音/视频分析”，应说明：我是基于后端多模态分析结果和你的文字一起判断，不是只凭文字猜测。不要否认已上传附件，也不要声称自己直接查看了原始文件。
                分析摘要：%s
                情绪标签：%s
                分析证据：%s
                """.formatted(
                modalities.isBlank() ? "附件" : modalities,
                analysis.summary(),
                analysis.emotionTagsJson(),
                evidence.isBlank() ? "无" : evidence);
    }

    private PsychologicalReport saveReport(
            UserAccount user,
            ChatSession session,
            String content,
            IntentType intent,
            PsychologyAssessment assessment,
            MultimodalAnalysis multimodalAnalysis
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
        if (multimodalAnalysis != null) {
            report.setEmotionTags(multimodalAnalysis.emotionTagsJson());
        }
        return reportRepository.save(report);
    }

    private List<AiMessage> buildMessages(
            UserAccount user,
            IntentType intent,
            RiskLevel riskLevel,
            AgenticRagResult ragResult,
            List<AiMessage> history
    ) {
        // Agentic RAG 计划和证据只作为系统上下文给模型使用，不直接展示后台评估信息给学生。
        String context = ragResult.contextBlock();
        List<AiMessage> messages = new ArrayList<>();
        messages.add(PromptTemplates.answerSystemPrompt(intent, riskLevel, context, user.getUsername()));

        int limit = messageWindowLimit();
        history.stream()
                .skip(Math.max(0, history.size() - limit))
                .forEach(messages::add);
        return messages;
    }

    private List<ChatMessage> recentHistory(ChatSession session) {
        List<ChatMessage> history = chatMessageRepository.findTop20BySession_IdOrderByCreatedAtDesc(session.getId());
        Collections.reverse(history);
        return history;
    }

    private List<AiMessage> recentModelHistory(ChatSession session) {
        List<MemoryMessage> redisHistory = shortTermMemoryService.recent(session.getPublicId());
        if (!redisHistory.isEmpty()) {
            return redisHistory.stream()
                    .map(this::toAiMessage)
                    .toList();
        }

        // Redis 中没有短期记忆时，从 MySQL 长期记忆恢复最近 10 轮上下文。
        List<ChatMessage> databaseHistory = recentHistory(session);
        shortTermMemoryService.refresh(session.getPublicId(), databaseHistory.stream()
                .map(message -> new MemoryMessage(message.getRole(), message.getContent()))
                .toList());
        return databaseHistory.stream()
                .map(this::toAiMessage)
                .toList();
    }

    private List<AiMessage> withCurrentUser(List<AiMessage> previousHistory, String currentInput) {
        List<AiMessage> history = new ArrayList<>(previousHistory);
        history.add(AiMessage.user(currentInput));
        int limit = messageWindowLimit();
        return history.stream()
                .skip(Math.max(0, history.size() - limit))
                .toList();
    }

    private int messageWindowLimit() {
        // history-limit 以轮次理解，这里乘 2 保留用户和助手两侧消息。
        return Math.max(2, properties.getChat().getHistoryLimit() * 2);
    }

    private AiMessage toAiMessage(ChatMessage chatMessage) {
        String content = privacySanitizer.sanitize(chatMessage.getContent());
        return switch (chatMessage.getRole()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> AiMessage.system(content);
            case USER -> AiMessage.user(content);
        };
    }

    private AiMessage toAiMessage(MemoryMessage memoryMessage) {
        String content = privacySanitizer.sanitize(memoryMessage.content());
        return switch (memoryMessage.role()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> AiMessage.system(content);
            case USER -> AiMessage.user(content);
        };
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private record PreparedConversation(
            UserAccount user,
            ChatSession session,
            IntentType intent,
            RiskLevel riskLevel,
            List<AiMessage> messages,
            Long reportId
    ) {
    }
}
