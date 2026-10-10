package com.multimodalAgent.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Client supplies a unique runId so it can connect to the live-only SSE route concurrently. */
public record AgentRunStartRequest(
        @NotBlank @Size(max = 64) String runId,
        @Size(max = 64)
        @Pattern(regexp = ".*\\S.*", message = "sessionId must not be blank when present")
        String sessionId,
        @NotBlank String message
) {

    public AgentRunStartRequest(String runId, String message) {
        this(runId, null, message);
    }
}
