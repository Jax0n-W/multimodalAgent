package com.multimodalAgent.agent.persistence.entity;

import com.multimodalAgent.agent.persistence.model.ChatProjectionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "chat_history_projections")
public class ChatHistoryProjectionEntity {

    @Id
    @Column(name = "run_id", length = 64, nullable = false, updatable = false)
    private String runId;

    @Column(name = "session_id", length = 64, nullable = false, updatable = false)
    private String sessionId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChatProjectionStatus status;

    @Column(name = "chat_message_id")
    private Long chatMessageId;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ChatHistoryProjectionEntity() {
    }

    public ChatHistoryProjectionEntity(
            String runId,
            String sessionId,
            Long userId,
            ChatProjectionStatus status
    ) {
        this.runId = requireText(runId, "runId");
        this.sessionId = requireText(sessionId, "sessionId");
        this.userId = java.util.Objects.requireNonNull(userId, "userId must not be null");
        this.status = java.util.Objects.requireNonNull(status, "status must not be null");
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = updatedAt == null ? now : updatedAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void complete(Long messageId) {
        chatMessageId = java.util.Objects.requireNonNull(messageId, "messageId must not be null");
        status = ChatProjectionStatus.COMPLETED;
        errorMessage = null;
    }

    public void fail(String error) {
        if (status == ChatProjectionStatus.COMPLETED) {
            return;
        }
        status = ChatProjectionStatus.FAILED;
        errorMessage = error == null ? "" : error.substring(0, Math.min(500, error.length()));
    }

    public String getRunId() { return runId; }
    public String getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public ChatProjectionStatus getStatus() { return status; }
    public Long getChatMessageId() { return chatMessageId; }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
