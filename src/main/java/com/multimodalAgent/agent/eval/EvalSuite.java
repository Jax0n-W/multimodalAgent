package com.multimodalAgent.agent.eval;

import java.util.HashSet;
import java.util.List;

/** Immutable, versioned collection of synthetic evaluation cases. */
public record EvalSuite(String suiteId, String suiteVersion, List<EvalCase> cases) {

    public EvalSuite {
        requireText(suiteId, "suiteId");
        requireText(suiteVersion, "suiteVersion");
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("cases must not be empty");
        }
        cases = List.copyOf(cases);
        HashSet<String> caseIds = new HashSet<>();
        for (EvalCase evalCase : cases) {
            if (evalCase == null) {
                throw new IllegalArgumentException("cases must not contain null");
            }
            if (!caseIds.add(evalCase.caseId())) {
                throw new IllegalArgumentException("Duplicate caseId: " + evalCase.caseId());
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
