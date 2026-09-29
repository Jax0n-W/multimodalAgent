package com.multimodalAgent.agent.recovery;

import java.util.Objects;

/** Establishes durable successful tool truth only while explicit recovery authority is valid. */
public final class ReliableToolOutcomeMaterializer {

    private final RecoveryAuthorityGuard authorityGuard;
    private final ReliableToolOutcomeMaterializationStore store;

    public ReliableToolOutcomeMaterializer(
            RecoveryAuthorityGuard authorityGuard,
            ReliableToolOutcomeMaterializationStore store
    ) {
        this.authorityGuard = Objects.requireNonNull(
                authorityGuard,
                "authorityGuard must not be null"
        );
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public ReliableToolOutcomeMaterialization materialize(String runId, String toolCallId) {
        requireText(runId, "runId");
        requireText(toolCallId, "toolCallId");
        authorityGuard.assertAuthority(runId);
        return store.materialize(runId, toolCallId);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
