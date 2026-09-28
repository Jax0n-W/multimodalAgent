package com.multimodalAgent.agent.eval;

import com.multimodalAgent.agent.runtime.AgentStopReason;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Deterministic contract oracle; it deliberately does not judge answer semantics. */
public final class EvalContractEvaluator {

    public EvalContractResult evaluate(
            EvalCase evalCase,
            AgentStopReason actualStopReason,
            List<String> selectedTools,
            long modelCalls,
            long toolCalls
    ) {
        Objects.requireNonNull(evalCase, "evalCase must not be null");
        Objects.requireNonNull(actualStopReason, "actualStopReason must not be null");
        Objects.requireNonNull(selectedTools, "selectedTools must not be null");
        Set<String> selectedToolSet = Set.copyOf(selectedTools);
        EvalOracle oracle = evalCase.oracle();
        int forbiddenViolations = Math.toIntExact(selectedTools.stream()
                .filter(oracle.forbiddenTools()::contains)
                .count());
        boolean expectedToolsSatisfied = selectedToolSet.containsAll(oracle.expectedTools());
        boolean toolSelectionCorrect = expectedToolsSatisfied && forbiddenViolations == 0;
        boolean stopReasonMatch = actualStopReason == oracle.expectedStopReason();
        boolean modelCallConstraintMatch = oracle.maxExpectedModelCalls() == null
                || modelCalls <= oracle.maxExpectedModelCalls();
        boolean toolCallConstraintMatch = oracle.maxExpectedToolCalls() == null
                || toolCalls <= oracle.maxExpectedToolCalls();
        return new EvalContractResult(
                stopReasonMatch
                        && toolSelectionCorrect
                        && modelCallConstraintMatch
                        && toolCallConstraintMatch,
                stopReasonMatch,
                toolSelectionCorrect,
                forbiddenViolations,
                modelCallConstraintMatch,
                toolCallConstraintMatch
        );
    }
}
