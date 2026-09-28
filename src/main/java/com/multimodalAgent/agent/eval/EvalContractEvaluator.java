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
        int forbiddenViolations = Math.toIntExact(selectedTools.stream()
                .filter(evalCase.forbiddenTools()::contains)
                .count());
        boolean expectedToolsSatisfied = selectedToolSet.containsAll(evalCase.expectedTools());
        boolean toolSelectionCorrect = expectedToolsSatisfied && forbiddenViolations == 0;
        boolean stopReasonMatch = actualStopReason == evalCase.expectedStopReason();
        boolean modelCallConstraintMatch = evalCase.maxModelCalls() == null
                || modelCalls <= evalCase.maxModelCalls();
        boolean toolCallConstraintMatch = evalCase.maxToolCalls() == null
                || toolCalls <= evalCase.maxToolCalls();
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
