package com.multimodalAgent.agent.adapter.model.springai.streaming;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.adapter.model.springai.SpringAiModelAdapterException;
import com.multimodalAgent.agent.runtime.model.AgentModel;
import com.multimodalAgent.agent.runtime.model.AgentModelRequest;
import com.multimodalAgent.agent.runtime.model.ModelTurn;
import com.multimodalAgent.agent.runtime.model.gateway.ModelFailureKind;
import com.multimodalAgent.agent.runtime.model.gateway.ModelProviderException;
import com.multimodalAgent.agent.runtime.model.gateway.ModelTimeoutPolicy;
import com.multimodalAgent.agent.runtime.model.gateway.TimeoutAwareAgentModel;
import com.multimodalAgent.agent.stream.ModelDelta;
import com.multimodalAgent.agent.stream.ModelDeltaObserver;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Synchronous AgentModel boundary backed by a truly streaming OpenAI-compatible provider call.
 *
 * <p>Many provider chunks are consumed inside one {@link #generate(AgentModelRequest)} call. Text
 * fragments are observable as ModelDelta values, while the return value remains one complete
 * ModelTurn. ToolCall fragments are never exposed to ToolExecutor before stream completion.</p>
 */
public final class OpenAiCompatibleStreamingAgentModelAdapter
        implements AgentModel, TimeoutAwareAgentModel {

    private static final System.Logger LOGGER = System.getLogger(
            OpenAiCompatibleStreamingAgentModelAdapter.class.getName()
    );

    private final OpenAiCompatibleStreamingClient client;
    private final OpenAiCompatibleStreamingOptions options;
    private final ObjectMapper objectMapper;
    private final OpenAiStreamingRequestFactory requestFactory;
    private final Supplier<StreamingModelInvocationScope.InvocationObservation>
            observationSupplier;

    public OpenAiCompatibleStreamingAgentModelAdapter(
            OpenAiCompatibleStreamingClient client,
            OpenAiCompatibleStreamingOptions options,
            ObjectMapper objectMapper
    ) {
        this(
                client,
                options,
                objectMapper,
                StreamingModelInvocationScope.InvocationObservation::disabled
        );
    }

    public OpenAiCompatibleStreamingAgentModelAdapter(
            OpenAiCompatibleStreamingClient client,
            OpenAiCompatibleStreamingOptions options,
            ObjectMapper objectMapper,
            StreamingModelInvocationScope invocationScope
    ) {
        this(
                client,
                options,
                objectMapper,
                Objects.requireNonNull(
                        invocationScope,
                        "invocationScope must not be null"
                )::capture
        );
    }

    OpenAiCompatibleStreamingAgentModelAdapter(
            OpenAiCompatibleStreamingClient client,
            OpenAiCompatibleStreamingOptions options,
            ObjectMapper objectMapper,
            ModelDeltaObserver observer,
            java.util.function.IntSupplier iterationSupplier
    ) {
        this(
                client,
                options,
                objectMapper,
                () -> new StreamingModelInvocationScope.InvocationObservation(
                        iterationSupplier.getAsInt(),
                        observer
                )
        );
    }

    private OpenAiCompatibleStreamingAgentModelAdapter(
            OpenAiCompatibleStreamingClient client,
            OpenAiCompatibleStreamingOptions options,
            ObjectMapper objectMapper,
            Supplier<StreamingModelInvocationScope.InvocationObservation> observationSupplier
    ) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.requestFactory = new OpenAiStreamingRequestFactory(options, objectMapper);
        this.observationSupplier = Objects.requireNonNull(
                observationSupplier,
                "observationSupplier must not be null"
        );
    }

    @Override
    public ModelTurn generate(AgentModelRequest request) {
        // Compatibility path for focused adapter tests and non-production callers. Production
        // supplies the configured policy through ModelGateway.
        return generate(request, new ModelTimeoutPolicy(
                Duration.ofMinutes(2), Duration.ofSeconds(30)
        ));
    }

    @Override
    public ModelTurn generate(
            AgentModelRequest request,
            ModelTimeoutPolicy timeoutPolicy
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(timeoutPolicy, "timeoutPolicy must not be null");
        StreamingModelInvocationScope.InvocationObservation observation =
                Objects.requireNonNull(
                        observationSupplier.get(),
                        "observationSupplier returned null"
                );
        StreamingTurnAccumulator accumulator = new StreamingTurnAccumulator(
                options.providerName(),
                objectMapper
        );
        SafeDeltaObservation deltaObservation = new SafeDeltaObservation(observation);
        try {
            Flux<OpenAiStreamEvent> events = Objects.requireNonNull(
                    client.stream(requestFactory.create(request)),
                    "streaming client returned null"
            );
            events.timeout(
                            timeoutPolicy.idleTimeout(),
                            Flux.error(timeout("stream idle timeout"))
                    )
                    .doOnNext(event -> accumulator.accept(event, deltaObservation::observe))
                    .then()
                    .timeout(
                            timeoutPolicy.invocationTimeout(),
                            Mono.error(timeout("invocation timeout"))
                    )
                    .block();
            return accumulator.complete();
        } catch (ModelProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new SpringAiModelAdapterException(
                    options.providerName() + " streaming model invocation failed",
                    exception
            );
        }
    }

    private SpringAiModelAdapterException timeout(String detail) {
        return new SpringAiModelAdapterException(
                ModelFailureKind.TIMEOUT,
                options.providerName() + " " + detail
        );
    }

    private final class SafeDeltaObservation {

        private final StreamingModelInvocationScope.InvocationObservation observation;
        private boolean failed;

        private SafeDeltaObservation(
                StreamingModelInvocationScope.InvocationObservation observation
        ) {
            this.observation = observation;
        }

        private void observe(String content) {
            if (!observation.enabled() || failed) {
                return;
            }
            try {
                observation.observer().onDelta(new ModelDelta(observation.iteration(), content));
            } catch (RuntimeException exception) {
                failed = true;
                LOGGER.log(
                        System.Logger.Level.WARNING,
                        "ModelDelta observer failed for provider " + options.providerName()
                                + "; live observation disabled for this invocation ("
                                + exception.getClass().getSimpleName() + ")"
                );
            }
        }
    }
}
