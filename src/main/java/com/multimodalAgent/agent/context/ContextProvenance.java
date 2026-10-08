package com.multimodalAgent.agent.context;

public record ContextProvenance(
        String sourceId,
        String sourceVersion,
        int order,
        int messageCount,
        String contentHash
) {

    public ContextProvenance {
        requireText(sourceId, "sourceId");
        requireText(sourceVersion, "sourceVersion");
        if (order < 0) {
            throw new IllegalArgumentException("order must not be negative");
        }
        if (messageCount < 0) {
            throw new IllegalArgumentException("messageCount must not be negative");
        }
        if (contentHash == null || !contentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 hex value");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
