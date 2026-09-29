package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

public record ReliableToolOutcomeMaterialization(
        ReliableToolOutcomeMaterializationStatus status,
        Optional<ReliableToolOutcome> outcome
) {

    public ReliableToolOutcomeMaterialization {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        if ((status == ReliableToolOutcomeMaterializationStatus.MATERIALIZED
                || status == ReliableToolOutcomeMaterializationStatus.ALREADY_MATERIALIZED)
                != outcome.isPresent()) {
            throw new IllegalArgumentException(
                    "Only successful materialization may expose a reliable outcome"
            );
        }
    }

    public static ReliableToolOutcomeMaterialization withoutOutcome(
            ReliableToolOutcomeMaterializationStatus status
    ) {
        return new ReliableToolOutcomeMaterialization(status, Optional.empty());
    }
}
