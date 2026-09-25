package com.multimodalAgent.agent.runtime.model;

public record TokenUsage(long inputTokens, long outputTokens, TokenUsageStatus status) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0, TokenUsageStatus.KNOWN);
    public static final TokenUsage UNKNOWN = new TokenUsage(
            0, 0, TokenUsageStatus.UNKNOWN_OR_INCOMPLETE
    );

    public TokenUsage(long inputTokens, long outputTokens) {
        this(inputTokens, outputTokens, TokenUsageStatus.KNOWN);
    }

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("Token counts must not be negative");
        }
        java.util.Objects.requireNonNull(status, "status must not be null");
    }

    public long totalTokens() {
        return inputTokens + outputTokens;
    }

    public TokenUsage plus(TokenUsage other) {
        java.util.Objects.requireNonNull(other, "other must not be null");
        TokenUsageStatus aggregateStatus = status == TokenUsageStatus.KNOWN
                && other.status == TokenUsageStatus.KNOWN
                ? TokenUsageStatus.KNOWN
                : TokenUsageStatus.UNKNOWN_OR_INCOMPLETE;
        return new TokenUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                aggregateStatus
        );
    }

    public boolean isComplete() {
        return status == TokenUsageStatus.KNOWN;
    }
}
