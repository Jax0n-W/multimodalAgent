package com.multimodalAgent.agent.service.chat;

import com.multimodalAgent.agent.domain.ChatSession;
import com.multimodalAgent.agent.domain.UserAccount;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Resolves an existing conversation or creates a new Runtime-backed chat session. */
@Service
public class ChatSessionRegistry {

    private final ChatSessionRepository sessions;

    public ChatSessionRegistry(ChatSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional
    public ChatSession resolveOrCreate(
            UserAccount user,
            String publicId,
            String titleSource
    ) {
        if (publicId != null && !publicId.isBlank()) {
            return sessions.findByPublicIdAndUser_Id(publicId, user.getId())
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        }

        ChatSession session = new ChatSession();
        session.setPublicId(UUID.randomUUID().toString().replace("-", ""));
        session.setUser(user);
        String title = titleSource == null ? "New conversation" : titleSource.trim();
        session.setTitle(title.length() > 36 ? title.substring(0, 36) : title);
        return sessions.saveAndFlush(session);
    }
}
