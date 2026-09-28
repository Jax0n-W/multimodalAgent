package com.multimodalAgent.agent.eval;

public record EvalContractResult(
        boolean contractPass,
        boolean stopReasonMatch,
        boolean toolSelectionCorrect,
        int forbiddenToolViolations,
        boolean modelCallConstraintMatch,
        boolean toolCallConstraintMatch
) {
}
