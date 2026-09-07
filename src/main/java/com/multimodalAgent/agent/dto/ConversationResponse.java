package com.multimodalAgent.agent.dto;

import com.multimodalAgent.agent.domain.ChatMessage;
import com.multimodalAgent.agent.domain.ChatSession;
import java.time.Instant;
import java.util.List;

/**
 * 管理员查看完整会话的响应体。
 */
public record ConversationResponse(
        String sessionId,
        String title,
        Long userId,
        String username,
        String displayName,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationMessageResponse> messages
) {
    public static ConversationResponse from(ChatSession session, List<ChatMessage> messages) {
        return new ConversationResponse(
                session.getPublicId(),
                session.getTitle(),
                session.getUser().getId(),
                session.getUser().getUsername(),
                session.getUser().getDisplayName(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                messages.stream()
                        .map(ConversationMessageResponse::from)
                        .toList());
    }
}
