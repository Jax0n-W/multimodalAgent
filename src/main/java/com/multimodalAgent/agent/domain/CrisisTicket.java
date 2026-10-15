package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "crisis_tickets")
public class CrisisTicket {

    @Id
    @Column(name = "ticket_id", length = 40)
    private String ticketId;

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "tool_call_id", nullable = false, length = 128)
    private String toolCallId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "runtime_config_snapshot_id", nullable = false, length = 96)
    private String runtimeConfigSnapshotId;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CrisisUrgency urgency;

    @Column(name = "contact_consent", nullable = false)
    private boolean contactConsent;

    @Column(name = "preferred_contact", length = 120)
    private String preferredContact;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CrisisTicketStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public String getTicketId() { return ticketId; }
    public void setTicketId(String value) { ticketId = value; }
    public String getRunId() { return runId; }
    public void setRunId(String value) { runId = value; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String value) { toolCallId = value; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String value) { sessionId = value; }
    public Long getUserId() { return userId; }
    public void setUserId(Long value) { userId = value; }
    public String getRuntimeConfigSnapshotId() { return runtimeConfigSnapshotId; }
    public void setRuntimeConfigSnapshotId(String value) { runtimeConfigSnapshotId = value; }
    public String getSummary() { return summary; }
    public void setSummary(String value) { summary = value; }
    public CrisisUrgency getUrgency() { return urgency; }
    public void setUrgency(CrisisUrgency value) { urgency = value; }
    public boolean isContactConsent() { return contactConsent; }
    public void setContactConsent(boolean value) { contactConsent = value; }
    public String getPreferredContact() { return preferredContact; }
    public void setPreferredContact(String value) { preferredContact = value; }
    public CrisisTicketStatus getStatus() { return status; }
    public void setStatus(CrisisTicketStatus value) { status = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant value) { createdAt = value; }
}
