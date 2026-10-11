package com.multimodalAgent.agent.context;

import java.util.Objects;

/** Run-scoped external evidence that must never inherit SYSTEM authority. */
public record UntrustedEvidence(String kind, String source, String content) {

    public UntrustedEvidence {
        kind = requireText(kind, "kind");
        source = requireText(source, "source");
        content = Objects.requireNonNull(content, "content must not be null");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
