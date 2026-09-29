package com.multimodalAgent.agent.recovery;

public record RecoveryCandidate(String runId, String sessionId) {
    public RecoveryCandidate {
        if (runId == null || runId.isBlank() || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Recovery candidate identities must not be blank");
        }
    }
}
