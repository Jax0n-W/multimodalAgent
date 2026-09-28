package com.multimodalAgent.agent.eval;

import com.multimodalAgent.agent.runtime.AgentRunResult;
import com.multimodalAgent.agent.runtime.budget.ModelPricing;
import com.multimodalAgent.agent.runtime.event.AgentEvent;
import com.multimodalAgent.agent.runtime.model.gateway.ModelInvocationTelemetry;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Raw facts observed from one existing Runtime execution. */
public record EvalObservation(
        AgentRunResult result,
        List<AgentEvent> events,
        List<ModelInvocationTelemetry> modelTelemetry,
        String runtimeConfigSnapshotId,
        ModelPricing pricing,
        Duration latency
) {

    public EvalObservation {
        Objects.requireNonNull(result, "result must not be null");
        events = List.copyOf(Objects.requireNonNull(events, "events must not be null"));
        modelTelemetry = List.copyOf(Objects.requireNonNull(
                modelTelemetry, "modelTelemetry must not be null"
        ));
        if (runtimeConfigSnapshotId != null && runtimeConfigSnapshotId.isBlank()) {
            throw new IllegalArgumentException(
                    "runtimeConfigSnapshotId must not be blank when present"
            );
        }
        Objects.requireNonNull(latency, "latency must not be null");
        if (latency.isNegative()) {
            throw new IllegalArgumentException("latency must not be negative");
        }
    }
}
