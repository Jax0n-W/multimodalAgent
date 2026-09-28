package com.multimodalAgent.agent.eval;

import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationTelemetry;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Adapts an existing Runtime execution entry and its case-scoped facts to Eval. */
public final class RuntimeEvalExecutionTarget implements EvalExecutionTarget {

    private final String targetName;
    private final Function<EvalCase, Capture> execution;

    public RuntimeEvalExecutionTarget(
            String targetName,
            Function<EvalCase, Capture> execution
    ) {
        if (targetName == null || targetName.isBlank()) {
            throw new IllegalArgumentException("targetName must not be blank");
        }
        this.targetName = targetName;
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
    }

    @Override
    public String targetName() {
        return targetName;
    }

    @Override
    public EvalObservation execute(EvalCase evalCase) {
        Capture capture = Objects.requireNonNull(
                execution.apply(Objects.requireNonNull(evalCase, "evalCase must not be null")),
                "Runtime execution returned null capture"
        );
        return new EvalObservation(
                capture.result(),
                capture.events(),
                capture.modelTelemetry(),
                capture.snapshotObservation(),
                capture.pricing(),
                capture.latency()
        );
    }

    public record Capture(
            AgentRunResult result,
            List<AgentEvent> events,
            List<ModelInvocationTelemetry> modelTelemetry,
            EvalSnapshotObservation snapshotObservation,
            ModelPricing pricing,
            Duration latency
    ) {

        public Capture {
            Objects.requireNonNull(result, "result must not be null");
            events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
            modelTelemetry = List.copyOf(Objects.requireNonNull(
                    modelTelemetry, "modelTelemetry must not be null"
            ));
            Objects.requireNonNull(snapshotObservation, "snapshotObservation must not be null");
            Objects.requireNonNull(latency, "latency must not be null");
        }
    }
}
