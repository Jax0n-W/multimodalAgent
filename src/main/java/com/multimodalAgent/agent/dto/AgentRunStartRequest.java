package com.multimodalAgent.agent.dto;

import jakarta.validation.constraints.NotBlank;

/** Client supplies a unique runId so it can connect to the live-only SSE route concurrently. */
public record AgentRunStartRequest(
        @NotBlank String runId,
        @NotBlank String message
) {
}
