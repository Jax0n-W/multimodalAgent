package com.multimodalAgent.agent.service;

import com.multimodalAgent.agent.domain.CrisisTicket;
import com.multimodalAgent.agent.domain.CrisisTicketStatus;
import com.multimodalAgent.agent.domain.CrisisUrgency;
import com.multimodalAgent.agent.repository.ChatSessionRepository;
import com.multimodalAgent.agent.repository.CrisisTicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class CrisisTicketService {

    private final CrisisTicketRepository tickets;
    private final ChatSessionRepository sessions;

    public CrisisTicketService(
            CrisisTicketRepository tickets,
            ChatSessionRepository sessions
    ) {
        this.tickets = tickets;
        this.sessions = sessions;
    }

    /**
     * Creates an idempotent local work item. Identity comes only from the governed runtime,
     * never from model-generated arguments.
     */
    @Transactional
    public CrisisTicket submit(
            String runId,
            String toolCallId,
            String sessionId,
            long userId,
            String runtimeConfigSnapshotId,
            String summary,
            CrisisUrgency urgency,
            boolean contactConsent,
            String preferredContact
    ) {
        String ticketId = ticketId(runId, toolCallId);
        CrisisTicket existing = tickets.findById(ticketId).orElse(null);
        if (existing != null) {
            return existing;
        }
        sessions.findByPublicIdAndUser_Id(sessionId, userId).orElseThrow(() ->
                new IllegalArgumentException("Session does not belong to the authenticated user")
        );

        CrisisTicket ticket = new CrisisTicket();
        ticket.setTicketId(ticketId);
        ticket.setRunId(runId);
        ticket.setToolCallId(toolCallId);
        ticket.setSessionId(sessionId);
        ticket.setUserId(userId);
        ticket.setRuntimeConfigSnapshotId(runtimeConfigSnapshotId);
        ticket.setSummary(summary);
        ticket.setUrgency(urgency);
        ticket.setContactConsent(contactConsent);
        ticket.setPreferredContact(blankToNull(preferredContact));
        ticket.setStatus(CrisisTicketStatus.QUEUED);
        ticket.setCreatedAt(Instant.now());
        return tickets.saveAndFlush(ticket);
    }

    private String ticketId(String runId, String toolCallId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (runId + "\u0000" + toolCallId).getBytes(StandardCharsets.UTF_8)
            );
            return "crisis-" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
