package com.multimodalAgent.agent.tool.campus;

import com.multimodalAgent.agent.domain.CrisisTicket;
import com.multimodalAgent.agent.domain.CrisisTicketStatus;
import com.multimodalAgent.agent.domain.CrisisUrgency;
import com.multimodalAgent.agent.runtime.tool.ContextAwareAgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolExecutionContext;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.UntrustedEvidenceTool;
import com.multimodalAgent.agent.service.CrisisTicketService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class SubmitCrisisTicketTool implements
        ContextAwareAgentTool<SubmitCrisisTicketTool.Input, SubmitCrisisTicketTool.Output>,
        UntrustedEvidenceTool {

    public static final String NAME = "submit_crisis_ticket";

    private final CrisisTicketService tickets;
    private final ToolDescriptor<Input> descriptor;

    public SubmitCrisisTicketTool(CrisisTicketService tickets, Duration timeout) {
        this.tickets = Objects.requireNonNull(tickets, "tickets must not be null");
        descriptor = new ToolDescriptor<>(
                NAME,
                "Create an idempotent local crisis-response work item for the authenticated user. Human approval is mandatory.",
                Input.class,
                ToolRisk.HIGH,
                false,
                true,
                true,
                timeout
        );
    }

    @Override
    public ToolDescriptor<Input> descriptor() {
        return descriptor;
    }

    @Override
    public Output execute(Input input, ToolExecutionContext context) {
        CrisisTicket ticket = tickets.submit(
                context.runId(),
                context.toolCallId(),
                context.sessionId(),
                context.requireUserId(),
                context.requireRuntimeConfigSnapshotId(),
                input.summary(),
                input.urgency(),
                input.contactConsent(),
                input.preferredContact()
        );
        return new Output(ticket.getTicketId(), ticket.getStatus(), ticket.getCreatedAt());
    }

    @Override
    public String evidenceKind() {
        return "crisis-ticket-receipt";
    }

    public record Input(
            @NotBlank @Size(max = 2000) String summary,
            @NotNull CrisisUrgency urgency,
            boolean contactConsent,
            @Size(max = 120) String preferredContact
    ) {
    }

    public record Output(
            String ticketId,
            CrisisTicketStatus status,
            Instant createdAt
    ) {
    }
}
