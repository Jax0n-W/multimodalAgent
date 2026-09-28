package com.multimodalAgent.agent.eval;

import com.multimodalAgent.agent.runtime.budget.ModelCostCalculator;
import com.multimodalAgent.agent.runtime.event.ModelStartedEvent;
import com.multimodalAgent.agent.runtime.event.ToolFailedEvent;
import com.multimodalAgent.agent.runtime.event.ToolRequestedEvent;
import com.multimodalAgent.agent.runtime.event.ToolStartedEvent;
import com.multimodalAgent.agent.runtime.event.ToolSucceededEvent;
import com.multimodalAgent.agent.runtime.model.TokenUsage;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Maps one case-scoped observation into the durable Eval record shape. */
public final class EvalRecordFactory {

    private final EvalContractEvaluator contractEvaluator;
    private final ModelCostCalculator costCalculator;

    public EvalRecordFactory() {
        this(new EvalContractEvaluator(), new ModelCostCalculator());
    }

    EvalRecordFactory(
            EvalContractEvaluator contractEvaluator,
            ModelCostCalculator costCalculator
    ) {
        this.contractEvaluator = java.util.Objects.requireNonNull(contractEvaluator);
        this.costCalculator = java.util.Objects.requireNonNull(costCalculator);
    }

    public EvalRecord create(EvalCase evalCase, EvalObservation observation) {
        long modelCalls = observation.events().stream()
                .filter(ModelStartedEvent.class::isInstance)
                .count();
        List<String> requestedTools = observation.events().stream()
                .filter(ToolRequestedEvent.class::isInstance)
                .map(ToolRequestedEvent.class::cast)
                .map(ToolRequestedEvent::toolName)
                .toList();
        List<String> startedTools = observation.events().stream()
                .filter(ToolStartedEvent.class::isInstance)
                .map(ToolStartedEvent.class::cast)
                .map(ToolStartedEvent::toolName)
                .toList();
        List<String> succeededTools = observation.events().stream()
                .filter(ToolSucceededEvent.class::isInstance)
                .map(ToolSucceededEvent.class::cast)
                .map(ToolSucceededEvent::toolName)
                .toList();
        List<String> failedTools = observation.events().stream()
                .filter(ToolFailedEvent.class::isInstance)
                .map(ToolFailedEvent.class::cast)
                .map(ToolFailedEvent::toolName)
                .toList();
        long toolRequests = requestedTools.size();
        long toolCalls = startedTools.size();
        EvalContractResult contract = contractEvaluator.evaluate(
                evalCase,
                observation.result().stopReason(),
                requestedTools,
                modelCalls,
                toolCalls
        );
        TokenUsage usage = observation.result().tokenUsage();
        boolean usageComplete = usage.isComplete();
        BigDecimal estimatedCost = observation.pricing() == null
                ? null
                : costCalculator.calculate(
                        observation.pricing().identity(), usage, observation.pricing()
                ).orElse(null);
        ModelFailureKind failureKind = observation.modelTelemetry().stream()
                .filter(telemetry -> telemetry.failureKind() != null)
                .reduce((first, second) -> second)
                .map(telemetry -> telemetry.failureKind())
                .orElse(null);
        List<String> toolsUsed = new ArrayList<>(observation.result().toolsUsed());
        toolsUsed.sort(Comparator.naturalOrder());
        return new EvalRecord(
                evalCase.caseId(),
                evalCase.category(),
                contract.contractPass(),
                contract.stopReasonMatch(),
                contract.toolSelectionCorrect(),
                contract.forbiddenToolViolations(),
                observation.result().stopReason(),
                toolsUsed,
                toolRequests,
                requestedTools,
                startedTools,
                succeededTools,
                failedTools,
                observation.result().iterations(),
                modelCalls,
                toolCalls,
                usageComplete ? usage.inputTokens() : null,
                usageComplete ? usage.outputTokens() : null,
                usageComplete ? usage.totalTokens() : null,
                usage.status(),
                estimatedCost,
                estimatedCost == null ? CostStatus.UNKNOWN : CostStatus.KNOWN,
                observation.latency().toMillis(),
                failureKind,
                observation.snapshotObservation().snapshotId(),
                observation.snapshotObservation().provenance()
        );
    }
}
