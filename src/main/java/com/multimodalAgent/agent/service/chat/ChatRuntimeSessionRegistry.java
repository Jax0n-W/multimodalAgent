package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.persistence.entity.ChatRuntimeSessionEntity;
import com.multimodalAgent.agent.persistence.repository.ChatRuntimeSessionRepository;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Durable routing marker that keeps each chat session on one execution path. */
@Service
public class ChatRuntimeSessionRegistry {

    private final ChatSessionRepository sessions;
    private final ChatRuntimeSessionRepository runtimeSessions;

    public ChatRuntimeSessionRegistry(
            ChatSessionRepository sessions,
            ChatRuntimeSessionRepository runtimeSessions
    ) {
        this.sessions = sessions;
        this.runtimeSessions = runtimeSessions;
    }

    @Transactional(readOnly = true)
    public boolean isRuntimeSession(Long userId, String sessionId) {
        ChatSession session = sessions.findByPublicIdAndUser_Id(sessionId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        return runtimeSessions.findById(session.getPublicId())
                .map(marker -> {
                    if (!marker.getUserId().equals(userId)) {
                        throw new IllegalStateException("Runtime session owner mismatch");
                    }
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public ChatSession resolveOrCreate(UserAccount user, String publicId, String titleSource) {
        if (publicId != null && !publicId.isBlank()) {
            ChatSession session = sessions.findByPublicIdAndUser_Id(publicId, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
            ChatRuntimeSessionEntity marker = runtimeSessions.findById(publicId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Legacy-only session cannot switch to Runtime"
                    ));
            if (!marker.getUserId().equals(user.getId())) {
                throw new IllegalStateException("Runtime session owner mismatch");
            }
            return session;
        }

        ChatSession session = new ChatSession();
        session.setPublicId(UUID.randomUUID().toString().replace("-", ""));
        session.setUser(user);
        String title = titleSource == null ? "New conversation" : titleSource.trim();
        session.setTitle(title.length() > 36 ? title.substring(0, 36) : title);
        ChatSession saved = sessions.saveAndFlush(session);
        runtimeSessions.saveAndFlush(new ChatRuntimeSessionEntity(
                saved.getPublicId(), user.getId()
        ));
        return saved;
    }
}
