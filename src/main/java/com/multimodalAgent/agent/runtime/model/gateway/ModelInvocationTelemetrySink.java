package com.multimodalAgent.agent.runtime.model.gateway;

@FunctionalInterface
public interface ModelInvocationTelemetrySink {

    ModelInvocationTelemetrySink NOOP = telemetry -> { };

    void record(ModelInvocationTelemetry telemetry);
}
