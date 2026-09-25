package com.multimodalAgent.agent.runtime.model.gateway;

import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.TokenUsage;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Provider-neutral governance boundary around one concrete AgentModel adapter. */
public final class ModelGateway implements GovernedAgentModel {

    private static final System.Logger LOGGER = System.getLogger(ModelGateway.class.getName());

    private final AgentModel delegate;
    private final ModelIdentity identity;
    private final ModelTimeoutPolicy timeoutPolicy;
    private final ModelInvocationTelemetrySink telemetry;
    private final LongSupplier nanoTime;

    public ModelGateway(
            AgentModel delegate,
            ModelIdentity identity,
            ModelTimeoutPolicy timeoutPolicy,
            ModelInvocationTelemetrySink telemetry
    ) {
        this(delegate, identity, timeoutPolicy, telemetry, System::nanoTime);
    }

    ModelGateway(
            AgentModel delegate,
            ModelIdentity identity,
            ModelTimeoutPolicy timeoutPolicy,
            ModelInvocationTelemetrySink telemetry,
            LongSupplier nanoTime
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.identity = Objects.requireNonNull(identity, "identity must not be null");
        this.timeoutPolicy = Objects.requireNonNull(
                timeoutPolicy, "timeoutPolicy must not be null"
        );
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry must not be null");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
    }

    @Override
    public ModelTurn generate(AgentModelRequest request, int iteration) {
        Objects.requireNonNull(request, "request must not be null");
        if (iteration < 1) {
            throw new IllegalArgumentException("iteration must be at least 1");
        }
        String invocationId = UUID.randomUUID().toString();
        long started = nanoTime.getAsLong();
        try {
            ModelTurn turn = Objects.requireNonNull(
                    delegate instanceof TimeoutAwareAgentModel timed
                            ? timed.generate(request, timeoutPolicy)
                            : delegate.generate(request),
                    "model adapter returned null"
            );
            observe(new ModelInvocationTelemetry(
                    invocationId, identity, iteration, elapsed(started), turn.finishReason(),
                    turn.tokenUsage(), null
            ));
            return turn;
        } catch (ModelProviderException exception) {
            observeFailure(invocationId, iteration, started, exception.failureKind());
            throw new ModelInvocationException(
                    exception.failureKind(),
                    "Model invocation failed for " + identity.provider() + "/" + identity.model(),
                    exception
            );
        } catch (RuntimeException exception) {
            observeFailure(invocationId, iteration, started, ModelFailureKind.PROVIDER_ERROR);
            throw new ModelInvocationException(
                    ModelFailureKind.PROVIDER_ERROR,
                    "Model invocation failed for " + identity.provider() + "/" + identity.model(),
                    exception
            );
        }
    }

    public ModelIdentity identity() {
        return identity;
    }

    @Override
    public Optional<ModelIdentity> modelIdentity() {
        return Optional.of(identity);
    }

    public ModelTimeoutPolicy timeoutPolicy() {
        return timeoutPolicy;
    }

    private void observeFailure(
            String invocationId,
            int iteration,
            long started,
            ModelFailureKind failureKind
    ) {
        observe(new ModelInvocationTelemetry(
                invocationId, identity, iteration, elapsed(started), null,
                TokenUsage.UNKNOWN, failureKind
        ));
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, nanoTime.getAsLong() - started));
    }

    private void observe(ModelInvocationTelemetry event) {
        try {
            telemetry.record(event);
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Model telemetry observation failed for invocation {0}: {1}",
                    event.invocationId(), exception.getClass().getSimpleName());
        }
    }
}
