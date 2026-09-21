package com.multimodalAgent.agent.streaming;

import com.multimodalAgent.agent.stream.ExecutionStreamPayload;
import com.multimodalAgent.agent.stream.RuntimeEventPayload;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Objects;

/** The single payload-to-sequenced-envelope publication boundary. */
@Component
public final class ExecutionStreamPublisher {

    private static final System.Logger LOGGER = System.getLogger(
            ExecutionStreamPublisher.class.getName()
    );

    private final ExecutionStreamHub hub;
    private final Clock clock;

    @Autowired
    public ExecutionStreamPublisher(ExecutionStreamHub hub) {
        this(hub, Clock.systemUTC());
    }

    ExecutionStreamPublisher(ExecutionStreamHub hub, Clock clock) {
        this.hub = Objects.requireNonNull(hub, "hub must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Publishes one observation if the run stream is open. Infrastructure failures are isolated
     * here and can never become Runtime execution failures.
     */
    public void publish(String runId, ExecutionStreamPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (payload instanceof RuntimeEventPayload runtimePayload
                && !runId.equals(runtimePayload.event().runId())) {
            throw new IllegalArgumentException("runId must match wrapped Runtime event");
        }
        try {
            hub.publish(runId, payload, clock);
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Execution stream publication failed for run {0}: {1}",
                    runId,
                    exception.getClass().getSimpleName()
            );
        }
    }
}
