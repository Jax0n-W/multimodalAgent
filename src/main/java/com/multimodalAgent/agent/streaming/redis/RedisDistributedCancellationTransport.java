package com.multimodalAgent.agent.streaming.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.runtime.control.CancelRequestResult;
import com.multimodalAgent.agent.streaming.integration.DistributedCancellationUnavailableException;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import com.multimodalAgent.agent.streaming.integration.RemoteCancellationDispatcher;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Redis Pub/Sub is delivery only; the local P8.4 entry remains cancellation authority. */
public final class RedisDistributedCancellationTransport
        implements RemoteCancellationDispatcher, MessageListener {

    private static final System.Logger LOGGER = System.getLogger(
            RedisDistributedCancellationTransport.class.getName()
    );

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final LocalExecutionControlRegistry controls;
    private final RedisCancellationProperties properties;
    private final ConcurrentMap<String, PendingAck> pending = new ConcurrentHashMap<>();

    public RedisDistributedCancellationTransport(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            LocalExecutionControlRegistry controls,
            RedisCancellationProperties properties
    ) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.controls = Objects.requireNonNull(controls, "controls must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public Optional<CancelRequestResult> dispatch(String runId) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        String commandId;
        PendingAck awaiting = new PendingAck(runId, new CompletableFuture<>());
        do {
            commandId = UUID.randomUUID().toString();
        } while (pending.putIfAbsent(commandId, awaiting) != null);
        try {
            try {
                redis.convertAndSend(
                        properties.commandChannel(),
                        mapper.writeValueAsString(new CancelCommand(commandId, runId))
                );
            } catch (RuntimeException | JsonProcessingException exception) {
                throw new DistributedCancellationUnavailableException(
                        "Redis cancellation command could not be published", exception
                );
            }
            try {
                return Optional.of(awaiting.result().get(
                        properties.ackTimeout().toNanos(), TimeUnit.NANOSECONDS
                ));
            } catch (TimeoutException exception) {
                return Optional.empty();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new DistributedCancellationUnavailableException(
                        "Interrupted while waiting for cancellation ACK", exception
                );
            } catch (ExecutionException exception) {
                throw new DistributedCancellationUnavailableException(
                        "Cancellation ACK failed", exception
                );
            }
        } finally {
            pending.remove(commandId, awaiting);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            if (properties.commandChannel().equals(channel)) {
                onCommand(message.getBody());
            } else if (properties.ackChannel().equals(channel)) {
                onAck(message.getBody());
            }
        } catch (RuntimeException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Distributed cancellation message handling failed: {0}",
                    exception.getClass().getSimpleName());
        }
    }

    private void onCommand(byte[] body) {
        CancelCommand command = decode(body, CancelCommand.class);
        if (command == null || blank(command.commandId()) || blank(command.runId())) {
            return;
        }
        CancelRequestResult result = controls.requestCancel(command.runId());
        if (result == CancelRequestResult.NOT_ACTIVE) {
            return; // A non-owner must never acknowledge another node's execution.
        }
        try {
            redis.convertAndSend(
                    properties.ackChannel(),
                    mapper.writeValueAsString(new CancelAck(
                            command.commandId(), command.runId(), result
                    ))
            );
        } catch (RuntimeException | JsonProcessingException exception) {
            // The P8.4 decision has already happened. A lost ACK leaves the requester uncertain.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Cancellation ACK publication failed for run {0}: {1}",
                    command.runId(), exception.getClass().getSimpleName());
        }
    }

    private void onAck(byte[] body) {
        CancelAck ack = decode(body, CancelAck.class);
        if (ack == null || blank(ack.commandId()) || blank(ack.runId())
                || ack.result() == null || ack.result() == CancelRequestResult.NOT_ACTIVE) {
            return;
        }
        PendingAck awaiting = pending.get(ack.commandId());
        if (awaiting != null && awaiting.runId().equals(ack.runId())) {
            awaiting.result().complete(ack.result());
        }
    }

    private <T> T decode(byte[] body, Class<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (IOException exception) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Malformed distributed cancellation message: {0}",
                    exception.getClass().getSimpleName());
            return null;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    int pendingCount() {
        return pending.size();
    }

    private record PendingAck(String runId, CompletableFuture<CancelRequestResult> result) {
    }

    private record CancelCommand(String commandId, String runId) {
    }

    private record CancelAck(String commandId, String runId, CancelRequestResult result) {
    }
}
