package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.MessageRole;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.persistence.entity.AgentRunEntity;
import com.multimodalAgent.agent.persistence.entity.ChatHistoryProjectionEntity;
import com.multimodalAgent.agent.persistence.model.AgentRunStatus;
import com.multimodalAgent.agent.persistence.model.ChatProjectionStatus;
import com.multimodalAgent.agent.persistence.repository.AgentRunRepository;
import com.multimodalAgent.agent.persistence.repository.ChatHistoryProjectionRepository;
import com.multimodalAgent.agent.persistence.repository.ChatRuntimeSessionRepository;
import com.multimodalAgent.agent.repository.ChatMessageRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.UserAccountRepository;
import com.multimodalAgent.agent.runtime.AgentStopReason;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Idempotently projects a durable successful AgentRun into the legacy chat history view. */
@Service
public class ChatHistoryProjector {

    private final AgentRunRepository runs;
    private final ChatHistoryProjectionRepository projections;
    private final ChatRuntimeSessionRepository runtimeSessions;
    private final ChatSessionRepository sessions;
    private final ChatMessageRepository messages;
    private final UserAccountRepository users;

    public ChatHistoryProjector(
            AgentRunRepository runs,
            ChatHistoryProjectionRepository projections,
            ChatRuntimeSessionRepository runtimeSessions,
            ChatSessionRepository sessions,
            ChatMessageRepository messages,
            UserAccountRepository users
    ) {
        this.runs = runs;
        this.projections = projections;
        this.runtimeSessions = runtimeSessions;
        this.sessions = sessions;
        this.messages = messages;
        this.users = users;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void projectCompleted(String runId, String sessionId, Long userId, String finalContent) {
        ChatHistoryProjectionEntity existing = projections.findById(runId).orElse(null);
        if (existing != null && existing.getStatus() == ChatProjectionStatus.COMPLETED) {
            validateProjectionIdentity(existing, sessionId, userId);
            return;
        }

        AgentRunEntity run = runs.findByRunIdAndUserId(runId, userId)
                .orElseThrow(() -> new IllegalStateException("Completed AgentRun not found"));
        if (!sessionId.equals(run.getSessionId())
                || run.getStatus() != AgentRunStatus.COMPLETED
                || run.getStopReason() != AgentStopReason.COMPLETED
                || run.getFinalContent() == null
                || !run.getFinalContent().equals(finalContent)) {
            throw new IllegalStateException("AgentRun is not durably projectable");
        }
        if (runtimeSessions.findBySessionIdAndUserId(sessionId, userId).isEmpty()) {
            throw new IllegalStateException("Runtime session marker not found");
        }
        ChatSession session = sessions.findByPublicIdAndUser_Id(sessionId, userId)
                .orElseThrow(() -> new IllegalStateException("Chat session not found"));
        UserAccount user = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("User not found"));

        ChatMessage assistant = new ChatMessage();
        assistant.setUser(user);
        assistant.setSession(session);
        assistant.setRole(MessageRole.ASSISTANT);
        assistant.setContent(finalContent);
        ChatMessage saved = messages.saveAndFlush(assistant);
        session.touch();
        sessions.save(session);

        ChatHistoryProjectionEntity projection = existing == null
                ? new ChatHistoryProjectionEntity(
                        runId, sessionId, userId, ChatProjectionStatus.FAILED
                )
                : existing;
        validateProjectionIdentity(projection, sessionId, userId);
        projection.complete(saved.getId());
        projections.saveAndFlush(projection);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String runId, String sessionId, Long userId, String error) {
        ChatHistoryProjectionEntity projection = projections.findById(runId)
                .orElseGet(() -> new ChatHistoryProjectionEntity(
                        runId, sessionId, userId, ChatProjectionStatus.FAILED
                ));
        validateProjectionIdentity(projection, sessionId, userId);
        projection.fail(error);
        projections.saveAndFlush(projection);
    }

    private void validateProjectionIdentity(
            ChatHistoryProjectionEntity projection,
            String sessionId,
            Long userId
    ) {
        if (!projection.getSessionId().equals(sessionId)
                || !projection.getUserId().equals(userId)) {
            throw new IllegalStateException("Chat projection identity mismatch");
        }
    }
}
