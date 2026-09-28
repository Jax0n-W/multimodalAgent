package com.multimodalAgent.agent.eval;

/** Runtime budget used to execute a case; it is not an Eval oracle. */
public record EvalExecutionBudget(Long maxModelCalls, Long maxToolCalls) {

    public static final EvalExecutionBudget UNLIMITED = new EvalExecutionBudget(null, null);

    public EvalExecutionBudget {
        requireNonNegative(maxModelCalls, "maxModelCalls");
        requireNonNegative(maxToolCalls, "maxToolCalls");
    }

    private static void requireNonNegative(Long value, String field) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
