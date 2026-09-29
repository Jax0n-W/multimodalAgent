package com.multimodalAgent.agent.recovery;

import java.util.Objects;
import java.util.Optional;

public record ToolReconciliationResult(
        ToolReconciliationOutcome outcome,
        Optional<String> externalReference,
        Optional<String> evidenceSummary
) {

    public ToolReconciliationResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(externalReference, "externalReference must not be null");
        Objects.requireNonNull(evidenceSummary, "evidenceSummary must not be null");
        externalReference.ifPresent(value -> requireText(value, "externalReference"));
        evidenceSummary.ifPresent(value -> requireText(value, "evidenceSummary"));
        externalReference.ifPresent(value -> requireLength(value, 500, "externalReference"));
        evidenceSummary.ifPresent(value -> requireLength(value, 2000, "evidenceSummary"));
    }

    public static ToolReconciliationResult of(ToolReconciliationOutcome outcome) {
        return new ToolReconciliationResult(outcome, Optional.empty(), Optional.empty());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireLength(String value, int maximum, String field) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
        }
    }
}
