package com.multimodalAgent.agent.streaming.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.multimodalAgent.agent.streaming.ExecutionStreamHub;
import com.multimodalAgent.agent.streaming.ExecutionStreamPublisher;
import com.multimodalAgent.agent.streaming.integration.DistributedCancellationUnavailableException;
import com.multimodalAgent.agent.streaming.integration.LocalExecutionControlRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisDistributedCancellationTransportTest {

    private static final RedisCancellationProperties PROPERTIES =
            new RedisCancellationProperties(
                    true, "test:p85:command", "test:p85:ack", Duration.ofMillis(30)
            );

    @Test
    void publishFailureCleansPendingAndNeverFakesAcceptance() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.convertAndSend(eq(PROPERTIES.commandChannel()), anyString()))
                .thenThrow(new RedisConnectionFailureException("Redis down"));
        RedisDistributedCancellationTransport transport = transport(redis);
        assertThrows(DistributedCancellationUnavailableException.class,
                () -> transport.dispatch("run-publish-failure"));
        assertEquals(0, transport.pendingCount());
    }

    @Test
    void missingOwnerAckTimesOutCleansPendingAndLateAckIsIgnored() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        AtomicReference<String> published = new AtomicReference<>();
        when(redis.convertAndSend(eq(PROPERTIES.commandChannel()), anyString()))
                .thenAnswer(invocation -> {
                    published.set(invocation.getArgument(1));
                    return 1L; // Publish success is not an owner ACK.
                });
        RedisDistributedCancellationTransport transport = transport(redis);
        assertEquals(Optional.empty(), transport.dispatch("run-timeout"));
        assertEquals(0, transport.pendingCount());

        String commandId = new ObjectMapper().readTree(published.get())
                .get("commandId").asText();
        String lateAck = "{\"commandId\":\"" + commandId
                + "\",\"runId\":\"run-timeout\",\"result\":\"ACCEPTED\"}";
        transport.onMessage(new DefaultMessage(
                PROPERTIES.ackChannel().getBytes(StandardCharsets.UTF_8),
                lateAck.getBytes(StandardCharsets.UTF_8)
        ), null);
        assertEquals(0, transport.pendingCount());
    }

    private RedisDistributedCancellationTransport transport(StringRedisTemplate redis) {
        LocalExecutionControlRegistry controls = new LocalExecutionControlRegistry(
                new ExecutionStreamPublisher(new ExecutionStreamHub())
        );
        return new RedisDistributedCancellationTransport(
                redis, new ObjectMapper(), controls, PROPERTIES
        );
    }
}
