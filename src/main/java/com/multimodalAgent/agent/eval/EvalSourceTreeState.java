package com.multimodalAgent.agent.eval;

import java.util.Locale;

/** Explicitly injected provenance for the source tree used by an Eval run. */
public enum EvalSourceTreeState {
    CLEAN,
    DIRTY,
    UNKNOWN;

    public static EvalSourceTreeState parse(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported eval source tree state: " + value,
                    exception
            );
        }
    }
}
