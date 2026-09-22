package com.multimodalAgent.agent.streaming.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** P8.5 channels are independent of P7 lease keys and values. */
@ConfigurationProperties(prefix = "multimodal-agent.cancellation.redis")
public record RedisCancellationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("mma:cancel:v1:command") String commandChannel,
        @DefaultValue("mma:cancel:v1:ack") String ackChannel,
        @DefaultValue("2s") Duration ackTimeout
) {

    public RedisCancellationProperties {
        if (commandChannel == null || commandChannel.isBlank()
                || ackChannel == null || ackChannel.isBlank()
                || commandChannel.equals(ackChannel)) {
            throw new IllegalArgumentException("Cancellation channels must be distinct and nonblank");
        }
        if (ackTimeout == null || ackTimeout.isZero() || ackTimeout.isNegative()) {
            throw new IllegalArgumentException("ackTimeout must be positive");
        }
    }
}
