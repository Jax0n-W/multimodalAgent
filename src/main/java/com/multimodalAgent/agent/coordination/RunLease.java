package com.multimodalAgent.agent.coordination;

/**
 * Immutable credential proving active ownership of one run.
 * The token is deliberately distinct from the durable business identity.
 */
public record RunLease(String runId, String leaseToken) {

    public RunLease {
        requireText(runId, "runId");
        requireText(leaseToken, "leaseToken");
        if (runId.equals(leaseToken)) {
            throw new IllegalArgumentException("leaseToken must not equal runId");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
