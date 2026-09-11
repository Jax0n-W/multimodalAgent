package com.multimodalAgent.agent.runtime.event;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

public final class AgentEventEmitter {

    private static final System.Logger LOGGER = System.getLogger(AgentEventEmitter.class.getName());

    private final String runId;
    private final AgentEventPublisher publisher;
    private final AtomicLong sequence = new AtomicLong();

    public AgentEventEmitter(String runId, AgentEventPublisher publisher) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        this.runId = runId;
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    public void emit(int iteration, Function<AgentEventMetadata, AgentEvent> eventFactory) {
        Objects.requireNonNull(eventFactory, "eventFactory must not be null");
        AgentEventMetadata metadata = new AgentEventMetadata(
                UUID.randomUUID().toString(),
                runId,
                sequence.incrementAndGet(),
                Instant.now(),
                iteration
        );
        AgentEvent event = Objects.requireNonNull(
                eventFactory.apply(metadata),
                "event factory returned null"
        );
        try {
            publisher.publish(event);
        } catch (RuntimeException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Agent event publication failed for {0}: {1}",
                    event.type(),
                    exception.getClass().getSimpleName()
            );
        }
    }
}
